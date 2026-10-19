/*
 * The JNI surface of libredtermmosh.so.
 *
 * Phase 1's whole job is a library that loads and demonstrably works on a device, so
 * this is deliberately a real surface and not an empty one. An earlier probe of this
 * build linked a 5,216-byte object with a single dynamic symbol -- it proved the
 * archives were on disk and nothing else, because nothing referenced anything. The
 * link now runs with --no-undefined, so a reference that cannot be satisfied is a
 * build failure rather than a library that quietly does nothing.
 *
 * This file is where the rules from AGENTS.md about secrets are load-bearing. The
 * session key is a secret: it must never reach a command line, a launcher script or
 * AppLog. It is passed as bytes and lives only in this library's memory for the life
 * of the session.
 */

#include <jni.h>

#include <string.h>
#include <stdio.h>

#include <string>

#include "crypto/crypto.h"
#include "redterm_mosh_client.h"

#define REDTERM_MOSH_VERSION "1.4.0"

extern "C" {

/*
 * Round-trips a message through mosh's authenticated encryption and checks the
 * plaintext comes back, including the nonce.
 *
 * This is the smallest slice of mosh that touches every dependency the JNI layer will
 * rely on: mosh's internal OCB, nettle's AES, base64, and the Nonce/Message value
 * types. If this returns 1 on a device then the native side is sound end to end.
 *
 *   1  round trip correct
 *   0  round trip wrong
 *  -1  an exception escaped
 */
JNIEXPORT jint JNICALL
Java_com_redtermapp_mosh_MoshNative_nativeSelfTest(JNIEnv *, jclass)
{
	try {
		Crypto::Base64Key key;
		Crypto::Session session(key);

		Crypto::Nonce nonce(static_cast<uint64_t>(0x0102030405060708ULL));
		const std::string plaintext("redterm-mosh-probe");

		const std::string ciphertext = session.encrypt(Crypto::Message(nonce, plaintext));

		/* Encrypting to nothing, or echoing the input back, would both satisfy a
		 * length check, so they are rejected before the round trip is even tried. */
		if (ciphertext.empty() || ciphertext == plaintext) {
			return 0;
		}

		const Crypto::Message back = session.decrypt(ciphertext.data(), ciphertext.size());
		if (back.text != plaintext) {
			return 0;
		}

		/* The nonce has to survive too, or two messages are interchangeable and the
		 * replay protection the server relies on is gone. */
		if (back.nonce.val() != nonce.val()) {
			return 0;
		}
		return 1;
	} catch (...) {
		/* mosh throws std::exception subclasses (CryptoException). A JNI boundary that
		 * lets one escape terminates the process, and a crashed app is how the first
		 * version of this reported its own failures. */
		return -1;
	}
}

JNIEXPORT jstring JNICALL
Java_com_redtermapp_mosh_MoshNative_nativeVersion(JNIEnv *env, jclass)
{
	return env->NewStringUTF(REDTERM_MOSH_VERSION);
}

/*
 * A session handle, kept as a raw pointer in a jlong.
 *
 * The alternative is a global table keyed by an int, and the table needs a lock and a
 * leak story for no benefit: the Java side owns the handle's lifetime, hands it back to
 * every call, and calls nativeFree when it is done. nativeStop joins the worker thread
 * before the object is destroyed, so no call can ever reach a freed MoshSession.
 */
static MoshSession *as_session(jlong handle)
{
	return reinterpret_cast<MoshSession *>(handle);
}

/*
 * Starts a session against an already-running mosh-server.
 *
 * `key` is the secret half of MOSH_KEY. It arrives from bundled ssh's output, so it
 * never appears in a command line or a launcher script on the device, and it is never
 * logged here -- returning an error string that describes the failure is enough, and the
 * string never contains the key.
 *
 * Returns a non-null handle, or 0 with `outError` set to something readable. A failure
 * the caller cannot read is a failure nobody can fix, so the message is what the remote
 * actually said wherever that is available.
 */
JNIEXPORT jlong JNICALL
Java_com_redtermapp_mosh_MoshNative_nativeStart(JNIEnv *env, jclass, jstring key, jstring ip,
                                                jint port, jint cols, jint rows, jobject outError)
{
	const char *keyChars = key ? env->GetStringUTFChars(key, NULL) : NULL;
	const char *ipChars = ip ? env->GetStringUTFChars(ip, NULL) : NULL;
	jlong result = 0;

	if (!keyChars || !ipChars) {
		if (keyChars) {
			env->ReleaseStringUTFChars(key, keyChars);
		}
		if (ipChars) {
			env->ReleaseStringUTFChars(ip, ipChars);
		}
		return 0;
	}

	char errbuf[512];
	errbuf[0] = '\0';

	/* The C API takes the port as a string, because that is how mosh's own client gets
	 * it -- straight out of the bootstrap output's third field. Rendering it here keeps
	 * that a detail of this file rather than something every caller has to remember. */
	char portbuf[16];
	snprintf(portbuf, sizeof(portbuf), "%d", static_cast<int>(port));

	try {
		MoshSession *s = mosh_session_start(keyChars, ipChars, portbuf,
		                                    static_cast<int>(cols), static_cast<int>(rows), errbuf,
		                                    sizeof(errbuf));
		if (s) {
			/* The key was copied into the session; this is the last chance to let the
			 * JVM's copy of the secret go. */
			memset((void *)keyChars, 0, strlen(keyChars));
			result = reinterpret_cast<jlong>(s);
		}
	} catch (...) {
		errbuf[0] = '\0';
		snprintf(errbuf, sizeof(errbuf), "could not start: unknown error");
	}

	if (keyChars) {
		memset((void *)keyChars, 0, strlen(keyChars));
		env->ReleaseStringUTFChars(key, keyChars);
	}
	if (ipChars) {
		env->ReleaseStringUTFChars(ip, ipChars);
	}

	if (result == 0 && outError != NULL) {
		jstring msg = env->NewStringUTF(errbuf[0] ? errbuf : "could not start");
		if (msg) {
			const char *msgChars = env->GetStringUTFChars(msg, NULL);
			if (msgChars) {
				jclass cls = env->GetObjectClass(outError);
				jmethodID set = cls ? env->GetMethodID(cls, "set", "(Ljava/lang/String;)V") : NULL;
				if (set) {
					env->CallVoidMethod(outError, set, msg);
				}
				env->ReleaseStringUTFChars(msg, msgChars);
			}
		}
	}
	return result;
}

JNIEXPORT void JNICALL
Java_com_redtermapp_mosh_MoshNative_nativeStop(JNIEnv *, jclass, jlong handle)
{
	MoshSession *s = as_session(handle);
	if (!s) {
		return;
	}
	/* stop() joins the worker thread, so nothing is using `s` after this returns. */
	mosh_session_stop(s);
}

JNIEXPORT void JNICALL
Java_com_redtermapp_mosh_MoshNative_nativeFree(JNIEnv *, jclass, jlong handle)
{
	MoshSession *s = as_session(handle);
	if (s) {
		mosh_session_free(s);
	}
}

/* Keystrokes in, remote bytes out. Byte-at-a-time in both directions is the terminal's
 * own model, and mosh's diffing is what makes it cheap on the wire. */
JNIEXPORT void JNICALL
Java_com_redtermapp_mosh_MoshNative_nativeInput(JNIEnv *env, jclass, jlong handle, jbyteArray data)
{
	MoshSession *s = as_session(handle);
	if (!s || !data) {
		return;
	}
	const jsize n = env->GetArrayLength(data);
	if (n <= 0) {
		return;
	}
	jbyte *bytes = env->GetByteArrayElements(data, NULL);
	if (bytes) {
		mosh_session_input(s, reinterpret_cast<const uint8_t *>(bytes), static_cast<size_t>(n));
		env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
	}
}

/* Rows and columns, not width and height: mosh's Resize takes rows first, and the two
 * swapped is a terminal that is 80x24 in a portrait phone rather than an error. */
JNIEXPORT void JNICALL
Java_com_redtermapp_mosh_MoshNative_nativeResize(JNIEnv *, jclass, jlong handle, jint cols, jint rows)
{
	MoshSession *s = as_session(handle);
	if (!s) {
		return;
	}
	mosh_session_resize(s, static_cast<int>(cols), static_cast<int>(rows));
}

/*
 * Returns however many bytes the remote has waiting, which may be 0.
 *
 * "0" and "the socket closed" are not the same thing, and a caller cannot tell them
 * apart without this: a mosh server that has gone away leaves the UDP socket readable
 * with nothing in it, which looks exactly like an idle connection. mosh's own
 * loop answers that question; this only reports what is already in the model.
 */
JNIEXPORT jint JNICALL
Java_com_redtermapp_mosh_MoshNative_nativePoll(JNIEnv *env, jclass, jlong handle, jbyteArray out)
{
	MoshSession *s = as_session(handle);
	if (!s || !out) {
		return 0;
	}
	const jsize capacity = env->GetArrayLength(out);
	if (capacity <= 0) {
		return 0;
	}
	jbyte *bytes = env->GetByteArrayElements(out, NULL);
	if (!bytes) {
		return 0;
	}
	const size_t n = mosh_session_poll(s, reinterpret_cast<uint8_t *>(bytes), static_cast<size_t>(capacity));
	if (n > 0) {
		env->SetByteArrayRegion(out, 0, static_cast<jsize>(n), bytes);
	}
	env->ReleaseByteArrayElements(out, bytes, 0);
	return static_cast<jint>(n);
}

JNIEXPORT jboolean JNICALL
Java_com_redtermapp_mosh_MoshNative_nativeIsConnected(JNIEnv *, jclass, jlong handle)
{
	MoshSession *s = as_session(handle);
	return (s && mosh_session_is_connected(s)) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Milliseconds until mosh's own transport wants attention again, already clamped by the
 * session to a sane maximum.
 *
 * Java should not busy-wait on this being small: mosh's timers are in milliseconds and a
 * transport with nothing to do reports a long wait, which is the normal case.
 */
JNIEXPORT jint JNICALL
Java_com_redtermapp_mosh_MoshNative_nativeWaitTime(JNIEnv *, jclass, jlong handle)
{
	MoshSession *s = as_session(handle);
	if (!s) {
		return -1;
	}
	const int wait = mosh_session_wait_time(s);
	return static_cast<jint>(wait < 0 ? 0 : wait);
}

JNIEXPORT jstring JNICALL
Java_com_redtermapp_mosh_MoshNative_nativeLastError(JNIEnv *env, jclass, jlong handle)
{
	MoshSession *s = as_session(handle);
	const char *why = s ? mosh_session_last_error(s) : "";
	return env->NewStringUTF(why ? why : "");
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *)
{
	return JNI_VERSION_1_6;
}

} /* extern "C" */