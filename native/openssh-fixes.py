#!/usr/bin/env python3
"""
Applies the Android/bionic compatibility fixes OpenSSH's configure cannot work
out on its own when cross-compiling with the NDK.

Run against an OpenSSH build tree after ./configure:

    python3 openssh-fixes.py <openssh-source-dir>

Three classes of problem, all caused by configure's feature probes failing
against a cross-compiler:

1. config.h attributes. Bionic's <unistd.h> uses __attribute__((__sentinel__(1))).
   When configure does not detect __sentinel__, OpenSSH's defines.h defines
   __sentinel__ as an empty macro, which rewrites that declaration to the invalid
   __attribute__(((1))). Declaring the attribute support stops that.

2. config.h functions bionic already provides. Without them OpenSSH compiles its
   own fallbacks, and bzero() in particular collides with the macro of the same
   name in <strings.h>. Conversely, functions bionic lacks must stay undefined.

3. openbsd-compat/xcrypt.c declares pick_salt() only when <shadow.h> exists but
   defines it unconditionally, and bionic has no shadow.h before API 24. The
   function is only used by server-side crypt authentication, which RedTerm does
   not build, so a fixed-salt stub is correct here.
"""
import os
import re
import sys

# Functions bionic provides; OpenSSH must not supply its own.
BIONIC_PROVIDES = [
    "HAVE_BZERO", "HAVE_BCOPY", "HAVE_BSWAP_16", "HAVE_BSWAP_32", "HAVE_BSWAP_64",
    "HAVE_MEMMOVE", "HAVE_MEMSET", "HAVE_STRCASECMP", "HAVE_STRNCASECMP",
    "HAVE_STRSEP", "HAVE_STRCASESTR",
    # bionic has had this since API 21/23; the openbsd-compat copies collide.
    "HAVE_TIMEGM",
    # Not API-gated in bionic, so the openbsd-compat copies just collide.
    "HAVE_ARC4RANDOM", "HAVE_ARC4RANDOM_BUF", "HAVE_ARC4RANDOM_UNIFORM",
]
# Attribute support clang has, which configure failed to detect.
ATTRIBUTES = ["HAVE_ATTRIBUTE__SENTINEL__", "HAVE_ATTRIBUTE__NORETURN__"]
# bionic only gained these at API 28, and RedTerm targets API 24.
# configure's probes are unreliable against a cross compiler, and bionic does not
# provide these: letting OpenSSH compile its own version is what breaks.
MUST_STAY_UNDEFINED = [
    "HAVE_SHADOW_H",
    # getrrsetbyname.c needs glibc's internal resolver state layout.
    "HAVE_GET_RRSET_BY_NAME",
    # Functions configure's cross-compile probes wrongly "found". bionic gained
    # these at API 28-34, and RedTerm targets API 24, so letting OpenSSH call
    # them produces undefined references at link time.
    "HAVE_CLOSE_RANGE",        # glibc 2.34, bionic API 34
    "HAVE_MEMCCPY",            # glibc 2.35, bionic API 34
    "HAVE_GETENTROPY",         # bionic API 28
    "HAVE_PIDFD_OPEN",         # bionic API 30
    "HAVE_EXECVEAT",           # bionic API 30
    "HAVE_FACCESSAT2",         # bionic API 33
    "HAVE_STATMOUNT",          # bionic API 34
    # Not a real function: a typo in configure's reallocarray probe, which it
    # "detects" because the undeclared call compiles with a warning.
    "HAVE_RECALLOCARRAY",
    # These were wrongly listed as provided by bionic. Verified against the
    # API 24 sysroot: neither libc.so nor libc.a defines reallocarray (API 26)
    # or nl_langinfo (API 26), and <stdlib.h>/<langinfo.h> do not even declare
    # them. Claiming they exist made OpenSSH call them, which failed to link a
    # dynamic client with undefined references. Undefining both sends reallocarray
    # to OpenSSH's own openbsd-compat/reallocarray.c, and makes utf8.c's
    # dangerous_locale() skip the codeset check, which is the correct behaviour
    # for a tool that always sets its own locale.
    "HAVE_REALLOCARRAY",
    "HAVE_NL_LANGINFO",
    "HAVE_LANGINFO_H",
]


def fix_config_h(path):
    with open(path, "r", encoding="utf-8", errors="surrogateescape") as fh:
        text = fh.read()
    changed = []

    def set_define(name, defined):
        nonlocal text
        undef = "/* #undef %s */" % name
        define = "#define %s 1" % name
        if defined:
            if undef in text:
                text = text.replace(undef, define)
                changed.append(name)
        else:
            if define in text:
                text = text.replace(define, undef)
                changed.append(name)

    for name in ATTRIBUTES:
        set_define(name, True)
    # Provided by native/ssh_compat.c, since bionic has no bzero symbol to
    # take the address of.
    set_define("HAVE_EXPLICIT_BZERO", True)
    for name in BIONIC_PROVIDES:
        set_define(name, True)
    for name in MUST_STAY_UNDEFINED:
        set_define(name, False)

    with open(path, "w", encoding="utf-8", errors="surrogateescape") as fh:
        fh.write(text)
    return changed


def fix_xcrypt(path):
    with open(path, "r", encoding="utf-8", errors="surrogateescape") as fh:
        text = fh.read()
    if "REDUCTERM_PICK_SALT_STUB" in text:
        return False
    pattern = re.compile(
        r"static const char \*\n(pick_salt\(void\)\n\{.*?\n\}\n)", re.S
    )
    match = pattern.search(text)
    if not match:
        return False
    body = match.group(1)
    stub = (
        "/* REDUCTERM_PICK_SALT_STUB\n"
        " * bionic has no <shadow.h> before API 24, so getpwent() and friends do not\n"
        " * exist. pick_salt() is only reached from crypt(3) authentication on the\n"
        " * server side, which RedTerm does not build. A fixed salt is correct here.\n"
        " */\n"
        "#if defined(HAVE_SHADOW_H) && !defined(DISABLE_SHADOW)\n"
        + body +
        "#else\n"
        "static const char *\n"
        "pick_salt(void)\n"
        "{\n"
        "\tstatic char salt[32];\n"
        "\n"
        "\tstrlcpy(salt, \"xx\", sizeof(salt));\n"
        "\treturn salt;\n"
        "}\n"
        "#endif\n"
    )
    text = text[: match.start()] + stub + text[match.end():]
    with open(path, "w", encoding="utf-8", errors="surrogateescape") as fh:
        fh.write(text)
    return True


def fix_openbsd_compat_h(path):
    """Declares explicit_bzero unconditionally.

    OpenSSH guards both the declaration and its own definition behind
    #ifndef HAVE_EXPLICIT_BZERO. RedTerm defines that macro (bionic has no bzero
    symbol for OpenSSH to take the address of) and supplies the implementation
    from native/ssh_compat.c, so the prototype has to be visible to callers.
    """
    with open(path, "r", encoding="utf-8", errors="surrogateescape") as fh:
        text = fh.read()
    old = ("#ifndef HAVE_EXPLICIT_BZERO\n"
           "void explicit_bzero(void *p, size_t n);\n"
           "#endif")
    new = ("/* REDUCTERM_EXPLICIT_BZERO_DECL: always declared, provided by "
           "native/ssh_compat.c */\n"
           "void explicit_bzero(void *p, size_t n);")
    if new in text:
        return False
    if old not in text:
        return False
    text = text.replace(old, new, 1)
    with open(path, "w", encoding="utf-8", errors="surrogateescape") as fh:
        fh.write(text)
    return True


def stub_getrrsetbyname(path):
    """Replaces getrrsetbyname.c with a stub.

    The real implementation needs glibc's internal resolver state (res.h,
    struct __res_state, RES_INIT). bionic ships none of it, so the file cannot
    compile. Returning -1 makes SSHFP host-key verification report a DNS lookup
    error and fall back to the normal trust path, which is exactly what a failed
    lookup does on a platform that does support it.
    """
    stub = """/* REDUCTERM_GETRRSETBYNAME_STUB
 *
 * bionic has no <res.h> and no struct __res_state, so OpenSSH's resolver
 * internals cannot be built here. Reporting a lookup failure keeps host-key DNS
 * verification optional rather than fatal.
 */
#include <stdlib.h>

#include "getrrsetbyname.h"

int
getrrsetbyname(const char *hostname, unsigned int rdclass, unsigned int rdtype,
    unsigned int flags, struct rrsetinfo **rri)
{
	(void)hostname; (void)rdclass; (void)rdtype; (void)flags;
	if (rri != NULL)
		*rri = NULL;
	return -1;
}

void
freerrset(struct rrsetinfo *rri)
{
	free(rri);
}
"""
    with open(path, "w", encoding="utf-8", errors="surrogateescape") as fh:
        fh.write(stub)
    return True


def fix_explicit_bzero(path):
    """bzero() is a macro in bionic's <strings.h>, so the file that takes its
    address needs that header. Without it clang reports an implicit
    declaration, because configure saw HAVE_BZERO and skipped OpenSSH's own
    bzero()."""
    with open(path, "r", encoding="utf-8", errors="surrogateescape") as fh:
        text = fh.read()
    if "REDUCTERM_STRINGS_H" in text:
        return False
    marker = "#include <string.h>"
    if marker not in text:
        return False
    text = text.replace(
        marker,
        marker + "\n#include <strings.h> /* REDUCTERM_STRINGS_H: bzero lives here on bionic */",
        1)
    with open(path, "w", encoding="utf-8", errors="surrogateescape") as fh:
        fh.write(text)
    return True


def fix_includes_h(path):
    """Adds <malloc.h> and <langinfo.h> to includes.h.

    Two declarations OpenSSH assumes some other header provides:

    * reallocarray() lives in bionic's <malloc.h>, not <stdlib.h>, and is only
      declared from API 29 even though the implementation is in libc.a. So once
      HAVE_REALLOCARRAY tells OpenSSH to skip its own copy, the declaration has to
      be added by hand.
    * nl_langinfo() needs <langinfo.h>, which nothing in the tree includes.

    includes.h is included by every OpenSSH source file, so fixing it once here
    is enough.
    """
    with open(path, "r", encoding="utf-8", errors="surrogateescape") as fh:
        text = fh.read()
    if "REDUCTERM_COMPAT_INCLUDES" in text:
        return False
    marker = "#include <sys/types.h>"
    if marker not in text:
        return False
    block = (
        marker
        + "\n/* REDUCTERM_COMPAT_INCLUDES */"
        + "\n#include <malloc.h>"
        + "\n#include <langinfo.h>"
        + "\n/* bionic only declares reallocarray() from API 29, but the implementation"
        + "\n * is present in libc.a, so declaring and using it here is safe. */"
        + "\n#if !defined(__ANDROID_API__) || __ANDROID_API__ < 29"
        + "\nvoid *reallocarray(void *ptr, size_t nmemb, size_t size);"
        + "\n#endif"
        + "\n/* Likewise nl_langinfo() is declared from API 26. */"
        + "\n#if !defined(__ANDROID_API__) || __ANDROID_API__ < 26"
        + "\nchar *nl_langinfo(nl_item item);"
        + "\n#endif"
    )
    text = text.replace(marker, block, 1)
    with open(path, "w", encoding="utf-8", errors="surrogateescape") as fh:
        fh.write(text)
    return True


def fix_makefile(path, compat_obj):
    """Adds our compat object to the link.

    The link line itself is left alone: native/build-openssh.sh installs a
    compiler wrapper that turns OpenSSH's -pie into a static link, because
    LDFLAGS also carries the -L. / -Lopenbsd-compat entries that resolve
    libssh.a and so cannot simply be overridden on the make command line.
    """
    with open(path, "r", encoding="utf-8", errors="surrogateescape") as fh:
        lines = fh.readlines()
    out = []
    changed = False
    for line in lines:
        if line.startswith("LIBS=") and (
            line.rstrip("\n") == "LIBS=" or "ssh_compat.o" in line
        ):
            line = "LIBS=%s\n" % compat_obj
            changed = True
        out.append(line)
    if changed:
        with open(path, "w", encoding="utf-8", errors="surrogateescape") as fh:
            fh.writelines(out)
    return changed


def main():
    if len(sys.argv) != 2:
        print("usage: openssh-fixes.py <openssh-source-dir>", file=sys.stderr)
        return 2
    root = sys.argv[1]
    config = os.path.join(root, "config.h")
    if not os.path.exists(config):
        print("config.h not found in %s" % root, file=sys.stderr)
        return 1
    compat_obj = os.environ.get("REDUCTERM_COMPAT_OBJ", "ssh_compat.o")
    makefile = os.path.join(root, "Makefile")
    if os.path.exists(makefile) and fix_makefile(makefile, compat_obj):
        print("   Makefile: static link + %s" % compat_obj)
    includes_h = os.path.join(root, "includes.h")
    if os.path.exists(includes_h) and fix_includes_h(includes_h):
        print("   includes.h: added <malloc.h>/<langinfo.h>")
    defines = fix_config_h(config)
    print("   config.h: %d define(s) adjusted" % len(defines))
    xcrypt = os.path.join(root, "openbsd-compat", "xcrypt.c")
    if os.path.exists(xcrypt) and fix_xcrypt(xcrypt):
        print("   xcrypt.c: pick_salt() stubbed for bionic")
    compat_h = os.path.join(root, "openbsd-compat", "openbsd-compat.h")
    if os.path.exists(compat_h) and fix_openbsd_compat_h(compat_h):
        print("   openbsd-compat.h: explicit_bzero() declared unconditionally")
    rrset = os.path.join(root, "openbsd-compat", "getrrsetbyname.c")
    if os.path.exists(rrset) and stub_getrrsetbyname(rrset):
        print("   getrrsetbyname.c: stubbed (bionic has no resolver internals)")
    explicit = os.path.join(root, "openbsd-compat", "explicit_bzero.c")
    if os.path.exists(explicit) and fix_explicit_bzero(explicit):
        print("   explicit_bzero.c: added <strings.h> for bzero")
    return 0


if __name__ == "__main__":
    sys.exit(main())
