#!/usr/bin/env python3
"""
Insert progress markers into OpenSSH's ssh.c so a crash can be localised without
a debugger on the device.

Every probe passes, so the link recipe, proot's loader, early constructors, the
static libcrypto and OpenSSH's hardening flags are all sound, yet `ssh -V` dies
before printing anything. A native crash leaves no tombstone here because the
process is proot's ptrace'd child, so the only signal is "how far did it get".

These use raw write(2) rather than stdio, so a marker still appears when the crash
happens early enough that stdio is not yet usable.
"""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "ssh.c"
src = open(path).read()

if "REDUCTERM_MARK" in src:
    print("mark-client: already patched")
    sys.exit(0)

applied = []
skipped = []


def insert_after(pattern, text, label):
    """Inserts text just after the line matching pattern."""
    global src
    m = re.search(pattern, src, re.M)
    if not m:
        skipped.append(label)
        return
    src = src[:m.end()] + text + src[m.end():]
    applied.append(label)


def insert_before(pattern, text, label, count=1):
    """Inserts text before the first match of pattern, or the line after it."""
    global src
    m = re.search(pattern, src)
    if not m:
        skipped.append(label)
        return
    src = src[:m.start()] + text + src[m.start():]
    applied.append(label)


helper = '''/* RedTerm diagnostics: see native/mark-client.py. */
#include <unistd.h>
#define REDUCTERM_MARK(s) do { (void)!write(2, (s), sizeof(s) - 1); } while (0)
__attribute__((constructor)) static void redterm_mark_ctor(void) {
    REDUCTERM_MARK("M0-ctor\\n");
}

'''

# The helper, plus a constructor of its own, goes just above main.
main_def = re.search(r"^int\nmain\(int ac, char \*\*av\)\n\{", src, re.M)
if not main_def:
    print("mark-client: could not find main() in %s" % path, file=sys.stderr)
    sys.exit(1)
src = src[:main_def.start()] + helper + src[main_def.start():]
applied.append("helper+ctor")

# First thing inside main: if M1 never appears, the crash is before main, in
# libc start-up or in an earlier constructor.
m = re.search(r"^main\(int ac, char \*\*av\)\n\{\n", src, re.M)
if m:
    at = m.end()
    src = src[:at] + '\tREDUCTERM_MARK("M1-main\\n");\n' + src[at:]
    applied.append("M1")
else:
    skipped.append("M1")

# Immediately before the options struct is initialised, so M2 means main is
# running and stdio's file descriptors are still intact.
insert_before(
    r"(?m)^\tinitialize_options\(&options\);",
    '\tREDUCTERM_MARK("M2-pre-options\\n");\n',
    "M2",
)

# After initialising options, i.e. readconf.c's defaults are in place.
m = re.search(r"(?m)^\tinitialize_options\(&options\);\n", src)
if m:
    src = src[:m.end()] + '\tREDUCTERM_MARK("M3-post-options\\n");\n' + src[m.end():]
    applied.append("M3")
else:
    skipped.append("M3")

# main() is entered and the client still dies before M2, so bracket each of the
# eight statements in between.
insert_after(
    r"(?m)^\tsanitise_stdfd\(\);\n",
    '\tREDUCTERM_MARK("M5-stdfd\\n");\n', "M5",
)
insert_after(
    r"(?m)^\tclosefrom\(STDERR_FILENO \+ 1\);\n",
    '\tREDUCTERM_MARK("M6-closefrom\\n");\n', "M6",
)
insert_after(
    r"(?m)^\t__progname = ssh_get_progname\(av\[0\]\);\n",
    '\tREDUCTERM_MARK("M7-progname\\n");\n', "M7",
)
# Before seed_rng: the setproctitle block above it is the one with conditionals.
insert_before(
    r"(?m)^\tseed_rng\(\);\n",
    '\tREDUCTERM_MARK("M8-pre-seedrng\\n");\n', "M8",
)
insert_after(
    r"(?m)^\tseed_rng\(\);\n",
    '\tREDUCTERM_MARK("M9-seedrng\\n");\n', "M9",
)
insert_after(
    r"(?m)^\tpw = pwcopy\(pw\);\n",
    '\tREDUCTERM_MARK("M10-getpwuid\\n");\n', "M10",
)
insert_after(
    r"(?m)^\tumask\(022 \| umask\(077\)\);\n",
    '\tREDUCTERM_MARK("M11-umask\\n");\n', "M11",
)

# The -V branch, immediately *after* the label. Inserting before it lands after
# the previous case's break, which is dead code the compiler discards.
m = re.search(r"(?m)^(\t\tcase 'V':\n)", src)
if m:
    at = m.end()
    src = src[:at] + '\t\t\tREDUCTERM_MARK("M4-version-case\\n");\n' + src[at:]
    applied.append("M4")
else:
    skipped.append("M4")

open(path, "w").write(src)
print("mark-client: applied %s" % ", ".join(applied))
if skipped:
    print("mark-client: NOT applied %s (anchors moved?)" % ", ".join(skipped))
