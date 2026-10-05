package io.wearbridge.ancs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AncsSessionTest {

    private class FakeTransport : AncsSession.Transport {
        val writes = mutableListOf<Pair<Int, ByteArray>>()
        override fun writeControlPoint(token: Int, payload: ByteArray) {
            writes += token to payload
        }
        val lastToken get() = writes.last().first
        val lastCommand get() = writes.last().second[0].toInt()
    }

    private class FakeScheduler : AncsSession.Scheduler {
        val tasks = mutableListOf<Pair<() -> Unit, BooleanArray>>()
        override fun schedule(delayMs: Long, block: () -> Unit): AncsSession.Cancellable {
            val cancelled = booleanArrayOf(false)
            tasks += block to cancelled
            return AncsSession.Cancellable { cancelled[0] = true }
        }
        fun fireLive() = tasks.filter { !it.second[0] }.forEach { it.second[0] = true; it.first() }
    }

    private class Recorder : AncsSession.Listener {
        val notifications = mutableListOf<Pair<AncsNotification, NotificationSourceEvent>>()
        val removed = mutableListOf<Long>()
        val appNames = mutableMapOf<String, String>()
        val actions = mutableListOf<Triple<Long, ActionId, Boolean>>()
        val issues = mutableListOf<String>()
        override fun onNotification(notification: AncsNotification, event: NotificationSourceEvent) { notifications += notification to event }
        override fun onNotificationRemoved(uid: Long) { removed += uid }
        override fun onAppName(appIdentifier: String, displayName: String) { appNames[appIdentifier] = displayName }
        override fun onActionResult(uid: Long, action: ActionId, success: Boolean) { actions += Triple(uid, action, success) }
        override fun onProtocolIssue(reason: String) { issues += reason }
    }

    private val transport = FakeTransport()
    private val scheduler = FakeScheduler()
    private val listener = Recorder()
    private val session = AncsSession(transport, listener, scheduler)

    private val callFlags = EventFlags.POSITIVE_ACTION or EventFlags.NEGATIVE_ACTION

    private fun ack() = session.onControlPointWriteResult(transport.lastToken, 0)

    /** Answers like iOS: exactly the requested attributes, split into 20-byte notifications. */
    private fun respond(uid: Long, appId: String = "com.apple.mobilephone", title: String = "Vaibhav", labels: Boolean = false) {
        ack()
        val base = arrayOf(0 to appId, 1 to title, 2 to "", 3 to "", 4 to "0", 5 to "20261005T090000")
        val attrs = if (labels) base + arrayOf(6 to "Answer", 7 to "Decline") else base
        notificationResponse(uid, *attrs).chunked(20).forEach(session::onDataSource)
    }

    @Test
    fun `added notification is fetched, delivered, then app name requested`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, callFlags, Category.INCOMING_CALL, 10))
        assertEquals(1, transport.writes.size)
        assertEquals(CommandId.GET_NOTIFICATION_ATTRIBUTES, transport.lastCommand)

        respond(10, labels = true)
        assertEquals(1, listener.notifications.size)
        val (n, e) = listener.notifications.single()
        assertEquals("Vaibhav", n.title)
        assertEquals("Answer", n.positiveActionLabel)
        assertEquals("Decline", n.negativeActionLabel)
        assertEquals(EventId.ADDED, e.eventId)

        assertEquals(CommandId.GET_APP_ATTRIBUTES, transport.lastCommand)
        ack()
        session.onDataSource(appResponse("com.apple.mobilephone", "Phone"))
        assertEquals("Phone", listener.appNames["com.apple.mobilephone"])
        assertEquals(2, transport.writes.size)
    }

    @Test
    fun `app name is requested once and reused`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 1))
        respond(1, appId = "net.whatsapp.WhatsApp")
        ack(); session.onDataSource(appResponse("net.whatsapp.WhatsApp", "WhatsApp"))
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 2))
        respond(2, appId = "net.whatsapp.WhatsApp")
        assertEquals("WhatsApp", listener.notifications.last().first.appDisplayName)
        assertEquals(3, transport.writes.size) // attrs, app, attrs: no second app request
    }

    @Test
    fun `only one control point command in flight`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 1))
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 2))
        assertEquals(1, transport.writes.size)
        respond(1)
        // The next fetch goes out only after the first response completes.
        assertEquals(CommandId.GET_NOTIFICATION_ATTRIBUTES, transport.lastCommand)
        assertEquals(2L, transport.writes.last().second.copyOfRange(1, 5).let { it[0].toLong() })
    }

    @Test
    fun `response before write ack still waits for the ack before sending next`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 1))
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 2))
        val firstToken = transport.lastToken
        session.onDataSource(notificationResponse(1, 0 to "a", 1 to "t", 2 to "", 3 to "", 4 to "0", 5 to ""))
        assertEquals(1, listener.notifications.size)
        assertEquals(1, transport.writes.size)
        session.onControlPointWriteResult(firstToken, 0)
        assertEquals(2, transport.writes.size)
    }

    @Test
    fun `removed while queued drops the fetch and reports removal`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 1))
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 2))
        session.onNotificationSource(nsPacket(EventId.REMOVED, 0, Category.SOCIAL, 2))
        assertEquals(listOf(2L), listener.removed)
        respond(1)
        // Only the app-name request follows; uid 2 was never fetched.
        assertEquals(CommandId.GET_APP_ATTRIBUTES, transport.lastCommand)
    }

    @Test
    fun `incoming call jumps the queue`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 1))
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 2))
        session.onNotificationSource(nsPacket(EventId.ADDED, callFlags, Category.INCOMING_CALL, 3))
        respond(1)
        assertArrayEquals(bytes(3, 0, 0, 0), transport.writes.last().second.copyOfRange(1, 5))
    }

    @Test
    fun `action is sent with the iOS action id and reported on ack`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, callFlags, Category.INCOMING_CALL, 9))
        respond(9, labels = true)
        ack(); session.onDataSource(appResponse("com.apple.mobilephone", "Phone"))

        assertTrue(session.performAction(9, ActionId.NEGATIVE))
        assertArrayEquals(AncsCommands.performNotificationAction(9, ActionId.NEGATIVE), transport.writes.last().second)
        ack()
        assertEquals(Triple(9L, ActionId.NEGATIVE, true), listener.actions.single())
    }

    @Test
    fun `action not offered by iOS is refused`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, EventFlags.NEGATIVE_ACTION, Category.SOCIAL, 4))
        assertFalse(session.performAction(4, ActionId.POSITIVE))
        assertFalse(session.performAction(999, ActionId.NEGATIVE))
    }

    @Test
    fun `action failure from iOS is reported`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, callFlags, Category.INCOMING_CALL, 9))
        respond(9, labels = true)
        session.performAction(9, ActionId.POSITIVE)
        // The app-name request was queued earlier, but actions go first.
        assertEquals(CommandId.GET_APP_ATTRIBUTES, transport.lastCommand)
        ack(); session.onDataSource(appResponse("com.apple.mobilephone", "Phone"))
        assertEquals(CommandId.PERFORM_NOTIFICATION_ACTION, transport.lastCommand)
        session.onControlPointWriteResult(transport.lastToken, AncsError.ACTION_FAILED)
        assertEquals(Triple(9L, ActionId.POSITIVE, false), listener.actions.single())
    }

    @Test
    fun `timeout frees the queue and late results are ignored`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 1))
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 2))
        val staleToken = transport.lastToken
        scheduler.fireLive()
        assertEquals(2, transport.writes.size)
        session.onControlPointWriteResult(staleToken, 0x0E) // late failure must not affect request 2
        assertTrue(listener.actions.isEmpty())
        respond(2)
        assertEquals(2L, listener.notifications.single().first.uid)
    }

    @Test
    fun `modified before fetch keeps added semantics`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 1))
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 2))
        session.onNotificationSource(nsPacket(EventId.MODIFIED, EventFlags.SILENT, Category.SOCIAL, 2))
        respond(1)
        respond(2)
        val (_, e) = listener.notifications.last()
        assertEquals(EventId.ADDED, e.eventId)
        assertTrue(e.isSilent)
    }

    @Test
    fun `reset clears state but keeps app names`() {
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 1))
        respond(1, appId = "x.y")
        ack(); session.onDataSource(appResponse("x.y", "XY"))
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 2))
        session.reset()
        assertEquals(0, session.activeCount)
        assertEquals("XY", session.appName("x.y"))
        val before = transport.writes.size
        session.onNotificationSource(nsPacket(EventId.ADDED, 0, Category.SOCIAL, 3))
        assertEquals(before + 1, transport.writes.size)
    }
}
