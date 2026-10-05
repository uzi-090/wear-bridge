package io.wearbridge.watch.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Small on-watch preferences. Nothing here is personal data. */
class BridgeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("bridge_settings", Context.MODE_PRIVATE)

    private val _endCallFromWatch = MutableStateFlow(prefs.getBoolean(KEY_END_CALL, false))

    /**
     * Experimental and off by default. iOS exposes "End Call" through an ANCS category that isn't in
     * Apple's published spec (observed: 12), so an iOS update could change or remove it.
     */
    val endCallFromWatch: StateFlow<Boolean> = _endCallFromWatch

    fun setEndCallFromWatch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_END_CALL, enabled).apply()
        _endCallFromWatch.value = enabled
    }

    private val _subtlePopups = MutableStateFlow(prefs.getBoolean(KEY_SUBTLE, false))

    /**
     * Post at default importance instead of high. On One UI Watch, high importance gets Samsung's
     * full-screen popup (when the screen was off). Default importance vibrates and shows the dot.
     * The popup style itself belongs to Samsung's system UI: apps can't choose it.
     */
    val subtlePopups: StateFlow<Boolean> = _subtlePopups

    fun setSubtlePopups(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SUBTLE, enabled).apply()
        _subtlePopups.value = enabled
    }

    private companion object {
        const val KEY_END_CALL = "experimental_end_call"
        const val KEY_SUBTLE = "subtle_popups"
    }
}
