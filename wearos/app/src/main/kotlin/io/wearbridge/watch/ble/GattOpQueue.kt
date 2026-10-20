package io.wearbridge.watch.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** One queue per GATT session. A timed-out or cancelled operation invalidates that session:
 * Android callbacks have no request ID, so retrying the same attribute would be ambiguous. */
class GattOpQueue(private val onInvalidated: () -> Unit = {}) {
    enum class Kind { CONNECT, MTU, DISCOVER, WRITE_DESCRIPTOR, WRITE_CHARACTERISTIC, READ_CHARACTERISTIC }
    class Result(val status: Int, val value: ByteArray? = null) {
        val ok: Boolean get() = status == 0
    }
    private class Pending(val kind: Kind, val target: String?, val deferred: CompletableDeferred<Result>)
    private val mutex = Mutex()
    @Volatile private var pending: Pending? = null
    @Volatile private var invalidated = false

    suspend fun run(kind: Kind, timeoutMs: Long = DEFAULT_TIMEOUT_MS, target: String? = null, start: () -> Boolean): Result = mutex.withLock {
        if (invalidated) return@withLock Result(STATUS_DISCONNECTED)
        val p = Pending(kind, target, CompletableDeferred())
        pending = p
        var started = false
        var completed = false
        try {
            started = start()
            if (!started) return@withLock Result(STATUS_NOT_STARTED)
            val result = withTimeoutOrNull(timeoutMs) { p.deferred.await() }
            completed = result != null
            result ?: Result(STATUS_TIMEOUT)
        } finally {
            pending = null
            if (started && !completed) {
                invalidated = true
                onInvalidated()
            }
        }
    }

    fun complete(kind: Kind, status: Int, value: ByteArray? = null, target: String? = null) {
        val p = pending ?: return
        if (!invalidated && p.kind == kind && p.target == target) p.deferred.complete(Result(status, value))
    }

    fun failAll() {
        invalidated = true
        pending?.deferred?.complete(Result(STATUS_DISCONNECTED))
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val STATUS_NOT_STARTED = -1
        const val STATUS_TIMEOUT = -2
        const val STATUS_DISCONNECTED = -3
    }
}
