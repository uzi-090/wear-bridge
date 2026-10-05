package io.wearbridge.watch.ble

import java.util.UUID

/** Custom bridge GATT service hosted by the watch. See docs/BLE_PROTOCOL.md. Must match the iOS app. */
object BridgeUuids {
    val SERVICE: UUID = UUID.fromString("9C664C93-19F2-435D-895A-A40751731AE1")
    val PAIRING: UUID = characteristic(0x0001)
    val CONTROL: UUID = characteristic(0x0002)
    /** Watch → iPhone events (Find iPhone). */
    val EVENTS: UUID = characteristic(0x0003)
    /** Watch → iPhone status (battery, charging, link). */
    val DEVICE_STATUS: UUID = characteristic(0x0005)
    /** Notification settings channel: iOS writes requests, the watch notifies responses (docs/BLE_PROTOCOL.md). */
    val SETTINGS: UUID = characteristic(0x0006)

    /** Characteristics the iPhone can subscribe to. */
    val NOTIFYING: Set<UUID> = setOf(SETTINGS, EVENTS, DEVICE_STATUS)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val PROTOCOL_VERSION = 1
    const val CAP_ANCS_CLIENT = 1 shl 0
    const val CAP_AMS_CLIENT = 1 shl 1
    const val CAP_SETTINGS = 1 shl 2
    const val CAP_DEVICE_STATUS = 1 shl 3
    const val CAP_FIND = 1 shl 4
    const val CAP_PREFS = 1 shl 5

    private fun characteristic(index: Int): UUID =
        UUID.fromString("9C664C93-19F2-435D-%04X-A40751731AE1".format(index))
}
