package io.wearbridge.watch.ble

/**
 * Link states. The watch is the LE peripheral, so the spec's SCANNING / DEVICE_FOUND states are
 * replaced by [ADVERTISING]. [RECONNECTING] is advertising after a previously working link dropped.
 */
enum class LinkState {
    DISCONNECTED,
    ADVERTISING,
    CONNECTED,
    PAIRING,
    DISCOVERING_SERVICES,
    SUBSCRIBING,
    READY,
    RECONNECTING,
    ERROR,
}

/** Why the link isn't working, so the UI can show plain-language guidance. */
enum class LinkIssue {
    BLUETOOTH_OFF,
    PERMISSION_MISSING,
    ADVERTISING_UNSUPPORTED,
    ADVERTISING_FAILED,
    PAIRING_FAILED,
    ANCS_UNAVAILABLE,
    SETUP_FAILED,
    BACKGROUND_START_BLOCKED,
}

data class LinkSnapshot(
    val state: LinkState = LinkState.DISCONNECTED,
    val issue: LinkIssue? = null,
    val attempt: Int = 0,
)

sealed interface LinkEvent {
    data object Start : LinkEvent
    data object Stop : LinkEvent
    data object UserReconnect : LinkEvent
    data object BluetoothOff : LinkEvent
    data object BluetoothOn : LinkEvent
    data object PermissionMissing : LinkEvent
    data class AdvertisingFailed(val unsupported: Boolean) : LinkEvent
    data object CentralConnected : LinkEvent
    data object CentralDisconnected : LinkEvent
    data object ClientReady : LinkEvent
    data class ServicesDiscovered(val hasAncs: Boolean, val bonded: Boolean, val serviceCount: Int = 1) : LinkEvent
    data object Bonded : LinkEvent
    data object BondRemoved : LinkEvent
    data object BondFailed : LinkEvent
    data object Subscribed : LinkEvent
    data class SubscribeFailed(val authRequired: Boolean) : LinkEvent
    data object SetupFailed : LinkEvent
    data object RetryTimer : LinkEvent
}

sealed interface LinkEffect {
    data object StartAdvertising : LinkEffect
    data object StopAdvertising : LinkEffect
    data object ClosePeripheral : LinkEffect
    data object OpenClient : LinkEffect
    data object CloseClient : LinkEffect
    data object Discover : LinkEffect
    data object CreateBond : LinkEffect
    data object Subscribe : LinkEffect
    data object ResetSession : LinkEffect
    data object DisconnectCentral : LinkEffect
    data class ScheduleRetry(val delayMs: Long) : LinkEffect
    data object CancelRetry : LinkEffect
}

/**
 * Pure transition logic: no Android, no timers. The controller executes the returned effects in order.
 * Retries use bounded exponential backoff and reset whenever a new link comes up.
 */
class ConnectionStateMachine(
    private val maxAttempts: Int = 8,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 60_000,
) {
    var snapshot = LinkSnapshot()
        private set

    /** True once this service lifetime has reached READY, so a drop shows as "Reconnecting". */
    private var everReady = false

    private val linkStates = setOf(
        LinkState.CONNECTED, LinkState.PAIRING, LinkState.DISCOVERING_SERVICES,
        LinkState.SUBSCRIBING, LinkState.READY,
    )
    private var linkUp = false

    fun backoffMs(attempt: Int): Long =
        (baseDelayMs shl (attempt - 1).coerceIn(0, 16)).coerceAtMost(maxDelayMs)

    fun on(event: LinkEvent): List<LinkEffect> {
        val s = snapshot.state
        return when (event) {
            LinkEvent.Start ->
                if (s == LinkState.DISCONNECTED || (s == LinkState.ERROR && !linkUp)) {
                    go(LinkState.ADVERTISING, attempt = 0)
                    listOf(LinkEffect.StartAdvertising)
                } else emptyList()

            LinkEvent.Stop -> {
                linkUp = false
                everReady = false
                go(LinkState.DISCONNECTED, attempt = 0)
                listOf(
                    LinkEffect.CancelRetry, LinkEffect.CloseClient, LinkEffect.ResetSession,
                    LinkEffect.DisconnectCentral, LinkEffect.StopAdvertising, LinkEffect.ClosePeripheral,
                )
            }

            LinkEvent.BondRemoved -> {
                linkUp = false
                everReady = false
                go(LinkState.ADVERTISING, attempt = 0)
                listOf(LinkEffect.CancelRetry, LinkEffect.CloseClient, LinkEffect.ResetSession,
                    LinkEffect.DisconnectCentral, LinkEffect.StartAdvertising)
            }

            LinkEvent.UserReconnect -> {
                linkUp = false
                go(LinkState.ADVERTISING, attempt = 0)
                listOf(
                    LinkEffect.CancelRetry, LinkEffect.CloseClient, LinkEffect.ResetSession,
                    LinkEffect.DisconnectCentral, LinkEffect.StartAdvertising,
                )
            }

            LinkEvent.BluetoothOff -> {
                linkUp = false
                go(LinkState.DISCONNECTED, LinkIssue.BLUETOOTH_OFF, attempt = 0)
                listOf(LinkEffect.CancelRetry, LinkEffect.CloseClient, LinkEffect.ResetSession, LinkEffect.ClosePeripheral)
            }

            LinkEvent.BluetoothOn ->
                if (s == LinkState.DISCONNECTED && snapshot.issue == LinkIssue.BLUETOOTH_OFF) {
                    go(if (everReady) LinkState.RECONNECTING else LinkState.ADVERTISING, attempt = 0)
                    listOf(LinkEffect.StartAdvertising)
                } else emptyList()

            LinkEvent.PermissionMissing -> {
                linkUp = false
                go(LinkState.DISCONNECTED, LinkIssue.PERMISSION_MISSING)
                listOf(LinkEffect.CancelRetry, LinkEffect.CloseClient, LinkEffect.ResetSession, LinkEffect.ClosePeripheral)
            }

            is LinkEvent.AdvertisingFailed ->
                if (s == LinkState.ADVERTISING || s == LinkState.RECONNECTING) {
                    go(LinkState.ERROR, if (event.unsupported) LinkIssue.ADVERTISING_UNSUPPORTED else LinkIssue.ADVERTISING_FAILED)
                    emptyList()
                } else emptyList()

            LinkEvent.CentralConnected ->
                if (!linkUp && s != LinkState.DISCONNECTED) {
                    linkUp = true
                    go(LinkState.CONNECTED, attempt = 0)
                    listOf(LinkEffect.StopAdvertising, LinkEffect.CancelRetry, LinkEffect.OpenClient)
                } else emptyList()

            LinkEvent.CentralDisconnected ->
                if (linkUp) {
                    linkUp = false
                    go(if (everReady) LinkState.RECONNECTING else LinkState.ADVERTISING, attempt = 0)
                    listOf(LinkEffect.CancelRetry, LinkEffect.CloseClient, LinkEffect.ResetSession, LinkEffect.StartAdvertising)
                } else emptyList()

            LinkEvent.ClientReady ->
                if (s == LinkState.CONNECTED) {
                    go(LinkState.DISCOVERING_SERVICES)
                    listOf(LinkEffect.Discover)
                } else emptyList()

            is LinkEvent.ServicesDiscovered -> when {
                s != LinkState.DISCOVERING_SERVICES -> emptyList()
                !event.bonded -> {
                    go(LinkState.PAIRING)
                    listOf(LinkEffect.CreateBond)
                }
                event.serviceCount == 0 -> retry(LinkState.CONNECTED, LinkIssue.SETUP_FAILED, LinkEffect.CloseClient)
                event.hasAncs -> {
                    go(LinkState.SUBSCRIBING)
                    listOf(LinkEffect.Subscribe)
                }
                // Bonded but iOS isn't exposing ANCS yet (e.g. "Share System Notifications" is off).
                else -> retry(LinkState.DISCOVERING_SERVICES, LinkIssue.ANCS_UNAVAILABLE)
            }

            LinkEvent.Bonded ->
                if (s == LinkState.PAIRING) {
                    go(LinkState.DISCOVERING_SERVICES)
                    listOf(LinkEffect.Discover)
                } else emptyList()

            LinkEvent.BondFailed ->
                if (s == LinkState.PAIRING) {
                    go(LinkState.ERROR, LinkIssue.PAIRING_FAILED)
                    emptyList()
                } else emptyList()

            LinkEvent.Subscribed ->
                if (s == LinkState.SUBSCRIBING) {
                    everReady = true
                    go(LinkState.READY, attempt = 0)
                    emptyList()
                } else emptyList()

            is LinkEvent.SubscribeFailed -> when {
                s != LinkState.SUBSCRIBING -> emptyList()
                event.authRequired -> {
                    go(LinkState.PAIRING)
                    listOf(LinkEffect.CreateBond)
                }
                else -> retry(LinkState.DISCOVERING_SERVICES, LinkIssue.SETUP_FAILED)
            }

            LinkEvent.SetupFailed ->
                if (linkUp && s != LinkState.READY && s != LinkState.ERROR) {
                    retry(LinkState.CONNECTED, LinkIssue.SETUP_FAILED, LinkEffect.CloseClient)
                } else if (linkUp && s == LinkState.READY) {
                    // The GATT client dropped while the link stayed up: rebuild it.
                    retry(LinkState.CONNECTED, null, LinkEffect.CloseClient, LinkEffect.ResetSession)
                } else emptyList()

            LinkEvent.RetryTimer -> when (s) {
                LinkState.DISCOVERING_SERVICES -> listOf(LinkEffect.Discover)
                LinkState.CONNECTED -> listOf(LinkEffect.OpenClient)
                else -> emptyList()
            }
        }
    }

    private fun retry(state: LinkState, issue: LinkIssue?, vararg before: LinkEffect): List<LinkEffect> {
        val attempt = snapshot.attempt + 1
        if (attempt > maxAttempts) {
            go(LinkState.ERROR, issue ?: LinkIssue.SETUP_FAILED, attempt)
            return before.toList()
        }
        go(state, issue, attempt)
        return before.toList() + LinkEffect.ScheduleRetry(backoffMs(attempt))
    }

    private fun go(state: LinkState, issue: LinkIssue? = null, attempt: Int = snapshot.attempt) {
        snapshot = LinkSnapshot(state, issue, attempt)
    }
}
