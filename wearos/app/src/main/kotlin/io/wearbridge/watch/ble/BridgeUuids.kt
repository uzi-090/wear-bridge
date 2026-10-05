package io.wearbridge.watch.ble

import java.util.UUID

/** Custom bridge GATT service hosted by the watch. See docs/BLE_PROTOCOL.md. Must match the iOS app. */
object BridgeUuids {
    val SERVICE: UUID = UUID.fromString("9C664C93-19F2-435D-895A-A40751731AE1")
    val PAIRING: UUID = characteristic(0x0001)
    val CONTROL: UUID = characteristic(0x0002)
    /** iPhone → watch contact sync (sealed batches). */
    val CONTACTS: UUID = characteristic(0x0004)
    /** Watch → iPhone events (Find iPhone). */
    val EVENTS: UUID = characteristic(0x0003)
    /** Watch → iPhone status (battery, charging, link). */
    val DEVICE_STATUS: UUID = characteristic(0x0005)
    /** Notification settings channel: iOS writes requests, the watch notifies responses (docs/BLE_PROTOCOL.md). */
    val SETTINGS: UUID = characteristic(0x0006)

    /** App-level authentication: HELLO from the iPhone app, the watch's answers (docs/BLE_PROTOCOL.md). */
    val AUTH: UUID = characteristic(0x000A)

    /** Characteristics the iPhone can subscribe to. */
    val NOTIFYING: Set<UUID> = setOf(SETTINGS, EVENTS, DEVICE_STATUS, AUTH)
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    const val PROTOCOL_VERSION = 1
    const val CAP_ANCS_CLIENT = 1 shl 0
    const val CAP_AMS_CLIENT = 1 shl 1
    const val CAP_SETTINGS = 1 shl 2
    const val CAP_DEVICE_STATUS = 1 shl 3
    const val CAP_FIND = 1 shl 4
    const val CAP_PREFS = 1 shl 5
    /** Bridge messages are sealed with app-level keys (the watch's public key follows the capabilities). */
    const val CAP_SECURE = 1 shl 6
    const val CAP_CONTACTS = 1 shl 7

    private fun characteristic(index: Int): UUID =
        UUID.fromString("9C664C93-19F2-435D-%04X-A40751731AE1".format(index))
}
