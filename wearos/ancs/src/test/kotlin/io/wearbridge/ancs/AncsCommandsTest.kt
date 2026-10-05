package io.wearbridge.ancs

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AncsCommandsTest {

    @Test
    fun `get notification attributes encodes max lengths only where required`() {
        val payload = AncsCommands.getNotificationAttributes(
            0x01020304L,
            listOf(
                AttributeRequest(NotificationAttribute.APP_IDENTIFIER),
                AttributeRequest(NotificationAttribute.TITLE, 64),
                AttributeRequest(NotificationAttribute.MESSAGE, 0x0200),
                AttributeRequest(NotificationAttribute.DATE),
                AttributeRequest(NotificationAttribute.POSITIVE_ACTION_LABEL),
            ),
        )
        assertArrayEquals(
            bytes(0x00, 0x04, 0x03, 0x02, 0x01, 0x00, 0x01, 0x40, 0x00, 0x03, 0x00, 0x02, 0x05, 0x06),
            payload,
        )
    }

    @Test
    fun `get app attributes is NUL-terminated`() {
        assertArrayEquals(
            bytes(0x01, 'a'.code, '.'.code, 'b'.code, 0x00, 0x00),
            AncsCommands.getAppAttributes("a.b"),
        )
    }

    @Test
    fun `perform action`() {
        assertArrayEquals(bytes(0x02, 0xEF, 0xBE, 0xAD, 0xDE, 0x01), AncsCommands.performNotificationAction(0xDEADBEEFL, ActionId.NEGATIVE))
        assertArrayEquals(bytes(0x02, 0x07, 0x00, 0x00, 0x00, 0x00), AncsCommands.performNotificationAction(7, ActionId.POSITIVE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `title without max length is rejected`() {
        AttributeRequest(NotificationAttribute.TITLE)
    }
}
