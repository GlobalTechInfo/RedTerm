#include <jni.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <pty.h>
#include <utmp.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <android/log.h>

#define TAG "Subprocess"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static struct sigaction old_sigchld;

static void sigchld_handler(int signo) {
    int saved_errno = errno;
    while (waitpid(-1, NULL, WNOHANG) > 0);
    errno = saved_errno;
}

static int create_pty_process(const char* shell_path, char* const args[],
                               char* const envp[], int rows, int cols) {
    int ptm_fd;
    pid_t pid;

    struct winsize winsz = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols,
        .ws_xpixel = 0,
        .ws_ypixel = 0
    };

    ptm_fd = posix_openpt(O_RDWR | O_NOCTTY);
    if (ptm_fd < 0) {
        LOGE("posix_openpt failed: %s", strerror(errno));
        return -1;
    }

    if (grantpt(ptm_fd) < 0) {
        LOGE("grantpt failed: %s", strerror(errno));
        close(ptm_fd);
        return -1;
    }

    if (unlockpt(ptm_fd) < 0) {
        LOGE("unlockpt failed: %s", strerror(errno));
        close(ptm_fd);
        return -1;
    }

    pid = fork();
    if (pid < 0) {
        LOGE("fork failed: %s", strerror(errno));
        close(ptm_fd);
        return -1;
    }

    if (pid == 0) {
        /* Child process */
        const char* pts_name = ptsname(ptm_fd);
        if (pts_name == NULL) {
            LOGE("ptsname failed");
            _exit(1);
        }

        close(ptm_fd);

        int pts_fd = open(pts_name, O_RDWR);
        if (pts_fd < 0) {
            LOGE("open pts failed: %s", strerror(errno));
            _exit(1);
        }

        setsid();

        ioctl(pts_fd, TIOCSCTTY, NULL);
        ioctl(pts_fd, TIOCSWINSZ, &winsz);

        dup2(pts_fd, STDIN_FILENO);
        dup2(pts_fd, STDOUT_FILENO);
        dup2(pts_fd, STDERR_FILENO);

        for (int i = 3; i < 256; i++) {
            close(i);
        }

        execve(shell_path, args, envp);

        LOGE("execve failed: %s", strerror(errno));
        _exit(127);
    }

    /* Parent */
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sigchld_handler;
    sigaction(SIGCHLD, &sa, &old_sigchld);

    ioctl(ptm_fd, TIOCSWINSZ, &winsz);

    return ptm_fd;
}

JNIEXPORT jint JNICALL
Java_com_redtermapp_jni_NativeSubprocess_createSubprocess(
    JNIEnv* env, jclass clazz,
    jstring shellPath, jobjectArray args, jobjectArray envp,
    jint rows, jint cols) {

    const char* shell_path = (*env)->GetStringUTFChars(env, shellPath, NULL);
    if (shell_path == NULL) return -1;

    int arg_count = (*env)->GetArrayLength(env, args);
    char** c_args = calloc(arg_count + 2, sizeof(char*));
    if (c_args == NULL) {
        (*env)->ReleaseStringUTFChars(env, shellPath, shell_path);
        return -1;
    }

    c_args[0] = strdup(shell_path);
    for (int i = 0; i < arg_count; i++) {
        jstring js = (*env)->GetObjectArrayElement(env, args, i);
        const char* s = (*env)->GetStringUTFChars(env, js, NULL);
        c_args[i + 1] = strdup(s ? s : "");
        (*env)->ReleaseStringUTFChars(env, js, s);
    }
    c_args[arg_count + 1] = NULL;

    int env_count = (*env)->GetArrayLength(env, envp);
    char** c_envp = calloc(env_count + 1, sizeof(char*));
    if (c_envp == NULL) {
        (*env)->ReleaseStringUTFChars(env, shellPath, shell_path);
        for (int i = 0; i <= arg_count; i++) free(c_args[i]);
        free(c_args);
        return -1;
    }

    for (int i = 0; i < env_count; i++) {
        jstring js = (*env)->GetObjectArrayElement(env, envp, i);
        const char* s = (*env)->GetStringUTFChars(env, js, NULL);
        c_envp[i] = strdup(s ? s : "");
        (*env)->ReleaseStringUTFChars(env, js, s);
    }
    c_envp[env_count] = NULL;

    int fd = create_pty_process(shell_path, c_args, c_envp, rows, cols);

    (*env)->ReleaseStringUTFChars(env, shellPath, shell_path);
    for (int i = 0; i <= arg_count; i++) free(c_args[i]);
    free(c_args);
    for (int i = 0; i < env_count; i++) free(c_envp[i]);
    free(c_envp);

    return fd;
}

JNIEXPORT void JNICALL
Java_com_redtermapp_jni_NativeSubprocess_setWindowSize(
    JNIEnv* env, jclass clazz, jint fd, jint rows, jint cols) {

    struct winsize winsz = {
        .ws_row = (unsigned short)rows,
        .ws_col = (unsigned short)cols,
        .ws_xpixel = 0,
        .ws_ypixel = 0
    };
    ioctl((int)fd, TIOCSWINSZ, &winsz);
}

JNIEXPORT jint JNICALL
Java_com_redtermapp_jni_NativeSubprocess_writeToProcess(
    JNIEnv* env, jclass clazz, jint fd, jbyteArray data) {

    jsize len = (*env)->GetArrayLength(env, data);
    jbyte* buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (buf == NULL) return -1;

    ssize_t written = write((int)fd, buf, (size_t)len);

    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return (jint)written;
}

JNIEXPORT jint JNICALL
Java_com_redtermapp_jni_NativeSubprocess_readFromProcess(
    JNIEnv* env, jclass clazz, jint fd, jbyteArray buffer) {

    jsize len = (*env)->GetArrayLength(env, buffer);
    jbyte* buf = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (buf == NULL) return -1;

    ssize_t n = read((int)fd, buf, (size_t)len);

    (*env)->ReleaseByteArrayElements(env, buffer, buf, 0);

    return (n < 0) ? -1 : (jint)n;
}

JNIEXPORT void JNICALL
Java_com_redtermapp_jni_NativeSubprocess_closeProcess(
    JNIEnv* env, jclass clazz, jint fd) {

    close((int)fd);
}
