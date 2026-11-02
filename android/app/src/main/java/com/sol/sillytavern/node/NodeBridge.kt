package com.sol.sillytavern.node

/**
 * Starts the bundled Node.js runtime in this process.
 *
 * [startNode] is blocking: it runs Node until the script exits. Call it from a worker
 * thread. The log file descriptor is dup2'ed onto stdout/stderr by the native side and
 * closed there, so Kotlin must not use it after the call.
 */
object NodeBridge {
    private val loaded: Boolean = try {
        // libnode.so must be mapped before libstbridge.so resolves node::Start.
        System.loadLibrary("node")
        System.loadLibrary("stbridge")
        true
    } catch (t: Throwable) {
        false
    }

    /** True when both native libraries were loaded. */
    fun load(): Boolean = loaded

    /**
     * JNI signature is @JvmStatic so the native symbol receives a jclass:
     * Java_com_sol_sillytavern_node_NodeBridge_startNode(JNIEnv*, jclass, jobjectArray, jint).
     */
    @JvmStatic
    external fun startNode(args: Array<String>, logFd: Int): Int
}
