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

    private val _popupStyle = MutableStateFlow(
        PopupStyle.entries.firstOrNull { it.name == prefs.getString(KEY_POPUP, null) }
            ?: if (prefs.getBoolean(KEY_SUBTLE_LEGACY, false)) PopupStyle.SUBTLE else PopupStyle.BANNER,
    )

    /** How alerting notifications appear. See [PopupStyle]. */
    val popupStyle: StateFlow<PopupStyle> = _popupStyle

    fun setPopupStyle(style: PopupStyle) {
        prefs.edit().putString(KEY_POPUP, style.name).apply()
        _popupStyle.value = style
    }

    private companion object {
        const val KEY_END_CALL = "experimental_end_call"
        const val KEY_POPUP = "popup_style"
        const val KEY_SUBTLE_LEGACY = "subtle_popups"
    }
}

/**
 * Pop-up presentation on One UI Watch, verified on hardware 2026-10-05. Samsung's system UI picks its
 * small top pop-up when the screen is already on as the notification is posted, and its full-screen
 * pop-up when the screen is off. There's no API to choose the style directly.
 */
enum class PopupStyle(val label: String, val description: String) {
    BANNER("Banner", "Wakes the screen and shows a small pop-up at the top"),
    FULL("Full screen", "Samsung's full-screen pop-up"),
    SUBTLE("Subtle", "Vibrates and shows a dot, with no pop-up"),
}
