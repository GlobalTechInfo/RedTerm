/*
 * mosh client for RedTerm: the State Synchronization Protocol.
 *
 * This is mosh's own C++ -- Network::Transport, StateSync::UserStream,
 * StateSync::Complete, Terminal::Framebuffer -- wrapped in a small C API so both the
 * JNI layer and a host test harness can drive it. Nothing here reimplements the
 * protocol: the wire format, the encryption and the terminal model are upstream's,
 * because the SSP is fiddly enough that a reimplementation is only plausible-looking.
 *
 * The wiring follows src/frontend/stmclient.cc, which is mosh's own client:
 *
 *     typedef Network::Transport< Network::UserStream, Terminal::Complete > MoshTransport;
 *
 *     user_state; local_terminal;
 *     network = MoshTransport( user_state, local_terminal, key, ip, port );
 *     network->get_current_state().push_back( Parser::Resize( cols, rows ) );
 *     ... network->recv(); ...
 *     new_state = network->get_latest_remote_state().state.get_fb();
 *     diff      = display.new_frame( false, local_fb, new_state );
 *
 * Two deliberate differences from stmclient:
 *
 *  - It uses mosh's own `Select` wrapper around the transport's fds. `Select` lives in
 *    src/network and is not built under --disable-client, so this polls directly. The
 *    fds are still mosh's: network->fds().
 *  - It owns a thread and hands finished frames out as a queue, because Kotlin drives
 *    the terminal through a pipe and a blocking native call would stall it.
 */

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <poll.h>
#include <stdint.h>
#include <string.h>

#include <atomic>
#include <deque>
#include <memory>
#include <mutex>
#include <utility>
#include <vector>
#include <string>
#include <thread>
#include <vector>

/* Included bare, with per-directory -I, the way src/frontend/stmclient.cc does it.
 * The Transport template itself is in the -impl.h; networktransport.h is only the
 * declaration, and including that alone gives a class with no members defined. */
/* networktransport-impl.h calls fatal_assert(), which lives in this header. Without
 * the include the template does not compile, and the error names a missing method on
 * a template parameter rather than a missing header. */
#include "fatal_assert.h"
#include "timestamp.h"
#include "networktransport-impl.h"
#include "completeterminal.h"
#include "user.h"
#include "parser.h"
#include "terminaldisplay.h"
#include "terminalframebuffer.h"

typedef Network::Transport< Network::UserStream, Terminal::Complete > MoshTransport;

/* mosh caps its own wait; so do we, or stop() takes up to this long to be noticed. */
static const int MAX_WAIT_MS = 25;

struct MoshSession {
	/* These two are held by reference by MoshTransport, so they must outlive it --
	 * mosh's own client keeps them as locals in main_init for the same reason. */
	Network::UserStream user_state;
	Terminal::Complete local_terminal;

	std::shared_ptr< MoshTransport > network;
	Terminal::Display display;
	Terminal::Framebuffer local_fb;

	std::deque< std::string > outbox;
	std::mutex outbox_lock;

	/* Input from the caller's thread, applied to the transport only on the worker.
	 *
	 * mosh's transport is not thread-safe: tick() diffs the user stream to build its
	 * outgoing packet while a push_back from another thread is changing the very deque
	 * it iterates. mosh's own client is single-threaded and never has this problem;
	 * ours does, because the terminal sits behind a pipe and the caller cannot wait.
	 * So the caller queues, and the worker applies what it queued between renders --
	 * the same shape the outbox uses in the other direction. */
	std::mutex input_lock;
	std::string pending_bytes;
	std::vector< std::pair< int, int > > pending_resizes;

	std::atomic< bool > running;
	std::thread worker;

	int cols;
	int rows;
	/* Atomic: the worker writes it while mosh_session_is_connected() reads it
	 * from the caller's thread, and a plain bool is a data race there. */
	std::atomic< bool > connected;

	/* Consecutive network failures, reset by any successful receive.
	 *
	 * mosh tolerates loss without complaint -- that is most of what the protocol is for
	 * -- so an exception here means the socket itself failed. The one that matters is
	 * what happens when the remote shell exits: mosh-server closes its UDP port, the
	 * next packet draws ICMP port-unreachable, and recv() throws. That is not a fault to
	 * recover from, it is the far end saying the session is over. */
	std::atomic< int > consecutive_errors;

	/* The last failure seen on the worker thread, for mosh_session_last_error().
	 *
	 * Deliberately kept rather than written anywhere: this library has no context and
	 * no log, and AGENTS.md's rule is that a failure nobody can read is a failure
	 * nobody can fix -- so the caller decides whether it is worth reporting. */
	std::string last_error;
	std::mutex error_lock;

	void note_error( const std::string &why )
	{
		std::lock_guard< std::mutex > lock( error_lock );
		last_error = why;
	}

	/*
	 * Display( use_environment = false ): mosh's Display reads $TERM through setupterm
	 * when that is true, which needs a terminfo database. There is none in an APK, and
	 * the Android terminal emulator already knows its own capabilities, so the display
	 * only has to emit the frame diff.
	 */
	MoshSession( int c, int r )
	    : local_terminal( c, r ), display( false ), local_fb( c, r ), running( false ),
	      cols( c ), rows( r ), connected( false ), consecutive_errors( 0 )
	{
	}
	~MoshSession() { stop(); }

	void stop()
	{
		bool was = running.exchange( false );
		if ( was && worker.joinable() ) {
			worker.join();
		}
	}

	/* Host output as a terminal would produce it: the minimal ANSI diff from the
	 * previous frame, which is what Terminal::Display computes. */
	void render()
	{
		try {
			const Terminal::Framebuffer new_state
			    = network->get_latest_remote_state().state.get_fb();
			if ( new_state == local_fb ) {
				return; /* nothing changed; do not emit an empty diff */
			}
			const std::string diff = display.new_frame( false, local_fb, new_state );
			local_fb = new_state;
			if ( !diff.empty() ) {
				std::lock_guard< std::mutex > lock( outbox_lock );
				outbox.push_back( diff );
			}
		} catch ( const std::exception &e ) {
			note_error( e.what() );
		} catch ( ... ) {
			note_error( "unknown render error" );
		}
	}

	void run()
	{
		while ( running.load() ) {
			render();

			int wait = network->wait_time();
			if ( wait < 0 ) {
				wait = 0;
			}
			if ( wait > MAX_WAIT_MS ) {
				wait = MAX_WAIT_MS;
			}

			/* mosh's fds, polled directly. See the note at the top about Select. */
			std::vector< int > fds;
			try {
				fds = network->fds();
			} catch ( ... ) {
				break;
			}
			if ( fds.empty() ) {
				break; /* no sockets to wait on, so nothing more can happen */
			}

			std::vector< struct pollfd > pfds;
			pfds.reserve( fds.size() );
			for ( std::vector< int >::const_iterator it = fds.begin(); it != fds.end(); ++it ) {
				struct pollfd p;
				p.fd = *it;
				p.events = POLLIN;
				p.revents = 0;
				pfds.push_back( p );
			}

			const int n = poll( &pfds[0], pfds.size(), wait );
			if ( n > 0 ) {
				try {
					network->recv();
					connected = true;
					consecutive_errors.store( 0 );
				} catch ( const std::exception &e ) {
					consecutive_errors.fetch_add( 1 );
					note_error( e.what() );
				} catch ( ... ) {
					note_error( "unknown receive error" );
					consecutive_errors.fetch_add( 1 );
				}
			}

			/* Advance mosh's clock. This is the single most surprising thing about
			 * mosh's internals and it was the entire send path.
			 *
			 * Network::timestamp() returns frozen_timestamp(), which reads a
			 * millisecond cache exactly once and then returns that same value for
			 * ever. It only advances when something calls freeze_timestamp(), and in
			 * the whole tree the callers are mosh's own frontends and its Select
			 * wrapper:
			 *
			 *     frontend/stmclient.cc:567   freeze_timestamp();
			 *     frontend/mosh-server.cc:141  freeze_timestamp();
			 *     util/select.h:187            freeze_timestamp();
			 *
			 * Replicate the loop without that call and the clock stops at the first
			 * timestamp the client ever read. Every timer comparison stays
			 * "now < next_send_time", so the initial state goes out and nothing ever
			 * does again: keystrokes queue, tick() is asked and declines every time,
			 * and the remote shell never sees them. No error, no exception, no
			 * output -- the connection looks alive and is one-way. */
			freeze_timestamp();

			/* Apply what the caller queued, on the thread that owns the transport.
			 * Anything queued on the caller's thread waited for exactly this point;
			 * without it, a keystroke typed between renders would sit until the next
			 * one for no better reason than where it was queued. */
			{
				std::lock_guard< std::mutex > lock( input_lock );
				if ( !pending_bytes.empty() || !pending_resizes.empty() ) {
					try {
						Network::UserStream &state = network->get_current_state();
						for ( size_t i = 0; i < pending_bytes.size(); i++ ) {
							state.push_back( Parser::UserByte( pending_bytes[ i ] ) );
						}
						for ( size_t i = 0; i < pending_resizes.size(); i++ ) {
							state.push_back(
								Parser::Resize( pending_resizes[ i ].first, pending_resizes[ i ].second )
							);
						}
					} catch ( ... ) {
						note_error( "could not queue input" );
					}
					pending_bytes.clear();
					pending_resizes.clear();
				}
			}

			try {
				network->tick();
			} catch ( const std::exception &e ) {
				note_error( e.what() );
			} catch ( ... ) {
				note_error( "unknown send error" );
			}
		}
	}
};

extern "C" {

/*
 * Starts a session against a mosh-server that has already printed its port and key.
 *
 * The key is a secret: it is passed as a string here but never logged, never written
 * to disk and never placed in a command line. Kotlin holds it in memory only, and the
 * same rule the passphrase helper follows for ssh.
 *
 * Returns NULL and fills errbuf on failure rather than throwing, because every caller
 * is a Kotlin boundary where an exception would take the process down.
 */
MoshSession *mosh_session_start(
    const char *key, const char *ip, const char *port, int cols, int rows,
    char *errbuf, size_t errlen )
{
	if ( key == NULL || ip == NULL || port == NULL ) {
		snprintf( errbuf, errlen, "key, address and port are all required" );
		return NULL;
	}
	if ( cols <= 0 || rows <= 0 ) {
		snprintf( errbuf, errlen, "terminal size %dx%d is not usable", cols, rows );
		return NULL;
	}

	MoshSession *s = new MoshSession( cols, rows );
	try {
		s->network = std::shared_ptr< MoshTransport >(
		    new MoshTransport( s->user_state, s->local_terminal, key, ip, port ) );
		s->network->set_send_delay( 1 ); /* minimal delay on outgoing keystrokes */
		/* REDTERM_MOSH_VERBOSE=1 turns on mosh's own tracing. Off by default: it
		 * writes to stderr per packet, which is a firehose on a live session. */
		{
			const char *v = getenv( "REDUCTERM_MOSH_VERBOSE" );
			s->network->set_verbose( ( v != NULL && atoi( v ) > 0 ) ? 2 : 0 );
		}
		/* The server needs our size before it will draw anything sensible. */
		s->network->get_current_state().push_back( Parser::Resize( cols, rows ) );
	} catch ( const std::exception &e ) {
		snprintf( errbuf, errlen, "could not start: %s", e.what() );
		delete s;
		return NULL;
	} catch ( ... ) {
		snprintf( errbuf, errlen, "could not start: unknown error" );
		delete s;
		return NULL;
	}

	s->running.store( true );
	try {
		s->worker = std::thread( &MoshSession::run, s );
	} catch ( const std::exception &e ) {
		snprintf( errbuf, errlen, "could not start the session thread: %s", e.what() );
		delete s;
		return NULL;
	}
	return s;
}

void mosh_session_stop( MoshSession *s )
{
	if ( s ) {
		s->stop();
	}
}

void mosh_session_free( MoshSession *s ) { delete s; }

/*
 * Queues keystrokes. The worker applies them to the user-state stream, which SSP
 * diffs and transmits; the server applies them and its frame comes back on the next
 * render. Queued rather than applied here because the transport lives on the worker
 * thread and mosh's own client never mutates it from anywhere else.
 */
void mosh_session_input( MoshSession *s, const uint8_t *bytes, size_t n )
{
	if ( !s || !bytes || n == 0 ) {
		return;
	}
	try {
		std::lock_guard< std::mutex > lock( s->input_lock );
		s->pending_bytes.append( reinterpret_cast< const char * >( bytes ), n );
	} catch ( ... ) {
	}
}

/*
 * Queues a resize. Applied by the worker like the keystrokes, and it re-sends the
 * whole screen, so the host output after this is expected to be large.
 */
void mosh_session_resize( MoshSession *s, int cols, int rows )
{
	if ( !s || cols <= 0 || rows <= 0 ) {
		return;
	}
	try {
		std::lock_guard< std::mutex > lock( s->input_lock );
		s->pending_resizes.push_back( std::make_pair( cols, rows ) );
	} catch ( ... ) {
	}
}

/*
 * Takes up to maxlen bytes of host output. Returns how many were written, 0 when
 * there is nothing yet.
 *
 * A single call drains the whole queue rather than one frame, so Kotlin's poll loop
 * does not turn into one JNI crossing per rendered frame.
 */
size_t mosh_session_poll( MoshSession *s, uint8_t *out, size_t maxlen )
{
	if ( !s || !out || maxlen == 0 ) {
		return 0;
	}
	std::lock_guard< std::mutex > lock( s->outbox_lock );
	size_t written = 0;
	while ( written < maxlen && !s->outbox.empty() ) {
		const std::string &frame = s->outbox.front();
		const size_t room = maxlen - written;
		if ( frame.size() <= room ) {
			memcpy( out + written, frame.data(), frame.size() );
			written += frame.size();
			s->outbox.pop_front();
		} else {
			/* Partially take a frame, and put the rest back at the front. */
			memcpy( out + written, frame.data(), room );
			written += room;
			s->outbox.front() = frame.substr( room );
			break;
		}
	}
	return written;
}

/* 1 once a frame has come back from the server, 0 before that. */
int mosh_session_is_connected( MoshSession *s ) { return ( s && s->connected ) ? 1 : 0; }

/* The last failure the worker saw, or "" if none. For Diagnostics, not for stdout.
 *
 * The text is copied out under the lock rather than referenced: the worker can
 * reassign `last_error` at any moment, and a caller reading a pointer into a
 * string another thread keeps mutating reads something between versions --
 * typically on the remote-exit path, where every recv() throws and calls
 * note_error again while the caller is still formatting the previous one. */
size_t mosh_session_last_error( MoshSession *s, char *out, size_t outlen )
{
	if ( !out || outlen == 0 ) {
		return 0;
	}
	out[ 0 ] = '\0';
	if ( !s ) {
		return 0;
	}
	std::lock_guard< std::mutex > lock( s->error_lock );
	snprintf( out, outlen, "%s", s->last_error.c_str() );
	return strlen( out );
}

/* How many network failures in a row, with no successful receive in between.
 *
 * Zero once the link is healthy. The far end closing its socket is the signal that a
 * session is genuinely over rather than merely quiet. */
int mosh_session_network_errors( MoshSession *s )
{
	return s ? s->consecutive_errors.load() : 0;
}

/* Milliseconds mosh's own transport wants to wait; -1 when there is no transport. */
int mosh_session_wait_time( MoshSession *s )
{
	if ( !s || !s->network ) {
		return -1;
	}
	return s->network->wait_time();
}

} /* extern "C" */