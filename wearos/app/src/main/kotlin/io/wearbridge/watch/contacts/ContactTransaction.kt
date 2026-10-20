package io.wearbridge.watch.contacts

import io.wearbridge.protocol.Contact
import io.wearbridge.protocol.ContactsCodec
import io.wearbridge.protocol.ContactsMessageType
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Rejects incomplete, duplicated or mixed transactions before touching durable contacts. */
internal class ContactTransaction(private val snapshot: () -> Map<String, Contact>) {
    data class Commit(val contacts: Map<String, Contact>, val generation: Long)
    private data class Pending(val contacts: HashMap<String, Contact>, val upserts: Int, val deletes: Int,
        val transaction: Long?, val seenUpserts: HashSet<String> = HashSet(), val seenDeletes: HashSet<String> = HashSet())
    private var pending: Pending? = null
    fun abort() { pending = null }
    fun handle(type: Int, bytes: ByteArray): Commit? {
        try {
            when (type) {
                ContactsMessageType.BEGIN -> {
                    abort()
                    val (full, count) = ContactsCodec.decodeBegin(bytes)
                    val b = ByteBuffer.wrap(bytes)
                    val extended = bytes.size == 9
                    require(extended || full) { "incremental sync requires integrity metadata" }
                    val deletes = if (extended) { b.position(3); b.short.toInt() and 65535 } else 0
                    val transaction = if (extended) b.int.toLong() and 0xFFFFFFFFL else null
                    require(transaction == null || transaction != 0L)
                    require(!full || deletes == 0)
                    pending = Pending(if (full) HashMap() else HashMap(snapshot()), count, deletes, transaction)
                }
                ContactsMessageType.UPSERT -> {
                    val p = requireNotNull(pending)
                    ContactsCodec.decodeUpsert(bytes).forEach { c ->
                        require(c.id.isNotEmpty() && p.seenUpserts.add(c.id) && c.id !in p.seenDeletes)
                        require(p.seenUpserts.size <= p.upserts)
                        p.contacts[c.id] = c
                    }
                }
                ContactsMessageType.DELETE -> {
                    val p = requireNotNull(pending)
                    ContactsCodec.decodeDelete(bytes).forEach { id ->
                        require(p.seenDeletes.add(id) && id !in p.seenUpserts && p.contacts.remove(id) != null)
                        require(p.seenDeletes.size <= p.deletes)
                    }
                }
                ContactsMessageType.END -> {
                    val p = requireNotNull(pending)
                    val generation = ContactsCodec.decodeEnd(bytes)
                    require(p.seenUpserts.size == p.upserts && p.seenDeletes.size == p.deletes)
                    if (p.transaction != null) {
                        require(bytes.size == 40)
                        val transaction = ByteBuffer.wrap(bytes, 4, 4).int.toLong() and 0xFFFFFFFFL
                        require(transaction == p.transaction)
                        require(MessageDigest.isEqual(bytes.copyOfRange(8, 40), ContactsCodec.bookDigest(p.contacts.values)))
                    } else require(bytes.size == 4)
                    abort()
                    return Commit(p.contacts, generation)
                }
                else -> throw IllegalArgumentException("unexpected contact message")
            }
            return null
        } catch (e: IllegalArgumentException) { abort(); throw e }
    }
}
