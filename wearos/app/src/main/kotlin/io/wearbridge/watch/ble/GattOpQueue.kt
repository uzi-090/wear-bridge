package io.wearbridge.watch.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Serializes GATT client operations. Android allows one outstanding operation per BluetoothGatt,
 * and callbacks don't say which request they answer.
 *
 * [run] starts an operation and suspends until the matching callback calls [complete], or it times out.
 * [complete] is thread-safe and may be called directly from Binder callback threads.
 */
class GattOpQueue {
    enum class Kind { CONNECT, MTU, DISCOVER, WRITE_DESCRIPTOR, WRITE_CHARACTERISTIC, READ_CHARACTERISTIC }

    /** [value] is set for reads. */
    class Result(val status: Int, val value: ByteArray? = null) {
        val ok: Boolean get() = status == 0
    }

    private class Pending(val kind: Kind, val deferred: CompletableDeferred<Result>)

    private val mutex = Mutex()

    @Volatile
    private var pending: Pending? = null

    suspend fun run(kind: Kind, timeoutMs: Long = DEFAULT_TIMEOUT_MS, start: () -> Boolean): Result = mutex.withLock {
        val p = Pending(kind, CompletableDeferred())
        pending = p
        try {
            if (!start()) return@withLock Result(STATUS_NOT_STARTED)
            withTimeoutOrNull(timeoutMs) { p.deferred.await() } ?: Result(STATUS_TIMEOUT)
        } finally {
            pending = null
        }
    }

    fun complete(kind: Kind, status: Int, value: ByteArray? = null) {
        val p = pending ?: return
        if (p.kind == kind) p.deferred.complete(Result(status, value))
    }

    /** Fails whatever is outstanding, e.g. on disconnect. */
    fun failAll() {
        pending?.deferred?.complete(Result(STATUS_DISCONNECTED))
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val STATUS_NOT_STARTED = -1
        const val STATUS_TIMEOUT = -2
        const val STATUS_DISCONNECTED = -3
    }
}
