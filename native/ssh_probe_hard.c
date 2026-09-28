/*
 * Trivial, but compiled with the exact flag set OpenSSH's own configure probed
 * and enabled: -ftrapv, -fzero-call-used-regs=used, -ftrivial-auto-var-init,
 * -mretpoline, -fno-builtin-memset, _FORTIFY_SOURCE=2 and the stack protector.
 *
 * configure only *links* its feature tests, it never runs them, so any of these
 * could pass that test and still fault at runtime. This carries all of them and
 * none of OpenSSH's code, which separates the flags from the client.
 */
#include <stdio.h>

int main(void) {
    int total = 0;
    for (int i = 0; i < 8; i++) total += i * 3;   /* exercises -ftrapv */
    fprintf(stdout, "hard-ok total=%d\n", total);
    fflush(stdout);
    return 0;
}
