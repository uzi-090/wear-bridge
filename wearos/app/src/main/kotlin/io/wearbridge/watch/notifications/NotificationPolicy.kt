package io.wearbridge.watch.notifications

import io.wearbridge.ancs.Category

/** What appears on the wrist (§9). Filtered-out notifications still go to the inbox, quietly. */
enum class FilterMode(val label: String) {
    ALL("All notifications"),
    IMPORTANT("Important only"),
    MESSAGES_AND_CALLS("Messages & calls"),
    CALLS_ONLY("Calls only"),
}

/** Per-app rule. [hideContent] null means use the default for the app's kind (finance apps are hidden). */
data class AppRule(
    val enabled: Boolean = true,
    val vibrate: Boolean = true,
    val hideContent: Boolean? = null,
)

data class PolicyDecision(
    /** Store it in the inbox at all. */
    val keep: Boolean,
    /** Post it as a watch notification (pop-up and notification list). */
    val surface: Boolean,
    /** Allowed to vibrate (alerting still requires a fresh, non-silent event). */
    val vibrate: Boolean,
    /** Hide title and body until the user explicitly reveals them on the watch. */
    val mask: Boolean,
)

/** Pure decision logic, so it's unit-testable without Android. */
object NotificationPolicy {
    private val CALL_CATEGORIES = setOf(Category.INCOMING_CALL, Category.ACTIVE_CALL, Category.MISSED_CALL, Category.VOICEMAIL)

    fun decide(category: Category, isImportant: Boolean, kind: AppKind, rule: AppRule, mode: FilterMode): PolicyDecision {
        if (!rule.enabled) return PolicyDecision(keep = false, surface = false, vibrate = false, mask = false)
        val isCall = category in CALL_CATEGORIES
        val surface = when (mode) {
            FilterMode.ALL -> true
            FilterMode.IMPORTANT -> isCall || isImportant
            FilterMode.MESSAGES_AND_CALLS -> isCall || kind == AppKind.MESSAGING || kind == AppKind.EMAIL ||
                category == Category.SOCIAL || category == Category.EMAIL
            FilterMode.CALLS_ONLY -> isCall
        }
        val defaultMask = kind == AppKind.FINANCE || category == Category.BUSINESS_AND_FINANCE
        // A caller's name is needed to decide whether to answer, so calls are never masked.
        val mask = !isCall && (rule.hideContent ?: defaultMask)
        return PolicyDecision(keep = true, surface = surface, vibrate = rule.vibrate, mask = mask)
    }
}
