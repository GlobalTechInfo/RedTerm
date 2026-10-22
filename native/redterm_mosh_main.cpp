/*
 * redterm-mosh: the native client a mosh terminal session runs.
 *
 * This is an executable rather than a library call because TerminalSession is final and
 * its constructor takes a shell *path*: it builds the subprocess itself through
 * JNI.createSubprocess, so a session that is not a process cannot be a session. The app
 * already ships real executables as per-ABI assets (`assets/ssh/<arch>/`) extracted into
 * the rootfs and run through proot's loader, and mosh takes exactly that path — which
 * means TerminalSession, the emulator, split panes, resize and the session list need no
 * change at all.
 *
 * It is mosh's client without curses: mosh's own SSP client, its terminal state machine
 * and its rendering, with stdio in place of a terminal. What it deliberately does not
 * have is a display. Display::new_frame() is what turns a framebuffer diff into escape
 * sequences, and that is kept; the curses capability discovery in Display's constructor
 * is what cannot be kept, and mosh_display_compat.cpp supplies it.
 *
 * The endpoint arrives in the environment, never on the command line:
 *
 *   MOSH_KEY   the secret half of MOSH_KEY -- the key ssh's bootstrap printed
 *   MOSH_IP    the address to send UDP to, the far end's own $SSH_CONNECTION field
 *   MOSH_PORT  the UDP port mosh-server chose
 *   MOSH_COLS, MOSH_ROWS  initial size
 *
 * The launcher script that runs this is written to app storage and logged on the way
 * past, so an argument would put the session key on disk in the one place it was promised
 * not to be. It is read once, handed to the session, and unset straight away.
 *
 * Exit codes are a protocol, not a log format: the app reads them to decide what to tell
 * the user, so 4 and 5 are kept distinct.
 *
 *   0  the session ended normally: stdin closed, or mosh-server said goodbye
 *      (the remote shell exited) and was acknowledged on its own protocol
 *   2  a required variable was missing or malformed
 *   3  the session would not start
 *   4  nothing ever came back — mosh-server answered but the UDP path does not
 *
 * There is deliberately no "went quiet afterwards" code: a session that has spoken once is
 * left alone, because silence is what a terminal is most of the time.
 *
 * 4 is the one that matters and is worth separating from 5: it means the session never
 * functioned, so there is nothing for the user to be doing and the right answer is to
 * stop. 5 means a session that was real, which is a different thing to report.
 *
 * 1 is left alone so a shell reporting an ordinary failure is never confused with ours.
 */

#include <errno.h>
#include <time.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

#include "redterm_mosh_client.h"

/* Set from a signal handler, so it must be the type that is written atomically. */
/* How long to block on stdin between passes, at minimum and at most. The minimum is
 * what yields the core to the session's worker thread; see the note at the call site. */
static const int IDLE_POLL_MS = 20;
static const int MAX_POLL_MS = 100;

static volatile sig_atomic_t window_changed = 0;

/* Writes one line and ends it.
 *
 * Used only for the failure report, never for progress.
 *
 * Three versions of a progress line got this wrong. Rewriting one line in place with
 * `\r\033[2K`, which is what mosh's own client does, orphans it the moment the far end
 * scrolls -- that escape clears *the current* line, so the old one is stranded at the top
 * and a second copy appears at the bottom with the shell printing under it. Printing the
 * status inside the terminal at all was the deeper mistake: it competes with the shell's
 * output for the same screen, and the user has to read a connection state in the same
 * place they would be reading a command they just typed.
 *
 * Connecting is the app's business, and the app has a proper place to say so. */
static void say( const char *text )
{
	fprintf( stdout, "%s\r\n", text );
	fflush( stdout );
}

/* Waits for a line on stdin, so a failure stays on screen until it is acknowledged.
 *
 * Bounded, because an unbounded wait on a session nobody is watching is a session that
 * never closes and never opens anything else. Returns at once if stdin has already gone,
 * since there is nobody left to press a key. */
static void wait_for_ack( void )
{
	struct pollfd pfd;
	pfd.fd = STDIN_FILENO;
	pfd.events = POLLIN;
	pfd.revents = 0;
	for ( int i = 0; i < 60; i++ ) {
		const int rv = poll( &pfd, 1, 2000 );
		if ( rv < 0 ) {
			if ( errno == EINTR ) {
				continue;
			}
			return;
		}
		if ( rv == 0 ) {
			continue;
		}
		char drain[ 256 ];
		if ( read( STDIN_FILENO, drain, sizeof( drain ) ) <= 0 ) {
			return;
		}
		return;
	}
}

/* Milliseconds on a monotonic clock.
 *
 * Counting loop iterations instead of measuring elapsed time is the obvious way to write
 * a give-up timer and it is wrong: this loop's iteration time is whatever mosh's transport
 * happens to want, and that is frequently zero -- poll is then called with a zero timeout
 * and returns immediately, so fifty "rounds" can be a few milliseconds. The client then
 * gives up on a session that was never given a chance to answer, which is exactly what
 * happened here: the shim exited with "the connection went away" before the first UDP
 * packet had been read. */
static long long monotonic_ms(void)
{
	struct timespec ts;
	if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
		return 0;
	}
	return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void on_sigwinch(int)
{
	window_changed = 1;
}

static void usage(void)
{
	fputs(
	    "redterm-mosh: internal client for RedTerm; not meant to be run by hand.\n"
	    "\n"
	    "It reads MOSH_KEY, MOSH_IP, MOSH_PORT, MOSH_COLS and MOSH_ROWS from the\n"
	    "environment. Start it through the app, or through a launcher script it writes.\n",
	    stderr);
}

/* Reads a required variable, or returns NULL having said why on stderr. */
static const char *need_env(const char *name)
{
	const char *value = getenv(name);
	if (value == NULL || *value == '\0') {
		fprintf(stderr, "redterm-mosh: %s is not set\n", name);
		return NULL;
	}
	return value;
}

static int env_int(const char *name, int fallback)
{
	const char *value = getenv(name);
	if (value == NULL || *value == '\0') {
		return fallback;
	}
	char *end = NULL;
	long parsed = strtol(value, &end, 10);
	if (end == value || parsed <= 0 || parsed > 65535) {
		return fallback;
	}
	return (int)parsed;
}

/* Writes everything or reports how much is left, because a short write to a terminal
 * is normal and treating it as an error would drop output. */
static int write_all(int fd, const unsigned char *data, size_t len)
{
	size_t done = 0;
	while (done < len) {
		const ssize_t n = write(fd, data + done, len - done);
		if (n < 0) {
			if (errno == EINTR) {
				continue;
			}
			return -1;
		}
		if (n == 0) {
			break;
		}
		done += (size_t)n;
	}
	return (int)done;
}

/* The remote end's idea of our size, so a resize reaches mosh rather than being
 * guessed at. Zero rows and columns is a pty that has not been sized yet, which is
 * what a very early SIGWINCH looks like and is not worth acting on. */
static void apply_size(MoshSession *s)
{
	struct winsize ws;
	if (ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws) != 0) {
		return;
	}
	if (ws.ws_col == 0 || ws.ws_row == 0) {
		return;
	}
	mosh_session_resize(s, ws.ws_col, ws.ws_row);
}

int main(void)
{
	const char *key = need_env("MOSH_KEY");
	const char *ip = need_env("MOSH_IP");
	const char *port_env = need_env("MOSH_PORT");
	if (key == NULL || ip == NULL || port_env == NULL) {
		usage();
		return 2;
	}

	const char *host = ip;
	const char *port = port_env;

	int cols = env_int("MOSH_COLS", 80);
	int rows = env_int("MOSH_ROWS", 24);

	/* Raw mode. Without it the pty line-disciplines every byte we forward: a
	 * keystroke would not reach the remote shell until Enter, and Ctrl-C would be
	 * swallowed by the local tty instead of interrupting the remote process.
	 * A failure here is not fatal -- a session with no pty is still usable -- so it
	 * is reported and the session is attempted anyway. */
	struct termios saved;
	const bool has_tty = isatty(STDIN_FILENO) && tcgetattr(STDIN_FILENO, &saved) == 0;
	if (has_tty) {
		struct termios raw = saved;
		raw.c_iflag &= (unsigned)~(IXON | ICRNL | INLCR | IGNCR);
		raw.c_lflag &= (unsigned)~(ECHO | ICANON | ISIG | IEXTEN);
		raw.c_oflag &= (unsigned)~(OPOST);
		raw.c_cc[VMIN] = 0;
		raw.c_cc[VTIME] = 0;
		if (tcsetattr(STDIN_FILENO, TCSANOW, &raw) != 0) {
			fprintf(stderr, "redterm-mosh: could not set raw mode: %s\n", strerror(errno));
		}
	}

	char errbuf[512];
	errbuf[0] = '\0';
	MoshSession *session = mosh_session_start(key, ip, port_env, cols, rows, errbuf, sizeof(errbuf));

	/* The secret has been handed over; leave nothing behind for a later /proc read
	 * or a core dump. */
	unsetenv("MOSH_KEY");
	memset((void *)key, 0, strlen(key));

	if (session == NULL) {
		fprintf(stderr, "redterm-mosh: %s\n", errbuf[0] ? errbuf : "could not start");
		if (has_tty) {
			tcsetattr(STDIN_FILENO, TCSANOW, &saved);
		}
		return 3;
	}

	struct sigaction action;
	memset(&action, 0, sizeof(action));
	action.sa_handler = on_sigwinch;
	sigaction(SIGWINCH, &action, NULL);

	int exit_code = 0;
	/* mosh tolerates loss, so silence is normal and one quiet second means nothing.
	 * These are two separate waits because they are two different questions: how long
	 * to wait for the first byte at all, and how long a connection that has spoken once
	 * may then go quiet. Both are measured in milliseconds, never in iterations. */
	const long long connect_grace_ms = 20000;
	const long long start_ms = monotonic_ms();
	/* Whether anything has arrived from the network *yet*.
	 *
	 * Not "whether we have rendered anything": the very first render differs from the
	 * initial framebuffer because the remote's terminal size is not the one we were
	 * seeded with, so mosh emits a diff, and that diff is ours rather than the server's.
	 * Timing on that set gave up the moment anything was drawn at all — including the
	 * one thing that happens before a single packet has arrived. */
	bool heard_from_server = false;

	/* No progress line here. Connecting is the app's business and the app has a proper
	 * place to say so — TerminalActivity's connecting overlay covers the attach and
	 * ends on the session's first drawn output. A status printed into the terminal
	 * competes with the shell's output for the same screen, and the in-place rewrite
	 * the erase relied on strands itself the moment the far end scrolls; say() is for
	 * the failure report only, and the history of the three wrong versions is written
	 * into its comment above. */

	for (;;) {
		unsigned char out[4096];
		size_t got = 0;
		while (got < sizeof(out)) {
			const size_t n = mosh_session_poll(session, out + got, sizeof(out) - got);
			if (n == 0) {
				break;
			}
			got += n;
		}
		if (got > 0) {
			if (write_all(STDOUT_FILENO, out, got) < 0) {
				break;
			}
		}

		if (window_changed) {
			window_changed = 0;
			apply_size(session);
		}

		/* The remote shell has exited, part one: mosh-server says so itself.
		 *
		 * `exit` at the far end closes the shell's pty, and mosh-server reacts by
		 * putting the connection into its shutdown sequence: ordinary encrypted
		 * packets whose state number is -1, resent with the same backoff as any
		 * other state — so they arrive wherever the session's packets arrive,
		 * including through a NAT that drops every ICMP error. The client answers
		 * with its own ack and the two sides are done: no error, no guesswork.
		 *
		 * Ignoring this signal is what left a session sitting at `logout` for ever:
		 * by the time the server is gone every state is acknowledged, so SSP sends
		 * nothing more, the network is silent in both directions, and the
		 * receive-error detector below waits for errors that the silent network
		 * never raises. Measured on this host before the fix: killed after 50
		 * seconds, still running. */
		if ( mosh_session_shutdown_requested( session ) ) {
			exit_code = 0;
			break;
		}

		/* The remote shell has exited, part two: only when the server had no chance
		 * to say goodbye — a crash on the far end rather than a shell exiting — does
		 * the receive side carry the news.
		 *
		 * Counted rather than taken from the first failure because one refused packet is
		 * not a verdict -- mosh itself tolerates loss silently, and a stray ICMP should
		 * not end a live session. Several in a row, with no successful receive in
		 * between, means the port is closed rather than unlucky. */
		if (heard_from_server && mosh_session_network_errors( session ) >= 5 ) {
			break;
		}

		if (!heard_from_server && mosh_session_is_connected(session)) {
			heard_from_server = true;
			/* Two stages rather than one: "connected" and "you have a shell" are
			 * different moments, and the gap between them is exactly when a user decides
			 * the app has hung. */
		}

		{
			/* Two separate waits, compared against two separate baselines.
			 *
			 * Writing this as `now - last_rx_ms > limit` with last_rx_ms starting at
			 * zero looks correct and is not: the clock here is CLOCK_MONOTONIC, so "now"
			 * is the milliseconds since boot -- millions of them -- and zero is not "now,
			 * a moment ago" but "before the machine started". The comparison is
			 * therefore true on the very first iteration and the client gives up
			 * instantly.
			 *
			 * Nothing caught it on loopback, where the handshake comes back inside the
			 * same iteration. Against a real server the round trip is a hundred
			 * milliseconds and upwards, so the check ran first and every session exited
			 * 4 immediately -- which read as "the server refused to speak mosh" and was
			 * the entire mosh feature failing for two reasons stacked. */
			/* Only the *first* frame is waited for.
			 *
			 * There is deliberately no silence timeout after that. A mosh session sits idle
			 * for minutes while somebody reads a man page, and killing it for being quiet
			 * would break the one thing mosh is for — surviving a network that would have
			 * dropped a TCP flow long before. mosh's own client has no such timer either.
			 *
			 * A previous version had one, at 60s, and it made the failure worse rather
			 * than better: the first bytes to arrive switch the timer from "waiting for
			 * the session" to "waiting for a shell", so a handshake that completed but
			 * produced nothing usable left the user staring at a blank terminal for a full
			 * minute. One timer, about one thing, is easier to reason about than two. */
			const long long now = monotonic_ms();
			if (!heard_from_server && now - start_ms > connect_grace_ms) {
				/* This is the case a firewall produces, and it is worth saying so.
				 *
				 * The bootstrap succeeded, so mosh-server is installed, running and holding
				 * a UDP port — and the usual reason nothing comes back is that nothing can
				 * reach that port. The range is named here because the single most useful
				 * thing to tell someone staring at an empty terminal is which firewall rule
				 * is missing. */
				/* Both causes, because measuring one of them is not enough.
				 *
				 * The obvious answer is the server's firewall, and it is the one worth
				 * checking first -- but on a cloud instance it is genuinely possible for
				 * the server to be configured correctly and for the packets still never
				 * to arrive, because the phone's own network can refuse to carry them
				 * and its NAT can leave no way for a reply to come back. Both were
				 * measured here: the instance firewall was opened, persisted, and its
				 * packet counter stayed at zero across four separate attempts. Naming only
				 * the server would send someone to fix something that was already right. */
				/* \r\n rather than \n throughout: raw mode is on by this point, so a bare
				 * newline moves down a line without returning the carriage, and the
				 * explanation lands as a staircase down the left edge. */
				{
					char heading[ 256 ];
					snprintf( heading, sizeof( heading ),
					          "mosh: mosh-server started, but nothing at all arrived from "
					          "%s in %llds.",
					          host, (long long)(connect_grace_ms / 1000) );
					say( heading );
				}
				{
					char body[ 700 ];
					snprintf( body, sizeof( body ),
					          "  Not one UDP packet reached the server, which is one of two "
					          "things:\r\n"
					          "    1. UDP %s is not open on the server, all the way through "
					          "the cloud's security list and the host's own firewall; or\r\n"
					          "    2. this device\u2019s network does not carry UDP to that "
					          "host, which is common on mobile networks behind carrier "
					          "NAT.\r\n"
					          "  SSH works in both cases; only mosh needs the UDP.\r\n"
					          "\r\n"
					          "Press Enter to close this session and open one over SSH.",
					          port );
					say( body );
				}
				wait_for_ack();
				exit_code = 4;
				break;
			}
		}

		/* mosh's own idea of how long it has nothing to do, bounded so a long wait
		 * still notices a keystroke promptly.
		 *
		 * The floor is not a nicety. The session pumps its own network on a worker
		 * thread, and when mosh's timers are due its wait time is zero -- so polling
		 * stdin with that value returns immediately and this loop spins. On a
		 * single-core device the spinner and the worker thread then divide one core
		 * between them and the worker, which is the only thing that can bring data in,
		 * gets almost none of it: the session sits there with no output and no error
		 * until the give-up timer fires. Yielding for a moment each pass costs at most
		 * a frame of keystroke latency and is what makes the two halves able to run at
		 * all. */
		int wait = mosh_session_wait_time(session);
		if (wait < IDLE_POLL_MS) {
			wait = IDLE_POLL_MS;
		} else if (wait > MAX_POLL_MS) {
			wait = MAX_POLL_MS;
		}

		struct pollfd pfd;
		pfd.fd = STDIN_FILENO;
		pfd.events = POLLIN;
		pfd.revents = 0;
		const int rv = poll(&pfd, 1, wait);
		if (rv < 0) {
			if (errno == EINTR) {
				continue;
			}
			break;
		}
		if (rv > 0 && (pfd.revents & (POLLIN | POLLHUP)) != 0) {
			unsigned char in[1024];
			const ssize_t n = read(STDIN_FILENO, in, sizeof(in));
			if (n > 0) {
				mosh_session_input(session, in, (size_t)n);
			} else if (n == 0) {
				/* stdin closed: the terminal went away, which is the one exit
				 * that is not a failure worth a message. */
				break;
			}
		}
	}

	char why[ 512 ];
	/* A session the server said goodbye to is not a failure, and an error that was
	 * noted earlier in its life is not the last thing the user should read under
	 * `logout`: the server hangs up its own way, whatever else happened before. */
	const bool clean_goodbye = mosh_session_shutdown_requested( session ) != 0 &&
	                           mosh_session_network_errors( session ) < 5;
	if ( !clean_goodbye && mosh_session_last_error( session, why, sizeof( why ) ) > 0 ) {
		fprintf(stderr, "redterm-mosh: %s\n", why);
	}

	if (has_tty) {
		tcsetattr(STDIN_FILENO, TCSANOW, &saved);
	}
	mosh_session_free(session);
	return exit_code;
}