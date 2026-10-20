package io.wearbridge.watch.calls

import io.wearbridge.watch.notifications.WatchNotification

/** ANCS reports call state, but not which phone action removed a ringing notification. */
class CallPresentation {
    private var ringingUid: Long? = null
    private var watchAnswered = false
    private var ringingProvider: String? = null
    private var ringingCaller: String? = null
    private var activeUid: Long? = null
    private var activeFromWatch = false
    private var activeAnswerUid: Long? = null

    fun ringing(uid: Long, provider: String? = null, callerName: String? = null) {
        if (ringingUid != uid) {
            ringingUid = uid
            watchAnswered = false
            ringingProvider = provider
            ringingCaller = callerName
        }
    }

    fun answerFromWatch(uid: Long) {
        if (uid == ringingUid) watchAnswered = true
    }

    fun answerFailed(uid: Long): Boolean {
        if (uid != ringingUid) return false
        watchAnswered = false
        if (activeAnswerUid == uid) activeFromWatch = false
        return true
    }

    fun active(call: WatchNotification, caller: Caller, startedAtMillis: Long): CallUi {
        if (activeUid != call.uid) {
            activeUid = call.uid
            activeFromWatch = watchAnswered &&
                (ringingProvider == null || ringingProvider == call.ancs.appIdentifier) &&
                (ringingCaller.isNullOrBlank() || call.ancs.title.isBlank() || ringingCaller == call.ancs.title)
            activeAnswerUid = if (activeFromWatch) ringingUid else null
        }
        return if (activeFromWatch) CallUi.Active(call, caller, startedAtMillis)
        else CallUi.PhoneHandled(caller, answered = true)
    }

    fun removedRinging(caller: Caller, outcome: CallUi.Outcome?): CallUi = when (outcome) {
        CallUi.Outcome.ANSWERING -> CallUi.Answering(caller)
        CallUi.Outcome.DECLINED, CallUi.Outcome.DECLINED_BY_SHAKE -> CallUi.Ended(caller, outcome, null)
        // Phone rejection, missed call and caller hang-up are indistinguishable here.
        else -> CallUi.PhoneHandled(caller, answered = false)
    }

    fun reset() {
        ringingUid = null
        watchAnswered = false
        ringingProvider = null
        ringingCaller = null
        activeUid = null
        activeFromWatch = false
        activeAnswerUid = null
    }
}
