package io.wearbridge.ancs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSourceEventTest {

    @Test
    fun `parses incoming call with both actions`() {
        val e = NotificationSourceEvent.parse(bytes(0x00, 0x18, 0x01, 0x01, 0x78, 0x56, 0x34, 0x12))!!
        assertEquals(EventId.ADDED, e.eventId)
        assertEquals(Category.INCOMING_CALL, e.category)
        assertEquals(1, e.categoryCount)
        assertEquals(0x12345678L, e.uid)
        assertTrue(e.hasPositiveAction)
        assertTrue(e.hasNegativeAction)
        assertFalse(e.isSilent)
        assertFalse(e.isPreExisting)
    }

    @Test
    fun `uid is unsigned 32-bit`() {
        val e = NotificationSourceEvent.parse(bytes(0x02, 0x00, 0x04, 0x00, 0xFF, 0xFF, 0xFF, 0xFF))!!
        assertEquals(EventId.REMOVED, e.eventId)
        assertEquals(4_294_967_295L, e.uid)
    }

    @Test
    fun `flags decode individually`() {
        val e = NotificationSourceEvent.parse(nsPacket(EventId.MODIFIED, EventFlags.SILENT or EventFlags.PRE_EXISTING or EventFlags.IMPORTANT, Category.SOCIAL, 7))!!
        assertTrue(e.isSilent)
        assertTrue(e.isPreExisting)
        assertTrue(e.isImportant)
        assertFalse(e.hasPositiveAction)
        assertEquals(EventId.MODIFIED, e.eventId)
    }

    @Test
    fun `unknown category maps to UNKNOWN, unknown event is rejected`() {
        assertEquals(Category.UNKNOWN, NotificationSourceEvent.parse(bytes(0, 0, 200, 0, 1, 0, 0, 0))!!.category)
        assertNull(NotificationSourceEvent.parse(bytes(9, 0, 0, 0, 1, 0, 0, 0)))
    }

    @Test
    fun `short packet is rejected, longer packet tolerated`() {
        assertNull(NotificationSourceEvent.parse(bytes(0, 0, 0, 0, 1, 0, 0)))
        assertEquals(1L, NotificationSourceEvent.parse(bytes(0, 0, 0, 0, 1, 0, 0, 0, 0xAA))!!.uid)
    }
}
