package io.wearbridge.watch.notifications

import io.wearbridge.ancs.AncsNotification
import io.wearbridge.ancs.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class NotificationRepositoryTest {

    private var history = false
    private var clock = 1_000_000L
    private val repo = NotificationRepository(historyEnabled = { history }, now = { clock })

    private fun n(
        uid: Long,
        title: String = "Alex",
        app: String = "net.whatsapp.WhatsApp",
        category: Category = Category.SOCIAL,
        minute: Int = uid.toInt(),
        masked: Boolean = false,
    ) = WatchNotification(
        AncsNotification(
            uid = uid, category = category, flags = 0x10, appIdentifier = app, appDisplayName = null,
            title = title, subtitle = "", message = "m$uid", messageSize = null,
            date = LocalDateTime.of(2026, 10, 5, 10, minute), positiveActionLabel = null, negativeActionLabel = "Clear",
        ),
        receivedAtMillis = 0,
        masked = masked,
    )

    @Test fun forgettingDeviceClearsHistoryCallsActionsAndReplayIds() {
        history = true
        repo.upsert(n(1)); repo.remove(1)
        repo.upsert(n(2)); repo.delete(2)
        repo.upsert(n(3, category = Category.INCOMING_CALL))
        repo.upsert(n(4, category = Category.ACTIVE_CALL))
        repo.setActionStatus(ActionStatus(3, io.wearbridge.ancs.ActionId.POSITIVE, ActionStatus.State.SENDING))
        repo.forgetDevice()
        assertTrue(repo.items.value.isEmpty())
        assertNull(repo.incomingCall.value)
        assertNull(repo.activeCall.value)
        assertNull(repo.actionStatus.value)
        assertFalse(repo.isDeleted(2))
        repo.upsert(n(2))
        assertEquals(listOf(2L), repo.items.value.map { it.uid })
    }

    @Test
    fun removedByIphoneIsDroppedWhenHistoryIsOff() {
        repo.upsert(n(1))
        repo.remove(1)
        assertTrue(repo.items.value.isEmpty())
    }

    @Test
    fun removedByIphoneIsKeptAsHistoryWithoutActions() {
        history = true
        repo.upsert(n(1))
        repo.remove(1)
        val kept = repo.get(1)!!
        assertTrue(kept.clearedOnPhone)
        assertFalse(kept.hasActions)
    }

    @Test
    fun historyNeverKeepsMaskedContentOrCalls() {
        history = true
        repo.upsert(n(1, masked = true))
        repo.upsert(n(2, app = "com.apple.mobilephone", category = Category.INCOMING_CALL))
        repo.remove(1)
        repo.remove(2)
        assertTrue(repo.items.value.isEmpty())
        assertNull(repo.incomingCall.value)
    }

    @Test
    fun historyExpiresAfter24Hours() {
        history = true
        repo.upsert(n(1))
        repo.remove(1)
        clock += NotificationRepository.HISTORY_TTL_MS + 1
        repo.upsert(n(2))
        assertEquals(listOf(2L), repo.items.value.map { it.uid })
    }

    @Test
    fun turningHistoryOffForgetsClearedItems() {
        history = true
        repo.upsert(n(1))
        repo.upsert(n(2))
        repo.remove(1)
        repo.clearHistory()
        assertEquals(listOf(2L), repo.items.value.map { it.uid })
    }

    @Test
    fun staleItemsNotReplayedBecomeHistory() {
        history = true
        repo.upsert(n(1))
        repo.upsert(n(2))
        repo.markAllStale()
        repo.upsert(n(2)) // replayed by iOS
        assertEquals(listOf(1L), repo.purgeStale())
        assertTrue(repo.get(1)!!.clearedOnPhone)
        assertFalse(repo.get(2)!!.stale)
    }

    @Test
    fun deleteIsWatchOnlyAndRemembered() {
        repo.upsert(n(1))
        repo.delete(1)
        assertNull(repo.get(1))
        assertTrue(repo.isDeleted(1))
        // iOS finally clears it: no need to remember it any more.
        repo.remove(1)
        assertFalse(repo.isDeleted(1))
    }

    @Test
    fun deleteAllKeepsCalls() {
        repo.upsert(n(1))
        repo.upsert(n(2, app = "com.apple.mobilephone", category = Category.INCOMING_CALL))
        assertEquals(listOf(1L), repo.deleteAll())
        assertEquals(listOf(2L), repo.items.value.map { it.uid })
    }

    @Test
    fun threadsGroupByAppAndTitleNewestFirst() {
        repo.upsert(n(1, title = "Alex"))
        repo.upsert(n(2, title = "Sam"))
        repo.upsert(n(3, title = "Alex"))
        repo.upsert(n(4, title = "Alex", app = "com.apple.MobileSMS"))
        val threads = NotificationRepository.threads(repo.items.value)
        assertEquals(listOf(4L, 3L, 2L), threads.map { it.latest.uid })
        assertEquals(listOf(3L, 1L), threads[1].items.map { it.uid })
    }

    @Test
    fun maskedThreadsDoNotDependOnHiddenTitles() {
        repo.upsert(n(1, title = "Bank A", masked = true))
        repo.upsert(n(2, title = "Bank B", masked = true))
        assertEquals(1, NotificationRepository.threads(repo.items.value).size)
    }

    @Test
    fun sameUidCanTransitionFromRingingToActiveForEveryProvider() {
        for (provider in listOf("com.apple.mobilephone", "net.whatsapp.WhatsApp", "com.apple.facetime", "test.voip")) {
            repo.upsert(n(1, app = provider, category = Category.INCOMING_CALL))
            repo.upsert(n(1, app = provider, category = Category.ACTIVE_CALL))
            assertNull(repo.incomingCall.value)
            assertEquals(1L, repo.activeCall.value?.uid)
            repo.upsert(n(1, app = provider, category = Category.MISSED_CALL))
            assertNull(repo.incomingCall.value)
            assertNull(repo.activeCall.value)
        }
    }

    @Test
    fun updatingAnotherCallDoesNotClearWaitingCall() {
        repo.upsert(n(1, category = Category.INCOMING_CALL))
        repo.upsert(n(2, category = Category.ACTIVE_CALL))
        repo.upsert(n(2, category = Category.MISSED_CALL))
        assertEquals(1L, repo.incomingCall.value?.uid)
        assertNull(repo.activeCall.value)
    }

    @Test
    fun activeCallIsAvailableForControlButNotInGenericInbox() {
        repo.upsert(n(1, category = Category.ACTIVE_CALL))
        assertTrue(repo.items.value.isEmpty())
        assertEquals(Category.ACTIVE_CALL, repo.get(1)?.ancs?.category)
        repo.remove(1)
        assertNull(repo.get(1))
    }
    @Test fun historyExpiresOnReadWithoutAnotherNotification() {
        history = true; repo.upsert(n(1)); repo.remove(1)
        clock += NotificationRepository.HISTORY_TTL_MS + 1
        assertNull(repo.get(1)); assertTrue(repo.items.value.isEmpty())
    }
    @Test fun timerExpiryUpdatesExistingSubscriberFlow() {
        history = true; repo.upsert(n(1)); repo.remove(1)
        val flow = repo.items
        clock += NotificationRepository.HISTORY_TTL_MS + 1
        repo.expireHistory()
        assertTrue(flow.value.isEmpty())
    }
    @Test fun newlyMaskedPolicyRemovesPreviouslyRetainedContent() {
        history = true; repo.upsert(n(1)); repo.remove(1)
        repo.setMasked(1, true)
        assertNull(repo.get(1))
    }
    @Test fun disabledCallIsInvalidatedWithoutPhoneHandledInference() {
        repo.upsert(n(1, category = Category.INCOMING_CALL)); repo.discard(1)
        assertNull(repo.incomingCall.value); assertTrue(repo.wasCallInvalidated(1))
        repo.upsert(n(1, category = Category.INCOMING_CALL))
        assertFalse(repo.wasCallInvalidated(1))
    }
}
