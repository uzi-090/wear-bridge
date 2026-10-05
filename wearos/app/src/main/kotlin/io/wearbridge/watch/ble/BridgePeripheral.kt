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
class BridgePeripheral(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onCentralConnected(device: BluetoothDevice)
        fun onCentralDisconnected(device: BluetoothDevice)
        fun onAdvertisingFailed(errorCode: Int, unsupported: Boolean)
        fun onControlWrite(device: BluetoothDevice, value: ByteArray)
    }

    private val manager = context.getSystemService(BluetoothManager::class.java)

    private var server: BluetoothGattServer? = null

    @Volatile private var serviceReady = false
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
                BluetoothProfile.STATE_DISCONNECTED -> listener.onCentralDisconnected(device)
            }
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
            val ok = characteristic.uuid == BridgeUuids.CONTROL && !preparedWrite && offset == 0 && value != null
            if (responseNeeded) {
                server?.sendResponse(
                    device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED,
                    offset, null,
                )
            }
            if (ok) listener.onControlWrite(device, value!!.copyOf())
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
        }

    private fun pairingValue(): ByteArray {
        val caps = BridgeUuids.CAP_ANCS_CLIENT
        return byteArrayOf(
            BridgeUuids.PROTOCOL_VERSION.toByte(),
            caps.toByte(), (caps ushr 8).toByte(), (caps ushr 16).toByte(), (caps ushr 24).toByte(),
        )
    }
}
