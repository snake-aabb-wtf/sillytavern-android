package com.sol.sillytavern.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sol.sillytavern.R
import com.sol.sillytavern.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that pins the app process while SillyTavern's Node runtime is up.
 *
 * Node runs on a thread inside this process ([ServerController]), so the process is the
 * server. Keeping a foreground service alive is what stops the system from freezing or
 * killing that process once the user leaves the app.
 */
class NodeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundFor(ServerController.state.value)
        serviceScope.launch {
            // The notification is the only visible trace of the server; keep it honest.
            ServerController.state.collectLatest { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(state))
            }
        }
    }

    /**
     * The service is a lifetime marker, not a command queue: it is never restarted by the
     * system, and the only meaningful command is [ACTION_STOP] ("quit").
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            quit()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /** Node runs inside this process; nothing can bind to the service. */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Swiping the app off the recents list is the "quit" signal for the whole app.
     *
     * Node cannot be stopped in-process (see [ServerController.stop]), so teardown means
     * ending the process that hosts it. Without the kill the Node thread would keep the
     * process alive with no UI and no service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        quit()
        // Not reached: quit() kills the process.
    }

    /**
     * Stops the controller and the service, then ends the process that hosts Node.
     *
     * `Process.killProcess` is SIGKILL: a JSON data write that is in flight at this instant
     * can be truncated. Runtime and first-run data installs are unaffected — they are
     * staged in a temp directory and renamed into place, so they are either absent or
     * complete — and a truncated SillyTavern data file is the accepted cost of the only
     * stop mechanism available to us (libnode exposes no way to stop Node in-process).
     */
    private fun quit() {
        ServerController.stop()
        stopSelf()
        Process.killProcess(Process.myPid())
    }

    private fun startForegroundFor(state: ServerState) {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(state),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(state: ServerState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(statusText(state)))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun statusText(state: ServerState): Int = when (state) {
        ServerState.Idle, ServerState.Installing -> R.string.status_installing
        ServerState.Starting -> R.string.status_starting
        is ServerState.Ready -> R.string.notification_text
        is ServerState.Failed -> R.string.notification_text_failed
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Quits the app: stops the controller and the service, then kills the process. */
        const val ACTION_STOP = "com.sol.sillytavern.action.STOP"

        private const val CHANNEL_ID = "sillytavern"
        private const val NOTIF_ID = 1
    }
}
