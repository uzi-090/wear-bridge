package io.wearbridge.protocol

import org.junit.Assert.*
import org.junit.Test

class DeviceLifecycleTest {
    @Test fun lifecycleRequestsAndAcknowledgementsMatchSwiftWireBytes() {
        for (forget in listOf(false, true)) for (ack in listOf(false, true)) {
            val message = BridgeCodec.Lifecycle(forget, ack)
            val bytes = BridgeCodec.encodeLifecycle(message)
            assertArrayEquals(byteArrayOf(if (forget) 2 else 1, if (ack) 1 else 0), bytes)
            assertEquals(message, BridgeCodec.decodeLifecycle(bytes))
        }
        assertEquals(0x14, BridgeMessageType.DEVICE_LIFECYCLE)
        assertEquals(0x93, BridgeMessageType.DEVICE_LIFECYCLE_EVENT)
    }
    @Test fun rejectsUnknownActionsAndTruncatedOrTrailingBytes() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(0, 0), byteArrayOf(3, 0), byteArrayOf(1, 2), byteArrayOf(1, 0, 0))) {
            assertThrows(IllegalArgumentException::class.java) { BridgeCodec.decodeLifecycle(bytes) }
        }
    }
    @Test fun authenticatedRemovalRoundTripsInBothDirectionsAtMinimumMtu() {
        for (forget in listOf(false, true)) for (initiatedByPhone in listOf(false, true)) {
            val keys = BridgeCrypto.sessionKeys(ByteArray(32) { 3 }, ByteArray(16) { 4 }, ByteArray(16) { 5 })
            val phone = SecureSession.forIphone(keys)
            val watch = SecureSession.forWatch(keys)
            val sender = if (initiatedByPhone) phone else watch
            val receiver = if (initiatedByPhone) watch else phone
            val requestType = if (initiatedByPhone) BridgeMessageType.DEVICE_LIFECYCLE else BridgeMessageType.DEVICE_LIFECYCLE_EVENT
            val ackType = if (initiatedByPhone) BridgeMessageType.DEVICE_LIFECYCLE_EVENT else BridgeMessageType.DEVICE_LIFECYCLE
            // Ordinary traffic preceding removal consumes sequence numbers, as on a real session.
            val earlier = sender.seal(BridgeMessageType.PING, byteArrayOf())
            assertNotNull(receiver.open(BridgeMessageType.PING, earlier))
            val request = BridgeCodec.Lifecycle(forget, false)
            val sealed = sender.seal(requestType, BridgeCodec.encodeLifecycle(request))
            val r = Reassembler()
            val result = Framing.split(requestType, 7, sealed, 20).map(r::accept).last() as Reassembler.Result.Complete
            val plain = receiver.open(result.message.type, result.message.payload)!!
            assertEquals(request, BridgeCodec.decodeLifecycle(plain))
            val ack = receiver.seal(ackType, BridgeCodec.encodeLifecycle(request.copy(acknowledgement = true)))
            assertEquals(request.copy(acknowledgement = true), BridgeCodec.decodeLifecycle(sender.open(ackType, ack)!!))
            assertNull(receiver.open(requestType, sealed)) // a duplicate removal cannot target a later pair
            assertNull(sender.open(ackType, ack))
        }
    }
    @Test fun replacementSessionRejectsOldDeviceRemovalAndAcknowledgement() {
        val oldKeys = BridgeCrypto.sessionKeys(ByteArray(32) { 1 }, ByteArray(16) { 2 }, ByteArray(16) { 3 })
        val newKeys = BridgeCrypto.sessionKeys(ByteArray(32) { 4 }, ByteArray(16) { 5 }, ByteArray(16) { 6 })
        val oldPhone = SecureSession.forIphone(oldKeys)
        val oldWatch = SecureSession.forWatch(oldKeys)
        val newPhone = SecureSession.forIphone(newKeys)
        val newWatch = SecureSession.forWatch(newKeys)
        val request = BridgeCodec.encodeLifecycle(BridgeCodec.Lifecycle(true, false))
        val ack = BridgeCodec.encodeLifecycle(BridgeCodec.Lifecycle(true, true))
        assertNull(newWatch.open(BridgeMessageType.DEVICE_LIFECYCLE, oldPhone.seal(BridgeMessageType.DEVICE_LIFECYCLE, request)))
        assertNull(newPhone.open(BridgeMessageType.DEVICE_LIFECYCLE_EVENT, oldWatch.seal(BridgeMessageType.DEVICE_LIFECYCLE_EVENT, ack)))
        assertArrayEquals(request, newWatch.open(BridgeMessageType.DEVICE_LIFECYCLE, newPhone.seal(BridgeMessageType.DEVICE_LIFECYCLE, request)))
    }
}
