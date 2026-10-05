package io.wearbridge.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.wearbridge.ancs.ActionId
import io.wearbridge.ancs.AncsUuids
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.BridgePermissions
import io.wearbridge.watch.ancs.AncsClient
import io.wearbridge.watch.bridge.SettingsChannel
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.diagnostics.Redact
import io.wearbridge.watch.notifications.ActionStatus
import io.wearbridge.watch.notifications.NotificationEngine
import io.wearbridge.watch.notifications.NotificationRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Orchestrates the iPhone link. It runs [ConnectionStateMachine] effects against [BridgePeripheral]
 * (advertising and the bridge GATT server), [GattSession] (client to iOS), [PairingManager] and
 * [ReconnectionManager], then hands ANCS to [NotificationEngine].
 *
 * Concurrency: every Binder callback becomes a message on [inbox], processed in order on a
 * single-threaded dispatcher. All mutable state here and in the helpers is confined to it.
 */
@SuppressLint("MissingPermission") // start() refuses to run without the runtime permissions
class ConnectionManager(
    private val context: Context,
    private val repository: NotificationRepository,
    engineFactory: (CoroutineScope) -> NotificationEngine,
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default.limitedParallelism(1) +
            CoroutineExceptionHandler { _, e -> DiagLog.w("connection error ${e::class.simpleName}") },
    )
    private val inbox = Channel<Msg>(Channel.UNLIMITED)

    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val sm = ConnectionStateMachine()
    private val engine = engineFactory(scope)
    private val peripheral = BridgePeripheral(context, PeripheralListener())
    private val pairing = PairingManager(scope, onBonded = { dispatch(LinkEvent.Bonded) }, onFailed = { dispatch(LinkEvent.BondFailed) })
    private val reconnection = ReconnectionManager(scope) { dispatch(LinkEvent.RetryTimer) }
    private val settingsChannel = SettingsChannel(peripheral, BridgeGraph.policy, BridgeGraph.icons, scope)

    private var device: BluetoothDevice? = null
    private var session: GattSession? = null
    private var ancs: AncsClient? = null
    private var setupJob: Job? = null
    private var receiversRegistered = false

    private sealed interface Msg {
        data class Link(val event: LinkEvent) : Msg
        data class CentralConnected(val device: BluetoothDevice) : Msg
        data class CentralDisconnected(val device: BluetoothDevice) : Msg
        data class ClientDisconnected(val session: GattSession, val status: Int) : Msg
        data class Characteristic(val session: GattSession, val uuid: UUID, val value: ByteArray) : Msg
        data class BondState(val device: BluetoothDevice, val state: Int, val previous: Int) : Msg
        data class Action(val uid: Long, val action: ActionId) : Msg
        data class SettingsChunk(val device: BluetoothDevice, val value: ByteArray) : Msg
        data class SettingsSubscription(val device: BluetoothDevice, val subscribed: Boolean) : Msg
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
                val waiting = sm.snapshot.state in setOf(LinkState.ADVERTISING, LinkState.RECONNECTING, LinkState.ERROR)
                val current = device
                when {
                    // While waiting for the iPhone, a remembered device is stale (for example after a
                    // Bluetooth toggle, where no disconnect callback arrives). Found on hardware.
                    waiting -> {
                        if (current != null && current.address != msg.device.address) {
                            DiagLog.i("replacing stale central ${Redact.device(current)} with ${Redact.device(msg.device)}")
                        }
                        device = msg.device
                        dispatch(LinkEvent.CentralConnected)
                    }
                    current != null && current.address != msg.device.address ->
                        DiagLog.i("ignoring second central ${Redact.device(msg.device)}")
                }
            }
            is Msg.CentralDisconnected -> {
                settingsChannel.onDisconnected(msg.device)
                if (msg.device.address == device?.address) {
                    device = null
                    dispatch(LinkEvent.CentralDisconnected)
                }
            }
            is Msg.SettingsChunk -> settingsChannel.onChunk(msg.device, msg.value)
            is Msg.SettingsSubscription -> settingsChannel.onSubscription(msg.device, msg.subscribed)
            is Msg.ClientDisconnected -> if (device != null && msg.session === session) {
                DiagLog.i("gatt client dropped status=${msg.status}")
                dispatch(LinkEvent.SetupFailed)
            }
            is Msg.Characteristic -> if (msg.session === session) {
                if (msg.uuid == AncsUuids.NOTIFICATION_SOURCE) engine.onSourcePacket(msg.value)
                ancs?.onCharacteristicChanged(msg.uuid, msg.value)
            }
            is Msg.BondState -> pairing.onBondStateChanged(msg.device, msg.state, msg.previous)
            is Msg.Action -> {
                val ok = engine.canPerform(msg.uid, msg.action) && ancs?.session?.performAction(msg.uid, msg.action) == true
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
            LinkEffect.ClosePeripheral -> {
                peripheral.close()
                device = null // closing the server drops every link, and no per-device callback follows
            }
            LinkEffect.OpenClient -> openClient()
            LinkEffect.CloseClient -> closeClient()
            LinkEffect.Discover -> discover()
            LinkEffect.CreateBond -> device?.let(pairing::request)
            LinkEffect.Subscribe -> subscribe()
            LinkEffect.ResetSession -> {
                ancs?.session?.reset()
                engine.onLinkReset()
            }
            LinkEffect.DisconnectCentral -> {
                device?.let(peripheral::disconnect)
                device = null
            }
            is LinkEffect.ScheduleRetry -> reconnection.schedule(effect.delayMs)
            LinkEffect.CancelRetry -> reconnection.cancel()
        }
    }

    private fun openClient() {
        val d = device ?: return
        setupJob?.cancel()
        val s = GattSession(context, d, SessionListener()).also { session = it }
        setupJob = scope.launch {
            BridgeGraph.phoneNameFlow.value = runCatching { d.name }.getOrNull()
            val status = s.connect()
            if (status != 0) {
                DiagLog.w("gatt client connect failed status=$status")
                dispatch(LinkEvent.SetupFailed)
                return@launch
            }
            DiagLog.i("gatt client connected")
            dispatch(LinkEvent.ClientReady)
        }
    }

    private fun closeClient() {
        setupJob?.cancel()
        pairing.cancel()
        ancs = null
        session?.close()
        session = null
    }

    private fun discover() {
        val s = session ?: return post(Msg.Link(LinkEvent.SetupFailed))
        setupJob?.cancel()
        setupJob = scope.launch {
            val r = s.discover()
            if (!r.ok) {
                DiagLog.w("discovery failed status=${r.status}")
                dispatch(LinkEvent.SetupFailed)
                return@launch
            }
            val bonded = device?.bondState == BluetoothDevice.BOND_BONDED
            DiagLog.i("services=${r.serviceCount} ancs=${r.hasAncs} bonded=$bonded")
            dispatch(LinkEvent.ServicesDiscovered(r.hasAncs, bonded))
        }
    }

    private fun subscribe() {
        val client = session?.ancsClient(scope, engine) ?: return post(Msg.Link(LinkEvent.SetupFailed))
        setupJob?.cancel()
        ancs = client
        setupJob = scope.launch {
            when (val r = client.subscribe()) {
                AncsClient.SubscribeResult.OK -> {
                    DiagLog.i("ancs subscribed")
                    dispatch(LinkEvent.Subscribed)
                    engine.onSubscribed()
                }
                else -> {
                    DiagLog.w("ancs subscribe failed $r")
                    ancs = null
                    dispatch(LinkEvent.SubscribeFailed(authRequired = r == AncsClient.SubscribeResult.AUTH_REQUIRED))
                }
            }
        }
    }

    // ---- Binder callbacks → inbox ----

    private inner class SessionListener : GattSession.Listener {
        override fun onCharacteristicChanged(session: GattSession, uuid: UUID, value: ByteArray) =
            post(Msg.Characteristic(session, uuid, value))
        override fun onDisconnected(session: GattSession, status: Int) = post(Msg.ClientDisconnected(session, status))
        override fun onMtu(session: GattSession, mtu: Int) {
            DiagLog.i("link mtu=$mtu")
            peripheral.recordMtu(session.device, mtu)
        }
        override fun onServiceChanged(session: GattSession) {
            DiagLog.i("iPhone GATT database changed")
            post(Msg.Link(LinkEvent.RetryTimer))
        }
    }

    private inner class PeripheralListener : BridgePeripheral.Listener {
        override fun onCentralConnected(device: BluetoothDevice) = post(Msg.CentralConnected(device))
        override fun onCentralDisconnected(device: BluetoothDevice) = post(Msg.CentralDisconnected(device))
        override fun onAdvertisingFailed(errorCode: Int, unsupported: Boolean) =
            post(Msg.Link(LinkEvent.AdvertisingFailed(unsupported)))
        override fun onControlWrite(device: BluetoothDevice, value: ByteArray) {
            DiagLog.i("bridge control write ${value.size} bytes from ${Redact.device(device)}")
        }
        override fun onSettingsChunk(device: BluetoothDevice, value: ByteArray) = post(Msg.SettingsChunk(device, value))
        override fun onSettingsSubscription(device: BluetoothDevice, subscribed: Boolean) =
            post(Msg.SettingsSubscription(device, subscribed))
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
        // Both are protected system broadcasts, so other apps can't spoof them.
        ContextCompat.registerReceiver(context, systemReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiversRegistered = true
    }
}
