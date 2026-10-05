package io.wearbridge.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import io.wearbridge.ancs.AncsUuids
import io.wearbridge.watch.diagnostics.DiagLog
import io.wearbridge.watch.diagnostics.Redact

/**
 * The watch's LE peripheral role: advertising (only while disconnected) and the bridge GATT server.
 *
 * Every bridge characteristic requires an encrypted link, so the iPhone's first access triggers
 * LE Secure Connections pairing. Callbacks arrive on Binder threads and are forwarded straight to
 * [Listener], which must hand them off without blocking.
 *
 * Permissions (BLUETOOTH_CONNECT / BLUETOOTH_ADVERTISE) are checked by [ConnectionManager] before use.
 */
@SuppressLint("MissingPermission")
class BridgePeripheral(
    private val context: Context,
    private val listener: Listener,
    /** The watch's app-level public key (X9.63), published in the PAIRING value. */
    private val identityPublicKey: ByteArray,
) {

    interface Listener {
        fun onCentralConnected(device: BluetoothDevice)
        fun onCentralDisconnected(device: BluetoothDevice)
        fun onAdvertisingFailed(errorCode: Int, unsupported: Boolean)
        fun onControlWrite(device: BluetoothDevice, value: ByteArray)
        fun onSettingsChunk(device: BluetoothDevice, value: ByteArray)
        fun onAuthChunk(device: BluetoothDevice, value: ByteArray)
        fun onContactsChunk(device: BluetoothDevice, value: ByteArray)
        /** The iPhone enabled or disabled notifications on SETTINGS, EVENTS or DEVICE_STATUS. */
        fun onSubscription(device: BluetoothDevice, characteristic: java.util.UUID, subscribed: Boolean)
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)

    private var server: BluetoothGattServer? = null

    @Volatile private var serviceReady = false

    /** ATT MTU per connected central (default 23 until the iPhone negotiates more). */
    private val mtus = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private class Outgoing(val device: BluetoothDevice, val characteristic: java.util.UUID, val value: ByteArray)
    private val notifyQueue = ArrayDeque<Outgoing>()
    private var notifyInFlight = false

    /** The ATT MTU is per link, so the MTU our GATT client negotiated with the iPhone applies here too. */
    fun recordMtu(device: BluetoothDevice, mtu: Int) {
        mtus.merge(device.address, mtu, ::maxOf)
    }

    /** Largest notification payload for [device]: ATT_MTU − 3. */
    fun maxPayload(device: BluetoothDevice): Int = (mtus[device.address] ?: 23) - 3

    /**
     * Queues notifications on a bridge characteristic (SETTINGS, EVENTS, DEVICE_STATUS). Android allows
     * one outstanding notification per server, so the next one is sent only after onNotificationSent.
     * Chunks of one message stay consecutive, because the queue is FIFO across characteristics.
     */
    fun notify(device: BluetoothDevice, characteristic: java.util.UUID, chunks: List<ByteArray>) {
        synchronized(notifyQueue) {
            chunks.forEach { notifyQueue.addLast(Outgoing(device, characteristic, it)) }
            if (!notifyInFlight) sendNextNotification()
        }
    }

    private fun sendNextNotification() {
        val next = notifyQueue.removeFirstOrNull() ?: run { notifyInFlight = false; return }
        val device = next.device
        val value = next.value
        val characteristic = server?.getService(BridgeUuids.SERVICE)?.getCharacteristic(next.characteristic)
            ?: run { notifyQueue.clear(); notifyInFlight = false; return }
        notifyInFlight = true
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            server?.notifyCharacteristicChanged(device, characteristic, false, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            server?.notifyCharacteristicChanged(device, characteristic, false) == true
        }
        if (!started) {
            DiagLog.w("bridge notify refused, dropping ${notifyQueue.size + 1} chunks")
            notifyQueue.clear()
            notifyInFlight = false
        }
    }
    @Volatile private var advertiseWanted = false
    @Volatile private var advertising = false

    val supportsAdvertising: Boolean
        get() = manager.adapter?.let { it.isEnabled && it.bluetoothLeAdvertiser != null && it.isMultipleAdvertisementSupported } == true

    /** Opens the GATT server and registers the bridge service. Idempotent. */
    @Synchronized
    fun open(): Boolean {
        if (server != null) return true
        val s = manager.openGattServer(context, serverCallback) ?: return false.also { DiagLog.w("openGattServer returned null") }
        server = s
        serviceReady = false
        if (!s.addService(buildService())) {
            DiagLog.w("addService refused")
            return false
        }
        return true
    }

    @Synchronized
    fun startAdvertising() {
        advertiseWanted = true
        if (serviceReady) startAdvertisingInternal()
        // Otherwise it starts from onServiceAdded, so iOS never sees a half-built GATT database.
    }

    @Synchronized
    fun stopAdvertising() {
        advertiseWanted = false
        if (!advertising) return
        advertising = false
        manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        DiagLog.i("advertising stopped")
    }

    fun disconnect(device: BluetoothDevice) {
        server?.cancelConnection(device)
    }

    @Synchronized
    fun close() {
        stopAdvertising()
        server?.close()
        server = null
        serviceReady = false
    }

    private fun startAdvertisingInternal() {
        if (advertising) return
        val advertiser = manager.adapter?.bluetoothLeAdvertiser
        if (advertiser == null || manager.adapter?.isMultipleAdvertisementSupported != true) {
            listener.onAdvertisingFailed(AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED, unsupported = true)
            return
        }
        val settings = AdvertiseSettings.Builder()
            // Low power (~1 s interval): reconnection takes a little longer but saves battery.
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        // Legacy advertising is limited to 31 bytes, so the 128-bit UUIDs go in separate packets:
        // the ANCS solicitation goes in the advertisement (for iOS's system-level consumer), and the
        // bridge UUID goes in the scan response (iOS scans actively in the foreground).
        val (data, scanResponse) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceSolicitationUuid(ParcelUuid(AncsUuids.SERVICE))
                .build() to
                AdvertiseData.Builder()
                    .setIncludeDeviceName(false)
                    .addServiceUuid(ParcelUuid(BridgeUuids.SERVICE))
                    .build()
        } else {
            // Wear OS 3 (API 30) can't solicit, so pairing has to start from the iOS app.
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(BridgeUuids.SERVICE))
                .build() to null
        }
        advertising = true
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            DiagLog.i("advertising started solicitation=${Build.VERSION.SDK_INT >= Build.VERSION_CODES.S}")
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) return
            advertising = false
            DiagLog.w("advertising failed code=$errorCode")
            listener.onAdvertisingFailed(errorCode, unsupported = errorCode == ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != BridgeUuids.SERVICE) return
            DiagLog.i("bridge service added status=$status")
            synchronized(this@BridgePeripheral) {
                serviceReady = status == BluetoothGatt.GATT_SUCCESS
                if (serviceReady && advertiseWanted) startAdvertisingInternal()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            DiagLog.i("server link ${Redact.device(device)} state=$newState status=$status")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> listener.onCentralConnected(device)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    mtus.remove(device.address)
                    synchronized(notifyQueue) {
                        notifyQueue.removeAll { it.device.address == device.address }
                        if (notifyQueue.isEmpty()) notifyInFlight = false
                    }
                    listener.onCentralDisconnected(device)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtus[device.address] = mtu
            DiagLog.i("server mtu=$mtu ${Redact.device(device)}")
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(notifyQueue) { sendNextNotification() }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: android.bluetooth.BluetoothGattDescriptor,
        ) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf(0, 0))
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: android.bluetooth.BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ok = descriptor.uuid == BridgeUuids.CCCD && descriptor.characteristic.uuid in BridgeUuids.NOTIFYING &&
                value != null && value.size == 2
            if (responseNeeded) {
                server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            }
            if (ok) listener.onSubscription(device, descriptor.characteristic.uuid, subscribed = value!![0].toInt() and 0x01 != 0)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val value = when (characteristic.uuid) {
                BridgeUuids.PAIRING -> pairingValue()
                else -> null
            }
            if (value == null || offset > value.size) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value.copyOfRange(offset, value.size))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ok = characteristic.uuid in WRITABLE &&
                !preparedWrite && offset == 0 && value != null
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset, null,
                )
            }
            if (!ok) return
            when (characteristic.uuid) {
                BridgeUuids.CONTROL -> listener.onControlWrite(device, value!!.copyOf())
                BridgeUuids.SETTINGS -> listener.onSettingsChunk(device, value!!.copyOf())
                BridgeUuids.AUTH -> listener.onAuthChunk(device, value!!.copyOf())
                BridgeUuids.CONTACTS -> listener.onContactsChunk(device, value!!.copyOf())
            }
        }
    }

    private fun buildService(): BluetoothGattService =
        BluetoothGattService(BridgeUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    BridgeUuids.PAIRING,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
                ),
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    BridgeUuids.CONTROL,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
                ),
            )
            addCharacteristic(
                notifying(BridgeUuids.SETTINGS, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED),
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    BridgeUuids.CONTACTS,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
                ),
            )
            addCharacteristic(notifying(BridgeUuids.EVENTS, 0, 0))
            addCharacteristic(notifying(BridgeUuids.DEVICE_STATUS, 0, 0))
            addCharacteristic(
                notifying(BridgeUuids.AUTH, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED),
            )
        }

    /** A characteristic with NOTIFY and an encrypted-only CCCD, plus any [extraProperties]. */
    private fun notifying(uuid: java.util.UUID, extraProperties: Int, permissions: Int) =
        BluetoothGattCharacteristic(uuid, extraProperties or BluetoothGattCharacteristic.PROPERTY_NOTIFY, permissions).apply {
            addDescriptor(
                android.bluetooth.BluetoothGattDescriptor(
                    BridgeUuids.CCCD,
                    android.bluetooth.BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                        android.bluetooth.BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
                ),
            )
        }

    private fun pairingValue(): ByteArray {
        val caps = BridgeUuids.CAP_ANCS_CLIENT or BridgeUuids.CAP_AMS_CLIENT or BridgeUuids.CAP_SETTINGS or
            BridgeUuids.CAP_DEVICE_STATUS or BridgeUuids.CAP_FIND or BridgeUuids.CAP_PREFS or BridgeUuids.CAP_SECURE or
            BridgeUuids.CAP_CONTACTS
        return byteArrayOf(
            BridgeUuids.PROTOCOL_VERSION.toByte(),
            caps.toByte(), (caps ushr 8).toByte(), (caps ushr 16).toByte(), (caps ushr 24).toByte(),
        ) + identityPublicKey // 70 bytes in total: iOS reads it with a long read (offsets are handled above)
    }

    private companion object {
        val WRITABLE = setOf(BridgeUuids.CONTROL, BridgeUuids.SETTINGS, BridgeUuids.AUTH, BridgeUuids.CONTACTS)
    }
}
