package io.wearbridge.protocol

import org.junit.Assert.*
import org.junit.Test

class ContactsIntegrityTest {
    @Test fun canonicalBookDigestMatchesIndependentWireFixture() {
        val a = Contact("a", "Alpha", listOf("+15550100001"), emptyList(), false)
        val b = Contact("b", "Beta", emptyList(), listOf("test@example.com"), true)
        assertEquals("b7268b291a58e9ebfb1a9288eaf044ca20a023051525be9b98f3da615ad1c32e",
            ContactsCodec.bookDigest(listOf(b, a)).joinToString("") { "%02x".format(it.toInt() and 255) })
    }
}
