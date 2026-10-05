package io.wearbridge.ancs

/**
 * Rebuilds Data Source responses that iOS splits across several GATT notifications.
 *
 * ANCS responses have no total-length field. A response is complete once every requested attribute
 * has been parsed. Only one request may be outstanding at a time, which [AncsSession] enforces.
 */
class DataSourceReassembler {

    sealed interface Result {
        data object Incomplete : Result
        data class NotificationAttributes(val uid: Long, val attributes: Map<Int, ByteArray>) : Result
        data class AppAttributes(val appIdentifier: String, val attributes: Map<Int, ByteArray>) : Result

        /** [reason] never contains notification content. */
        data class Error(val reason: String) : Result
    }

    private sealed interface Expectation {
        val ids: Set<Int>

        class Notification(val uid: Long, override val ids: Set<Int>) : Expectation
        class App(val appIdentifier: String, override val ids: Set<Int>) : Expectation
    }

    private var expectation: Expectation? = null
    private var buf = ByteArray(512)
    private var len = 0

    // Reused scratch space for attribute offsets so the receive path doesn't allocate on every fragment.
    private var tupleIds = IntArray(8)
    private var tupleStarts = IntArray(8)
    private var tupleLens = IntArray(8)

    val isExpecting: Boolean get() = expectation != null

    fun expectNotification(uid: Long, attributeIds: Set<Int>) {
        expectation = Expectation.Notification(uid, attributeIds)
        len = 0
    }

    fun expectApp(appIdentifier: String, attributeIds: Set<Int>) {
        expectation = Expectation.App(appIdentifier, attributeIds)
        len = 0
    }

    fun reset() {
        expectation = null
        len = 0
    }

    fun accept(fragment: ByteArray): Result {
        val exp = expectation ?: return Result.Error("unsolicited data source packet")
        append(fragment)
        return parse(exp)
    }

    private fun parse(exp: Expectation): Result {
        if (len < 1) return Result.Incomplete
        var p: Int
        when (exp) {
            is Expectation.Notification -> {
                if (buf[0].u8() != CommandId.GET_NOTIFICATION_ATTRIBUTES) return fail("unexpected command ${buf[0].u8()}")
                if (len < 5) return Result.Incomplete
                if (buf.readU32Le(1) != exp.uid) return fail("uid mismatch")
                p = 5
            }
            is Expectation.App -> {
                if (buf[0].u8() != CommandId.GET_APP_ATTRIBUTES) return fail("unexpected command ${buf[0].u8()}")
                val nul = indexOfZero(from = 1)
                if (nul < 0) return if (len > MAX_APP_ID_BYTES) fail("app identifier too long") else Result.Incomplete
                if (String(buf, 1, nul - 1, Charsets.UTF_8) != exp.appIdentifier) return fail("app identifier mismatch")
                p = nul + 1
            }
        }

        var count = 0
        var remaining = exp.ids.size
        while (remaining > 0) {
            if (p + 3 > len) return Result.Incomplete
            val id = buf[p].u8()
            val valueLen = buf.readU16Le(p + 1)
            if (p + 3 + valueLen > len) return Result.Incomplete
            if (count == tupleIds.size) growTuples()
            tupleIds[count] = id
            tupleStarts[count] = p + 3
            tupleLens[count] = valueLen
            count++
            if (id in exp.ids && (0 until count - 1).none { tupleIds[it] == id }) remaining--
            p += 3 + valueLen
        }

        val attributes = HashMap<Int, ByteArray>(count * 2)
        for (i in 0 until count) {
            attributes[tupleIds[i]] = buf.copyOfRange(tupleStarts[i], tupleStarts[i] + tupleLens[i])
        }
        reset()
        return when (exp) {
            is Expectation.Notification -> Result.NotificationAttributes(exp.uid, attributes)
            is Expectation.App -> Result.AppAttributes(exp.appIdentifier, attributes)
        }
    }

    private fun fail(reason: String): Result {
        reset()
        return Result.Error(reason)
    }

    private fun append(fragment: ByteArray) {
        if (len + fragment.size > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + fragment.size))
        fragment.copyInto(buf, len)
        len += fragment.size
    }

    private fun indexOfZero(from: Int): Int {
        for (i in from until len) if (buf[i].toInt() == 0) return i
        return -1
    }

    private fun growTuples() {
        val n = tupleIds.size * 2
        tupleIds = tupleIds.copyOf(n)
        tupleStarts = tupleStarts.copyOf(n)
        tupleLens = tupleLens.copyOf(n)
    }

    private companion object {
        const val MAX_APP_ID_BYTES = 512
    }
}
