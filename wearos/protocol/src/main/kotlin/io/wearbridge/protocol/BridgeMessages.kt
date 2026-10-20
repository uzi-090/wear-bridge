package io.wearbridge.protocol

/**
 * Messages on the CONTROL (iOS → watch), EVENTS (watch → iOS) and DEVICE_STATUS (watch → iOS)
 * characteristics. Same chunk framing as SETTINGS ([Framing]). See docs/BLE_PROTOCOL.md.
 */
object BridgeMessageType {
    // CONTROL, iOS → watch
    /** u8: 1 = start ringing the watch, 0 = stop. */
    const val FIND_WATCH = 0x10
    /** empty: the user stopped the ringing on the iPhone ("Found it" there). */
    const val FIND_PHONE_STOPPED = 0x11
    /** empty. The watch answers with a STATUS push. */
    const val PING = 0x12
    /** u8 battery (255 = unknown), u8 flags (bit0 charging, bit1 full): the iPhone's power, from its app. */
    const val PHONE_STATUS = 0x13
    const val DEVICE_LIFECYCLE = 0x14

    // EVENTS, watch → iOS
    /** u8: 1 = the user asked the watch to ring the iPhone, 0 = stop. */
    const val FIND_PHONE = 0x90
    /** empty: the user dismissed "Find watch" on the watch. */
    const val FIND_WATCH_STOPPED = 0x91
    /** str phone, str name: "Call from iPhone". The iPhone app offers the call; the user confirms there. */
    const val CALL_REQUEST = 0x92
    const val DEVICE_LIFECYCLE_EVENT = 0x93

    // DEVICE_STATUS, watch → iOS
    const val STATUS = 0xA0
}

/**
 * The watch's state for the iPhone app's home screen. [battery] is 0–100, or null if unknown.
 * Strings are the watch model and OS version (no identifiers).
 */
data class WatchStatus(
    val battery: Int?,
    val charging: Boolean,
    val notificationsReady: Boolean,
    val mediaReady: Boolean,
    val inboxCount: Int,
    val model: String,
    val osVersion: String,
    val appVersion: String,
    /** Last contacts sync the watch committed (0 = none). Trailer added in v1.2. */
    val contactsGeneration: Long = 0,
    val contactCount: Int = 0,
)

object BridgeCodec {
    data class Lifecycle(val forget: Boolean, val acknowledgement: Boolean)
    fun encodeLifecycle(value: Lifecycle) = byteArrayOf(if (value.forget) 2 else 1, if (value.acknowledgement) 1 else 0)
    fun decodeLifecycle(bytes: ByteArray): Lifecycle {
        require(bytes.size == 2 && bytes[0].toInt() in 1..2 && bytes[1].toInt() in 0..1) { "bad DEVICE_LIFECYCLE" }
        return Lifecycle(bytes[0].toInt() == 2, bytes[1].toInt() == 1)
    }

    fun encodeStatus(s: WatchStatus): ByteArray = Bytes.Writer().apply {
        u8(s.battery?.coerceIn(0, 100) ?: UNKNOWN_BATTERY)
        u8((if (s.charging) 1 else 0) or (if (s.notificationsReady) 2 else 0) or (if (s.mediaReady) 4 else 0))
        u16(s.inboxCount.coerceIn(0, 0xFFFF))
        str(s.model)
        str(s.osVersion)
        str(s.appVersion)
        u32(s.contactsGeneration)
        u16(s.contactCount.coerceIn(0, 0xFFFF))
    }.bytes()

    fun decodeStatus(bytes: ByteArray): WatchStatus {
        val r = Bytes.Reader(bytes)
        val battery = r.u8().takeIf { it <= 100 }
        val flags = r.u8()
        return WatchStatus(
            battery = battery,
            charging = flags and 1 != 0,
            notificationsReady = flags and 2 != 0,
            mediaReady = flags and 4 != 0,
            inboxCount = r.u16(),
            model = r.str(),
            osVersion = r.str(),
            appVersion = r.str(),
        ).let { if (r.remaining >= 6) it.copy(contactsGeneration = r.u32(), contactCount = r.u16()) else it }
    }

    /** The iPhone's power as its app sees it (iOS's Bluetooth battery service has the level only). */
    data class PhoneStatus(val battery: Int?, val charging: Boolean, val full: Boolean)

    fun encodePhoneStatus(s: PhoneStatus): ByteArray =
        byteArrayOf((s.battery?.coerceIn(0, 100) ?: UNKNOWN_BATTERY).toByte(), ((if (s.charging) 1 else 0) or (if (s.full) 2 else 0)).toByte())

    fun decodePhoneStatus(bytes: ByteArray): PhoneStatus {
        require(bytes.size >= 2) { "bad PHONE_STATUS" }
        val b = bytes[0].toInt() and 0xFF
        val f = bytes[1].toInt() and 0xFF
        return PhoneStatus(b.takeIf { it <= 100 }, charging = f and 1 != 0, full = f and 2 != 0)
    }

    fun encodeCallRequest(phone: String, name: String): ByteArray = Bytes.Writer().apply { str(phone); str(name) }.bytes()

    fun decodeCallRequest(bytes: ByteArray): Pair<String, String> {
        val r = Bytes.Reader(bytes)
        return r.str() to r.str()
    }

    fun encodeSwitch(on: Boolean): ByteArray = byteArrayOf(if (on) 1 else 0)

    fun decodeSwitch(bytes: ByteArray): Boolean {
        require(bytes.size == 1) { "bad switch" }
        return bytes[0].toInt() != 0
    }

    private const val UNKNOWN_BATTERY = 0xFF
}

/** Shared binary helpers: u8 / u16 big-endian, strings as u8 length + UTF-8 (≤ 255 bytes). */
internal object Bytes {
    class Writer {
        private val out = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v ushr 8); u8(v) }
        fun u32(v: Long) { for (shift in intArrayOf(24, 16, 8, 0)) u8((v ushr shift).toInt()) }
        fun str(s: String) {
            val b = SettingsCodec.utf8Prefix(s, 255)
            u8(b.size)
            out.write(b)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    class Reader(private val b: ByteArray) {
        private var p = 0
        val remaining: Int get() = b.size - p
        fun u8(): Int {
            require(p < b.size) { "truncated" }
            return b[p++].toInt() and 0xFF
        }
        fun u16() = (u8() shl 8) or u8()
        fun u32(): Long = (0 until 4).fold(0L) { acc, _ -> (acc shl 8) or u8().toLong() }
        fun str(): String {
            val n = u8()
            require(p + n <= b.size) { "truncated string" }
            return String(b, p, n, Charsets.UTF_8).also { p += n }
        }
    }
}
