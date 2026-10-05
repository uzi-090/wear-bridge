package io.wearbridge.protocol

/**
 * Bridge protocol v1 framing (docs/BLE_PROTOCOL.md). A message is split into chunks that each fit one
 * ATT packet:
 *
 *   | version u8 | type u8 | messageId u8 | flags u8 | data… |      flags bit0 = more chunks follow
 *
 * GATT writes and notifications on one characteristic arrive in order, so chunks of a message are
 * consecutive. Confidentiality and integrity currently come from the bonded LE Secure Connections link
 * (AES-CCM). App-layer AEAD is Phase 10.
 */
object Framing {
    const val VERSION = 1
    const val HEADER_BYTES = 4
    private const val FLAG_MORE = 0x01

    /** [maxChunkBytes] is the largest single ATT payload (ATT_MTU − 3). Must leave room for the header. */
    fun split(type: Int, messageId: Int, payload: ByteArray, maxChunkBytes: Int): List<ByteArray> {
        require(maxChunkBytes > HEADER_BYTES) { "chunk size too small" }
        val dataPerChunk = maxChunkBytes - HEADER_BYTES
        val count = maxOf(1, (payload.size + dataPerChunk - 1) / dataPerChunk)
        return (0 until count).map { i ->
            val start = i * dataPerChunk
            val end = minOf(payload.size, start + dataPerChunk)
            val more = i < count - 1
            ByteArray(HEADER_BYTES + (end - start)).also { chunk ->
                chunk[0] = VERSION.toByte()
                chunk[1] = type.toByte()
                chunk[2] = messageId.toByte()
                chunk[3] = (if (more) FLAG_MORE else 0).toByte()
                payload.copyInto(chunk, HEADER_BYTES, start, end)
            }
        }
    }
}

/** Reassembles chunks produced by [Framing.split]. Not thread-safe. Use from one serialized context. */
class Reassembler(private val maxMessageBytes: Int = 32 * 1024) {

    data class Message(val type: Int, val messageId: Int, val payload: ByteArray)

    sealed interface Result {
        data object Incomplete : Result
        data class Complete(val message: Message) : Result
        data class Error(val reason: String) : Result
    }

    private var type = -1
    private var messageId = -1
    private var buffer = ByteArray(256)
    private var length = 0

    fun reset() {
        type = -1
        messageId = -1
        length = 0
    }

    fun accept(chunk: ByteArray): Result {
        if (chunk.size < Framing.HEADER_BYTES) return fail("short chunk")
        val version = chunk[0].toInt() and 0xFF
        if (version != Framing.VERSION) return fail("unsupported version $version")
        val chunkType = chunk[1].toInt() and 0xFF
        val chunkId = chunk[2].toInt() and 0xFF
        val more = (chunk[3].toInt() and 0x01) != 0

        if (type >= 0 && (chunkType != type || chunkId != messageId)) {
            // A new message started before the previous one finished: the old one is lost.
            reset()
        }
        type = chunkType
        messageId = chunkId
        val dataLength = chunk.size - Framing.HEADER_BYTES
        if (length + dataLength > maxMessageBytes) return fail("message too large")
        if (length + dataLength > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, length + dataLength))
        chunk.copyInto(buffer, length, Framing.HEADER_BYTES, chunk.size)
        length += dataLength
        if (more) return Result.Incomplete

        val message = Message(type, messageId, buffer.copyOf(length))
        reset()
        return Result.Complete(message)
    }

    private fun fail(reason: String): Result {
        reset()
        return Result.Error(reason)
    }
}
