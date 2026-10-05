package io.wearbridge.watch.ancs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import io.wearbridge.ancs.AncsSession
import io.wearbridge.ancs.AncsUuids
import io.wearbridge.watch.ble.GattOpQueue
import io.wearbridge.watch.ble.writeCharacteristicCompat
import io.wearbridge.watch.ble.enableNotifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Drives [AncsSession] over a real BluetoothGatt. Must be used only from the controller's
 * single-threaded BLE scope.
 */
@SuppressLint("MissingPermission")
class AncsClient(
    private val gatt: BluetoothGatt,
    private val queue: GattOpQueue,
    private val scope: CoroutineScope,
    listener: AncsSession.Listener,
) : AncsSession.Transport {

    enum class SubscribeResult { OK, NOT_FOUND, AUTH_REQUIRED, FAILED }

    val session = AncsSession(
        transport = this,
        listener = listener,
        scheduler = { delayMs, block ->
            val job = scope.launch { delay(delayMs); block() }
            AncsSession.Cancellable { job.cancel() }
        },
    )

    private var controlPoint: BluetoothGattCharacteristic? = null

    /** Subscribes Data Source first, so no attribute response can be missed, then Notification Source. */
    suspend fun subscribe(): SubscribeResult {
        val service = gatt.getService(AncsUuids.SERVICE) ?: return SubscribeResult.NOT_FOUND
        val ns = service.getCharacteristic(AncsUuids.NOTIFICATION_SOURCE)
        val ds = service.getCharacteristic(AncsUuids.DATA_SOURCE)
        val cp = service.getCharacteristic(AncsUuids.CONTROL_POINT)
        if (ns == null || ds == null || cp == null) return SubscribeResult.NOT_FOUND
        controlPoint = cp
        for (characteristic in listOf(ds, ns)) {
            val status = gatt.enableNotifications(queue, characteristic)
            if (status != 0) {
                return if (status in AUTH_STATUSES) SubscribeResult.AUTH_REQUIRED else SubscribeResult.FAILED
            }
        }
        return SubscribeResult.OK
    }

    fun onCharacteristicChanged(uuid: UUID, value: ByteArray) {
        when (uuid) {
            AncsUuids.NOTIFICATION_SOURCE -> session.onNotificationSource(value)
            AncsUuids.DATA_SOURCE -> session.onDataSource(value)
        }
    }

    override fun writeControlPoint(token: Int, payload: ByteArray) {
        val cp = controlPoint
        scope.launch {
            val status = if (cp == null) {
                GattOpQueue.STATUS_NOT_STARTED
            } else {
                queue.run(GattOpQueue.Kind.WRITE_CHARACTERISTIC) {
                    gatt.writeCharacteristicCompat(cp, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                }.status
            }
            session.onControlPointWriteResult(token, status)
        }
    }

    private companion object {
        /** Insufficient authentication / authorization / encryption, plus the stack's generic auth failure. */
        val AUTH_STATUSES = setOf(0x05, 0x08, 0x0F, 0x89)
    }
}
