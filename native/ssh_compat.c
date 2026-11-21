/*
 * Compatibility shims for the bundled OpenSSH client on Android/bionic.
 *
 * explicit_bzero: bionic declares bzero() only as a function-like macro, so
 * OpenSSH's own implementation -- which takes the address of bzero to defeat
 * dead-store elimination -- cannot compile. Defining HAVE_EXPLICIT_BZERO tells
 * OpenSSH to skip its version, and this supplies the symbol instead.
 *
 * getpwuid/getpwnam: bionic resolves these through NSS, which walks
 * /system/etc/nsswitch.xml and reaches service modules through the dynamic
 * loader. Inside the client's private proot rootfs that path dereferences a NULL
 * function pointer, so the client died with SIGSEGV at fault address 0x0 during
 * main()'s first getpwuid() call, and again from getpwnam() in auth.c. The client
 * is linked as the executable, and an executable's definitions take precedence
 * over the shared libc, so defining these here keeps every passwd lookup in the
 * process out of bionic's NSS.
 *
 * Two callers need an entry. The SSH client in its rootfs runs under -0, so
 * it is uid 0 and its one account is root (home /root). The same binary in
 * the device shell runs under the app's own uid, without -0; there ssh and
 * ssh-keygen call getpwuid(getuid()) at startup and abort with "No user
 * exists for uid N" before a single option is read if that lookup returns
 * NULL. So a lookup for uid 0 returns the fixed root entry, and a lookup for
 * any other uid returns a synthesised entry for that uid with its home taken
 * from $HOME (the device shell points that at the app's writable storage).
 * Neither reads /etc/passwd; the executable's own definitions are what
 * bionic's NSS would have resolved to anyway.
 */
#include <errno.h>
#include <pwd.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>

/* The volatile pointer keeps the compiler from optimising the wipe away. */
static void *(*volatile redterm_memset)(void *, int, size_t) = memset;

void
explicit_bzero(void *p, size_t n)
{
	if (p != NULL && n != 0)
		(void)redterm_memset(p, 0, n);
}

#define REDTERM_HOME "/root"
#define REDTERM_SHELL "/bin/sh"

static int
redterm_passwd(struct passwd *pw, char *buf, size_t buflen,
    uid_t uid, const char *name, const char *home)
{
	/*
	 * The four strings (name, passwd "x", home, shell) plus their NULs,
	 * laid out contiguously in buf. bionic's own version is allowed to
	 * fail when the buffer is too small, so keep that behaviour.
	 */
	size_t need = strlen(name) + 1 + 2 + strlen(home) + 1 +
	    strlen(REDTERM_SHELL) + 1;

	if (pw == NULL || buf == NULL || buflen < need)
		return 0;

	memset(buf, 0, need);
	pw->pw_name = buf;
	(void)strlcpy(pw->pw_name, name, buflen);
	pw->pw_passwd = buf + strlen(name) + 1;
	(void)strlcpy(pw->pw_passwd, "x", buflen - (size_t)(pw->pw_passwd - buf));
	pw->pw_gecos = pw->pw_passwd;
	pw->pw_dir = pw->pw_passwd + 2;
	(void)strlcpy(pw->pw_dir, home, buflen - (size_t)(pw->pw_dir - buf));
	pw->pw_shell = pw->pw_dir + strlen(home) + 1;
	(void)strlcpy(pw->pw_shell, REDTERM_SHELL,
	    buflen - (size_t)(pw->pw_shell - buf));
	pw->pw_uid = uid;
	pw->pw_gid = uid;
	return 1;
}

int
getpwuid_r(uid_t uid, struct passwd *pw, char *buf, size_t buflen,
    struct passwd **result)
{
	const char *name, *home;

	if (result == NULL)
		return EINVAL;
	*result = NULL;
	if (uid == 0) {
		/* The one account the SSH rootfs carries. */
		name = "root";
		home = REDTERM_HOME;
	} else {
		/*
		 * The app's own uid, when the device shell runs these binaries
		 * without -0. Home is $HOME (the launcher points it at the
		 * app's writable storage); "." when it is unset, so the entry
		 * always has a home the caller can resolve.
		 */
		const char *h = getenv("HOME");
		name = "android";
		home = (h != NULL && *h != '\0') ? h : ".";
	}
	if (!redterm_passwd(pw, buf, buflen, uid, name, home))
		return ERANGE;
	*result = pw;
	return 0;
}

int
getpwnam_r(const char *name, struct passwd *pw, char *buf, size_t buflen,
    struct passwd **result)
{
	const char *h;

	if (result == NULL)
		return EINVAL;
	*result = NULL;
	if (name == NULL)
		return 0;
	if (strcmp(name, "root") == 0) {
		/* The one account the SSH rootfs carries. */
		if (!redterm_passwd(pw, buf, buflen, 0, "root", REDTERM_HOME))
			return ERANGE;
		*result = pw;
		return 0;
	}
	/*
	 * Any other name the caller asks for is, in this private environment,
	 * the process's own user. Resolve it to the current uid, with home
	 * from $HOME (or /root), so a lookup never aborts the session with a
	 * missing-user error the way the original did for the app uid.
	 */
	h = getenv("HOME");
	if (!redterm_passwd(pw, buf, buflen, getuid(), name,
	    (h != NULL && *h != '\0') ? h : REDTERM_HOME))
		return ERANGE;
	*result = pw;
	return 0;
}

struct passwd *
getpwuid(uid_t uid)
{
	static char buf[512];
	static struct passwd pw;
	struct passwd *result = NULL;

	if (getpwuid_r(uid, &pw, buf, sizeof(buf), &result) != 0)
		return NULL;
	return result;
}

struct passwd *
getpwnam(const char *name)
{
	static char buf[512];
	static struct passwd pw;
	struct passwd *result = NULL;

	if (getpwnam_r(name, &pw, buf, sizeof(buf), &result) != 0)
		return NULL;
	return result;
}
