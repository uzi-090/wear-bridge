package io.wearbridge.watch

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.wearbridge.ancs.ActionId
import io.wearbridge.watch.ble.ConnectionManager
import io.wearbridge.watch.ble.LinkSnapshot
import io.wearbridge.watch.notifications.ActionStatus
import io.wearbridge.watch.notifications.MirrorNotifier
import io.wearbridge.watch.notifications.NotificationRepository
import io.wearbridge.watch.settings.AppIconStore
import io.wearbridge.watch.settings.BridgeSettings
import io.wearbridge.watch.settings.PolicyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class BridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BridgeGraph.init(this)
    }
}

/** Process-wide wiring. The UI only reads StateFlows from here and never touches Bluetooth. */
object BridgeGraph {
    lateinit var repository: NotificationRepository
        private set
    lateinit var notifier: MirrorNotifier
        private set
    lateinit var settings: BridgeSettings
        private set
    lateinit var policy: PolicyStore
        private set
    lateinit var icons: AppIconStore
        private set

    internal val linkFlow = MutableStateFlow(LinkSnapshot())
    val link: StateFlow<LinkSnapshot> = linkFlow

    /** The bonded iPhone's Bluetooth name, for on-watch display only. Never logged. */
    internal val phoneNameFlow = MutableStateFlow<String?>(null)
    val phoneName: StateFlow<String?> = phoneNameFlow

    @Volatile
    internal var controller: ConnectionManager? = null

    fun init(app: Application) {
        repository = NotificationRepository()
        settings = BridgeSettings(app)
        policy = PolicyStore(app)
        icons = AppIconStore(app)
        notifier = MirrorNotifier(app, settings).also { it.createChannels() }
    }

    fun performAction(uid: Long, action: ActionId) {
        val c = controller
        if (c == null) {
            repository.setActionStatus(ActionStatus(uid, action, ActionStatus.State.UNAVAILABLE))
        } else {
            c.performAction(uid, action)
        }
    }

    fun reconnect() {
        controller?.reconnect()
    }
}

object BridgePermissions {
    /** Runtime permissions without which the bridge can't run at all. */
    val required: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            emptyArray()
        }

    /** Requested alongside, but the bridge still works without them (in-app list only). */
    val optional: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun hasRequired(context: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
