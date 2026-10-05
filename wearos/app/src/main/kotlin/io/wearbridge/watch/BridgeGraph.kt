package io.wearbridge.watch

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.wearbridge.ams.NowPlaying
import io.wearbridge.ams.RemoteCommand
import io.wearbridge.ancs.ActionId
import io.wearbridge.watch.ble.ConnectionManager
import io.wearbridge.watch.bridge.WatchBattery
import io.wearbridge.watch.find.FindPhoneState
import io.wearbridge.watch.find.FindWatchAlert
import io.wearbridge.watch.contacts.CallRequestState
import io.wearbridge.watch.contacts.ContactsStore
import io.wearbridge.watch.security.ApprovalNotifier
import io.wearbridge.watch.security.IdentityStore
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
    lateinit var findWatch: FindWatchAlert
        private set
    lateinit var identity: IdentityStore
        private set
    lateinit var contacts: ContactsStore
        private set

    internal val callRequestFlow = MutableStateFlow(CallRequestState.IDLE)
    val callRequest: StateFlow<CallRequestState> = callRequestFlow

    /** The 6-digit code while a new iPhone app waits for the user to allow it on the watch. */
    internal val pendingApprovalFlow = MutableStateFlow<String?>(null)
    val pendingApproval: StateFlow<String?> = pendingApprovalFlow

    /** The watch's own battery (from ACTION_BATTERY_CHANGED), sent to the iPhone app. */
    internal val watchBatteryFlow = MutableStateFlow(WatchBattery())
    val watchBattery: StateFlow<WatchBattery> = watchBatteryFlow

    /** True while the iPhone app is connected and listening for events (needed for Find iPhone). */
    internal val phoneAppConnectedFlow = MutableStateFlow(false)
    val phoneAppConnected: StateFlow<Boolean> = phoneAppConnectedFlow

    internal val findPhoneFlow = MutableStateFlow(FindPhoneState.IDLE)
    val findPhone: StateFlow<FindPhoneState> = findPhoneFlow

    internal val linkFlow = MutableStateFlow(LinkSnapshot())
    val link: StateFlow<LinkSnapshot> = linkFlow

    /** The bonded iPhone's Bluetooth name, for on-watch display only. Never logged. */
    internal val phoneNameFlow = MutableStateFlow<String?>(null)
    val phoneName: StateFlow<String?> = phoneNameFlow

    /** iPhone battery from its Battery Service (0x180F), or null when unknown or disconnected. */
    internal val phoneBatteryFlow = MutableStateFlow<Int?>(null)
    val phoneBattery: StateFlow<Int?> = phoneBatteryFlow

    /** iPhone model identifier from Device Information (e.g. "iPhone17,1"). */
    internal val phoneModelFlow = MutableStateFlow<String?>(null)
    val phoneModel: StateFlow<String?> = phoneModelFlow

    /** The iPhone's Now Playing state over AMS. Null when AMS isn't available (not connected, or unsupported). */
    internal val mediaFlow = MutableStateFlow<NowPlaying?>(null)
    val media: StateFlow<NowPlaying?> = mediaFlow

    @Volatile
    internal var controller: ConnectionManager? = null

    /**
     * Debug builds only (set by the debug SAMPLE receiver): real iPhone notifications are ignored, so
     * the inbox can be screenshotted with fake content. Cleared by restarting the app.
     */
    @Volatile
    var demoMode = false

    fun init(app: Application) {
        settings = BridgeSettings(app)
        repository = NotificationRepository(historyEnabled = { settings.history.value })
        policy = PolicyStore(app)
        icons = AppIconStore(app)
        notifier = MirrorNotifier(app, settings).also { it.createChannels() }
        findWatch = FindWatchAlert(app).also { it.createChannel() }
        identity = IdentityStore(app)
        contacts = ContactsStore(app)
        approvals = ApprovalNotifier(app).also { it.createChannel() }
    }

    fun performAction(uid: Long, action: ActionId) {
        val c = controller
        if (c == null) {
            repository.setActionStatus(ActionStatus(uid, action, ActionStatus.State.UNAVAILABLE))
        } else {
            c.performAction(uid, action)
        }
    }

    /** Removes a notification from the watch only (the iPhone keeps it). */
    fun delete(uid: Long) {
        repository.delete(uid)
        notifier.cancel(uid)
    }

    /** Clears the watch inbox (calls in progress excepted). The iPhone keeps its notifications. */
    fun deleteAll() {
        repository.deleteAll().forEach(notifier::cancel)
    }

    fun setHistory(enabled: Boolean) {
        settings.setHistoryFlag(enabled)
        if (!enabled) repository.clearHistory()
    }

    internal lateinit var approvals: ApprovalNotifier
        private set

    /** The user allowed (or denied) a new Bridge iPhone app on the watch. */
    fun approveIphoneApp(allow: Boolean) {
        controller?.approveIphoneApp(allow)
    }

    /** Forgets every allowed iPhone app. The current connection is reset, so the iPhone app asks again. */
    fun forgetIphoneApps() {
        identity.forgetAll()
        controller?.reconnect()
    }

    /** Asks the iPhone app to offer this call. The user confirms on the iPhone (iOS doesn't let apps dial silently). */
    fun callFromIphone(phone: String, name: String) {
        val c = controller
        if (c == null) callRequestFlow.value = CallRequestState.NO_APP else c.callFromIphone(phone, name)
    }

    /** Rings the iPhone (best effort: needs the iPhone app alive in the background). */
    fun findPhone(ring: Boolean) {
        val c = controller
        if (c == null) findPhoneFlow.value = FindPhoneState.NO_LINK else c.findPhone(ring)
    }

    internal fun sendEvent(type: Int, payload: ByteArray = ByteArray(0)) {
        controller?.sendEvent(type, payload)
    }

    fun mediaCommand(command: RemoteCommand) {
        if (demoMode) return applyDemoMedia(command) // debug screenshots must never touch the real iPhone
        controller?.mediaCommand(command)
    }

    private fun applyDemoMedia(command: RemoteCommand) {
        val np = mediaFlow.value ?: return
        val step = 1f / 16
        mediaFlow.value = when (command) {
            RemoteCommand.VOLUME_UP -> np.copy(volume = ((np.volume ?: 0f) + step).coerceAtMost(1f))
            RemoteCommand.VOLUME_DOWN -> np.copy(volume = ((np.volume ?: 0f) - step).coerceAtLeast(0f))
            RemoteCommand.TOGGLE_PLAY_PAUSE -> np.copy(
                state = if (np.isPlaying) io.wearbridge.ams.PlaybackState.PAUSED else io.wearbridge.ams.PlaybackState.PLAYING,
                elapsedSeconds = np.positionSeconds(android.os.SystemClock.elapsedRealtime()),
                elapsedAtMillis = android.os.SystemClock.elapsedRealtime(),
            )
            else -> np
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
