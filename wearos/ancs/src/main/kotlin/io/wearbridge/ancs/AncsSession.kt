package io.wearbridge.ancs

/**
 * Transport-independent ANCS consumer logic.
 *
 * It turns Notification Source events into attribute fetches, keeps exactly one Control Point
 * command in flight, reassembles Data Source responses and routes user actions.
 *
 * Threading: not thread-safe. Every method, including [Scheduler] callbacks, must be called from
 * one serialized context (on Android, the BLE coroutine scope).
 */
class AncsSession(
    private val transport: Transport,
    private val listener: Listener,
    private val scheduler: Scheduler,
    private val config: Config = Config(),
) {
    interface Transport {
        /**
         * Write [payload] to the Control Point. The result must eventually be delivered via
         * [onControlPointWriteResult] with the same [token], including on failure or timeout.
         */
        fun writeControlPoint(token: Int, payload: ByteArray)
    }

    interface Listener {
        fun onNotification(notification: AncsNotification, event: NotificationSourceEvent)
        fun onNotificationRemoved(uid: Long)
        fun onAppName(appIdentifier: String, displayName: String)
        fun onActionResult(uid: Long, action: ActionId, success: Boolean)

        /** Diagnostics only. [reason] never contains notification content. */
        fun onProtocolIssue(reason: String)
    }

    fun interface Cancellable {
        fun cancel()
    }

    fun interface Scheduler {
        fun schedule(delayMs: Long, block: () -> Unit): Cancellable
    }

    data class Config(
        val titleMaxBytes: Int = 64,
        val subtitleMaxBytes: Int = 64,
        val messageMaxBytes: Int = 512,
        /** Max silence while waiting for a write result or the next Data Source fragment. */
        val requestTimeoutMs: Long = 5_000,
    )

    private sealed interface Request {
        data class Attributes(val event: NotificationSourceEvent) : Request
        data class App(val appIdentifier: String) : Request
        data class Action(val uid: Long, val action: ActionId) : Request
    }

    private val queue = ArrayDeque<Request>()
    private val reassembler = DataSourceReassembler()
    private val known = HashMap<Long, NotificationSourceEvent>()

    // These survive reset(): app names don't change between connections.
    private val appNames = HashMap<String, String>()
    private val appNamesRequested = HashSet<String>()

    private var inFlight: Request? = null
    private var writePending = false
    private var writeToken = 0
    private var timeout: Cancellable? = null

    /** Number of notifications iOS currently reports as present. */
    val activeCount: Int get() = known.size

    fun appName(appIdentifier: String): String? = appNames[appIdentifier]

    fun onNotificationSource(packet: ByteArray) {
        val event = NotificationSourceEvent.parse(packet)
            ?: return listener.onProtocolIssue("malformed notification source packet (${packet.size} bytes)")

        when (event.eventId) {
            EventId.REMOVED -> {
                known.remove(event.uid)
                queue.removeAll { it is Request.Attributes && it.event.uid == event.uid }
                listener.onNotificationRemoved(event.uid)
            }
            EventId.ADDED, EventId.MODIFIED -> {
                val previous = known[event.uid]
                // A Modified that arrives before the original Added was fetched is still "new" to us.
                val effective = if (previous?.eventId == EventId.ADDED && event.eventId == EventId.MODIFIED &&
                    queue.any { it is Request.Attributes && it.event.uid == event.uid }
                ) event.copy(eventId = EventId.ADDED) else event
                known[event.uid] = effective

                val request = Request.Attributes(effective)
                val queuedIndex = queue.indexOfFirst { it is Request.Attributes && it.event.uid == event.uid }
                when {
                    queuedIndex >= 0 -> queue[queuedIndex] = request
                    // Calls are latency-critical: jump ahead of other fetches, but stay behind user actions.
                    event.category == Category.INCOMING_CALL -> queue.add(queue.count { it is Request.Action }, request)
                    else -> queue.addLast(request)
                }
                pump()
            }
        }
    }

    /**
     * Queues a positive or negative action. Returns false if iOS no longer reports the notification
     * or doesn't offer that action.
     */
    fun performAction(uid: Long, action: ActionId): Boolean {
        val event = known[uid] ?: return false
        val offered = when (action) {
            ActionId.POSITIVE -> event.hasPositiveAction
            ActionId.NEGATIVE -> event.hasNegativeAction
        }
        if (!offered) return false
        queue.add(queue.count { it is Request.Action }, Request.Action(uid, action))
        pump()
        return true
    }

    fun onControlPointWriteResult(token: Int, status: Int) {
        if (token != writeToken || !writePending) return // stale result from a timed-out request
        writePending = false
        val request = inFlight
        if (request == null) {
            pump() // the response already completed through the Data Source
            return
        }
        if (status != 0) {
            listener.onProtocolIssue("control point write failed status=0x${status.toString(16)}")
            complete(request, success = false)
        } else if (request is Request.Action) {
            complete(request, success = true)
        } else {
            rearmTimeout(request) // now waiting for Data Source fragments
        }
    }

    fun onDataSource(packet: ByteArray) {
        val request = inFlight
        when (val result = reassembler.accept(packet)) {
            DataSourceReassembler.Result.Incomplete -> if (request != null) rearmTimeout(request)
            is DataSourceReassembler.Result.Error -> {
                listener.onProtocolIssue("data source: ${result.reason}")
                if (request != null && request !is Request.Action) complete(request, success = false)
            }
            is DataSourceReassembler.Result.NotificationAttributes -> {
                val attrRequest = request as? Request.Attributes
                if (attrRequest != null) {
                    // Freshest flags (it may have been modified meanwhile), but the event type this
                    // fetch was for, so an Added is still delivered as new.
                    val event = known[result.uid]?.copy(eventId = attrRequest.event.eventId)
                    if (event != null) deliver(event, result.attributes)
                    complete(attrRequest, success = true)
                }
            }
            is DataSourceReassembler.Result.AppAttributes -> {
                val name = result.attributes[AppAttribute.DISPLAY_NAME]?.toString(Charsets.UTF_8)
                if (!name.isNullOrBlank()) {
                    appNames[result.appIdentifier] = name
                    listener.onAppName(result.appIdentifier, name)
                }
                if (request is Request.App) complete(request, success = true)
            }
        }
    }

    /** Clears per-connection state. Call on disconnect. App names are kept. */
    fun reset() {
        timeout?.cancel()
        timeout = null
        queue.clear()
        known.clear()
        reassembler.reset()
        inFlight = null
        writePending = false
        writeToken++
        appNamesRequested.retainAll(appNames.keys)
    }

    private fun deliver(event: NotificationSourceEvent, attributes: Map<Int, ByteArray>) {
        val notification = AncsNotification.from(event, attributes, appDisplayName = null)
        val appId = notification.appIdentifier
        listener.onNotification(notification.copy(appDisplayName = appId?.let(appNames::get)), event)
        if (appId != null && appId !in appNames && appNamesRequested.add(appId)) {
            queue.addLast(Request.App(appId))
        }
    }

    private fun pump() {
        if (inFlight != null || writePending) return
        val request = queue.removeFirstOrNull() ?: return
        val payload = when (request) {
            is Request.Attributes -> {
                val attrs = attributeRequests(request.event)
                reassembler.expectNotification(request.event.uid, attrs.mapTo(HashSet()) { it.attribute.id })
                AncsCommands.getNotificationAttributes(request.event.uid, attrs)
            }
            is Request.App -> {
                reassembler.expectApp(request.appIdentifier, setOf(AppAttribute.DISPLAY_NAME))
                AncsCommands.getAppAttributes(request.appIdentifier)
            }
            is Request.Action -> {
                reassembler.reset()
                AncsCommands.performNotificationAction(request.uid, request.action)
            }
        }
        inFlight = request
        writePending = true
        val token = ++writeToken
        rearmTimeout(request)
        transport.writeControlPoint(token, payload)
    }

    private fun attributeRequests(event: NotificationSourceEvent): List<AttributeRequest> = buildList {
        add(AttributeRequest(NotificationAttribute.APP_IDENTIFIER))
        add(AttributeRequest(NotificationAttribute.TITLE, config.titleMaxBytes))
        add(AttributeRequest(NotificationAttribute.SUBTITLE, config.subtitleMaxBytes))
        add(AttributeRequest(NotificationAttribute.MESSAGE, config.messageMaxBytes))
        add(AttributeRequest(NotificationAttribute.MESSAGE_SIZE))
        add(AttributeRequest(NotificationAttribute.DATE))
        // Labels are fetched lazily: only when iOS says the action exists.
        if (event.hasPositiveAction) add(AttributeRequest(NotificationAttribute.POSITIVE_ACTION_LABEL))
        if (event.hasNegativeAction) add(AttributeRequest(NotificationAttribute.NEGATIVE_ACTION_LABEL))
    }

    private fun complete(request: Request, success: Boolean) {
        if (inFlight !== request) return
        timeout?.cancel()
        timeout = null
        inFlight = null
        if (request !is Request.Action) reassembler.reset()
        if (request is Request.Action) listener.onActionResult(request.uid, request.action, success)
        if (request is Request.App && !success) appNamesRequested.remove(request.appIdentifier)
        pump()
    }

    private fun rearmTimeout(request: Request) {
        timeout?.cancel()
        timeout = scheduler.schedule(config.requestTimeoutMs) { onTimeout(request) }
    }

    private fun onTimeout(request: Request) {
        if (inFlight !== request) return
        listener.onProtocolIssue("request timed out (${request::class.simpleName})")
        // Invalidate the outstanding write so a late result can't be attributed to the next request.
        writePending = false
        writeToken++
        complete(request, success = false)
    }
}
