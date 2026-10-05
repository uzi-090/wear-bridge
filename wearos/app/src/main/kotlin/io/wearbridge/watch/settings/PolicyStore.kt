package io.wearbridge.watch.settings

import android.content.Context
import io.wearbridge.watch.notifications.AppRule
import io.wearbridge.watch.notifications.FilterMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Filtering preferences (§9): the global mode, per-app rules, and the apps seen so far (bundle ID →
 * display name), so the user can configure an app without waiting for its next notification.
 * Local only. It stores no notification content.
 */
class PolicyStore(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_policy", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        FilterMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: FilterMode.ALL,
    )
    val mode: StateFlow<FilterMode> = _mode

    private val _rules = MutableStateFlow(loadRules())
    val rules: StateFlow<Map<String, AppRule>> = _rules

    private val _seenApps = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(SEEN) }.mapKeys { it.key.removePrefix(SEEN) }.mapValues { it.value as String },
    )
    val seenApps: StateFlow<Map<String, String>> = _seenApps

    fun setMode(mode: FilterMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    fun rule(bundleId: String?): AppRule = bundleId?.let { _rules.value[it] } ?: AppRule()

    fun setRule(bundleId: String, rule: AppRule) {
        prefs.edit().putString(RULE + bundleId, encode(rule)).apply()
        _rules.value = _rules.value + (bundleId to rule)
    }

    /** Records an app the first time it's seen, or when its name changes. Cheap no-op otherwise. */
    private val _mediaApps = MutableStateFlow(
        prefs.all.filterKeys { it.startsWith(MEDIA) }.mapKeys { it.key.removePrefix(MEDIA) }.mapValues { it.value as String },
    )
    /** Apps seen playing media (bundle ID → name), so the iPhone can send their App Store icons. */
    val mediaApps: StateFlow<Map<String, String>> = _mediaApps

    fun noteMediaApp(bundleId: String, name: String) {
        if (_mediaApps.value[bundleId] == name) return
        prefs.edit().putString(MEDIA + bundleId, name).apply()
        _mediaApps.value = _mediaApps.value + (bundleId to name)
    }

    fun noteApp(bundleId: String, name: String) {
        if (_seenApps.value[bundleId] == name) return
        prefs.edit().putString(SEEN + bundleId, name).apply()
        _seenApps.value = _seenApps.value + (bundleId to name)
    }

    private fun loadRules(): Map<String, AppRule> =
        prefs.all.filterKeys { it.startsWith(RULE) }
            .mapNotNull { (k, v) -> (v as? String)?.let(::decode)?.let { k.removePrefix(RULE) to it } }
            .toMap()

    // Compact encoding: "<enabled><vibrate><hide>", e.g. "11-" (hide = default), "100".
    private fun encode(r: AppRule) =
        "${if (r.enabled) 1 else 0}${if (r.vibrate) 1 else 0}${when (r.hideContent) { true -> 1; false -> 0; null -> "-" }}"

    private fun decode(s: String): AppRule? {
        if (s.length != 3) return null
        return AppRule(
            enabled = s[0] == '1',
            vibrate = s[1] == '1',
            hideContent = when (s[2]) { '1' -> true; '0' -> false; else -> null },
        )
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val RULE = "rule."
        const val SEEN = "seen."
        const val MEDIA = "media."
    }
}
