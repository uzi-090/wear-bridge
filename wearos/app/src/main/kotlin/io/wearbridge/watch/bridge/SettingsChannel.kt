package io.wearbridge.watch.bridge

import android.bluetooth.BluetoothDevice
import io.wearbridge.ancs.Category
import io.wearbridge.protocol.AppEntry
import io.wearbridge.protocol.Framing
import io.wearbridge.protocol.MessageType
import io.wearbridge.protocol.ModeCode
import io.wearbridge.protocol.PopupCode
import io.wearbridge.protocol.Reassembler
import io.wearbridge.protocol.SettingsCodec
import io.wearbridge.protocol.SettingsSnapshot
import io.wearbridge.protocol.CallGestures
import io.wearbridge.protocol.WatchPrefs
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.ble.BridgePeripheral
import io.wearbridge.watch.security.SecureLink
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.diagnostics.Redact
import io.wearbridge.watch.notifications.AppCatalog
import io.wearbridge.watch.notifications.AppKind
import io.wearbridge.watch.notifications.AppRule
import io.wearbridge.watch.notifications.FilterMode
import io.wearbridge.watch.settings.AppIconStore
import io.wearbridge.watch.settings.BridgeSettings
import io.wearbridge.watch.settings.PolicyStore
import io.wearbridge.watch.settings.PopupStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The SETTINGS channel: the iPhone app shows and edits notification settings, and the watch, which
 * receives ANCS directly, stores and applies them. Every change is answered with a fresh snapshot to
 * all subscribers, so the iPhone always shows what the watch actually holds. Confined to the BLE scope.
 */
class SettingsChannel(
    private val peripheral: BridgePeripheral,
    private val secure: SecureLink,
    private val policy: PolicyStore,
    private val icons: AppIconStore,
    private val settings: BridgeSettings,
    private val scope: CoroutineScope,
) {
    private val reassemblers = HashMap<String, Reassembler>()
    private val subscribers = HashMap<String, BluetoothDevice>()
    private var messageId = 0
    private var pushJob: Job? = null

    init {
        scope.launch {
            combine(
                policy.mode, policy.rules, combine(policy.seenApps, policy.mediaApps) { a, b -> a to b }, icons.version,
                combine(
                    settings.popupStyle, settings.history, settings.endCallFromWatch,
                    combine(settings.shakeToDecline, settings.shakeToEnd, settings.bezelToAnswer) { _, _, _ -> },
                ) { _, _, _, _ -> },
            ) { _, _, _, _, _ -> }.drop(1).collect { schedulePush() }
        }
    }

    fun onSubscription(device: BluetoothDevice, subscribed: Boolean) {
        DiagLog.i("settings subscribed=$subscribed ${Redact.device(device)}")
        if (subscribed) {
            subscribers[device.address] = device
            send(device)
        } else {
            subscribers.remove(device.address)
        }
    }

    fun onDisconnected(device: BluetoothDevice) {
        subscribers.remove(device.address)
        reassemblers.remove(device.address)
    }

    /** The iPhone app just authenticated: send what it subscribed to before it could be trusted. */
    fun onAuthenticated(device: BluetoothDevice) {
        if (device.address in subscribers) send(device)
    }

    fun onChunk(device: BluetoothDevice, chunk: ByteArray) {
        val reassembler = reassemblers.getOrPut(device.address) { Reassembler(maxMessageBytes = 40 * 1024) }
        when (val r = reassembler.accept(chunk)) {
            Reassembler.Result.Incomplete -> Unit
            is Reassembler.Result.Error -> DiagLog.w("settings: ${r.reason}")
            is Reassembler.Result.Complete -> {
                val plain = secure.open(device, r.message.type, r.message.payload) ?: return
                handle(device, r.message.copy(payload = plain))
            }
        }
    }

    private fun handle(device: BluetoothDevice, message: Reassembler.Message) {
        try {
            when (message.type) {
                MessageType.GET_SETTINGS -> send(device)
                MessageType.SET_APP_RULE -> {
                    val m = SettingsCodec.decodeSetAppRule(message.payload)
                    // Only apps the watch has actually seen can be configured.
                    if (m.bundleId !in policy.seenApps.value) return DiagLog.w("settings: rule for unknown app ignored")
                    policy.setRule(m.bundleId, AppRule(enabled = m.enabled, vibrate = m.vibrate, hideContent = m.hideContent))
                    DiagLog.i("settings: rule app=${m.bundleId} enabled=${m.enabled} vibrate=${m.vibrate} hide=${m.hideContent}")
                }
                MessageType.SET_MODE -> {
                    val mode = modeFromCode(SettingsCodec.decodeSetMode(message.payload)) ?: return DiagLog.w("settings: bad mode")
                    policy.setMode(mode)
                    DiagLog.i("settings: mode=$mode")
                }
                MessageType.SET_APP_ICON -> {
                    val (bundleId, image) = SettingsCodec.decodeSetAppIcon(message.payload)
                    if (bundleId !in policy.seenApps.value && bundleId !in policy.mediaApps.value) {
                        return DiagLog.w("settings: icon for unknown app ignored")
                    }
                    val ok = icons.save(bundleId, image)
                    DiagLog.i("settings: icon app=$bundleId ${image.size}B saved=$ok")
                }
                MessageType.SET_PREFS -> {
                    val p = SettingsCodec.decodeSetPrefs(message.payload)
                    settings.setPopupStyle(if (p.popupStyle == PopupCode.SUBTLE) PopupStyle.SUBTLE else PopupStyle.FULL)
                    BridgeGraph.setHistory(p.history)
                    settings.setEndCallFromWatch(p.endCall)
                    p.calls?.let {
                        settings.setShakeToDecline(it.shakeToDecline)
                        settings.setShakeToEnd(it.shakeToEnd)
                        settings.setBezelToAnswer(it.bezelToAnswer)
                    }
                    DiagLog.i("settings: prefs popup=${p.popupStyle} history=${p.history} endCall=${p.endCall}")
                }
                else -> DiagLog.w("settings: unknown message type ${message.type}")
            }
        } catch (e: IllegalArgumentException) {
            DiagLog.w("settings: malformed message type=${message.type}")
        }
    }

    /** Coalesces bursts (e.g. several new apps during a reconnect replay) into one push. */
    private fun schedulePush() {
        if (subscribers.isEmpty()) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            subscribers.values.toList().forEach(::send)
        }
    }

    private fun send(device: BluetoothDevice) {
        if (BridgeGraph.pairing.state.value.phase != io.wearbridge.watch.ble.PairingPhase.PAIRED) return
        val payload = secure.seal(device, MessageType.SETTINGS, SettingsCodec.encodeSnapshot(snapshot())) ?: return
        messageId = (messageId + 1) and 0xFF
        val chunks = Framing.split(MessageType.SETTINGS, messageId, payload, peripheral.maxPayload(device))
        peripheral.notify(device, io.wearbridge.watch.ble.BridgeUuids.SETTINGS, chunks)
        DiagLog.i("settings: snapshot ${payload.size}B in ${chunks.size} chunks to ${Redact.device(device)}")
    }

    private fun snapshot(): SettingsSnapshot {
        val apps = policy.seenApps.value.entries
            .map { (id, name) -> AppCatalog.resolve(id, name, Category.OTHER) }
            .sortedBy { it.name.lowercase() }
            .map { info ->
                val rule = policy.rule(info.bundleId)
                AppEntry(
                    bundleId = info.bundleId!!,
                    name = info.name,
                    enabled = rule.enabled,
                    vibrate = rule.vibrate,
                    hideContent = rule.hideContent,
                    hiddenByDefault = info.kind == AppKind.FINANCE,
                    color = info.color,
                    logo = info.logo.orEmpty(),
                    hasIcon = icons.has(info.bundleId),
                )
            } + policy.mediaApps.value.filterKeys { it !in policy.seenApps.value }.map { (id, name) ->
                val info = AppCatalog.resolve(id, name, Category.ENTERTAINMENT)
                AppEntry(id, info.name, true, true, null, false, info.color, info.logo.orEmpty(), icons.has(id), mediaOnly = true)
            }
        val prefs = WatchPrefs(
            popupStyle = if (settings.popupStyle.value == PopupStyle.SUBTLE) PopupCode.SUBTLE else PopupCode.STANDARD,
            history = settings.history.value,
            endCall = settings.endCallFromWatch.value,
            calls = CallGestures(settings.shakeToDecline.value, settings.shakeToEnd.value, settings.bezelToAnswer.value),
        )
        return SettingsSnapshot(modeToCode(policy.mode.value), apps, prefs)
    }

    private companion object {
        const val PUSH_DEBOUNCE_MS = 400L

        fun modeToCode(m: FilterMode) = when (m) {
            FilterMode.ALL -> ModeCode.ALL
            FilterMode.IMPORTANT -> ModeCode.IMPORTANT
            FilterMode.MESSAGES_AND_CALLS -> ModeCode.MESSAGES_AND_CALLS
            FilterMode.CALLS_ONLY -> ModeCode.CALLS_ONLY
        }

        fun modeFromCode(c: Int) = when (c) {
            ModeCode.ALL -> FilterMode.ALL
            ModeCode.IMPORTANT -> FilterMode.IMPORTANT
            ModeCode.MESSAGES_AND_CALLS -> FilterMode.MESSAGES_AND_CALLS
            ModeCode.CALLS_ONLY -> FilterMode.CALLS_ONLY
            else -> null
        }
    }
}
