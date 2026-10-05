package io.wearbridge.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.wearbridge.ancs.ActionId
import io.wearbridge.ancs.AncsNotification
import io.wearbridge.ancs.AncsSession
import io.wearbridge.ancs.AncsUuids
import io.wearbridge.ancs.EventId
import io.wearbridge.ancs.NotificationSourceEvent
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.BridgePermissions
import io.wearbridge.watch.ancs.AncsClient
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.diagnostics.Redact
import io.wearbridge.watch.notifications.ActionStatus
import io.wearbridge.watch.notifications.MirrorNotifier
import io.wearbridge.watch.notifications.NotificationRepository
import io.wearbridge.watch.notifications.WatchNotification
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Owns the Bluetooth link: it runs [ConnectionStateMachine] effects against [BridgePeripheral] and a
 * GATT client towards the iPhone, and feeds ANCS into the repository and notifier.
 *
 * Concurrency: every Binder callback is turned into a message on [inbox], which is processed in order
 * on a single-threaded dispatcher. All mutable state below is confined to that dispatcher.
 */
@SuppressLint("MissingPermission") // start() refuses to run without the runtime permissions
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeController(
    private val context: Context,
    private val repository: NotificationRepository,
    private val notifier: MirrorNotifier,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
            CoroutineExceptionHandler { _, e -> DiagLog.w("controller error ${e::class.simpleName}") },
    )
    private val inbox = Channel<Msg>(Channel.UNLIMITED)

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val sm = ConnectionStateMachine()
    private val queue = GattOpQueue()
    private val peripheral = BridgePeripheral(context, PeripheralListener())

    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var ancs: AncsClient? = null
    private var setupJob: Job? = null
    private var retryJob: Job? = null
    private var bondTimeoutJob: Job? = null
    private var purgeJob: Job? = null
    private var receiversRegistered = false

    private var lastAlertAt = 0L
    private val sourceArrival = HashMap<Long, Long>()

    private sealed interface Msg {
        data class Link(val event: LinkEvent) : Msg
        data class CentralConnected(val device: BluetoothDevice) : Msg
        data class CentralDisconnected(val device: BluetoothDevice) : Msg
        data class ClientDisconnected(val gatt: BluetoothGatt, val status: Int) : Msg
        data class Characteristic(val uuid: UUID, val value: ByteArray) : Msg
        data class BondState(val device: BluetoothDevice, val state: Int, val previous: Int) : Msg
        data class Action(val uid: Long, val action: ActionId) : Msg
        data object Shutdown : Msg
    }

    fun start() {
        scope.launch { for (msg in inbox) handle(msg) }
        registerReceivers()
        post(
            Msg.Link(
                when {
                    !BridgePermissions.hasRequired(context) -> LinkEvent.PermissionMissing
                    adapter?.isEnabled != true -> LinkEvent.BluetoothOff
                    else -> LinkEvent.Start
                },
            ),
        )
    }

    fun stop() {
        post(Msg.Link(LinkEvent.Stop))
        post(Msg.Shutdown) // processed after Stop's effects, since the inbox is ordered
    }

    fun performAction(uid: Long, action: ActionId) = post(Msg.Action(uid, action))

    fun reconnect() = post(Msg.Link(LinkEvent.UserReconnect))

    private fun post(msg: Msg) {
        inbox.trySend(msg)
    }

    // ---- message handling (BLE dispatcher only) ----

    private fun handle(msg: Msg) {
        when (msg) {
            is Msg.Link -> dispatch(msg.event)
            is Msg.CentralConnected -> {
                val current = device
                if (current != null && current.address != msg.device.address) {
                    DiagLog.i("ignoring second central ${Redact.device(msg.device)}")
                    return
                }
                if (current == null && sm.snapshot.state in setOf(LinkState.ADVERTISING, LinkState.RECONNECTING, LinkState.ERROR)) {
                    device = msg.device
                    dispatch(LinkEvent.CentralConnected)
                }
            }
            is Msg.CentralDisconnected -> if (msg.device.address == device?.address) {
                device = null
                dispatch(LinkEvent.CentralDisconnected)
            }
            is Msg.ClientDisconnected -> if (device != null && msg.gatt === gatt) {
                DiagLog.i("gatt client dropped status=${msg.status}")
                dispatch(LinkEvent.SetupFailed)
            }
            is Msg.Characteristic -> {
                if (msg.uuid == AncsUuids.NOTIFICATION_SOURCE) {
                    NotificationSourceEvent.parse(msg.value)?.let {
                        sourceArrival[it.uid] = SystemClock.elapsedRealtime()
                        if (it.category == io.wearbridge.ancs.Category.UNKNOWN) {
                            DiagLog.i("ns unknown category code=${msg.value[2].toInt() and 0xFF} flags=${it.flags}")
                        }
                    }
                }
                ancs?.onCharacteristicChanged(msg.uuid, msg.value)
            }
            is Msg.BondState -> onBondState(msg)
            is Msg.Action -> {
                val ok = ancs?.session?.performAction(msg.uid, msg.action) == true
                repository.setActionStatus(
                    ActionStatus(msg.uid, msg.action, if (ok) ActionStatus.State.SENDING else ActionStatus.State.UNAVAILABLE),
                )
                DiagLog.i("action ${msg.action} uid=${Redact.uid(msg.uid)} queued=$ok")
            }
            Msg.Shutdown -> {
                if (receiversRegistered) runCatching { context.unregisterReceiver(systemReceiver) }
                receiversRegistered = false
                scope.cancel()
            }
        }
    }

    private fun dispatch(event: LinkEvent) {
        val before = sm.snapshot
        val effects = sm.on(event)
        val after = sm.snapshot
        if (before != after) {
            DiagLog.i("link ${before.state} → ${after.state}${after.issue?.let { " ($it)" } ?: ""} on ${event::class.simpleName}")
            BridgeGraph.linkFlow.value = after
        }
        effects.forEach(::execute)
    }

    private fun execute(effect: LinkEffect) {
        when (effect) {
            LinkEffect.StartAdvertising ->
                if (peripheral.open()) peripheral.startAdvertising() else post(Msg.Link(LinkEvent.AdvertisingFailed(unsupported = false)))
            LinkEffect.StopAdvertising -> peripheral.stopAdvertising()
            LinkEffect.ClosePeripheral -> peripheral.close()
            LinkEffect.OpenClient -> openClient()
            LinkEffect.CloseClient -> closeClient()
            LinkEffect.Discover -> discover()
            LinkEffect.CreateBond -> createBond()
            LinkEffect.Subscribe -> subscribe()
            LinkEffect.ResetSession -> {
                ancs?.session?.reset()
                sourceArrival.clear()
                purgeJob?.cancel()
                repository.markAllStale()
                notifier.cancelAll() // actions on the posted notifications can't work until replay
            }
            LinkEffect.DisconnectCentral -> {
                device?.let(peripheral::disconnect)
                device = null
            }
            is LinkEffect.ScheduleRetry -> {
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(effect.delayMs)
                    dispatch(LinkEvent.RetryTimer)
                }
            }
            LinkEffect.CancelRetry -> retryJob?.cancel()
        }
    }

    private fun openClient() {
        val d = device ?: return
        setupJob?.cancel()
        setupJob = scope.launch {
            BridgeGraph.phoneNameFlow.value = runCatching { d.name }.getOrNull()
            // On a link the iPhone opened, Android reuses the existing ACL for this client.
            val connect = queue.run(GattOpQueue.Kind.CONNECT, timeoutMs = 15_000) {
                gatt = d.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
                gatt != null
            }
            if (!connect.ok) {
                DiagLog.w("gatt client connect failed status=${connect.status}")
                dispatch(LinkEvent.SetupFailed)
                return@launch
            }
            // A larger MTU means fewer Data Source fragments. iOS usually grants 185. A failure here is harmless.
            val mtu = queue.run(GattOpQueue.Kind.MTU, timeoutMs = 5_000) { gatt?.requestMtu(185) == true }
            DiagLog.i("gatt client connected mtuStatus=${mtu.status}")
            dispatch(LinkEvent.ClientReady)
        }
    }

    private fun closeClient() {
        setupJob?.cancel()
        bondTimeoutJob?.cancel()
        queue.failAll()
        ancs = null
        gatt?.close()
        gatt = null
    }

    private fun discover() {
        val g = gatt ?: return post(Msg.Link(LinkEvent.SetupFailed))
        setupJob?.cancel()
        setupJob = scope.launch {
            val r = queue.run(GattOpQueue.Kind.DISCOVER, timeoutMs = 20_000) { g.discoverServices() }
            if (!r.ok) {
                DiagLog.w("discovery failed status=${r.status}")
                dispatch(LinkEvent.SetupFailed)
                return@launch
            }
            val hasAncs = g.getService(AncsUuids.SERVICE) != null
            val bonded = device?.bondState == BluetoothDevice.BOND_BONDED
            DiagLog.i("services=${g.services.size} ancs=$hasAncs bonded=$bonded")
            dispatch(LinkEvent.ServicesDiscovered(hasAncs, bonded))
        }
    }

    private fun createBond() {
        val d = device ?: return
        when (d.bondState) {
            BluetoothDevice.BOND_BONDED -> return post(Msg.Link(LinkEvent.Bonded))
            BluetoothDevice.BOND_NONE -> {
                val started = d.createBond()
                DiagLog.i("pairing requested started=$started")
            }
            else -> DiagLog.i("pairing already in progress")
        }
        bondTimeoutJob?.cancel()
        bondTimeoutJob = scope.launch {
            delay(60_000) // the user has to confirm the code on both screens
            dispatch(LinkEvent.BondFailed)
        }
    }

    private fun onBondState(msg: Msg.BondState) {
        if (msg.device.address != device?.address) return
        DiagLog.i("bond state ${msg.previous} → ${msg.state}")
        when (msg.state) {
            BluetoothDevice.BOND_BONDED -> {
                bondTimeoutJob?.cancel()
                dispatch(LinkEvent.Bonded)
            }
            BluetoothDevice.BOND_NONE -> if (msg.previous == BluetoothDevice.BOND_BONDING) {
                bondTimeoutJob?.cancel()
                dispatch(LinkEvent.BondFailed)
            }
        }
    }

    private fun subscribe() {
        val g = gatt ?: return post(Msg.Link(LinkEvent.SetupFailed))
        setupJob?.cancel()
        setupJob = scope.launch {
            val client = AncsClient(g, queue, scope, AncsListener())
            ancs = client
            when (val r = client.subscribe()) {
                AncsClient.SubscribeResult.OK -> {
                    DiagLog.i("ancs subscribed")
                    dispatch(LinkEvent.Subscribed)
                    // iOS replays every current notification right after subscription. Whatever isn't
                    // replayed within a few seconds was cleared on the iPhone while we were away.
                    purgeJob?.cancel()
                    purgeJob = scope.launch {
                        delay(STALE_PURGE_MS)
                        repository.purgeStale().forEach(notifier::cancel)
                    }
                }
                else -> {
                    DiagLog.w("ancs subscribe failed $r")
                    ancs = null
                    dispatch(LinkEvent.SubscribeFailed(authRequired = r == AncsClient.SubscribeResult.AUTH_REQUIRED))
                }
            }
        }
    }

    // ---- ANCS → repository / notifier ----

    private inner class AncsListener : AncsSession.Listener {
        override fun onNotification(notification: AncsNotification, event: NotificationSourceEvent) {
            val now = System.currentTimeMillis()
            val wn = WatchNotification(notification, receivedAtMillis = now)
            repository.upsert(wn)

            // Reconnect grace: never buzz for replayed (pre-existing) notifications, and coalesce bursts.
            val elapsed = SystemClock.elapsedRealtime()
            val isNew = event.eventId == EventId.ADDED && !event.isPreExisting
            // A connected call is a state change the user just caused, not news: never buzz for it.
            val alert = !wn.isActiveCall && !event.isSilent && (isNew || wn.isIncomingCall && !event.isPreExisting) &&
                (wn.isIncomingCall || elapsed - lastAlertAt > ALERT_COALESCE_MS)
            if (alert) lastAlertAt = elapsed
            // Replayed (pre-existing) notifications go to the in-app inbox only. Posting them all
            // floods the watch and Android's per-app notification quota. A ringing call is the exception.
            val surface = !event.isPreExisting || wn.isIncomingCall || notifier.isPosted(notification.uid)
            if (surface) notifier.show(wn, alert)

            if (notification.category in CALL_CATEGORIES) {
                // System call-UI labels (e.g. "Answer"), not user content. Logged to verify action semantics.
                DiagLog.i("call actions cat=${notification.category} positive=${notification.positiveActionLabel} negative=${notification.negativeActionLabel}")
            }
            val latency = sourceArrival.remove(notification.uid)?.let { elapsed - it }
            DiagLog.i(
                "notif ${event.eventId} app=${notification.appIdentifier} cat=${notification.category} " +
                    "uid=${Redact.uid(notification.uid)} alert=$alert latency=${latency ?: "?"}ms",
            )
        }

        override fun onNotificationRemoved(uid: Long) {
            DiagLog.i("notif REMOVED uid=${Redact.uid(uid)}")
            repository.remove(uid)
            notifier.cancel(uid)
            sourceArrival.remove(uid)
        }

        override fun onAppName(appIdentifier: String, displayName: String) {
            repository.updateAppName(appIdentifier, displayName)
            // Re-post visible notifications from this app so they show the proper name (silently).
            repository.items.value.filter { it.ancs.appIdentifier == appIdentifier && !it.stale && notifier.isPosted(it.uid) }
                .forEach { notifier.show(it, alert = false) }
        }

        override fun onActionResult(uid: Long, action: ActionId, success: Boolean) {
            repository.setActionStatus(ActionStatus(uid, action, if (success) ActionStatus.State.DONE else ActionStatus.State.FAILED))
            DiagLog.i("action $action uid=${Redact.uid(uid)} success=$success")
        }

        override fun onProtocolIssue(reason: String) = DiagLog.w("ancs: $reason")
    }

    // ---- Binder callbacks → inbox ----

    private inner class PeripheralListener : BridgePeripheral.Listener {
        override fun onCentralConnected(device: BluetoothDevice) = post(Msg.CentralConnected(device))
        override fun onCentralDisconnected(device: BluetoothDevice) = post(Msg.CentralDisconnected(device))
        override fun onAdvertisingFailed(errorCode: Int, unsupported: Boolean) =
            post(Msg.Link(LinkEvent.AdvertisingFailed(unsupported)))
        override fun onControlWrite(device: BluetoothDevice, value: ByteArray) {
            DiagLog.i("bridge control write ${value.size} bytes from ${Redact.device(device)}")
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> queue.complete(GattOpQueue.Kind.CONNECT, status)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    queue.failAll()
                    post(Msg.ClientDisconnected(g, status))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = queue.complete(GattOpQueue.Kind.MTU, status)

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = queue.complete(GattOpQueue.Kind.DISCOVER, status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: android.bluetooth.BluetoothGattDescriptor, status: Int) =
            queue.complete(GattOpQueue.Kind.WRITE_DESCRIPTOR, status)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            queue.complete(GattOpQueue.Kind.WRITE_CHARACTERISTIC, status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            post(Msg.Characteristic(c.uuid, value.copyOf()))
        }

        @Deprecated("Pre-API 33 delivery path")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) post(Msg.Characteristic(c.uuid, c.value.copyOf()))
        }

        override fun onServiceChanged(g: BluetoothGatt) {
            DiagLog.i("iPhone GATT database changed")
            post(Msg.Link(LinkEvent.RetryTimer))
        }
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> post(Msg.Link(LinkEvent.BluetoothOn))
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> post(Msg.Link(LinkEvent.BluetoothOff))
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    post(
                        Msg.BondState(
                            d,
                            intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR),
                            intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR),
                        ),
                    )
                }
            }
        }
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(context, systemReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiversRegistered = true
    }

    private companion object {
        val CALL_CATEGORIES = setOf(
            io.wearbridge.ancs.Category.INCOMING_CALL,
            io.wearbridge.ancs.Category.MISSED_CALL,
            io.wearbridge.ancs.Category.ACTIVE_CALL,
        )
        const val ALERT_COALESCE_MS = 1_500L
        const val STALE_PURGE_MS = 5_000L
    }
}
