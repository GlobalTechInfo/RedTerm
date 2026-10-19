/*
 * Terminal::Display's constructor, for the build that ships without curses.
 *
 * mosh puts this constructor in terminaldisplayinit.cc, together with
 * TerminalDisplayInit -- the one file in src/terminal that calls setupterm,
 * tigetflag and tigetstr, and which therefore cannot compile without a curses
 * header. build-mosh.sh deletes that object from libmoshterminal.a and relies on
 * the declaration-only curses.h stub, which was sound while nothing referenced
 * anything in it.
 *
 * It is not sound any more. Display is not optional for us: new_frame() is what
 * turns a Framebuffer diff into the escape sequences the Android terminal
 * emulator understands, and reimplementing it would mean reimplementing wrapping,
 * scrolling regions, wide characters and SGR bookkeeping -- all of which mosh has
 * already got right, and all of which are the sort of thing that is subtly wrong
 * in a way only a real terminal session would ever show.
 *
 * The other half of that file was thrown away for one reason, and it is worth being
 * precise about it: it is thrown away because there is no terminfo database on an
 * Android device. So this constructor is the part that does not need one.
 *
 * Look at what it actually does. Given use_environment == false -- which is what
 * RedTerm passes, because the Android terminal already knows its own capabilities
 * and the device has no $TERM worth reading -- the entire body is the member
 * initialiser list: has_ech, has_bce and has_title true, smcup and rmcup null.
 * Not one terminfo lookup happens. The other five members, and the 4 KB of
 * capability discovery around them, are all inside `if ( use_environment )`.
 *
 * Those defaults are also the right answer for us, not merely the only one
 * available:
 *
 *   smcup / rmcup  NULL, so no alternate-screen sequences. A phone has no
 *                  alternate screen, and emitting smcup would switch the
 *                  emulator into a mode nothing ever leaves.
 *   has_bce        true. An erase is sent with the background colour to match, so
 *                  a cleared region repaints correctly against whatever was
 *                  underneath it.
 *   has_ech        true. ECH is universally supported and produces shorter erases.
 *   has_title      true. A window-title escape written into a phone terminal's
 *                  byte stream is ignored by the emulator, which is the correct
 *                  outcome.
 *
 * use_environment == true throws rather than guessing. We cannot honour it on
 * this platform -- there is no database to read -- and upstream throws in exactly
 * the same circumstance ("Terminfo database could not be found."). Silently
 * behaving as though a capability existed would be worse than refusing: an
 * invisible terminfo assumption is how a terminal ends up subtly wrong on one
 * device model and fine on another. Nothing in RedTerm asks for it, so this path
 * only exists to be a loud failure rather than a quiet one.
 */

#include "terminal/terminaldisplay.h"

#include <stdexcept>

namespace Terminal {

Display::Display( bool use_environment )
    : has_ech( true ), has_bce( true ), has_title( true ), smcup( NULL ), rmcup( NULL )
{
	if ( use_environment ) {
		throw std::runtime_error(
		    "Terminfo database could not be found: RedTerm's terminal reads no $TERM, "
		    "because an Android device ships no terminfo database to read it from." );
	}
}

} /* namespace Terminal */