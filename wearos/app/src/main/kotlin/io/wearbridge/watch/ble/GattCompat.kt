package io.wearbridge.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothStatusCodes
import android.os.Build

// API 33 replaced the value-on-object GATT writes. Wear OS 3 (API 30) still needs the old ones.

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun BluetoothGatt.writeDescriptorCompat(descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        descriptor.value = value
        writeDescriptor(descriptor)
    }

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
fun BluetoothGatt.writeCharacteristicCompat(
    characteristic: BluetoothGattCharacteristic,
    value: ByteArray,
    writeType: Int,
): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        writeCharacteristic(characteristic, value, writeType) == BluetoothStatusCodes.SUCCESS
    } else {
        characteristic.writeType = writeType
        characteristic.value = value
        writeCharacteristic(characteristic)
    }

/** Enables notifications (local + CCCD write) through [queue]. Returns the GATT status (0 = success). */
@SuppressLint("MissingPermission")
suspend fun BluetoothGatt.enableNotifications(queue: GattOpQueue, characteristic: BluetoothGattCharacteristic): Int {
    if (!setCharacteristicNotification(characteristic, true)) return GattOpQueue.STATUS_NOT_STARTED
    val cccd = characteristic.getDescriptor(CCCD_UUID) ?: return GattOpQueue.STATUS_NOT_STARTED
    return queue.run(GattOpQueue.Kind.WRITE_DESCRIPTOR, target = "${characteristic.uuid}/${cccd.uuid}") {
        writeDescriptorCompat(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
    }.status
}

/** Reads a characteristic through [queue]. Null on failure. */
@SuppressLint("MissingPermission")
suspend fun BluetoothGatt.readValue(queue: GattOpQueue, characteristic: BluetoothGattCharacteristic): ByteArray? {
    val r = queue.run(GattOpQueue.Kind.READ_CHARACTERISTIC, target = characteristic.uuid.toString()) { readCharacteristic(characteristic) }
    return if (r.ok) r.value else null
}

private val CCCD_UUID: java.util.UUID = java.util.UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
