/*
 * Declarations bionic does not provide at this app's minimum API.
 *
 * Force-included with -include so mosh's own sources compile unchanged; the
 * definitions are in mosh_compat.c and are linked into libredtermmosh.so.
 *
 * This is the arrangement native/ssh_compat.c already uses for OpenSSH, and for the
 * same reason: mosh is an upstream tree that assumes a glibc feature level, and
 * patching it in place would mean re-applying the patch on every version bump.
 */
#ifndef REDTERM_MOSH_COMPAT_H
#define REDTERM_MOSH_COMPAT_H

/* Declared by bionic only under __ANDROID_API__ >= 26. See mosh_compat.c. */
char *nl_langinfo(int item);

#endif /* REDTERM_MOSH_COMPAT_H */
