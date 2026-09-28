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
 * The client only ever runs as the root user that proot maps the whole session
 * to, in a rootfs that contains exactly one account, so a fixed struct is
 * equivalent to reading /etc/passwd. A lookup for any other user correctly
 * returns NULL, because no such user exists here.
 */
#include <errno.h>
#include <pwd.h>
#include <stddef.h>
#include <string.h>
#include <sys/types.h>

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
redterm_passwd(struct passwd *pw, char *buf, size_t buflen)
{
	size_t need;

	if (pw == NULL)
		return 0;

	/*
	 * Room for the strings plus the alignment the caller's buffer is
	 * unlikely to provide. bionic's own version is allowed to fail when the
	 * buffer is too small, so keep that behaviour.
	 */
	need = sizeof("root") + sizeof(REDTERM_HOME) + sizeof(REDTERM_SHELL) +
	    sizeof("root") + 5 * sizeof(char *);
	if (buf == NULL || buflen < need)
		return 0;

	memset(buf, 0, need);
	pw->pw_name = buf;
	(void)strlcpy(buf, "root", buflen);
	pw->pw_passwd = buf + strlen("root") + 1;
	(void)strlcpy(pw->pw_passwd, "x", buflen - (size_t)(pw->pw_passwd - buf));
	pw->pw_gecos = pw->pw_passwd;
	pw->pw_dir = pw->pw_passwd + 2;
	(void)strlcpy(pw->pw_dir, REDTERM_HOME, buflen - (size_t)(pw->pw_dir - buf));
	pw->pw_shell = pw->pw_dir + sizeof(REDTERM_HOME);
	(void)strlcpy(pw->pw_shell, REDTERM_SHELL,
	    buflen - (size_t)(pw->pw_shell - buf));
	pw->pw_uid = 0;
	pw->pw_gid = 0;
	return 1;
}

int
getpwuid_r(uid_t uid, struct passwd *pw, char *buf, size_t buflen,
    struct passwd **result)
{
	if (result == NULL)
		return EINVAL;
	*result = NULL;
	if (uid != 0)
		return 0;			/* no such user in this rootfs */
	if (!redterm_passwd(pw, buf, buflen))
		return ERANGE;
	*result = pw;
	return 0;
}

int
getpwnam_r(const char *name, struct passwd *pw, char *buf, size_t buflen,
    struct passwd **result)
{
	if (result == NULL)
		return EINVAL;
	*result = NULL;
	if (name == NULL || strcmp(name, "root") != 0)
		return 0;			/* no such user in this rootfs */
	if (!redterm_passwd(pw, buf, buflen))
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
