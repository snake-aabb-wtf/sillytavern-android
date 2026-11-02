package com.sol.sillytavern.node

import android.util.Log

/**
 * Starts the bundled Node.js runtime in this process.
 *
 * ### Threading
 * [startNode] calls `node::Start()`, which runs Node's event loop and **blocks until Node
 * exits**. Call it only from a dedicated worker thread with a generous stack (>= 8 MB,
 * e.g. `Thread(null, body, "node", 8L * 1024 * 1024)`), never from the UI thread.
 *
 * ### Log fd ownership
 * The caller opens [logFd] (e.g. a `FileOutputStream` fd), passes it in, and **closes it
 * after [startNode] returns**. Native code only `dup2()`s it onto stdout/stderr; those
 * copies are independent descriptors, so closing the original does not disturb output.
 */
object NodeBridge {
    private const val TAG = "NodeBridge"

    private val loaded: Boolean = try {
        // libnode.so must be mapped before libstbridge.so resolves node::Start.
        System.loadLibrary("node")
        System.loadLibrary("stbridge")
        true
    } catch (t: Throwable) {
        // A missing or ABI-mismatched libnode.so must be diagnosable, not an opaque false.
        Log.e(TAG, "failed to load native libraries (libnode.so/libstbridge.so)", t)
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
