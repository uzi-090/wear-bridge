package io.wearbridge.watch.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Build
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.BridgePermissions
import io.wearbridge.watch.ble.ConnectionManager
import io.wearbridge.watch.ble.LinkState
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.notifications.MirrorNotifier
import io.wearbridge.watch.notifications.NotificationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the iPhone link alive as a `connectedDevice` foreground service. It's event-driven:
 * no timers or wakelocks of its own.
 */
class BridgeService : Service() {

    private var controller: ConnectionManager? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!BridgePermissions.hasRequired(this)) {
            DiagLog.w("service started without Bluetooth permission")
            stopSelf()
            return
        }
        try { ServiceCompat.startForeground(
            this,
            MirrorNotifier.SERVICE_NOTIFICATION_ID,
            BridgeGraph.notifier.serviceNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        ) } catch (e: RuntimeException) {
            if (!isStartupRestriction(e)) throw e
            needsForegroundOpen(this)
            stopSelf()
            return
        }
        // Checked after startForeground: a service started as foreground must call it even when it
        // stops at once (pairing was cancelled between start and create), or the app is killed.
        if (!BridgeGraph.pairing.state.value.enabled) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java).cancel(RECOVERY_NOTIFICATION)
        BridgeGraph.notifier.clearOrphans()
        controller = ConnectionManager(applicationContext, BridgeGraph.repository) { scope ->
            NotificationEngine(BridgeGraph.repository, BridgeGraph.notifier, BridgeGraph.settings, BridgeGraph.policy, scope)
        }.also {
            BridgeGraph.controller = it
            it.start()
            it.setForeground(BridgeGraph.appForeground)
        }
        scope.launch {
            BridgeGraph.pairing.state.collect {
                if (it.phase == io.wearbridge.watch.ble.PairingPhase.UNPAIRED) stopSelf()
            }
        }
        scope.launch {
            BridgeGraph.link.map { it.state }.distinctUntilChanged().collect { state ->
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.notify(MirrorNotifier.SERVICE_NOTIFICATION_ID, BridgeGraph.notifier.serviceNotification(statusText(state)))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RECONNECT) BridgeGraph.reconnect()
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        controller?.stop()
        if (BridgeGraph.controller === controller) BridgeGraph.controller = null
        controller = null
        super.onDestroy()
    }

    private fun statusText(state: LinkState) = when (state) {
        LinkState.READY -> "Connected to iPhone"
        LinkState.RECONNECTING -> "Reconnecting…"
        LinkState.ADVERTISING -> "Waiting for iPhone"
        LinkState.ERROR -> "Needs attention"
        LinkState.DISCONNECTED -> "Not connected"
        else -> "Connecting…"
    }

    companion object {
        const val ACTION_RECONNECT = "io.wearbridge.watch.RECONNECT"

        fun start(context: Context) {
            if (!BridgePermissions.hasRequired(context) || !BridgeGraph.pairing.state.value.enabled) return
            try { ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java)) }
            catch (e: RuntimeException) {
                if (!isStartupRestriction(e)) throw e
                needsForegroundOpen(context)
            }
        }

        private const val RECOVERY_NOTIFICATION = 2
        private fun isStartupRestriction(e: RuntimeException): Boolean = e is SecurityException ||
            (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException)

        @android.annotation.SuppressLint("MissingPermission")
        private fun needsForegroundOpen(context: Context) {
            DiagLog.w("background startup blocked; open Bridge to recover")
            BridgeGraph.linkFlow.value = io.wearbridge.watch.ble.LinkSnapshot(LinkState.ERROR,
                io.wearbridge.watch.ble.LinkIssue.BACKGROUND_START_BLOCKED)
            if (!BridgeGraph.notifier.canPost) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("startup_recovery", "Connection recovery", NotificationManager.IMPORTANCE_DEFAULT))
            val open = PendingIntent.getActivity(context, 200,
                Intent(context, io.wearbridge.watch.ui.MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(RECOVERY_NOTIFICATION, NotificationCompat.Builder(context, "startup_recovery")
                .setSmallIcon(io.wearbridge.watch.R.drawable.ic_stat_bridge)
                .setContentTitle("Open Bridge to reconnect")
                .setContentText("Your watch paused background startup. Tap to restore your iPhone connection.")
                .setContentIntent(open).setAutoCancel(true).setLocalOnly(true).build())
        }
    }
}

/** Restores the bridge after a watch reboot or app update. `connectedDevice` FGS may start from boot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            BridgeService.start(context)
        }
    }
}
