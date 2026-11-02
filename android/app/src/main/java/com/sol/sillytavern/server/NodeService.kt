package com.sol.sillytavern.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.app.NotificationCompat
import com.sol.sillytavern.R
import com.sol.sillytavern.ui.MainActivity

/**
 * Foreground service that pins the app process while SillyTavern's Node runtime is up.
 *
 * Node runs on a thread inside this process ([ServerController]), so the process is the
 * server. Keeping a foreground service alive is what stops the system from freezing or
 * killing that process once the user leaves the app.
 */
class NodeService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification())
    }

    /**
     * The service is a lifetime marker, not a command queue: it is never restarted by the
     * system, and the only meaningful command is [ACTION_STOP].
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServerController.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    /**
     * Swiping the app off the recents list is the "quit" signal for the whole app.
     *
     * Node cannot be stopped in-process (see [ServerController.stop]), so teardown means
     * ending the process that hosts it. Without the kill the Node thread would keep the
     * process alive with no UI and no service.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        ServerController.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
        Process.killProcess(Process.myPid())
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

    private fun buildNotification(): Notification {
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
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        /** Stops the service; used by a future in-app quit action. */
        const val ACTION_STOP = "com.sol.sillytavern.action.STOP"

        private const val CHANNEL_ID = "sillytavern"
        private const val NOTIF_ID = 1
    }
}
