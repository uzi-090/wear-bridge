package io.wearbridge.protocol

/** Message types on the SETTINGS characteristic. Requests come from iOS (0x0_), responses from the watch (0x8_). */
object MessageType {
    const val GET_SETTINGS = 0x01
    const val SET_APP_RULE = 0x02
    const val SET_MODE = 0x03
    /** str bundleId + image bytes (JPEG/PNG, ≤ 32 KB). The iPhone fetches official App Store icons. */
    const val SET_APP_ICON = 0x04
    const val SETTINGS = 0x81
}

/** Wire codes for the filter mode. They're explicit, so reordering an enum can't change the protocol. */
object ModeCode {
    const val ALL = 0
    const val IMPORTANT = 1
    const val MESSAGES_AND_CALLS = 2
    const val CALLS_ONLY = 3
}

data class AppEntry(
    val bundleId: String,
    val name: String,
    val enabled: Boolean,
    val vibrate: Boolean,
    /** null = use the default for the app's kind. */
    val hideContent: Boolean?,
    /** What [hideContent] = null resolves to (finance apps are hidden by default). */
    val hiddenByDefault: Boolean,
    /** ARGB brand colour for the badge. */
    val color: Long,
    /** Bundled logo slug, or "" for a monogram. */
    val logo: String,
    /** The watch already holds an App Store icon for this app (so the iPhone needn't send it again). */
    val hasIcon: Boolean = false,
)

data class SettingsSnapshot(val mode: Int, val apps: List<AppEntry>)

data class SetAppRule(val bundleId: String, val enabled: Boolean, val vibrate: Boolean, val hideContent: Boolean?)

/**
 * Compact binary encoding: u8 / u32 big-endian, strings as u8 length + UTF-8 (≤ 255 bytes, cut on a
 * character boundary), tri-state as 0 = false, 1 = true, 2 = default.
 */
object SettingsCodec {

    fun encodeSnapshot(s: SettingsSnapshot): ByteArray = Writer().apply {
        u8(s.mode)
        u16(s.apps.size)
        for (a in s.apps) {
            str(a.bundleId)
            str(a.name)
            u8(flags(a.enabled, a.vibrate, a.hiddenByDefault) or (if (a.hasIcon) 8 else 0))
            u8(tri(a.hideContent))
            u32(a.color)
            str(a.logo)
        }
    }.bytes()

    fun decodeSnapshot(bytes: ByteArray): SettingsSnapshot {
        val r = Reader(bytes)
        val mode = r.u8()
        val count = r.u16()
        val apps = List(count) {
            val bundleId = r.str()
            val name = r.str()
            val f = r.u8()
            val hide = untri(r.u8())
            val color = r.u32()
            val logo = r.str()
            AppEntry(bundleId, name, f and 1 != 0, f and 2 != 0, hide, f and 4 != 0, color, logo, f and 8 != 0)
        }
        return SettingsSnapshot(mode, apps)
    }

    fun encodeSetAppRule(m: SetAppRule): ByteArray = Writer().apply {
        str(m.bundleId)
        u8(flags(m.enabled, m.vibrate, false))
        u8(tri(m.hideContent))
    }.bytes()

    fun decodeSetAppRule(bytes: ByteArray): SetAppRule {
        val r = Reader(bytes)
        val bundleId = r.str()
        val f = r.u8()
        return SetAppRule(bundleId, f and 1 != 0, f and 2 != 0, untri(r.u8()))
    }

    fun encodeSetAppIcon(bundleId: String, image: ByteArray): ByteArray = Writer().apply { str(bundleId) }.bytes() + image

    /** Returns bundle ID and image bytes. */
    fun decodeSetAppIcon(bytes: ByteArray): Pair<String, ByteArray> {
        val r = Reader(bytes)
        val bundleId = r.str()
        return bundleId to bytes.copyOfRange(r.position, bytes.size)
    }

    fun encodeSetMode(mode: Int): ByteArray = byteArrayOf(mode.toByte())

    fun decodeSetMode(bytes: ByteArray): Int {
        require(bytes.size == 1) { "bad SET_MODE" }
        return bytes[0].toInt() and 0xFF
    }

    private fun flags(a: Boolean, b: Boolean, c: Boolean) = (if (a) 1 else 0) or (if (b) 2 else 0) or (if (c) 4 else 0)
    private fun tri(v: Boolean?) = when (v) { false -> 0; true -> 1; null -> 2 }
    private fun untri(v: Int): Boolean? = when (v) { 0 -> false; 1 -> true; 2 -> null; else -> throw IllegalArgumentException("bad tri-state $v") }

    private class Writer {
        private val out = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v ushr 8); u8(v) }
        fun u32(v: Long) { for (shift in intArrayOf(24, 16, 8, 0)) u8((v ushr shift).toInt()) }
        fun str(s: String) {
            val b = utf8Prefix(s, 255)
            u8(b.size)
            out.write(b)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class Reader(private val b: ByteArray) {
        private var p = 0
        val position: Int get() = p
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

    /** UTF-8 bytes of [s], cut to at most [max] bytes without splitting a character. */
    internal fun utf8Prefix(s: String, max: Int): ByteArray {
        val full = s.toByteArray(Charsets.UTF_8)
        if (full.size <= max) return full
        var end = max
        while (end > 0 && (full[end].toInt() and 0xC0) == 0x80) end-- // step back over continuation bytes
        return full.copyOf(end)
    }
}
