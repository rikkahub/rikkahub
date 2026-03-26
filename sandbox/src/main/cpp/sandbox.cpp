#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <android/log.h>

#define TAG "Sandbox-PTY"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

static char **jstringArray_to_carray(JNIEnv *env, jobjectArray arr) {
    if (!arr) return nullptr;
    jsize len = env->GetArrayLength(arr);
    char **result = static_cast<char **>(calloc(len + 1, sizeof(char *)));
    if (!result) return nullptr;
    for (jsize i = 0; i < len; i++) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arr, i));
        const char *utf = env->GetStringUTFChars(s, nullptr);
        result[i] = strdup(utf);
        env->ReleaseStringUTFChars(s, utf);
        env->DeleteLocalRef(s);
    }
    return result;
}

static void free_carray(char **arr) {
    if (!arr) return;
    for (int i = 0; arr[i]; i++) free(arr[i]);
    free(arr);
}

JNIEXPORT jintArray JNICALL
Java_me_rerere_sandbox_Pty_nativeExec(
        JNIEnv *env, jclass,
        jstring cmd, jobjectArray argv, jobjectArray envp,
        jstring cwd, jint rows, jint cols) {

    int master = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (master < 0) {
        LOGE("open /dev/ptmx: %s", strerror(errno));
        return nullptr;
    }
    if (grantpt(master) < 0 || unlockpt(master) < 0) {
        LOGE("grantpt/unlockpt: %s", strerror(errno));
        close(master);
        return nullptr;
    }

    char slave_path[256];
    if (ptsname_r(master, slave_path, sizeof(slave_path)) != 0) {
        LOGE("ptsname_r: %s", strerror(errno));
        close(master);
        return nullptr;
    }

    struct winsize ws = {};
    ws.ws_row = static_cast<unsigned short>(rows);
    ws.ws_col = static_cast<unsigned short>(cols);
    ioctl(master, TIOCSWINSZ, &ws);

    const char *c_cmd = env->GetStringUTFChars(cmd, nullptr);
    const char *c_cwd = cwd ? env->GetStringUTFChars(cwd, nullptr) : nullptr;
    char **c_argv = jstringArray_to_carray(env, argv);
    char **c_envp = jstringArray_to_carray(env, envp);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork: %s", strerror(errno));
        close(master);
        env->ReleaseStringUTFChars(cmd, c_cmd);
        if (c_cwd) env->ReleaseStringUTFChars(cwd, c_cwd);
        free_carray(c_argv);
        free_carray(c_envp);
        return nullptr;
    }

    if (pid == 0) {
        // ---- Child process ----
        close(master);
        setsid();

        int slave = open(slave_path, O_RDWR);
        if (slave < 0) _exit(126);

        ioctl(slave, TIOCSCTTY, 0);

        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        if (c_cwd) chdir(c_cwd);

        if (c_envp) {
            execve(c_cmd, c_argv, c_envp);
        } else {
            execvp(c_cmd, c_argv);
        }
        _exit(127);
    }

    // ---- Parent process ----
    env->ReleaseStringUTFChars(cmd, c_cmd);
    if (c_cwd) env->ReleaseStringUTFChars(cwd, c_cwd);
    free_carray(c_argv);
    free_carray(c_envp);

    jintArray result = env->NewIntArray(2);
    jint vals[2] = {master, static_cast<jint>(pid)};
    env->SetIntArrayRegion(result, 0, 2, vals);
    return result;
}

JNIEXPORT void JNICALL
Java_me_rerere_sandbox_Pty_nativeSetWindowSize(
        JNIEnv *, jclass, jint fd, jint rows, jint cols) {
    struct winsize ws = {};
    ws.ws_row = static_cast<unsigned short>(rows);
    ws.ws_col = static_cast<unsigned short>(cols);
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_me_rerere_sandbox_Pty_nativeWaitFor(
        JNIEnv *, jclass, jint pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_me_rerere_sandbox_Pty_nativeClose(
        JNIEnv *, jclass, jint fd) {
    close(fd);
}

JNIEXPORT jint JNICALL
Java_me_rerere_sandbox_Pty_nativeRead(
        JNIEnv *env, jclass, jint fd, jbyteArray buffer, jint len) {
    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    ssize_t n;
    do {
        n = read(fd, buf, len);
    } while (n < 0 && errno == EINTR);
    env->ReleaseByteArrayElements(buffer, buf, n > 0 ? 0 : JNI_ABORT);
    return static_cast<jint>(n);
}

JNIEXPORT jint JNICALL
Java_me_rerere_sandbox_Pty_nativeWrite(
        JNIEnv *env, jclass, jint fd, jbyteArray data, jint len) {
    jbyte *buf = env->GetByteArrayElements(data, nullptr);
    ssize_t written;
    do {
        written = write(fd, buf, len);
    } while (written < 0 && errno == EINTR);
    env->ReleaseByteArrayElements(data, buf, JNI_ABORT);
    return static_cast<jint>(written);
}

JNIEXPORT void JNICALL
Java_me_rerere_sandbox_Pty_nativeKill(
        JNIEnv *, jclass, jint pid, jint sig) {
    kill(pid, sig);
}

} // extern "C"
