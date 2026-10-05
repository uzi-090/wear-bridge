package io.wearbridge.watch.bridge

import android.bluetooth.BluetoothDevice
import android.os.Build
import io.wearbridge.protocol.BridgeCodec
import io.wearbridge.protocol.BridgeMessageType
import io.wearbridge.protocol.Framing
import io.wearbridge.protocol.Reassembler
import io.wearbridge.protocol.WatchStatus
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.ble.BridgePeripheral
import io.wearbridge.watch.ble.BridgeUuids
import io.wearbridge.watch.ble.LinkState
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.diagnostics.Redact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The iPhone app's command and status channels (docs/BLE_PROTOCOL.md):
 * - CONTROL (iOS → watch): Find watch, ping.
 * - EVENTS (watch → iOS): Find iPhone, "found it" on the watch.
 * - DEVICE_STATUS (watch → iOS): battery, charging, link state, pushed on change (no polling).
 * These need the iPhone app running or relaunchable. Notifications never depend on them.
 * Confined to the BLE scope.
 */
class DeviceChannel(
    private val peripheral: BridgePeripheral,
    private val scope: CoroutineScope,
    private val appVersion: String,
) {
    private val reassemblers = HashMap<String, Reassembler>()
    private val statusSubscribers = HashMap<String, BluetoothDevice>()
    private val eventSubscribers = HashMap<String, BluetoothDevice>()
    private var messageId = 0
    private var pushJob: Job? = null

    val canReachPhoneApp: Boolean get() = eventSubscribers.isNotEmpty()

    init {
        scope.launch {
            combine(BridgeGraph.watchBattery, BridgeGraph.link, BridgeGraph.media, BridgeGraph.repository.items) { b, l, m, items ->
                status(b, l.state == LinkState.READY, m != null, items.size)
            }.distinctUntilChanged().collect { schedulePush() }
        }
    }

    fun onSubscription(device: BluetoothDevice, characteristic: UUID, subscribed: Boolean) {
        val map = when (characteristic) {
            BridgeUuids.DEVICE_STATUS -> statusSubscribers
            BridgeUuids.EVENTS -> eventSubscribers
            else -> return
        }
        DiagLog.i("bridge ${if (characteristic == BridgeUuids.EVENTS) "events" else "status"} subscribed=$subscribed ${Redact.device(device)}")
        if (subscribed) map[device.address] = device else map.remove(device.address)
        if (subscribed && characteristic == BridgeUuids.DEVICE_STATUS) sendStatus(device)
        BridgeGraph.phoneAppConnectedFlow.value = eventSubscribers.isNotEmpty()
    }

    fun onDisconnected(device: BluetoothDevice) {
        reassemblers.remove(device.address)
        statusSubscribers.remove(device.address)
        eventSubscribers.remove(device.address)
        BridgeGraph.phoneAppConnectedFlow.value = eventSubscribers.isNotEmpty()
    }

    fun onControlChunk(device: BluetoothDevice, chunk: ByteArray) {
        val r = reassemblers.getOrPut(device.address) { Reassembler(maxMessageBytes = 1024) }
        when (val result = r.accept(chunk)) {
            is Reassembler.Result.Incomplete -> Unit
            is Reassembler.Result.Error -> DiagLog.w("control: ${result.reason}")
            is Reassembler.Result.Complete -> handle(device, result.message)
        }
    }

    /** Sends an event to every subscribed iPhone app. Returns false if none is listening. */
    fun sendEvent(type: Int, payload: ByteArray = ByteArray(0)): Boolean {
        if (eventSubscribers.isEmpty()) return false
        eventSubscribers.values.forEach { d -> send(d, BridgeUuids.EVENTS, type, payload) }
        return true
    }

    private fun handle(device: BluetoothDevice, m: Reassembler.Message) {
        try {
            when (m.type) {
                BridgeMessageType.FIND_WATCH -> {
                    val on = BridgeCodec.decodeSwitch(m.payload)
                    DiagLog.i("control: find watch on=$on")
                    if (on) BridgeGraph.findWatch.start() else BridgeGraph.findWatch.stop(fromUser = false)
                }
                BridgeMessageType.FIND_PHONE_STOPPED -> {
                    DiagLog.i("control: found on iPhone")
                    BridgeGraph.findPhoneFlow.value = io.wearbridge.watch.find.FindPhoneState.FOUND
                }
                BridgeMessageType.PING -> sendStatus(device)
                else -> DiagLog.w("control: unknown message type ${m.type}")
            }
        } catch (e: IllegalArgumentException) {
            DiagLog.w("control: malformed message type=${m.type}")
        }
    }

    private fun schedulePush() {
        if (statusSubscribers.isEmpty()) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(PUSH_DEBOUNCE_MS)
            statusSubscribers.values.toList().forEach(::sendStatus)
        }
    }

    private fun sendStatus(device: BluetoothDevice) {
        val s = status(BridgeGraph.watchBattery.value, BridgeGraph.link.value.state == LinkState.READY,
            BridgeGraph.media.value != null, BridgeGraph.repository.items.value.size)
        send(device, BridgeUuids.DEVICE_STATUS, BridgeMessageType.STATUS, BridgeCodec.encodeStatus(s))
    }

    private fun send(device: BluetoothDevice, characteristic: UUID, type: Int, payload: ByteArray) {
        messageId = (messageId + 1) and 0xFF
        peripheral.notify(device, characteristic, Framing.split(type, messageId, payload, peripheral.maxPayload(device)))
    }

    private fun status(b: WatchBattery, ready: Boolean, media: Boolean, inbox: Int) = WatchStatus(
        battery = b.percent,
        charging = b.charging,
        notificationsReady = ready,
        mediaReady = media,
        inboxCount = inbox,
        model = Build.MODEL,
        osVersion = Build.VERSION.RELEASE,
        appVersion = appVersion,
    )

    private companion object {
        /** Battery broadcasts can come in bursts; one push per half second is plenty. */
        const val PUSH_DEBOUNCE_MS = 500L
    }
}

/** The watch's own battery, from the sticky ACTION_BATTERY_CHANGED broadcast. */
data class WatchBattery(val percent: Int? = null, val charging: Boolean = false)
