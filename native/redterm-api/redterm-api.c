/*
 * redterm-api — the guest-side half of RedTerm's termux:API bridge.
 *
 * Termux:API's native helper speaks to its app over an abstract Unix socket:
 * a uint16 LE length, then the text of a fake `am` command line, which the
 * app parses into an action and extras and answers on the same connection
 * (again length-prefixed). This helper does the same for RedTerm, with an
 * argument surface that termux-style scripts understand:
 *
 *   redterm-api [--user 0] -a <action> [--es k "v"] [--esa k "a,b"] \
 *               [--ei k n] [--ez k true|false] [--ef k f]
 *
 * Value tokens following an option are quoted and escaped before the
 * request is sent, because the app side parses quoted values — a raw
 * join would break on the first space inside a value.
 *
 * The app answers with the result (JSON for battery-status, text for
 * errors) and closes; the body is printed to stdout. When the app's
 * process is not up, the socket refuses and the helper says so rather
 * than hanging: the listener is an app thread, not a system service.
 */

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define API_NAME "com.redtermapp://listen"
#define MAX_REQUEST 8192

/*
 * Which options carry value tokens, and how they are sent. -a/--user are
 * forwarded bare (an action or a uid never carries a space). The --es
 * family takes a bare key and then the value: string values are quoted and
 * escaped, because the app side parses quoted values — a raw join would
 * break on the first space inside one. Numeric and boolean values travel
 * bare, the way Termux:API's own helper sends them: they cannot hold a
 * space, and the app side's grammar expects them unquoted.
 */
static int optionValues(const char *a)
{
    if (strcmp(a, "-e") == 0 || strcmp(a, "--es") == 0 || strcmp(a, "--esa") == 0) {
        return 2; /* bare key, quoted value */
    }
    if (strcmp(a, "--ei") == 0 || strcmp(a, "--ez") == 0 || strcmp(a, "--ef") == 0) {
        return 3; /* bare key, bare value */
    }
    return 0;
}

/* Quote s in the wire grammar: backslash first, then double quote. */
static void appendQuoted(char *buf, size_t *pos, const char *s)
{
    buf[(*pos)++] = '"';
    for (const char *p = s; *p != 0; p++) {
        if (*p == '\\' || *p == '"') {
            if (*pos + 2 >= MAX_REQUEST) goto too_long;
            buf[(*pos)++] = '\\';
        }
        if (*pos + 1 >= MAX_REQUEST) goto too_long;
        buf[(*pos)++] = *p;
    }
    buf[(*pos)++] = '"';
    return;
too_long:
    fprintf(stderr, "redterm-api: request too long\n");
    exit(1);
}

static int connectWithRetry(int fd, const struct sockaddr_un *addr, socklen_t len)
{
    for (int attempt = 0; attempt < 25; attempt++) {
        if (connect(fd, (const struct sockaddr *) addr, len) == 0) return 0;
        int e = errno;
        if (e != ECONNREFUSED && e != EAGAIN && e != ENOENT) return -1;
        usleep(200 * 1000);
    }
    return -1;
}

static int readFully(int fd, void *buf, size_t count)
{
    size_t got = 0;
    while (got < count) {
        ssize_t n = read(fd, (char *) buf + got, count - got);
        if (n <= 0) return -1;
        got += (size_t) n;
    }
    return 0;
}

static int writeFully(int fd, const void *buf, size_t count)
{
    size_t sent = 0;
    while (sent < count) {
        ssize_t n = write(fd, (const char *) buf + sent, count - sent);
        if (n <= 0) return -1;
        sent += (size_t) n;
    }
    return 0;
}

int main(int argc, char **argv)
{
    char request[MAX_REQUEST];
    size_t pos = 0;
    request[0] = 0;

    for (int i = 1; i < argc; i++) {
        const char *arg = argv[i];
        if (pos + strlen(arg) + 2 >= MAX_REQUEST) {
            fprintf(stderr, "redterm-api: request too long\n");
            return 1;
        }
        if (i > 1) request[pos++] = ' ';
        memcpy(request + pos, arg, strlen(arg));
        pos += strlen(arg);
        int trailing = optionValues(arg);
        if (trailing > 0) {
            /* key is a bare word; the value follows, quoted or bare */
            if (i + 1 < argc) {
                i++;
                const char *key = argv[i];
                if (pos + strlen(key) + 2 >= MAX_REQUEST) {
                    fprintf(stderr, "redterm-api: request too long\n");
                    return 1;
                }
                request[pos++] = ' ';
                memcpy(request + pos, key, strlen(key));
                pos += strlen(key);
            }
            if (i + 1 < argc) {
                i++;
                if (trailing == 2) {
                    request[pos++] = ' ';
                    appendQuoted(request, &pos, argv[i]);
                } else {
                    if (pos + strlen(argv[i]) + 2 >= MAX_REQUEST) {
                        fprintf(stderr, "redterm-api: request too long\n");
                        return 1;
                    }
                    request[pos++] = ' ';
                    memcpy(request + pos, argv[i], strlen(argv[i]));
                    pos += strlen(argv[i]);
                }
            }
        }
    }

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        perror("redterm-api: socket");
        return 1;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    addr.sun_path[0] = 0; /* abstract namespace */
    const char *name = API_NAME;
    memcpy(addr.sun_path + 1, name, strlen(name));
    /*
     * The kernel's abstract name is sun_path[1] up to the byte covered by
     * addrlen; addrlen must therefore reach offsetof(sun_path)+1+strlen,
     * not strlen+1. A short addrlen silently truncates the name and the
     * connect lands on an empty address, which reads as ECONNREFUSED.
     */
    socklen_t addrLen = (socklen_t) (offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name));

    if (connectWithRetry(fd, &addr, addrLen) != 0) {
        fprintf(stderr, "redterm-api: cannot reach the RedTerm app: %s "
                       "(start RedTerm first; the API listener lives in its process)\n",
               strerror(errno));
        close(fd);
        return 1;
    }

    uint16_t reqLen = (uint16_t) pos;
    uint8_t header[2] = { (uint8_t) (reqLen & 0xff), (uint8_t) (reqLen >> 8) };
    if (writeFully(fd, header, 2) != 0 || writeFully(fd, request, pos) != 0) {
        fprintf(stderr, "redterm-api: send failed: %s\n", strerror(errno));
        close(fd);
        return 1;
    }

    uint8_t respHeader[2];
    if (readFully(fd, respHeader, 2) != 0) {
        fprintf(stderr, "redterm-api: no response from the app\n");
        close(fd);
        return 1;
    }
    uint16_t respLen = (uint16_t) (respHeader[0] | (respHeader[1] << 8));

    char *response = malloc((size_t) respLen + 1);
    if (response == NULL) {
        fprintf(stderr, "redterm-api: out of memory\n");
        close(fd);
        return 1;
    }
    if (readFully(fd, response, respLen) != 0) {
        fprintf(stderr, "redterm-api: short response from the app\n");
        close(fd);
        free(response);
        return 1;
    }
    response[respLen] = 0;
    close(fd);

    fwrite(response, 1, respLen, stdout);
    if (respLen > 0 && response[respLen - 1] != '\n') fputc('\n', stdout);
    free(response);
    return 0;
}
