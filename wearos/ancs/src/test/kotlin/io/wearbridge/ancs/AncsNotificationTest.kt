package io.wearbridge.ancs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AncsNotificationTest {

    private fun event(flags: Int) = NotificationSourceEvent(EventId.ADDED, flags, Category.INCOMING_CALL, 1, 5)

    @Test
    fun `date parses and rejects garbage`() {
        assertEquals(LocalDateTime.of(2026, 10, 5, 14, 30, 12), AncsDate.parse("20261005T143012"))
        assertNull(AncsDate.parse("2026-10-05"))
        assertNull(AncsDate.parse(""))
    }

    @Test
    fun `labels are taken verbatim and only when the flag is set`() {
        val attrs = mapOf(
            NotificationAttribute.TITLE.id to "Vaibhav".toByteArray(),
            NotificationAttribute.POSITIVE_ACTION_LABEL.id to "Answer".toByteArray(),
            NotificationAttribute.NEGATIVE_ACTION_LABEL.id to "Decline".toByteArray(),
        )
        val both = AncsNotification.from(event(EventFlags.POSITIVE_ACTION or EventFlags.NEGATIVE_ACTION), attrs, null)
        assertEquals("Answer", both.positiveActionLabel)
        assertEquals("Decline", both.negativeActionLabel)

        val negOnly = AncsNotification.from(event(EventFlags.NEGATIVE_ACTION), attrs, null)
        assertNull(negOnly.positiveActionLabel)
        assertEquals("Decline", negOnly.negativeActionLabel)
    }

    @Test
    fun `truncation detected from message size`() {
        val attrs = mapOf(
            NotificationAttribute.MESSAGE.id to "abc".toByteArray(),
            NotificationAttribute.MESSAGE_SIZE.id to "900".toByteArray(),
        )
        assertTrue(AncsNotification.from(event(0), attrs, null).isMessageTruncated)
        val full = attrs + (NotificationAttribute.MESSAGE_SIZE.id to "3".toByteArray())
        assertFalse(AncsNotification.from(event(0), full, null).isMessageTruncated)
    }

    @Test
    fun `toString never contains content`() {
        val n = AncsNotification.from(event(0), mapOf(NotificationAttribute.TITLE.id to "Secret".toByteArray()), null)
        assertFalse(n.toString().contains("Secret"))
    }
}
