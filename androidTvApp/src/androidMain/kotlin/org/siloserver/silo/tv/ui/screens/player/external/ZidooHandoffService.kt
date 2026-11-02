package org.siloserver.silo.tv.ui.screens.player.external

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.siloserver.silo.common.player.external.ExternalPlayerSettings
import org.siloserver.silo.common.player.external.ZidooHandoffLog

/**
 * Keeps Silo's process alive while the Zidoo player has the screen.
 *
 * Zidoo firmware lets only a couple of apps stay in the background, and
 * PlexToZidoo plus the Zidoo player already use them, so an ordinary
 * background Silo would be killed mid-film and its skip and resume work would
 * die with it. A foreground service exempts the process from that limit.
 *
 * The service holds no playback logic. It promotes itself immediately, then
 * stops as soon as [ZidooHandoffCoordinator] leaves the active states. Letting
 * the service stop itself, rather than having the coordinator call
 * `stopService`, means it always calls `startForeground` first, which Android
 * requires of every service started with `startForegroundService`.
 */
class ZidooHandoffService : Service() {

    private val coordinator: ZidooHandoffCoordinator by inject()
    private val handoffLog: ZidooHandoffLog by inject()
    private val settings: ExternalPlayerSettings by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dataSync = promote()
        handoffLog.add("Keep-alive service running (" + (if (dataSync) "data sync" else "media playback") + ")")
        // Each start belongs to a newer run. stopSelf(startId) is ignored once
        // a later start has arrived, so an older run ending cannot take the
        // service away from the run that is playing now.
        watcher?.cancel()
        watcher = scope.launch {
            coordinator.state.first { !it.isActive() }
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handoffLog.add("Keep-alive service stopped")
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Runs in the foreground; returns true when as a data sync service, which
     * Settings → External Player → Keep-Alive Service can ask for before
     * Android 14 (which needs a permission for it), and false when as media
     * playback.
     */
    private fun promote(): Boolean {
        val dataSync = settings.keepAliveMode.value == ExternalPlayerSettings.KEEP_ALIVE_DATA_SYNC &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val manager = getSystemService(NotificationManager::class.java)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "External player", NotificationManager.IMPORTANCE_LOW),
            )
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Playing in the Zidoo player")
            .setContentText("Silo is saving your progress.")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = if (dataSync) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return dataSync
    }

    companion object {
        private const val TAG = "ZidooHandoffService"
        private const val CHANNEL_ID = "zidoo_handoff"
        private const val NOTIFICATION_ID = 4_210

        /**
         * Starts the service. Playback still works without it, just less
         * reliably, so a failure is only logged; returns what went wrong, or
         * null when it started.
         */
        fun start(context: Context): String? {
            val intent = Intent(context, ZidooHandoffService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                null
            } catch (e: RuntimeException) {
                // IllegalStateException, or ForegroundServiceStartNotAllowedException on Android 12+.
                Log.w(TAG, "Could not keep Silo alive during Zidoo playback", e)
                e.toString()
            }
        }
    }
}
