package io.wearbridge.watch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import java.util.UUID

/**
 * Standard Bluetooth SIG services the iPhone hosts for bonded accessories: Battery (0x180F) and Device
 * Information (0x180A). Read straight from iOS, so no iPhone app is needed. Each one is optional.
 */
@SuppressLint("MissingPermission")
class PhoneServicesClient(
    private val gatt: BluetoothGatt,
    private val queue: GattOpQueue,
    private val listener: Listener,
) {
    interface Listener {
        fun onPhoneBattery(percent: Int)
        fun onPhoneModel(model: String)
    }

    data class Found(val battery: Boolean, val deviceInfo: Boolean)

    suspend fun start(): Found {
        val model = gatt.getService(DEVICE_INFO)?.getCharacteristic(MODEL_NUMBER)
        model?.let { c -> gatt.readValue(queue, c)?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotEmpty() }?.let(listener::onPhoneModel) }

        val level = gatt.getService(BATTERY)?.getCharacteristic(BATTERY_LEVEL)
        if (level != null) {
            gatt.readValue(queue, level)?.let(::onBatteryValue)
            gatt.enableNotifications(queue, level) // iOS notifies on change. Harmless if refused.
        }
        return Found(battery = level != null, deviceInfo = model != null)
    }

    /** Returns true if this client consumed the notification. */
    fun onCharacteristicChanged(uuid: UUID, value: ByteArray): Boolean {
        if (uuid != BATTERY_LEVEL) return false
        onBatteryValue(value)
        return true
    }

    private fun onBatteryValue(value: ByteArray) {
        val percent = value.firstOrNull()?.toInt()?.and(0xFF) ?: return
        if (percent in 0..100) listener.onPhoneBattery(percent)
    }

    companion object {
        val BATTERY: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
        val DEVICE_INFO: UUID = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
        val MODEL_NUMBER: UUID = UUID.fromString("00002A24-0000-1000-8000-00805F9B34FB")

        /** Marketing names for model identifiers. Unknown identifiers fall back to "iPhone". */
        private val MODELS = mapOf(
            "iPhone15,4" to "iPhone 15", "iPhone15,5" to "iPhone 15 Plus",
            "iPhone16,1" to "iPhone 15 Pro", "iPhone16,2" to "iPhone 15 Pro Max",
            "iPhone17,1" to "iPhone 16 Pro", "iPhone17,2" to "iPhone 16 Pro Max",
            "iPhone17,3" to "iPhone 16", "iPhone17,4" to "iPhone 16 Plus", "iPhone17,5" to "iPhone 16e",
            "iPhone18,2" to "iPhone 17 Pro Max", "iPhone19,2" to "iPhone 18 Pro",
        )

        fun marketingName(model: String?): String = model?.let { MODELS[it] } ?: "iPhone"
    }
}
