package io.wearbridge.watch.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.BridgePermissions
import io.wearbridge.watch.ble.BridgeController
import io.wearbridge.watch.ble.LinkState
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.notifications.MirrorNotifier
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

    private var controller: BridgeController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!BridgePermissions.hasRequired(this)) {
            DiagLog.w("service started without Bluetooth permission")
            stopSelf()
            return
        }
        ServiceCompat.startForeground(
            this,
            MirrorNotifier.SERVICE_NOTIFICATION_ID,
            BridgeGraph.notifier.serviceNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        BridgeGraph.notifier.clearOrphans()
        controller = BridgeController(applicationContext, BridgeGraph.repository, BridgeGraph.notifier).also {
            BridgeGraph.controller = it
            it.start()
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
            if (!BridgePermissions.hasRequired(context)) return
            ContextCompat.startForegroundService(context, Intent(context, BridgeService::class.java))
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
