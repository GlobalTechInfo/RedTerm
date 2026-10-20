/*
 * The C API of the mosh client. See redterm_mosh_client.cpp.
 *
 * Deliberately C so the JNI layer and the host harness in
 * /home/ubuntu/.cache/redterm-build/host_harness.c can both use it without a C++
 * toolchain of their own.
 */
#ifndef REDTERM_MOSH_CLIENT_H
#define REDTERM_MOSH_CLIENT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct MoshSession MoshSession;

MoshSession *mosh_session_start( const char *key, const char *ip, const char *port,
                                 int cols, int rows, char *errbuf, size_t errlen );
void mosh_session_stop( MoshSession *s );
void mosh_session_free( MoshSession *s );
void mosh_session_input( MoshSession *s, const uint8_t *bytes, size_t n );
void mosh_session_resize( MoshSession *s, int cols, int rows );
size_t mosh_session_poll( MoshSession *s, uint8_t *out, size_t maxlen );
int mosh_session_is_connected( MoshSession *s );
int mosh_session_wait_time( MoshSession *s );
int mosh_session_network_errors( MoshSession *s );
size_t mosh_session_last_error( MoshSession *s, char *out, size_t outlen );

#ifdef __cplusplus
}
#endif

#endif /* REDTERM_MOSH_CLIENT_H */
