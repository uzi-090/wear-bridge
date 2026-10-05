package io.wearbridge.protocol

/**
 * CONTACTS characteristic (iPhone → watch), sealed like every bridge message. A sync is
 * BEGIN, any number of UPSERT / DELETE batches, then END with a new generation number. The watch
 * applies the batches to a staging copy and commits only at END, so an interrupted sync never leaves
 * a half-updated address book. The watch reports its generation in STATUS, and the iPhone does a full
 * resync whenever it doesn't match what it last sent. See docs/BLE_PROTOCOL.md.
 */
object ContactsMessageType {
    /** u8 full (1 = replace everything), u16 expected contact count (for progress). */
    const val BEGIN = 0x30
    /** u8 count, then per contact: see [ContactsCodec.encodeUpsert]. */
    const val UPSERT = 0x31
    /** u8 count, then str id per contact. */
    const val DELETE = 0x32
    /** u32 generation (never 0). */
    const val END = 0x33
    /** empty: remove every contact from the watch (the user turned sync off). */
    const val CLEAR = 0x34
}

/** The minimal watch-side contact (§14). Phones are normalized: digits with an optional leading "+". */
data class Contact(
    val id: String,
    val name: String,
    val phones: List<String>,
    val emails: List<String>,
    val favorite: Boolean,
) {
    // Contact details are personal data: keep them out of logs.
    override fun toString(): String = "Contact(id=***, phones=${phones.size}, emails=${emails.size}, favorite=$favorite)"
}

object ContactsCodec {
    const val MAX_PHONES = 8
    const val MAX_EMAILS = 3

    fun encodeBegin(full: Boolean, count: Int): ByteArray = Bytes.Writer().apply {
        u8(if (full) 1 else 0)
        u16(count.coerceIn(0, 0xFFFF))
    }.bytes()

    fun decodeBegin(bytes: ByteArray): Pair<Boolean, Int> {
        val r = Bytes.Reader(bytes)
        return (r.u8() == 1) to r.u16()
    }

    /** Per contact: str id, str name, u8 flags (bit0 favorite), u8 n + str phones, u8 n + str emails. */
    fun encodeUpsert(contacts: List<Contact>): ByteArray = Bytes.Writer().apply {
        require(contacts.size <= 255) { "batch too large" }
        u8(contacts.size)
        for (c in contacts) {
            str(c.id)
            str(c.name)
            u8(if (c.favorite) 1 else 0)
            val phones = c.phones.take(MAX_PHONES)
            u8(phones.size)
            phones.forEach(::str)
            val emails = c.emails.take(MAX_EMAILS)
            u8(emails.size)
            emails.forEach(::str)
        }
    }.bytes()

    fun decodeUpsert(bytes: ByteArray): List<Contact> {
        val r = Bytes.Reader(bytes)
        return List(r.u8()) {
            val id = r.str()
            val name = r.str()
            val favorite = r.u8() and 1 != 0
            val phones = List(r.u8()) { r.str() }
            val emails = List(r.u8()) { r.str() }
            Contact(id, name, phones, emails, favorite)
        }
    }

    fun encodeDelete(ids: List<String>): ByteArray = Bytes.Writer().apply {
        require(ids.size <= 255) { "batch too large" }
        u8(ids.size)
        ids.forEach(::str)
    }.bytes()

    fun decodeDelete(bytes: ByteArray): List<String> {
        val r = Bytes.Reader(bytes)
        return List(r.u8()) { r.str() }
    }

    fun encodeEnd(generation: Long): ByteArray = Bytes.Writer().apply { u32(generation) }.bytes()

    fun decodeEnd(bytes: ByteArray): Long {
        val g = Bytes.Reader(bytes).u32()
        require(g != 0L) { "generation 0 is reserved" }
        return g
    }

    /** Digits with an optional leading "+": "+91 98765-43210" → "+919876543210". Used for caller matching. */
    fun normalizePhone(raw: String): String {
        val digits = raw.filter(Char::isDigit)
        return if (raw.trimStart().startsWith("+")) "+$digits" else digits
    }

    /**
     * Do two normalized numbers refer to the same line? Compares the last 10 digits, so "+91 98765 43210"
     * matches "098765 43210". Short numbers (service codes) must match exactly.
     */
    fun samePhone(a: String, b: String): Boolean {
        val x = a.filter(Char::isDigit)
        val y = b.filter(Char::isDigit)
        if (x.length < 7 || y.length < 7) return x == y
        return x.takeLast(10) == y.takeLast(10)
    }
}
