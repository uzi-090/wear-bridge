package io.wearbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsMessagesTest {

    private val contacts = listOf(
        Contact("A1:ABPerson", "Ålice Ünicode 🙂", listOf("+919876543210", "02012345678"), listOf("a@example.com"), favorite = true),
        Contact("B2:ABPerson", "محمد", emptyList(), emptyList(), favorite = false),
    )

    @Test
    fun `upsert batch round-trips with Unicode and empty lists`() {
        assertEquals(contacts, ContactsCodec.decodeUpsert(ContactsCodec.encodeUpsert(contacts)))
    }

    @Test
    fun `phones and emails are capped`() {
        val many = Contact("x", "X", List(20) { "+1555000$it" }, List(10) { "$it@x.com" }, false)
        val back = ContactsCodec.decodeUpsert(ContactsCodec.encodeUpsert(listOf(many))).single()
        assertEquals(ContactsCodec.MAX_PHONES, back.phones.size)
        assertEquals(ContactsCodec.MAX_EMAILS, back.emails.size)
    }

    @Test
    fun `begin delete and end round-trip and generation 0 is rejected`() {
        assertEquals(true to 1234, ContactsCodec.decodeBegin(ContactsCodec.encodeBegin(true, 1234)))
        assertEquals(listOf("a", "b"), ContactsCodec.decodeDelete(ContactsCodec.encodeDelete(listOf("a", "b"))))
        assertEquals(0xFEEDBEEFL, ContactsCodec.decodeEnd(ContactsCodec.encodeEnd(0xFEEDBEEFL)))
        try {
            ContactsCodec.decodeEnd(ContactsCodec.encodeEnd(0))
            throw AssertionError("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `phone normalization and matching`() {
        assertEquals("+919876543210", ContactsCodec.normalizePhone("+91 98765-43210"))
        assertEquals("09876543210", ContactsCodec.normalizePhone("(098) 765 43210"))
        assertTrue(ContactsCodec.samePhone("+919876543210", "09876543210"))
        assertFalse(ContactsCodec.samePhone("+919876543210", "+919876543211"))
        assertTrue(ContactsCodec.samePhone("121", "121"))
        assertFalse(ContactsCodec.samePhone("121", "1121"))
    }

    @Test
    fun `contact toString never shows personal data`() {
        assertFalse(contacts[0].toString().contains("lice"))
        assertFalse(contacts[0].toString().contains("9876"))
    }

    @Test
    fun `status trailer with contacts round-trips and older status decodes`() {
        val s = WatchStatus(50, false, true, true, 1, "m", "16", "1", contactsGeneration = 77, contactCount = 512)
        assertEquals(s, BridgeCodec.decodeStatus(BridgeCodec.encodeStatus(s)))
        val old = BridgeCodec.encodeStatus(s).copyOfRange(0, BridgeCodec.encodeStatus(s).size - 6)
        assertEquals(0L, BridgeCodec.decodeStatus(old).contactsGeneration)
    }

    @Test
    fun `call request round-trips`() {
        assertEquals("+919876543210" to "Ålice", BridgeCodec.decodeCallRequest(BridgeCodec.encodeCallRequest("+919876543210", "Ålice")))
    }
}
