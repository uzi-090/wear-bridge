package io.wearbridge.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.wearbridge.ams.AmsUuids
import io.wearbridge.ams.NowPlaying
import io.wearbridge.ams.RemoteCommand
import io.wearbridge.ancs.ActionId
import io.wearbridge.ancs.AncsUuids
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.BridgePermissions
import io.wearbridge.watch.ancs.AncsClient
import io.wearbridge.protocol.BridgeCodec
import io.wearbridge.protocol.BridgeMessageType
import io.wearbridge.watch.bridge.DeviceChannel
import io.wearbridge.watch.bridge.SettingsChannel
import io.wearbridge.watch.bridge.WatchBattery
import io.wearbridge.watch.find.FindPhoneState
import io.wearbridge.watch.contacts.CallRequestState
import io.wearbridge.watch.contacts.ContactsChannel
import io.wearbridge.watch.security.SecureLink
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.diagnostics.Redact
import io.wearbridge.watch.media.AmsClient
import io.wearbridge.watch.notifications.AppCatalog
import io.wearbridge.watch.notifications.ActionStatus
import io.wearbridge.watch.notifications.NotificationEngine
import io.wearbridge.watch.notifications.NotificationRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private val peripheral = BridgePeripheral(context, PeripheralListener(), BridgeGraph.identity.publicKey)
    private val pairing = PairingManager(scope, onBonded = { dispatch(LinkEvent.Bonded) }, onFailed = { dispatch(LinkEvent.BondFailed) })
    private val reconnection = ReconnectionManager(scope) { dispatch(LinkEvent.RetryTimer) }
    private val power = LinkPower(
        request = { priority -> session?.requestPriority(priority) == true },
        timer = { delayMs, action ->
            val job = scope.launch {
                kotlinx.coroutines.delay(delayMs)
                action()
            }
            ({ job.cancel() })
        },
        log = DiagLog::i,
    )
    private val secureLink: SecureLink = SecureLink(peripheral, BridgeGraph.identity, BridgeGraph.approvals, onApprovalRequired = { request ->
        // A new app key means Bridge on the iPhone was reinstalled or removed this watch while it
        // was away: the old approval is dead, so revoke it now rather than after the user answers.
        runCatching { BridgeGraph.identity.forgetAll() }.onFailure { DiagLog.w("trust reset failed ${it::class.simpleName}") }
        BridgeGraph.pairing.setNotice(null)
        BridgeGraph.pairing.set(BridgeGraph.pairing.state.value.begin())
        if (sm.snapshot.state == LinkState.READY) dispatch(LinkEvent.SetupFailed)
        approvalTimeout?.cancel()
        approvalTimeout = scope.launch { delay(APPROVAL_TIMEOUT_MS); post(Msg.ApprovalExpired(request)) }
    }) { device ->
        approvalTimeout?.cancel()
        val approved = BridgeGraph.pairing.state.value.approved(device.address)
        BridgeGraph.pairing.set(approved)
        if (approved.phase == PairingPhase.PAIRED) {
            settingsChannel.onAuthenticated(device)
            deviceChannel.onAuthenticated(device)
            if (sm.snapshot.state == LinkState.SUBSCRIBING) subscribe()
        }
    }
    private val settingsChannel: SettingsChannel = SettingsChannel(peripheral, secureLink, BridgeGraph.policy, BridgeGraph.icons, BridgeGraph.settings, scope)
    private val deviceChannel: DeviceChannel = DeviceChannel(peripheral, secureLink, scope, appVersion(context), context) { post(Msg.Lifecycle(it)) }
    private val contactsChannel = ContactsChannel(secureLink, BridgeGraph.contacts) { nonce ->
        deviceChannel.sendEvent(io.wearbridge.protocol.ContactsMessageType.CLEARED, nonce)
    }
    private var findPhoneTimeout: Job? = null
    private var removal: BridgeCodec.Lifecycle? = null
    private var removalGeneration = 0L
    private var removalTimeout: Job? = null
    private var removalRemote = false
    /** The pairing phase when removal started: cancelling setup isn't the same as unpairing. */
    private var removalFrom = PairingPhase.PAIRED
    private var cleanupFailed = false
    private var approvalTimeout: Job? = null


    private var device: BluetoothDevice? = null
    private var session: GattSession? = null
    private var ancs: AncsClient? = null
    private var ams: AmsClient? = null
    private var phoneServices: PhoneServicesClient? = null
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
        data class Subscription(val device: BluetoothDevice, val characteristic: UUID, val subscribed: Boolean) : Msg
        data class ControlChunk(val device: BluetoothDevice, val value: ByteArray) : Msg
        data class FindPhone(val ring: Boolean) : Msg
        data class Event(val type: Int, val payload: ByteArray) : Msg
        data class AuthChunk(val device: BluetoothDevice, val value: ByteArray) : Msg
        data class ContactsChunk(val device: BluetoothDevice, val value: ByteArray) : Msg
        data class CallRequest(val phone: String, val name: String) : Msg
        data class Approve(val allow: Boolean) : Msg
        data class ApprovalExpired(val request: Int) : Msg
        data class Media(val command: RemoteCommand) : Msg
        data class Foreground(val on: Boolean) : Msg
        data object Pair : Msg
        data class Lifecycle(val value: BridgeCodec.Lifecycle) : Msg
        data class Remove(val forget: Boolean) : Msg
        data class FinishRemoval(val generation: Long) : Msg
        data object Shutdown : Msg
    }

    fun start() {
        scope.launch {
            for (msg in inbox) {
                // One bad message must never stop the link: before this guard, a single exception ended
                // this loop and silently stopped every later notification and call (found on hardware).
                try {
                    handle(msg)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DiagLog.w("link: ${msg::class.simpleName} failed ${e::class.simpleName}")
                }
            }
        }
        registerReceivers()
        post(
            Msg.Link(
                when {
                    !BridgePermissions.hasRequired(context) -> LinkEvent.PermissionMissing
                    adapter?.isEnabled != true -> LinkEvent.BluetoothOff
                    BridgeGraph.pairing.state.value.enabled -> LinkEvent.Start
                    else -> LinkEvent.Stop
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
    fun pairDevice() = post(Msg.Pair)
    fun removeDevice(forget: Boolean) = post(Msg.Remove(forget))

    fun mediaCommand(command: RemoteCommand) = post(Msg.Media(command))

    fun findPhone(ring: Boolean) = post(Msg.FindPhone(ring))

    fun sendEvent(type: Int, payload: ByteArray) = post(Msg.Event(type, payload))

    fun approveIphoneApp(allow: Boolean) = post(Msg.Approve(allow))

    fun callFromIphone(phone: String, name: String) = post(Msg.CallRequest(phone, name))

    /** A Bridge screen is showing (or not): keeps the link responsive while the user is looking. */
    fun setForeground(on: Boolean) = post(Msg.Foreground(on))

    private fun post(msg: Msg) {
        inbox.trySend(msg)
    }

    // ---- message handling (BLE dispatcher only) ----

    private fun handle(msg: Msg) {
        // Traffic either way (and user commands) keeps the link quick; quiet lets it drop to low power.
        if (msg !is Msg.Link && msg !is Msg.BondState && msg !is Msg.Foreground && msg !is Msg.Shutdown) power.activity()
        when (msg) {
            is Msg.Foreground -> power.setForeground(msg.on)
            // BridgeGraph.pairIphone has already entered PAIRING; start advertising if the link is idle.
            Msg.Pair -> if (removal == null && BridgeGraph.pairing.state.value.phase == PairingPhase.PAIRING) {
                dispatch(when {
                    !BridgePermissions.hasRequired(context) -> LinkEvent.PermissionMissing
                    adapter?.isEnabled != true -> LinkEvent.BluetoothOff
                    else -> LinkEvent.Start
                })
            }
            is Msg.Remove -> beginRemoval(BridgeCodec.Lifecycle(msg.forget, false), remote = false)
            is Msg.Lifecycle -> {
                if (msg.value.acknowledgement) {
                    if (removal?.forget == msg.value.forget) finishRemoval(confirmed = true)
                } else beginRemoval(msg.value, remote = true)
            }
            is Msg.FinishRemoval -> if (msg.generation == removalGeneration) finishRemoval()
            is Msg.Link -> {
                if (msg.event == LinkEvent.Stop || msg.event == LinkEvent.BluetoothOff || msg.event == LinkEvent.PermissionMissing ||
                    BridgeGraph.pairing.state.value.enabled) dispatch(msg.event)
            }
            is Msg.CentralConnected -> {
                if (!accepts(msg.device.address)) {
                    DiagLog.i("rejecting central ${Redact.device(msg.device)} pairing=${BridgeGraph.pairing.state.value.phase}")
                    peripheral.disconnect(msg.device)
                    return
                }
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
                    current != null && current.address != msg.device.address -> {
                        DiagLog.i("ignoring second central ${Redact.device(msg.device)}")
                        peripheral.disconnect(msg.device)
                    }
                }
            }
            is Msg.CentralDisconnected -> {
                secureLink.onDisconnected(msg.device)
                contactsChannel.onDisconnected(msg.device)
                settingsChannel.onDisconnected(msg.device)
                deviceChannel.onDisconnected(msg.device)
                if (msg.device.address == device?.address) {
                    if (removal != null) { finishRemoval(); return }
                    device = null
                    dispatch(LinkEvent.CentralDisconnected)
                }
            }
            is Msg.SettingsChunk -> if (BridgeGraph.pairing.state.value.phase == PairingPhase.PAIRED && msg.device.address == device?.address) settingsChannel.onChunk(msg.device, msg.value)
            is Msg.Subscription -> if (msg.device.address == device?.address) when (msg.characteristic) {
                BridgeUuids.SETTINGS -> settingsChannel.onSubscription(msg.device, msg.subscribed)
                // Answers go out when a HELLO arrives. Unsubscribing withdraws a waiting request, so
                // a code still on the watch can't approve an iPhone app that cancelled setup.
                BridgeUuids.AUTH -> if (!msg.subscribed && secureLink.withdraw(msg.device)) {
                    approvalTimeout?.cancel()
                    BridgeGraph.pairing.setNotice("Pairing cancelled on iPhone.")
                }
                else -> deviceChannel.onSubscription(msg.device, msg.characteristic, msg.subscribed)
            }
            is Msg.AuthChunk -> if (removal == null && accepts(msg.device.address) && msg.device.address == device?.address) secureLink.onAuthChunk(msg.device, msg.value)
            is Msg.ContactsChunk -> if (BridgeGraph.pairing.state.value.phase == PairingPhase.PAIRED && msg.device.address == device?.address) contactsChannel.onChunk(msg.device, msg.value)
            is Msg.CallRequest -> {
                if (!BridgeGraph.featuresAvailable) return
                val sent = deviceChannel.sendEvent(BridgeMessageType.CALL_REQUEST, BridgeCodec.encodeCallRequest(msg.phone, msg.name))
                BridgeGraph.callRequestFlow.value = if (sent) CallRequestState.SENT else CallRequestState.NO_APP
                DiagLog.i("call request sent=$sent")
            }
            is Msg.Approve -> if (removal == null && BridgeGraph.pairing.state.value.phase == PairingPhase.PAIRING &&
                BridgeGraph.pendingApproval.value != null) {
                approvalTimeout?.cancel()
                // A newly approved app starts a new relationship: nothing synced for an earlier one carries over.
                if (msg.allow) clearPeerData()
                secureLink.approve(msg.allow)
                BridgeGraph.pairing.setNotice(if (msg.allow) null else
                    "Pairing declined. Tap Ask Again in Bridge on iPhone, or Cancel pairing here.")
            }
            is Msg.ApprovalExpired -> if (secureLink.expire(msg.request)) {
                BridgeGraph.pairing.setNotice("Approval timed out. Tap Ask Again in Bridge on iPhone, or Cancel pairing here.")
            }
            is Msg.ControlChunk -> if (msg.device.address == device?.address) deviceChannel.onControlChunk(msg.device, msg.value)
            is Msg.Event -> if (BridgeGraph.featuresAvailable) deviceChannel.sendEvent(msg.type, msg.payload)
            is Msg.FindPhone -> ringPhone(msg.ring)
            is Msg.ClientDisconnected -> if (device != null && msg.session === session) {
                DiagLog.i("gatt client dropped status=${msg.status}")
                dispatch(LinkEvent.SetupFailed)
            }
            is Msg.Characteristic -> if (msg.session === session) {
                when (msg.uuid) {
                    AmsUuids.REMOTE_COMMAND, AmsUuids.ENTITY_UPDATE -> ams?.let { c ->
                        // Launched, because a truncated title triggers a GATT read. Runs in order on this dispatcher.
                        scope.launch { c.onCharacteristicChanged(msg.uuid, msg.value) }
                    }
                    PhoneServicesClient.BATTERY_LEVEL -> phoneServices?.onCharacteristicChanged(msg.uuid, msg.value)
                    else -> {
                        if (msg.uuid == AncsUuids.NOTIFICATION_SOURCE) engine.onSourcePacket(msg.value)
                        ancs?.onCharacteristicChanged(msg.uuid, msg.value)
                    }
                }
            }
            is Msg.Media -> {
                val c = ams.takeIf { BridgeGraph.featuresAvailable }
                if (c == null) {
                    DiagLog.i("media ${msg.command} unavailable")
                } else {
                    scope.launch { DiagLog.i("media ${msg.command} ${c.send(msg.command)}") }
                }
            }
            is Msg.BondState -> {
                val current = msg.device.address == device?.address
                if ((current || msg.device.address == BridgeGraph.pairing.state.value.address) && msg.state == BluetoothDevice.BOND_NONE &&
                    msg.previous == BluetoothDevice.BOND_BONDED) {
                    // The system pairing was forgotten in Bluetooth settings on either device. That
                    // isn't an app Unpair: approval stays with the iPhone app's key, and the phone
                    // can pair again at the system level (with a new address) and reconnect.
                    DiagLog.i("system bond removed ${Redact.device(msg.device)} current=$current")
                    // Invalidate authentication now, before any queued old CONTROL can refill battery.
                    secureLink.onDisconnected(msg.device)
                    deviceChannel.onDisconnected(msg.device)
                    settingsChannel.onDisconnected(msg.device)
                    contactsChannel.onDisconnected(msg.device)
                    // Re-advertise for discovery; don't drop a different phone that's mid-setup.
                    if (removal == null && BridgeGraph.pairing.state.value.enabled && (current || device == null) &&
                        sm.snapshot.state != LinkState.DISCONNECTED) {
                        peripheral.stopAdvertising()
                        dispatch(LinkEvent.BondRemoved)
                    }
                } else pairing.onBondStateChanged(msg.device, msg.state, msg.previous)
            }
            is Msg.Action -> {
                val ok = BridgeGraph.featuresAvailable && engine.canPerform(msg.uid, msg.action) && ancs?.session?.performAction(msg.uid, msg.action) == true
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

    private fun clearLiveData() {
        BridgeGraph.mediaFlow.value = null
        BridgeGraph.phoneBatteryFlow.value = null
        BridgeGraph.phoneChargingFlow.value = null
        BridgeGraph.phoneModelFlow.value = null
        BridgeGraph.phoneNameFlow.value = null
        BridgeGraph.phoneStatusReportedAt = 0
        BridgeGraph.findPhoneFlow.value = FindPhoneState.IDLE
        BridgeGraph.callRequestFlow.value = CallRequestState.IDLE
        findPhoneTimeout?.cancel()
        BridgeGraph.findWatch.stop(fromUser = false)
        io.wearbridge.watch.battery.LowBatteryAlerts.disconnected(context)
    }

    /** Data synced for a previous iPhone app. Failures are logged; the new approval still proceeds. */
    private fun clearPeerData() {
        engine.onLinkReset()
        listOf<() -> Unit>(BridgeGraph.contacts::forgetDevice, repository::forgetDevice,
            BridgeGraph.policy::forgetDevice, BridgeGraph.icons::clear).forEach { cleanup ->
            runCatching(cleanup).onFailure { DiagLog.w("previous device data cleanup failed ${it::class.simpleName}") }
        }
    }

    private fun accepts(address: String) = BridgeGraph.pairing.state.value.accepts(address, savedBonded())

    /** Whether the saved iPhone still has a system bond (unknown counts as bonded, the strict choice). */
    private fun savedBonded(): Boolean {
        val saved = BridgeGraph.pairing.state.value.address ?: return false
        return runCatching { adapter?.bondedDevices?.any { it.address == saved } }.getOrNull() ?: true
    }

    private fun beginRemoval(value: BridgeCodec.Lifecycle, remote: Boolean) {
        if (removal != null) return
        val generation = ++removalGeneration
        removal = value
        removalFrom = BridgeGraph.pairing.state.value.phase
        DiagLog.i("device removal started forget=${value.forget} remote=$remote from=$removalFrom")
        removalRemote = remote
        cleanupFailed = false
        approvalTimeout?.cancel()
        BridgeGraph.pairing.beginRemoval(value.forget)
        BridgeGraph.pairing.setNotice(null)
        closeClient()
        engine.onLinkReset()
        repository.forgetDevice()
        clearLiveData()
        device?.let {
            contactsChannel.onDisconnected(it)
            settingsChannel.onDisconnected(it)
        }
        listOf<() -> Unit>(BridgeGraph.identity::forgetAll, BridgeGraph.contacts::forgetDevice,
            BridgeGraph.policy::forgetDevice, BridgeGraph.icons::clear).forEach { cleanup ->
            runCatching(cleanup).onFailure {
                cleanupFailed = true
                DiagLog.w("device cleanup failed ${it::class.simpleName}")
            }
        }
        device?.let(peripheral::discardPendingNotifications)
        val sent = deviceChannel.sendEvent(BridgeMessageType.DEVICE_LIFECYCLE_EVENT,
            BridgeCodec.encodeLifecycle(value.copy(acknowledgement = remote)))
        DiagLog.i("device removal message queued=$sent acknowledgement=$remote")
        // A received request gets an application ACK. Wait for the phone to disconnect after reading it.
        // A local request waits for the phone's ACK; the timeout only completes local removal.
        if (!sent) finishRemoval()
        else removalTimeout = scope.launch { delay(5_000); post(Msg.FinishRemoval(generation)) }
    }

    private fun finishRemoval(confirmed: Boolean = false) {
        val value = removal ?: return
        DiagLog.i("device removal finished forget=${value.forget} confirmed=$confirmed remote=$removalRemote cleanupFailed=$cleanupFailed")
        removalTimeout?.cancel()
        BridgeGraph.pairing.completeRemoval(value.forget, !cleanupFailed)
        BridgeGraph.pairing.setNotice(when {
            cleanupFailed -> "Unpaired. Some saved data could not be deleted. Try Forget Device again."
            confirmed || removalRemote -> if (value.forget) "Forgotten on both devices."
                else "Unpaired on both. Your iPhone stays saved."
            // Nothing was paired yet, so there's nothing for the iPhone to confirm.
            removalFrom == PairingPhase.UNPAIRED -> "Saved iPhone removed."
            removalFrom == PairingPhase.PAIRING -> if (value.forget) "Pairing cancelled. Saved iPhone removed." else "Pairing cancelled."
            else -> if (value.forget) "Couldn't confirm iPhone removal. Forget this watch in Bridge on iPhone too."
                else "Couldn't confirm iPhone removal. Unpair in Bridge on iPhone too."
        })
        secureLink.reset()
        device?.let {
            contactsChannel.onDisconnected(it)
            settingsChannel.onDisconnected(it)
            deviceChannel.onDisconnected(it)
        }
        BridgeGraph.phoneAppConnectedFlow.value = false
        dispatch(LinkEvent.Stop)
        removal = null
    }

    private fun dispatch(event: LinkEvent) {
        val before = sm.snapshot
        val effects = sm.on(event)
        val after = sm.snapshot
        if (before != after) {
            DiagLog.i("link ${before.state} → ${after.state}${after.issue?.let { " ($it)" } ?: ""} on ${event::class.simpleName}")
            BridgeGraph.linkFlow.value = after
            if (after.state == LinkState.READY && before.state != LinkState.READY) power.onReady()
            if (before.state == LinkState.READY && after.state != LinkState.READY) power.onDown()
        }
        effects.forEach(::execute)
    }

    private fun execute(effect: LinkEffect) {
        when (effect) {
            // Lead with the Bridge service whenever the iPhone has to find this watch by scanning:
            // during setup, and after the system bond was forgotten (iOS can't recognise it then).
            LinkEffect.StartAdvertising ->
                if (peripheral.open()) peripheral.startAdvertising(
                    discoveryFirst = BridgeGraph.pairing.state.value.phase == PairingPhase.PAIRING || !savedBonded(),
                ) else post(Msg.Link(LinkEvent.AdvertisingFailed(unsupported = false)))
            LinkEffect.StopAdvertising -> peripheral.stopAdvertising()
            LinkEffect.ClosePeripheral -> {
                secureLink.reset()
                BridgeGraph.phoneAppConnectedFlow.value = false
                peripheral.close()
                device = null // closing the server drops every link, and no per-device callback follows
            }
            LinkEffect.OpenClient -> openClient()
            LinkEffect.CloseClient -> closeClient()
            LinkEffect.Discover -> discover()
            LinkEffect.CreateBond -> device?.let(pairing::request)
            LinkEffect.Subscribe -> subscribe()
            LinkEffect.ResetSession -> {
                clearLiveData()
                ancs?.session?.reset()
                engine.onLinkReset()
                BridgeGraph.mediaFlow.value = null
                BridgeGraph.phoneBatteryFlow.value = null
                BridgeGraph.phoneChargingFlow.value = null
            }
            LinkEffect.DisconnectCentral -> {
                device?.let {
                    secureLink.onDisconnected(it)
                    contactsChannel.onDisconnected(it)
                    settingsChannel.onDisconnected(it)
                    deviceChannel.onDisconnected(it)
                    peripheral.disconnect(it)
                }
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
            if (session !== s) return@launch
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
        power.onDown()
        setupJob?.cancel()
        pairing.cancel()
        ancs?.session?.reset()
        ancs = null
        ams = null
        phoneServices = null
        session?.close()
        session = null
    }

    private fun discover() {
        val s = session ?: return post(Msg.Link(LinkEvent.SetupFailed))
        setupJob?.cancel()
        setupJob = scope.launch {
            val r = s.discover()
            if (session !== s) return@launch
            if (!r.ok) {
                DiagLog.w("discovery failed status=${r.status}")
                dispatch(LinkEvent.SetupFailed)
                return@launch
            }
            val bonded = device?.bondState == BluetoothDevice.BOND_BONDED
            DiagLog.i("services=${r.serviceCount} ancs=${r.hasAncs} bonded=$bonded")
            // Service UUIDs are public protocol identifiers (no personal data). Logged to document what iOS exposes.
            DiagLog.i("iPhone services: ${r.services.joinToString(" ") { Redact.serviceName(it) }}")
            dispatch(LinkEvent.ServicesDiscovered(r.hasAncs, bonded, r.serviceCount))
        }
    }

    private fun subscribe() {
        val pairingState = BridgeGraph.pairing.state.value
        if (pairingState.phase != PairingPhase.PAIRED || pairingState.address != device?.address) return
        val s = session ?: return post(Msg.Link(LinkEvent.SetupFailed))
        // ANCS write completions run asynchronously. An old client must never publish into a new
        // paired phone's repository, even if the new link has already become READY.
        val listener = object : io.wearbridge.ancs.AncsSession.Listener by engine {
            private fun current() = session === s && BridgeGraph.featuresAvailable
            override fun onNotification(notification: io.wearbridge.ancs.AncsNotification,
                event: io.wearbridge.ancs.NotificationSourceEvent) {
                if (current()) engine.onNotification(notification, event)
            }
            override fun onNotificationRemoved(uid: Long) { if (current()) engine.onNotificationRemoved(uid) }
            override fun onAppName(appIdentifier: String, displayName: String) {
                if (current()) engine.onAppName(appIdentifier, displayName)
            }
            override fun onActionResult(uid: Long, action: ActionId, success: Boolean) {
                if (current()) engine.onActionResult(uid, action, success)
            }
        }
        val client = s.ancsClient(scope, listener) ?: return post(Msg.Link(LinkEvent.SetupFailed))
        setupJob?.cancel()
        ancs = client
        setupJob = scope.launch {
            val r = client.subscribe()
            if (session !== s) return@launch
            when (r) {
                AncsClient.SubscribeResult.OK -> {
                    DiagLog.i("ancs subscribed")
                    dispatch(LinkEvent.Subscribed)
                    engine.onSubscribed()
                    startOptionalServices()
                }
                else -> {
                    DiagLog.w("ancs subscribe failed $r")
                    ancs = null
                    dispatch(LinkEvent.SubscribeFailed(authRequired = r == AncsClient.SubscribeResult.AUTH_REQUIRED))
                }
            }
        }
    }

    /**
     * Find iPhone: an event to the iPhone app, which plays an alert. Only works while the app is
     * connected (iOS can suspend it, and a force-quit app is never relaunched), so the UI says so.
     */
    private fun ringPhone(ring: Boolean) {
        findPhoneTimeout?.cancel()
        if (!ring) {
            deviceChannel.sendEvent(BridgeMessageType.FIND_PHONE, BridgeCodec.encodeSwitch(false))
            BridgeGraph.findPhoneFlow.value = FindPhoneState.IDLE
            return
        }
        val state = when {
            !BridgeGraph.featuresAvailable -> FindPhoneState.NO_LINK
            !deviceChannel.sendEvent(BridgeMessageType.FIND_PHONE, BridgeCodec.encodeSwitch(true)) -> FindPhoneState.NO_APP
            else -> FindPhoneState.RINGING
        }
        DiagLog.i("find phone: $state")
        BridgeGraph.findPhoneFlow.value = state
        // The iPhone's alert sound lasts 30 s. Back to idle after that (or an error message clears itself).
        findPhoneTimeout = scope.launch {
            delay(FIND_PHONE_MS)
            BridgeGraph.findPhoneFlow.value = FindPhoneState.IDLE
        }
    }

    /**
     * Media (AMS) and the iPhone's battery/model. Started only once notifications work, and failures
     * here are logged, never fatal: they can't take ANCS down.
     */
    private fun startOptionalServices() {
        val s = session ?: return
        scope.launch {
            val media = s.amsClient(object : AmsClient.Listener {
                override fun onNowPlaying(state: NowPlaying) {
                    if (session !== s || BridgeGraph.demoMode) return // reject obsolete session updates
                    val before = BridgeGraph.mediaFlow.value
                    if (state.playerName.isNotBlank() && state.playerName != before?.playerName) {
                        // Remember the player's bundle ID (when known) so the iPhone app sends its official icon.
                        val app = AppCatalog.forPlayerName(state.playerName, BridgeGraph.policy.seenApps.value)
                        app.bundleId?.let { BridgeGraph.policy.noteMediaApp(it, app.name) }
                    }
                    BridgeGraph.mediaFlow.value = state
                    if (before?.hasPlayer != state.hasPlayer || before.state != state.state || before.supported != state.supported) {
                        DiagLog.i("ams $state") // redacted: state and counts only, never titles
                    }
                }
            })
            if (media != null && media.start()) {
                if (session === s) ams = media
                // With nothing playing, iOS sends no initial values: AMS works, there's just no player yet.
                if (session === s && BridgeGraph.mediaFlow.value == null && !BridgeGraph.demoMode) BridgeGraph.mediaFlow.value = NowPlaying()
                DiagLog.i("ams ready")
            } else {
                DiagLog.i("ams unavailable")
            }
            if (session !== s) return@launch
            val phone = s.phoneServices(object : PhoneServicesClient.Listener {
                override fun onPhoneBattery(percent: Int) {
                    if (session !== s) return
                    val previous = BridgeGraph.phoneBatteryFlow.value
                    BridgeGraph.phoneBatteryFlow.value = percent
                    // Without a recent report from the iPhone app, infer charging from the trend.
                    val appReportedRecently = android.os.SystemClock.elapsedRealtime() - BridgeGraph.phoneStatusReportedAt < APP_POWER_TRUST_MS
                    if (!appReportedRecently && previous != null && percent != previous) {
                        BridgeGraph.phoneChargingFlow.value = percent > previous
                    }
                    // BAS has no charging flag. Only a fresh companion report can authorize a warning.
                    io.wearbridge.watch.battery.LowBatteryAlerts.receive(context, s.device.address, percent,
                        BridgeGraph.phoneChargingFlow.value.takeIf { appReportedRecently })
                }
                override fun onPhoneModel(model: String) {
                    if (session !== s) return
                    BridgeGraph.phoneModelFlow.value = model
                }
            })
            if (phone != null) {
                val found = phone.start()
                if (session === s) phoneServices = phone
                DiagLog.i("phone services battery=${found.battery} deviceInfo=${found.deviceInfo} model=${BridgeGraph.phoneModel.value}")
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
        override fun onControlWrite(device: BluetoothDevice, value: ByteArray) = post(Msg.ControlChunk(device, value))
        override fun onAuthChunk(device: BluetoothDevice, value: ByteArray) = post(Msg.AuthChunk(device, value))
        override fun onContactsChunk(device: BluetoothDevice, value: ByteArray) = post(Msg.ContactsChunk(device, value))
        override fun onSettingsChunk(device: BluetoothDevice, value: ByteArray) = post(Msg.SettingsChunk(device, value))
        override fun onSubscription(device: BluetoothDevice, characteristic: UUID, subscribed: Boolean) =
            post(Msg.Subscription(device, characteristic, subscribed))
    }

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                    BluetoothAdapter.STATE_ON -> post(Msg.Link(LinkEvent.BluetoothOn))
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> post(Msg.Link(LinkEvent.BluetoothOff))
                }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    val b = WatchBattery(if (level >= 0 && scale > 0) level * 100 / scale else null, plugged)
                    if (BridgeGraph.watchBattery.value != b) BridgeGraph.watchBatteryFlow.value = b
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
            addAction(Intent.ACTION_BATTERY_CHANGED) // sticky: delivers the current level at once
        }
        // Both are protected system broadcasts, so other apps can't spoof them.
        ContextCompat.registerReceiver(context, systemReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiversRegistered = true
    }
}

private const val FIND_PHONE_MS = 30_000L

/** How long a matching-code request waits for an answer on the watch (the code screen stays on as long). */
private const val APPROVAL_TIMEOUT_MS = 120_000L

/** An iPhone app report outranks the battery-trend guess for this long (it re-reports on every change). */
private const val APP_POWER_TRUST_MS = 10 * 60_000L

private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
