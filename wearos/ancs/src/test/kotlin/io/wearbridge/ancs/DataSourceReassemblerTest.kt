package io.wearbridge.ancs

import io.wearbridge.ancs.DataSourceReassembler.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataSourceReassemblerTest {

    private val ids = setOf(0, 1, 3, 5)
    private val response = notificationResponse(
        42,
        0 to "net.whatsapp.WhatsApp",
        1 to "Rahul",
        3 to "Are you coming today? 🙂 مرحبا",
        5 to "20261005T143012",
    )

    private fun DataSourceReassembler.feed(fragments: List<ByteArray>): List<Result> = fragments.map(::accept)

    private fun assertComplete(result: Result) {
        result as Result.NotificationAttributes
        assertEquals(42L, result.uid)
        assertEquals("Rahul", result.attributes.getValue(1).toString(Charsets.UTF_8))
        assertEquals("Are you coming today? 🙂 مرحبا", result.attributes.getValue(3).toString(Charsets.UTF_8))
        assertEquals("20261005T143012", result.attributes.getValue(5).toString(Charsets.UTF_8))
    }

    @Test
    fun `single packet`() {
        val r = DataSourceReassembler().apply { expectNotification(42, ids) }
        assertComplete(r.accept(response))
        assertFalse(r.isExpecting)
    }

    @Test
    fun `split into two fragments at every offset`() {
        for (cut in 1 until response.size) {
            val r = DataSourceReassembler().apply { expectNotification(42, ids) }
            assertEquals("cut=$cut", Result.Incomplete, r.accept(response.copyOfRange(0, cut)))
            assertComplete(r.accept(response.copyOfRange(cut, response.size)))
        }
    }

    @Test
    fun `one byte at a time and MTU-sized chunks`() {
        for (size in listOf(1, 3, 20, 182)) {
            val r = DataSourceReassembler().apply { expectNotification(42, ids) }
            val results = r.feed(response.chunked(size))
            results.dropLast(1).forEach { assertEquals(Result.Incomplete, it) }
            assertComplete(results.last())
        }
    }

    @Test
    fun `empty attribute values count as present`() {
        val r = DataSourceReassembler().apply { expectNotification(1, setOf(1, 2)) }
        val result = r.accept(notificationResponse(1, 1 to "", 2 to "")) as Result.NotificationAttributes
        assertEquals(0, result.attributes.getValue(2).size)
    }

    @Test
    fun `uid mismatch is an error and resets`() {
        val r = DataSourceReassembler().apply { expectNotification(7, ids) }
        assertTrue(r.accept(response) is Result.Error)
        assertFalse(r.isExpecting)
    }

    @Test
    fun `unsolicited packet is an error`() {
        assertTrue(DataSourceReassembler().accept(response) is Result.Error)
    }

    @Test
    fun `app attributes with identifier split across fragments`() {
        val packet = appResponse("com.burbn.instagram", "Instagram")
        for (cut in 1 until packet.size) {
            val r = DataSourceReassembler().apply { expectApp("com.burbn.instagram", setOf(AppAttribute.DISPLAY_NAME)) }
            assertEquals(Result.Incomplete, r.accept(packet.copyOfRange(0, cut)))
            val result = r.accept(packet.copyOfRange(cut, packet.size)) as Result.AppAttributes
            assertEquals("Instagram", result.attributes.getValue(0).toString(Charsets.UTF_8))
        }
    }

    @Test
    fun `reusable after completion`() {
        val r = DataSourceReassembler()
        repeat(3) {
            r.expectNotification(42, ids)
            assertComplete(r.accept(response))
        }
    }
}
