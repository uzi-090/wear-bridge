package io.wearbridge.watch.calls

import io.wearbridge.ancs.AncsNotification
import io.wearbridge.ancs.Category
import io.wearbridge.watch.notifications.WatchNotification
import org.junit.Assert.*
import org.junit.Test

class CallPresentationTest {
    private val caller = Caller("Test caller", "", null)
    private fun active(uid: Long = 2, provider: String = "com.apple.mobilephone") = WatchNotification(
        AncsNotification(uid, Category.ACTIVE_CALL, 0x10, provider, "Test provider", "Test caller", "", "", null, null, null, "End Call"),
        receivedAtMillis = 0,
    )

    @Test fun phoneAnswerHasOnlyAcknowledgementForEveryProvider() {
        for (provider in listOf("com.apple.mobilephone", "net.whatsapp.WhatsApp", "com.apple.facetime", "test.telegram", "test.voip")) {
            val p = CallPresentation()
            p.ringing(1)
            assertEquals(CallUi.PhoneHandled(caller, answered = false), p.removedRinging(caller, null))
            assertEquals(CallUi.PhoneHandled(caller, answered = true), p.active(active(provider = provider), caller, 1000))
        }
    }

    @Test fun phoneRemovalDoesNotClaimRejectedOrExposeDuration() {
        assertEquals(CallUi.PhoneHandled(caller, answered = false), CallPresentation().removedRinging(caller, null))
    }

    @Test fun watchAnswerRetainsItsTimerAcrossTheRemovalGap() {
        val p = CallPresentation()
        p.ringing(1)
        p.answerFromWatch(1)
        assertEquals(CallUi.Answering(caller), p.removedRinging(caller, CallUi.Outcome.ANSWERING))
        assertEquals(CallUi.Active(active(), caller, 1000), p.active(active(), caller, 1000))
    }

    @Test fun failedWatchAnswerThenPhoneAnswerDoesNotGetWatchTimer() {
        val p = CallPresentation()
        p.ringing(1)
        p.answerFromWatch(1)
        p.answerFailed(1)
        assertTrue(p.active(active(), caller, 1000) is CallUi.PhoneHandled)
    }

    @Test fun newRingingCallDoesNotInheritWatchAnswer() {
        val p = CallPresentation()
        p.ringing(1)
        p.answerFromWatch(1)
        p.ringing(3)
        assertTrue(p.active(active(4), caller, 1000) is CallUi.PhoneHandled)
    }

    @Test fun returningToExistingActiveCallKeepsItsOriginDuringCallWaiting() {
        val p = CallPresentation()
        p.ringing(1)
        p.answerFromWatch(1)
        assertTrue(p.active(active(), caller, 1000) is CallUi.Active)
        p.ringing(3)
        assertTrue(p.active(active(), caller, 1000) is CallUi.Active)
    }

    @Test fun resetAndActiveReplayCannotInheritWatchOrigin() {
        val p = CallPresentation()
        p.ringing(1)
        p.answerFromWatch(1)
        p.reset()
        assertTrue(p.active(active(), caller, 1000) is CallUi.PhoneHandled)
    }

    @Test fun watchDeclineAndShakeKeepTheirOwnOutcome() {
        val p = CallPresentation()
        for (outcome in listOf(CallUi.Outcome.DECLINED, CallUi.Outcome.DECLINED_BY_SHAKE)) {
            assertEquals(CallUi.Ended(caller, outcome, null), p.removedRinging(caller, outcome))
        }
    }

    @Test fun overlappingDifferentProviderDoesNotInheritWatchAnswer() {
        val p = CallPresentation()
        p.ringing(1, "net.whatsapp.WhatsApp", "Test caller")
        p.answerFromWatch(1)
        assertTrue(p.active(active(provider = "com.apple.facetime"), caller, 1000) is CallUi.PhoneHandled)
    }

    @Test fun overlappingDifferentCallerDoesNotInheritWatchAnswer() {
        val p = CallPresentation()
        p.ringing(1, "com.apple.mobilephone", "Other caller")
        p.answerFromWatch(1)
        assertTrue(p.active(active(), caller, 1000) is CallUi.PhoneHandled)
    }

    @Test fun lateFailureRevokesWatchTimer() {
        val p = CallPresentation()
        p.ringing(1)
        p.answerFromWatch(1)
        assertTrue(p.active(active(), caller, 1000) is CallUi.Active)
        p.answerFailed(1)
        assertTrue(p.active(active(), caller, 1000) is CallUi.PhoneHandled)
    }

    @Test fun failedWaitingCallDoesNotRevokeExistingWatchCall() {
        val p = CallPresentation()
        p.ringing(1)
        p.answerFromWatch(1)
        assertTrue(p.active(active(), caller, 1000) is CallUi.Active)
        p.ringing(3)
        p.answerFromWatch(3)
        p.answerFailed(3)
        assertTrue(p.active(active(), caller, 1000) is CallUi.Active)
    }
}
