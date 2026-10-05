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

    private val _shakeToDecline = MutableStateFlow(prefs.getBoolean(KEY_SHAKE_DECLINE, true))
    /** Shake the wrist to decline a ringing call. On by default; the detector needs a deliberate shake. */
    val shakeToDecline: StateFlow<Boolean> = _shakeToDecline

    private val _shakeToEnd = MutableStateFlow(prefs.getBoolean(KEY_SHAKE_END, false))
    /** Shake to end a connected call. Off by default: ending a call by accident is worse. Needs End Call. */
    val shakeToEnd: StateFlow<Boolean> = _shakeToEnd

    private val _bezelToAnswer = MutableStateFlow(prefs.getBoolean(KEY_BEZEL_ANSWER, true))
    /** Turn the bezel 3 clicks: clockwise answers, anticlockwise declines. */
    val bezelToAnswer: StateFlow<Boolean> = _bezelToAnswer

    fun setShakeToDecline(on: Boolean) { prefs.edit().putBoolean(KEY_SHAKE_DECLINE, on).apply(); _shakeToDecline.value = on }
    fun setShakeToEnd(on: Boolean) { prefs.edit().putBoolean(KEY_SHAKE_END, on).apply(); _shakeToEnd.value = on }
    fun setBezelToAnswer(on: Boolean) { prefs.edit().putBoolean(KEY_BEZEL_ANSWER, on).apply(); _bezelToAnswer.value = on }

    private val _history = MutableStateFlow(prefs.getBoolean(KEY_HISTORY, false))

    /**
     * Keep notifications for 24 hours after the iPhone clears them (in memory only). Off by default:
     * the inbox then shows exactly what's on the iPhone right now. Use [io.wearbridge.watch.BridgeGraph.setHistory].
     */
    val history: StateFlow<Boolean> = _history

    internal fun setHistoryFlag(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_HISTORY, enabled).apply()
        _history.value = enabled
    }

    private val _popupStyle = MutableStateFlow(loadPopupStyle())

    /**
     * Unknown stored values (e.g. the removed "BANNER" style) mean Standard. The pre-enum boolean is
     * migrated once and then deleted, so it can never override a later choice (found on hardware:
     * a stale "subtle" flag silently turned pop-ups off).
     */
    private fun loadPopupStyle(): PopupStyle {
        val stored = prefs.getString(KEY_POPUP, null)
        val style = PopupStyle.entries.firstOrNull { it.name == stored }
            ?: if (stored == null && prefs.getBoolean(KEY_SUBTLE_LEGACY, false)) PopupStyle.SUBTLE else PopupStyle.FULL
        prefs.edit().putString(KEY_POPUP, style.name).remove(KEY_SUBTLE_LEGACY).apply()
        return style
    }

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
        const val KEY_HISTORY = "history"
        const val KEY_SHAKE_DECLINE = "shake_decline"
        const val KEY_SHAKE_END = "shake_end"
        const val KEY_BEZEL_ANSWER = "bezel_answer"
    }
}

/**
 * Pop-up presentation on One UI Watch, verified on hardware 2026-10-05. Samsung's system UI picks the
 * style by what's in front: over the watch face it always uses its full-screen pop-up (even with
 * the screen on), and on top of an open app it uses its small top pop-up. Anything that alerts
 * (vibrates) gets a pop-up. An app can't choose the style, and waking the screen doesn't change it
 * (that lands on the watch face), so no "banner" option is offered.
 */
enum class PopupStyle(val label: String, val description: String) {
    FULL("Standard", "Pops up and vibrates"),
    SUBTLE("Subtle", "Vibrates, no pop-up"),
}
