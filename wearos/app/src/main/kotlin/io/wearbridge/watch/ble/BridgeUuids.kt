package io.wearbridge.watch.ble

import java.util.UUID

/** Custom bridge GATT service hosted by the watch. See docs/BLE_PROTOCOL.md. Must match the iOS app. */
object BridgeUuids {
    val SERVICE: UUID = UUID.fromString("9C664C93-19F2-435D-895A-A40751731AE1")
    val PAIRING: UUID = characteristic(0x0001)
    val CONTROL: UUID = characteristic(0x0002)

    const val PROTOCOL_VERSION = 1
    const val CAP_ANCS_CLIENT = 1 shl 0

    private fun characteristic(index: Int): UUID =
        UUID.fromString("9C664C93-19F2-435D-%04X-A40751731AE1".format(index))
}
