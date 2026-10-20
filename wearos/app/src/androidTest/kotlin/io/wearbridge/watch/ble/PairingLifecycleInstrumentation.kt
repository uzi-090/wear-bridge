package io.wearbridge.watch.ble

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import java.util.UUID

/** Real SharedPreferences commit/reload checks, isolated from the user's pairing and trusted keys. */
class PairingLifecycleInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val prefix = "pairing-lifecycle-test-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(targetContext) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences(prefix + name, mode)
        }
        val result = Bundle()
        try {
            val paired = PairingStore(isolated, false)
            paired.set(paired.state.value.begin().approved("old-phone"))
            paired.beginRemoval(true)
            // Simulate death before cleanup, with residual identity trust still on disk.
            val interrupted = PairingStore(isolated, true)
            check(interrupted.cleanupPending.value)
            check(interrupted.pendingRemovalForget == true)
            check(interrupted.state.value.address == null)
            check(!interrupted.state.value.enabled)
            interrupted.completeRemoval(true, cleanupSucceeded = false)
            val failedCleanup = PairingStore(isolated, true)
            check(failedCleanup.cleanupPending.value)
            check(!failedCleanup.state.value.enabled)
            failedCleanup.completeRemoval(true, cleanupSucceeded = true)
            val forgotten = PairingStore(isolated, false)
            check(!forgotten.cleanupPending.value && forgotten.pendingRemovalForget == null)
            check(forgotten.state.value.address == null)
            forgotten.set(forgotten.state.value.begin())
            check(!forgotten.state.value.canAccess(LinkSnapshot(LinkState.READY)))
            forgotten.set(forgotten.state.value.approved("replacement-phone"))
            val replacement = PairingStore(isolated, true)
            check(replacement.state.value.address == "replacement-phone")
            check(replacement.state.value.canAccess(LinkSnapshot(LinkState.READY)))
            check(!replacement.state.value.accepts("old-phone"))
            replacement.beginRemoval(false)
            val interruptedUnpair = PairingStore(isolated, true)
            check(interruptedUnpair.cleanupPending.value)
            check(interruptedUnpair.pendingRemovalForget == false)
            check(interruptedUnpair.state.value.address == "replacement-phone")
            check(!interruptedUnpair.state.value.enabled)
            interruptedUnpair.completeRemoval(false, true)
            val unpaired = PairingStore(isolated, false)
            check(!unpaired.cleanupPending.value)
            check(unpaired.state.value.address == "replacement-phone")
            check(!unpaired.state.value.accepts("replacement-phone"))
            result.putString("stream", "PASS: interrupted Forget, failed-cleanup retry, replacement pairing, and interrupted Unpair persisted correctly in isolated watch storage.\n")
            finish(-1, result)
        } catch (failure: Throwable) {
            result.putString("stream", "FAIL: ${failure::class.simpleName}: ${failure.message}\n")
            finish(0, result)
        } finally {
            targetContext.deleteSharedPreferences(prefix + "device_pairing")
        }
    }
}
