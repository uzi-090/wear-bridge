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
