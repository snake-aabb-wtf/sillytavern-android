// Minimal JNI bridge that starts the Node.js runtime inside the app process.
//
// libnode.so is a prebuilt (nodejs-mobile) library: it exports node::Start(int, char**)
// but has no Java_* entry points, so the glue lives here. Everything the runtime writes
// to stdout/stderr is redirected into the log file descriptor opened by Kotlin.
//
// fd ownership: Kotlin opens logFd, passes it in, and closes it after startNode returns.
// This file only dup2()s it onto STDOUT_FILENO/STDERR_FILENO. The dup2 copies are
// independent descriptors, so Kotlin closing the original fd does not affect the runtime.
#include <jni.h>

#include <unistd.h>

#include <cerrno>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "node.h"

#if defined(__ANDROID__)
#include <android/log.h>
#define STBRIDGE_TAG "NodeBridge"

static void LogError(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    __android_log_vprint(ANDROID_LOG_ERROR, STBRIDGE_TAG, fmt, ap);
    va_end(ap);
}
#else
// Host-side (syntax/compile check) fallback: no NDK logging available.
static void LogError(const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    std::fputs("NodeBridge: ", stderr);
    std::vfprintf(stderr, fmt, ap);
    std::fputc('\n', stderr);
    va_end(ap);
}
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_sol_sillytavern_node_NodeBridge_startNode(JNIEnv* env, jclass /*clazz*/,
                                                  jobjectArray args, jint logFd) {
    if (logFd < 0) {
        LogError("startNode: invalid logFd %d (contract: Kotlin must pass an open fd)", logFd);
        return -1;
    }

    // Redirect before Node starts so no early output is lost. The originals stay open
    // (dup2 duplicates); the caller closes logFd itself after we return.
    if (dup2(logFd, STDOUT_FILENO) == -1) {
        LogError("startNode: dup2(logFd, STDOUT_FILENO) failed: %s", std::strerror(errno));
        return -1;
    }
    if (dup2(logFd, STDERR_FILENO) == -1) {
        LogError("startNode: dup2(logFd, STDERR_FILENO) failed: %s", std::strerror(errno));
        return -1;
    }

    setenv("ST_SKIP_WEBPACK", "1", 1);
    setenv("ST_MOBILE", "1", 1);

    const jsize argc = args == nullptr ? 0 : env->GetArrayLength(args);

    // Copy every argument into owned storage so the UTF chars can be released immediately
    // and argv stays contiguous (strings are stored in a vector, which may reallocate,
    // so pointers are collected only after all strings are in place).
    std::vector<std::string> store;
    store.reserve(static_cast<size_t>(argc > 0 ? argc : 1));
    if (args != nullptr) {
        for (jsize i = 0; i < argc; ++i) {
            auto element = static_cast<jstring>(env->GetObjectArrayElement(args, i));
            if (element == nullptr) {
                store.emplace_back();
                continue;
            }
            const char* utf = env->GetStringUTFChars(element, nullptr);
            store.emplace_back(utf != nullptr ? utf : "");
            if (utf != nullptr) {
                env->ReleaseStringUTFChars(element, utf);
            }
            env->DeleteLocalRef(element);
        }
    }
    if (store.empty()) {
        store.emplace_back("node");
    }

    std::vector<char*> argv;
    argv.reserve(store.size() + 1);
    for (std::string& arg : store) {
        argv.push_back(arg.data());
    }
    argv.push_back(nullptr);  // conventional NULL terminator for argv

    const int nodeArgc = static_cast<int>(store.size());
    return node::Start(nodeArgc, argv.data());
}
