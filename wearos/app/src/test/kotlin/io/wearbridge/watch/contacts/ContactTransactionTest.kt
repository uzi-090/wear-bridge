package io.wearbridge.watch.contacts

import io.wearbridge.protocol.*
import org.junit.Assert.*
import org.junit.Test

class ContactTransactionTest {
    private val a = Contact("a", "Alpha", listOf("+15550100001"), emptyList(), false)
    private val b = Contact("b", "Beta", emptyList(), listOf("test@example.com"), true)
    private var book = mapOf(a.id to a)
    private val tx = ContactTransaction { book }
    private fun begin(full: Boolean, upserts: Int, deletes: Int = 0, id: Long = 2) =
        tx.handle(ContactsMessageType.BEGIN, ContactsCodec.encodeBegin(full, upserts, deletes, id))
    private fun end(contacts: List<Contact>, id: Long = 2) =
        tx.handle(ContactsMessageType.END, ContactsCodec.encodeEnd(id, id, ContactsCodec.bookDigest(contacts)))
    private fun reject(block: () -> Unit) {
        try { block(); fail("transaction must be rejected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun completeFullTransferCommitsOnlyAtEnd() {
        begin(true, 1)
        assertNull(tx.handle(ContactsMessageType.UPSERT, ContactsCodec.encodeUpsert(listOf(b))))
        assertEquals(mapOf(a.id to a), book)
        assertEquals(mapOf(b.id to b), end(listOf(b))?.contacts)
    }
    @Test fun missingUpsertCannotReplacePreviousBook() {
        begin(true, 1); reject { end(listOf(b)) }
        assertEquals(mapOf(a.id to a), book)
    }
    @Test fun missingDeleteCannotCommitEvenWithNoUpserts() {
        begin(false, 0, deletes = 1); reject { end(emptyList()) }
    }
    @Test fun malformedBatchAbortsEvenIfValidBatchFollows() {
        begin(true, 1)
        reject { tx.handle(ContactsMessageType.UPSERT, byteArrayOf(1)) }
        reject { tx.handle(ContactsMessageType.UPSERT, ContactsCodec.encodeUpsert(listOf(b))) }
        reject { end(listOf(b)) }
    }
    @Test fun duplicateRecordsCannotSatisfyExpectedCount() {
        begin(true, 2)
        reject { tx.handle(ContactsMessageType.UPSERT, ContactsCodec.encodeUpsert(listOf(b, b))) }
    }
    @Test fun wrongTransactionEndAbortsStaging() {
        begin(true, 0, id = 2); reject { end(emptyList(), id = 3) }
        reject { end(emptyList()) }
    }
    @Test fun wrongFinalDigestCannotPublishGeneration() {
        begin(true, 1); tx.handle(ContactsMessageType.UPSERT, ContactsCodec.encodeUpsert(listOf(b)))
        reject { end(listOf(b.copy(name = "Changed"))) }
    }
    @Test fun interruptedSyncRetainsPreviousBook() {
        begin(true, 1); tx.abort(); reject { end(listOf(b)) }
        assertEquals(mapOf(a.id to a), book)
    }
    @Test fun incrementalDeleteAndUpsertValidateFinalBook() {
        begin(false, 1, 1)
        tx.handle(ContactsMessageType.DELETE, ContactsCodec.encodeDelete(listOf(a.id)))
        tx.handle(ContactsMessageType.UPSERT, ContactsCodec.encodeUpsert(listOf(b)))
        assertEquals(mapOf(b.id to b), end(listOf(b))?.contacts)
    }
    @Test fun legacyIncrementalRequiresCompanionUpdate() {
        reject { tx.handle(ContactsMessageType.BEGIN, ContactsCodec.encodeBegin(false, 0)) }
    }
    @Test fun legacyFullStillChecksEveryExpectedRecord() {
        tx.handle(ContactsMessageType.BEGIN, ContactsCodec.encodeBegin(true, 1))
        reject { tx.handle(ContactsMessageType.END, ContactsCodec.encodeEnd(2)) }
    }
    @Test fun clearedBookHasAtomicGenerationAndNoRecords() {
        val encoded = ContactBookCodec.encode(emptyList(), 0)
        assertEquals(ContactBookCodec.Book(emptyList(), 0), ContactBookCodec.decode(encoded, 123))
    }
    @Test fun newFileGenerationWinsOverStaleLegacyPreference() {
        val encoded = ContactBookCodec.encode(listOf(a), 42)
        assertEquals(ContactBookCodec.Book(listOf(a), 42), ContactBookCodec.decode(encoded, 7))
    }
    @Test fun truncatedOrTrailingRecordsAreRejected() {
        val encoded = ContactBookCodec.encode(listOf(a), 42)
        reject { ContactBookCodec.decode(encoded.dropLast(1).toByteArray(), 0) }
        reject { ContactBookCodec.decode(encoded + byteArrayOf(1), 0) }
    }
    @Test fun legacyEncryptedBookLoadsWithItsExistingGeneration() {
        val record = ContactsCodec.encodeUpsert(listOf(a))
        val bytes = java.nio.ByteBuffer.allocate(6 + record.size)
            .putInt(1).putShort(record.size.toShort()).put(record).array()
        assertEquals(ContactBookCodec.Book(listOf(a), 123), ContactBookCodec.decode(bytes, 123))
    }
}
