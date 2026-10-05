package io.wearbridge.watch.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs the retry timer the state machine asks for. The backoff policy itself lives in
 * [ConnectionStateMachine]. There's only ever one pending retry, and nothing polls or spins:
 * between retries the watch only advertises (or waits for iOS) at low power.
 */
class ReconnectionManager(private val scope: CoroutineScope, private val onRetry: () -> Unit) {
    private var job: Job? = null

    fun schedule(delayMs: Long) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            onRetry()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}
