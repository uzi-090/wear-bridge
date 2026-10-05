package io.wearbridge.ancs

internal fun Byte.u8(): Int = toInt() and 0xFF

internal fun ByteArray.readU16Le(offset: Int): Int =
    this[offset].u8() or (this[offset + 1].u8() shl 8)

internal fun ByteArray.readU32Le(offset: Int): Long =
    (this[offset].u8().toLong()) or
        (this[offset + 1].u8().toLong() shl 8) or
        (this[offset + 2].u8().toLong() shl 16) or
        (this[offset + 3].u8().toLong() shl 24)

/** Small append-only byte writer, so we don't pull in java.io for a few bytes. */
internal class ByteWriter(capacity: Int = 32) {
    private var buf = ByteArray(capacity)
    private var len = 0

    fun u8(v: Int) = apply {
        ensure(1)
        buf[len++] = v.toByte()
    }

    fun u16Le(v: Int) = apply {
        u8(v and 0xFF)
        u8((v ushr 8) and 0xFF)
    }

    fun u32Le(v: Long) = apply {
        for (shift in 0 until 32 step 8) u8(((v ushr shift) and 0xFF).toInt())
    }

    fun bytes(b: ByteArray) = apply {
        ensure(b.size)
        b.copyInto(buf, len)
        len += b.size
    }

    fun toByteArray(): ByteArray = buf.copyOf(len)

    private fun ensure(extra: Int) {
        if (len + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + extra))
    }
}
