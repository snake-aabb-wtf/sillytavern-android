// Minimal JNI bridge that starts the Node.js runtime inside the app process.
//
// libnode.so is a prebuilt (nodejs-mobile) library: it exports node::Start(int, char**)
// but has no Java_* entry points, so the glue lives here. Everything the runtime writes
// to stdout/stderr is redirected into the log file descriptor opened by Kotlin.
#include <jni.h>

#include <unistd.h>

#include <cstdlib>
#include <string>
#include <vector>

#include "node.h"

extern "C" JNIEXPORT jint JNICALL
Java_com_sol_sillytavern_node_NodeBridge_startNode(JNIEnv* env, jclass /*clazz*/,
                                                  jobjectArray args, jint logFd) {
    if (logFd >= 0) {
        // Persistent redirection: the runtime and its child threads keep writing to the log.
        dup2(logFd, STDOUT_FILENO);
        dup2(logFd, STDERR_FILENO);
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
