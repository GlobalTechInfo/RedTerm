/*
 * Deliberately trivial, built by native/build-openssh.sh with exactly the same
 * link recipe as the ssh client.
 *
 * It exists to bisect a failure that otherwise has two indistinguishable
 * causes. If this prints "probe-ok" through the app's proot launcher then the
 * crt objects, -nostartfiles, -static, page size and libproot-loader.so are all
 * fine, and a crash in ssh is OpenSSL/OpenSSH's. If this segfaults too then the
 * link recipe itself is wrong, and ssh was never the problem.
 */
#include <stdio.h>
#include <unistd.h>
#include <sys/auxv.h>

int main(int argc, char **argv) {
    (void)argv;
    fprintf(stdout, "probe-ok argc=%d pid=%d\n", argc, (int)getpid());
    fprintf(stderr, "probe-stderr-ok\n");
    fflush(NULL);
    /* Touch a couple of startup services so a broken libc shows up here too. */
    fprintf(stdout, "page-size=%lu uid=%d\n",
            (unsigned long)getauxval(AT_PAGESZ), (int)getuid());
    fflush(NULL);
    return 0;
}
