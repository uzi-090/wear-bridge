package io.wearbridge.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import io.wearbridge.ancs.AncsSession
import io.wearbridge.ancs.AncsUuids
import io.wearbridge.watch.ancs.AncsClient
import io.wearbridge.watch.media.AmsClient
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * One GATT client session towards the iPhone, on the LE link the iPhone opened. It owns the
 * BluetoothGatt, its [GattOpQueue] and the client callbacks. Callbacks go to [Listener] on Binder
 * threads, and the owner hands them to its own serialized scope.
 */
@SuppressLint("MissingPermission") // ConnectionManager checks permissions before creating sessions
class GattSession(
    private val context: Context,
    val device: BluetoothDevice,
    private val listener: Listener,
) {
    interface Listener {
        fun onCharacteristicChanged(session: GattSession, uuid: UUID, value: ByteArray)
        fun onDisconnected(session: GattSession, status: Int)
        fun onServiceChanged(session: GattSession)
        fun onMtu(session: GattSession, mtu: Int)
    }

    /** [services] are the iPhone's GATT service UUIDs (public Bluetooth SIG / Apple service IDs, not personal data). */
    data class Discovery(val ok: Boolean, val status: Int, val hasAncs: Boolean, val serviceCount: Int, val services: List<UUID> = emptyList())

    val queue = GattOpQueue()
    private var gatt: BluetoothGatt? = null

    /** Connects the client and requests a larger MTU. Returns the connect status (0 = success). */
    suspend fun connect(): Int {
        val r = queue.run(GattOpQueue.Kind.CONNECT, timeoutMs = 15_000) {
            // Android reuses the existing ACL for a client on a link the iPhone opened.
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            gatt != null
        }
        if (!r.ok) return r.status
        // A larger MTU means fewer Data Source fragments. iOS usually grants 185. A failure is harmless.
        queue.run(GattOpQueue.Kind.MTU, timeoutMs = 5_000) { gatt?.requestMtu(185) == true }
        return 0
    }

    suspend fun discover(): Discovery {
        val g = gatt ?: return Discovery(false, GattOpQueue.STATUS_NOT_STARTED, false, 0)
        val r = queue.run(GattOpQueue.Kind.DISCOVER, timeoutMs = 20_000) { g.discoverServices() }
        return Discovery(r.ok, r.status, g.getService(AncsUuids.SERVICE) != null, g.services.size, g.services.map { it.uuid })
    }

    fun ancsClient(scope: CoroutineScope, listener: AncsSession.Listener): AncsClient? =
        gatt?.let { AncsClient(it, queue, scope, listener) }

    fun amsClient(listener: AmsClient.Listener): AmsClient? = gatt?.let { AmsClient(it, queue, listener) }

    fun phoneServices(listener: PhoneServicesClient.Listener): PhoneServicesClient? =
        gatt?.let { PhoneServicesClient(it, queue, listener) }

    /** Asks for a connection interval (see [LinkPower]). Not a GATT operation, so it isn't queued. */
    fun requestPriority(priority: Int): Boolean =
        runCatching { gatt?.requestConnectionPriority(priority) == true }.getOrDefault(false)

    fun close() {
        queue.failAll()
        gatt?.close()
        gatt = null
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> queue.complete(GattOpQueue.Kind.CONNECT, status)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    queue.failAll()
                    listener.onDisconnected(this@GattSession, status)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) listener.onMtu(this@GattSession, mtu)
            queue.complete(GattOpQueue.Kind.MTU, status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = queue.complete(GattOpQueue.Kind.DISCOVER, status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            queue.complete(GattOpQueue.Kind.WRITE_DESCRIPTOR, status)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            queue.complete(GattOpQueue.Kind.WRITE_CHARACTERISTIC, status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            listener.onCharacteristicChanged(this@GattSession, c.uuid, value.copyOf())
        }

        @Deprecated("Pre-API 33 delivery path")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                listener.onCharacteristicChanged(this@GattSession, c.uuid, c.value.copyOf())
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            queue.complete(GattOpQueue.Kind.READ_CHARACTERISTIC, status, value.copyOf())

        @Deprecated("Pre-API 33 delivery path")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                queue.complete(GattOpQueue.Kind.READ_CHARACTERISTIC, status, c.value?.copyOf())
            }
        }

        override fun onServiceChanged(g: BluetoothGatt) = listener.onServiceChanged(this@GattSession)
    }
}
