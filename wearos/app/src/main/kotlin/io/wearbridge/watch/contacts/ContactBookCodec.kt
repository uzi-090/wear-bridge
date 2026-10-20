package io.wearbridge.watch.contacts

import io.wearbridge.protocol.Contact
import io.wearbridge.protocol.ContactsCodec
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer

/** Generation and records share one authenticated, atomically replaced file. */
internal object ContactBookCodec {
    private const val MAGIC = 0x42524332
    private const val MAX_CONTACTS = 5000
    data class Book(val contacts: List<Contact>, val generation: Long)
    fun encode(contacts: Collection<Contact>, generation: Long): ByteArray {
        require(contacts.size <= MAX_CONTACTS && generation in 0..0xFFFFFFFFL)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC); out.writeInt(generation.toInt()); out.writeInt(contacts.size)
            contacts.forEach { c ->
                val record = ContactsCodec.encodeUpsert(listOf(c))
                require(record.size <= 65535)
                out.writeShort(record.size); out.write(record)
            }
        }
        return bytes.toByteArray()
    }
    fun decode(bytes: ByteArray, legacyGeneration: Long): Book {
        require(bytes.size >= 4)
        val b = ByteBuffer.wrap(bytes)
        val first = b.int
        val generation: Long
        val count: Int
        if (first == MAGIC) {
            require(b.remaining() >= 8)
            generation = b.int.toLong() and 0xFFFFFFFFL
            count = b.int
        } else { generation = legacyGeneration; count = first }
        require(count in 0..MAX_CONTACTS)
        val contacts = List(count) {
            require(b.remaining() >= 2)
            val length = b.short.toInt() and 65535
            require(length > 0 && b.remaining() >= length)
            val record = ByteArray(length); b.get(record)
            ContactsCodec.decodeUpsert(record).single()
        }
        require(!b.hasRemaining() && contacts.map { it.id }.toSet().size == contacts.size)
        return Book(contacts, generation)
    }
}
