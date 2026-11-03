package com.sol.sillytavern.server

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.sol.sillytavern.BuildConfig
import com.sol.sillytavern.node.NodeBridge
import com.sol.sillytavern.runtime.InstallResult
import com.sol.sillytavern.runtime.RuntimeInstaller
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Observable lifecycle of the embedded SillyTavern server. */
sealed interface ServerState {
    /** Nothing has been requested yet. */
    data object Idle : ServerState

    /** Unpacking/validating the bundled runtime and seeding user data. */
    data object Installing : ServerState

    /** Node has been launched; waiting for the HTTP endpoint to answer. */
    data object Starting : ServerState

    /** The server answered on [port] and the WebView may load it. */
    data class Ready(val port: Int) : ServerState

    /** Startup failed; [reason] is user-facing and [logPath] is node.log. */
    data class Failed(val reason: String, val logPath: String) : ServerState
}

/**
 * Process-wide owner of the embedded Node runtime.
 *
 * [start] is idempotent: it is a no-op while an install/start is in progress or once the
 * server is ready. After a [ServerState.Failed] it may be called again to retry.
 *
 * All blocking work (asset extraction, seed copy, port pick, fd open, readiness polling)
 * runs on [Dispatchers.IO]; the UI never blocks on startup.
 */
object ServerController {
    private const val TAG = "ServerController"

    private const val RUNTIME_ASSET = "runtime.zip"
    private const val FALLBACK_RUNTIME_VERSION = "dev"
    private const val LOG_RELATIVE_PATH = "logs/node.log"
    private const val CONFIG_NAME = "config.yaml"
    private const val DATA_DIR_NAME = "data"

    /** Shown when a retry cannot start Node again in this process. */
    private const val NODE_EXITED_RESTART_REQUIRED = "Node 已退出，请重启应用"

    /** node.log is rotated once it passes this size; the previous log is kept as .1. */
    private const val MAX_LOG_BYTES = 2L * 1024 * 1024

    private const val CONNECT_TIMEOUT_MS = 1_000
    private const val READ_TIMEOUT_MS = 1_000
    private const val POLL_INTERVAL_MS = 250L

    /** Default readiness budget for [awaitReady] and [start]. */
    const val READY_TIMEOUT_MS = 30_000L

    /** node::Start() recurses deeply; give it far more than the default 1 MB stack. */
    private const val NODE_THREAD_STACK_BYTES = 16L * 1024 * 1024

    /**
     * First-run defaults. `whitelistMode` stays on (the loopback address is whitelisted by
     * SillyTavern's own defaults) and the server listens on 127.0.0.1 only.
     */
    private val DEFAULT_CONFIG = """
        listen: false
        port: 8000
        whitelistMode: true
        basicAuthMode: false
        browserLaunch:
          enabled: false
        skipContentCheck: true
        securityOverride: false
    """.trimIndent() + "\n"

    /** Startup attempts are app-scoped: Node must outlive an Activity recreation. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Monotonic attempt id; stale workers compare against it before mutating state. */
    private val attemptCounter = AtomicInteger(0)

    /** The one Node thread we may have launched; guarded by [rememberNode]/[forgetNode]. */
    @Volatile
    private var nodeThread: Thread? = null

    /** Port handed to [nodeThread]; -1 when no Node thread is tracked. */
    @Volatile
    private var nodePort: Int = -1

    /**
     * True once `node::Start()` has been entered in this process. libnode is single-shot:
     * a second call aborts natively, so this latch never goes back to false, not even
     * when the thread exits or [stop] clears [nodeThread].
     */
    @Volatile
    private var nodeStartedInProcess = false

    private val _state = MutableStateFlow<ServerState>(ServerState.Idle)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    /**
     * Starts the server, or retries after failure. Safe to call repeatedly from
     * `Activity.onCreate` and from a retry button.
     *
     * If an earlier attempt timed out but its Node thread is still alive, a retry only
     * re-enters [awaitReady] on that thread's port. `node::Start()` must never run twice
     * in one process: libnode can only be initialised once, so once the Node thread has
     * exited a retry reports that a restart is required instead of starting it again.
     */
    @Synchronized
    fun start(context: Context) {
        when (_state.value) {
            ServerState.Installing, ServerState.Starting, is ServerState.Ready -> return
            ServerState.Idle, is ServerState.Failed -> Unit
        }
        val appContext = context.applicationContext

        if (nodeStartedInProcess && nodeThread == null) {
            val logPath = File(appContext.filesDir, LOG_RELATIVE_PATH).absolutePath
            _state.value = ServerState.Failed(NODE_EXITED_RESTART_REQUIRED, logPath)
            return
        }

        val liveThread = nodeThread
        val livePort = nodePort
        if (liveThread != null && liveThread.isAlive && livePort > 0) {
            val attemptId = attemptCounter.incrementAndGet()
            _state.value = ServerState.Starting
            scope.launch { resumeWaiting(appContext, attemptId, livePort) }
            return
        }

        val attemptId = attemptCounter.incrementAndGet()
        _state.value = ServerState.Installing
        scope.launch { begin(appContext, attemptId) }
    }

    /**
     * Tears down the tracked Node runtime and returns to [ServerState.Idle].
     *
     * Node cannot be un-started in-process: libnode exposes only
     * `node::Stop(Environment*)`, and the `Environment` is created inside `node::Start()`,
     * which we call through [NodeBridge.startNode] and which never returns its handle.
     * Stopping the server therefore means terminating the process that hosts it, so this
     * is called only from [NodeService]'s quit path (recents swipe or [NodeService.ACTION_STOP]),
     * immediately before the process is killed. Clearing the retained thread/port under
     * this monitor keeps a late [start] from adopting a thread that is already going away.
     */
    @Synchronized
    fun stop() {
        // Invalidate in-flight workers so none of them can re-publish a state after this.
        attemptCounter.incrementAndGet()
        _state.value = ServerState.Idle
        nodeThread = null
        nodePort = -1
    }

    /**
     * True when no Node thread is alive, i.e. `data/` may be renamed without detaching the
     * directory a running server still writes into.
     *
     * [ServerState.Failed] alone is not enough: a readiness timeout fails the state while
     * the Node thread keeps running and may still come up.
     */
    @Synchronized
    fun canSafelyRewriteData(): Boolean = nodeThread?.isAlive != true

    /**
     * Polls `http://127.0.0.1:[port]/` until it answers 2xx/3xx, at most [timeoutMs].
     *
     * Suspends on the caller's coroutine; cancellation is observed between probes and the
     * per-request timeouts bound the blocking part to about 1 s. The controller calls this
     * from its app-scoped worker, deliberately **not** from the Activity's scope, so a
     * destroyed Activity does not abort a server that is still starting. Returns early when
     * the controller has already reached [ServerState.Failed].
     */
    suspend fun awaitReady(port: Int, timeoutMs: Long = READY_TIMEOUT_MS): Boolean =
        withContext(Dispatchers.IO) {
            val deadlineNanos = System.nanoTime() + timeoutMs.coerceAtLeast(0L) * 1_000_000L
            var ready = false
            while (true) {
                currentCoroutineContext().ensureActive()
                if (_state.value is ServerState.Failed) return@withContext false
                if (probeOnce(port)) {
                    ready = true
                    break
                }
                currentCoroutineContext().ensureActive()
                val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0L) break
                delay(minOf(POLL_INTERVAL_MS, remainingMs))
            }
            ready
        }

    /** Retry path when a previous attempt timed out but its Node thread is still alive. */
    private suspend fun resumeWaiting(context: Context, attemptId: Int, port: Int) {
        val logPath = File(context.filesDir, LOG_RELATIVE_PATH).absolutePath
        val ready = try {
            awaitReady(port)
        } catch (t: Throwable) {
            if (t is CancellationException || t is VirtualMachineError) throw t
            fail(attemptId, "等待服务器就绪失败：${t.javaClass.simpleName}: ${t.message}", logPath)
            return
        }
        if (!isCurrent(attemptId)) return
        if (ready) {
            markReadyIfStarting(attemptId, port)
        } else {
            fail(attemptId, "服务器在 ${READY_TIMEOUT_MS / 1000} 秒内未就绪", logPath)
        }
    }

    private suspend fun begin(context: Context, attemptId: Int) {
        val logPath = File(context.filesDir, LOG_RELATIVE_PATH).absolutePath
        try {
            startInternal(context, attemptId, logPath)
        } catch (t: Throwable) {
            if (t is CancellationException || t is VirtualMachineError) throw t
            // Any unexpected failure must still leave the splash: report it as Failed.
            fail(attemptId, "启动失败：${t.javaClass.simpleName}: ${t.message}", logPath)
        }
    }

    private suspend fun startInternal(context: Context, attemptId: Int, logPath: String) {
        val filesDir = context.filesDir
        val configFile = File(filesDir, CONFIG_NAME)
        val logFile = File(filesDir, LOG_RELATIVE_PATH)

        val version = BuildConfig.RUNTIME_VERSION.ifBlank { FALLBACK_RUNTIME_VERSION }

        val installResult = try {
            RuntimeInstaller.ensureInstalled(context, RUNTIME_ASSET, version)
        } catch (t: Throwable) {
            if (t is CancellationException || t is VirtualMachineError) throw t
            fail(attemptId, "运行时安装异常：${t.javaClass.simpleName}: ${t.message}", logPath)
            return
        }
        if (!isCurrent(attemptId)) return

        val runtimeDir = when (installResult) {
            is InstallResult.Ready -> installResult.runtimeDir
            is InstallResult.Failed -> {
                fail(attemptId, "运行时安装失败：${installResult.reason}", logPath)
                return
            }
        }

        // Seeding is best effort: a partial or existing data dir must not block startup.
        try {
            RuntimeInstaller.seedDataIfMissing(filesDir, runtimeDir)
        } catch (e: Exception) {
            Log.e(TAG, "seedDataIfMissing failed; continuing with existing data", e)
        }
        if (!isCurrent(attemptId)) return

        try {
            writeDefaultConfigIfMissing(configFile)
        } catch (e: IOException) {
            fail(attemptId, "无法写入 config.yaml：${e.message}", logPath)
            return
        }

        val serverJs = File(runtimeDir, "server/server.js")
        if (!serverJs.isFile) {
            fail(attemptId, "运行时缺少 server/server.js（版本 $version）", logPath)
            return
        }

        val port = try {
            ServerSocket(0).use { it.localPort }
        } catch (e: IOException) {
            fail(attemptId, "无法选择空闲端口：${e.message}", logPath)
            return
        }

        if (!NodeBridge.load()) {
            fail(attemptId, "无法加载 Node 原生库（libnode.so/libstbridge.so）", logPath)
            return
        }

        rotateLogIfNeeded(logFile)

        val fd = try {
            logFile.parentFile?.mkdirs()
            ParcelFileDescriptor.open(
                logFile,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_APPEND or
                    ParcelFileDescriptor.MODE_WRITE_ONLY,
            ).detachFd()
        } catch (e: IOException) {
            fail(attemptId, "无法打开日志文件 $logPath：${e.message}", logPath)
            return
        }

        val args = arrayOf(
            "node",
            serverJs.absolutePath,
            "--port", port.toString(),
            "--listen", "false",
            "--configPath", configFile.absolutePath,
            "--dataRoot", File(filesDir, DATA_DIR_NAME).absolutePath,
            "--browserLaunchEnabled", "false",
        )

        if (!updateIfCurrent(attemptId, ServerState.Starting)) {
            closeFd(fd)
            return
        }

        val thread = try {
            Thread(
                null,
                { runNode(args, fd, logPath) },
                "node",
                NODE_THREAD_STACK_BYTES,
            )
        } catch (t: Throwable) {
            closeFd(fd)
            fail(attemptId, "无法创建 Node 线程：${t.message}", logPath)
            return
        }

        // stop() may have landed while this worker was preparing the thread; publishing and
        // starting Node after the controller was reset would run node::Start() with
        // state == Idle and an untracked thread.
        if (!rememberNode(thread, port, attemptId)) {
            closeFd(fd)
            return
        }
        try {
            thread.start()
            nodeStartedInProcess = true
        } catch (t: Throwable) {
            forgetNode(thread)
            closeFd(fd)
            fail(attemptId, "无法启动 Node 线程：${t.message}", logPath)
            return
        }

        val ready = awaitReady(port)
        if (!isCurrent(attemptId)) return
        if (ready) {
            markReadyIfStarting(attemptId, port)
        } else {
            fail(attemptId, "服务器在 ${READY_TIMEOUT_MS / 1000} 秒内未就绪", logPath)
        }
    }

    /**
     * Node's event loop runs here until the process exits. The Kotlin-side log fd is
     * closed only after [NodeBridge.startNode] returns; native code keeps dup2 copies.
     */
    private fun runNode(args: Array<String>, fd: Int, logPath: String) {
        val self = Thread.currentThread()
        var exitCode = -1
        try {
            exitCode = NodeBridge.startNode(args, fd)
        } catch (t: Throwable) {
            Log.e(TAG, "Node thread crashed", t)
        } finally {
            closeFd(fd)
        }
        Log.i(TAG, "Node exited with code $exitCode")
        markNodeExited(self, exitCode, logPath)
    }

    private fun probeOnce(port: Int): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val conn = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
            connection = conn
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            code in 200..399
        } catch (e: IOException) {
            false
        } catch (e: ClassCastException) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun writeDefaultConfigIfMissing(file: File) {
        if (file.isFile) return
        val parent = file.parentFile ?: throw IOException("no parent directory for ${file.path}")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("cannot create ${parent.path}")
        }
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(DEFAULT_CONFIG)
        if (file.exists()) {
            // Another writer won the race; keep its file.
            temp.delete()
            return
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("cannot rename ${temp.path} to ${file.path}")
        }
    }

    /**
     * Renames an oversized node.log to node.log.1 (replacing the previous rotation) before
     * the fd is opened. Append mode means an unrotated log grows without bound.
     */
    private fun rotateLogIfNeeded(logFile: File) {
        try {
            if (!logFile.isFile || logFile.length() <= MAX_LOG_BYTES) return
            val parent = logFile.parentFile ?: return
            val rotated = File(parent, "${logFile.name}.1")
            rotated.delete()
            if (logFile.renameTo(rotated)) {
                Log.i(TAG, "rotated ${logFile.name} (${rotated.length()} bytes) to ${rotated.name}")
            } else {
                Log.w(TAG, "failed to rotate ${logFile.path}")
            }
        } catch (e: Exception) {
            // Rotation is housekeeping: never block startup on it.
            Log.w(TAG, "failed to rotate ${logFile.path}", e)
        }
    }

    /** Adopts and closes the raw fd handed to (and duplicated by) native code. */
    private fun closeFd(fd: Int) {
        try {
            ParcelFileDescriptor.adoptFd(fd).close()
        } catch (e: Exception) {
            Log.w(TAG, "failed to close Node log fd $fd", e)
        }
    }

    private fun isCurrent(attemptId: Int): Boolean = attemptCounter.get() == attemptId

    @Synchronized
    private fun updateIfCurrent(attemptId: Int, next: ServerState): Boolean {
        if (attemptCounter.get() != attemptId) return false
        _state.value = next
        return true
    }

    /** Never clobber a later Failed (e.g. Node exited while the probe was in flight). */
    @Synchronized
    private fun markReadyIfStarting(attemptId: Int, port: Int) {
        if (attemptCounter.get() == attemptId && _state.value is ServerState.Starting) {
            _state.value = ServerState.Ready(port)
        }
    }

    /**
     * Publishes the Node thread only while [attemptId] is still current.
     *
     * Runs under the [stop] monitor, so a concurrent [stop] either wins here (false is
     * returned and Node is never started) or follows this call and is tearing the process
     * down anyway. Must be called immediately before `thread.start()`.
     */
    @Synchronized
    private fun rememberNode(thread: Thread, port: Int, attemptId: Int): Boolean {
        if (attemptCounter.get() != attemptId) return false
        nodeThread = thread
        nodePort = port
        return true
    }

    @Synchronized
    private fun forgetNode(thread: Thread) {
        if (nodeThread === thread) {
            nodeThread = null
            nodePort = -1
        }
    }

    @Synchronized
    private fun markNodeExited(thread: Thread, exitCode: Int, logPath: String) {
        // Identity check: only the tracked thread may clear the slot or fail the state.
        if (nodeThread !== thread) return
        nodeThread = null
        nodePort = -1
        val current = _state.value
        if (current is ServerState.Starting || current is ServerState.Ready) {
            _state.value = ServerState.Failed("Node 进程已退出（code=$exitCode）", logPath)
        }
    }

    @Synchronized
    private fun fail(attemptId: Int, reason: String, logPath: String) {
        if (attemptCounter.get() != attemptId) return
        // Keep the first failure: a later probe timeout must not hide "Node exited".
        if (_state.value is ServerState.Failed) return
        Log.e(TAG, reason)
        _state.value = ServerState.Failed(reason, logPath)
    }
}
