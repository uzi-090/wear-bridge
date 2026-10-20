package io.wearbridge.watch.ble

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PairingStore(context: Context, legacyApproved: Boolean) {
    private val prefs = context.getSharedPreferences("device_pairing", Context.MODE_PRIVATE)
    val pendingRemovalForget: Boolean? get() = if (prefs.contains("removal_forget")) prefs.getBoolean("removal_forget", false) else null
    private val cleanupFlow = MutableStateFlow(pendingRemovalForget != null || prefs.getString("phase", null) == PairingPhase.REMOVING.name)
    val cleanupPending: StateFlow<Boolean> = cleanupFlow
    // An interrupted removal must never restore pairing; an interrupted setup requires a fresh tap.
    private val flow = MutableStateFlow(DevicePairing.restore(
        prefs.getString("phase", null), prefs.getString("address", null), legacyApproved, pendingRemovalForget))
    val state: StateFlow<DevicePairing> = flow
    private val noticeFlow = MutableStateFlow<String?>(prefs.getString("notice", null))
    val notice: StateFlow<String?> = noticeFlow
    fun setNotice(message: String?) {
        prefs.edit().putString("notice", message).apply()
        noticeFlow.value = message
    }
    init {
        if (cleanupFlow.value && pendingRemovalForget == null) {
            check(prefs.edit().putBoolean("removal_forget", false).commit())
        }
        set(flow.value)
    }
    fun beginRemoval(forget: Boolean) {
        val value = state.value.removing().let { if (forget) it.copy(address = null) else it }
        flow.value = value
        cleanupFlow.value = true
        check(prefs.edit().putBoolean("removal_forget", forget).putString("phase", value.phase.name)
            .putString("address", value.address).commit())
    }
    fun completeRemoval(forget: Boolean, cleanupSucceeded: Boolean) {
        val value = state.value.removed(forget)
        val edit = prefs.edit().putString("phase", value.phase.name).putString("address", value.address)
        if (cleanupSucceeded) edit.remove("removal_forget")
        check(edit.commit())
        flow.value = value
        cleanupFlow.value = !cleanupSucceeded
    }
    fun set(value: DevicePairing) {
        check(prefs.edit().putString("phase", value.phase.name).putString("address", value.address).commit())
        flow.value = value
    }
}
