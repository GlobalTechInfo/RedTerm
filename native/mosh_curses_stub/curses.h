/*
 * Declaration-only curses.h, for cross-compiling mosh.
 *
 * src/terminal/terminaldisplayinit.cc includes <curses.h> and calls setupterm,
 * tigetflag and tigetstr for terminfo capability lookups, and hard-errors without a
 * header:
 *
 *     #error "SysV or X/Open-compatible Curses header file required"
 *
 * That is the only reason ncurses would be needed for the protocol libraries.
 * Nothing in the tree references TerminalDisplayInit -- it exists for the curses
 * frontend, and --disable-client does not build that -- so the object is never pulled
 * into libredtermmosh.so and these three symbols are never linked.
 *
 * Declaring them is therefore enough to compile the object and nothing more. If a
 * future change does reference it, the link fails with an undefined setupterm rather
 * than silently binding to a terminfo database we do not ship.
 */
#ifndef REDTERM_STUB_CURSES_H
#define REDTERM_STUB_CURSES_H

/* The two result macros ncurses defines; terminaldisplayinit.cc compares setupterm's
 * return against OK. */
#define OK 0
#define ERR (-1)

#ifdef __cplusplus
extern "C" {
#endif

int setupterm(const char *type, int filedes, int *errret);
int tigetflag(const char *capname);
char *tigetstr(const char *capname);

#ifdef __cplusplus
}
#endif

#endif /* REDTERM_STUB_CURSES_H */
