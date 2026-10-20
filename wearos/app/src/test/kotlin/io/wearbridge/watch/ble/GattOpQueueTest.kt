package io.wearbridge.watch.ble

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class GattOpQueueTest {
    @Test fun timeoutPreventsLateCallbackFromSatisfyingAnotherRequest() = runBlocking {
        var invalidations = 0
        val queue = GattOpQueue { invalidations++ }
        assertEquals(GattOpQueue.STATUS_TIMEOUT, queue.run(GattOpQueue.Kind.READ_CHARACTERISTIC, 20, "battery") { true }.status)
        var started = false
        queue.complete(GattOpQueue.Kind.READ_CHARACTERISTIC, 0, byteArrayOf(42), "battery")
        assertEquals(GattOpQueue.STATUS_DISCONNECTED, queue.run(GattOpQueue.Kind.READ_CHARACTERISTIC) { started = true; true }.status)
        assertFalse(started)
        assertEquals(1, invalidations)
    }
    @Test fun wrongAttributeCallbackCannotCompletePendingRead() = runBlocking {
        val queue = GattOpQueue()
        val started = CompletableDeferred<Unit>()
        val pending = async { queue.run(GattOpQueue.Kind.READ_CHARACTERISTIC, 1000, "battery") { started.complete(Unit); true } }
        started.await()
        queue.complete(GattOpQueue.Kind.READ_CHARACTERISTIC, 0, byteArrayOf(1), "media")
        assertFalse(pending.isCompleted)
        queue.complete(GattOpQueue.Kind.READ_CHARACTERISTIC, 0, byteArrayOf(2), "battery")
        assertArrayEquals(byteArrayOf(2), pending.await().value)
    }
    @Test fun cancellationInvalidatesOutstandingSession() = runBlocking {
        val queue = GattOpQueue()
        val started = CompletableDeferred<Unit>()
        val pending = launch { queue.run(GattOpQueue.Kind.DISCOVER) { started.complete(Unit); true } }
        started.await(); pending.cancelAndJoin()
        assertEquals(GattOpQueue.STATUS_DISCONNECTED, queue.run(GattOpQueue.Kind.DISCOVER) { true }.status)
    }
    @Test fun notStartedDoesNotInvalidateSession() = runBlocking {
        val queue = GattOpQueue()
        assertEquals(GattOpQueue.STATUS_NOT_STARTED, queue.run(GattOpQueue.Kind.DISCOVER) { false }.status)
        assertEquals(0, queue.run(GattOpQueue.Kind.DISCOVER) { queue.complete(GattOpQueue.Kind.DISCOVER, 0); true }.status)
    }
    @Test fun disconnectFailsQueuedWorkWithoutStartingIt() = runBlocking {
        val queue = GattOpQueue()
        queue.failAll()
        assertEquals(GattOpQueue.STATUS_DISCONNECTED, queue.run(GattOpQueue.Kind.DISCOVER) { fail("must not start"); true }.status)
    }
}
