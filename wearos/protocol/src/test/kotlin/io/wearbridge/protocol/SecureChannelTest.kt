package io.wearbridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The expected values were produced by Apple's CryptoKit (the iPhone side's implementation), so passing
 * here proves the watch and the iPhone derive identical keys, codes and frames. The same vectors are
 * in ios/PairingPOCTests/BridgeCryptoTests.swift.
 */
class SecureChannelTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private val dI = hex("1f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a79881f2e3d4c5b6a7988")
    private val dW = hex("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
    private val pubI = hex("04bd7c73b88b2e9b4ceda62022b2da8be13193a5b56edc26e7df7842e24cd0b5eb0605ada7bda83ac6a2b80d7e314040fa47ff16b83bac85cedb014451bb7ce71a")
    private val pubW = hex("04515c3d6eb9e396b904d3feca7f54fdcd0cc1e997bf375dca515ad0a6c3b4035f4536be3a50f318fbf9a5475902a221502bef0d57e08c53b2cc0a56f17d9f9354")
    private val staticKey = hex("72a7e2f45c8da9bbb854daa696adacc54a738a34d788da5347d17afecbd2c2e6")
    private val nonceI = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
    private val nonceW = hex("b0b1b2b3b4b5b6b7b8b9babbbcbdbebf")

    @Test
    fun `ECDH from either side matches CryptoKit`() {
        val fromWatch = BridgeCrypto.staticKey(BridgeCrypto.ecdh(BridgeCrypto.privateKeyFromScalar(dW), BridgeCrypto.decodePublicKey(pubI)))
        val fromIphone = BridgeCrypto.staticKey(BridgeCrypto.ecdh(BridgeCrypto.privateKeyFromScalar(dI), BridgeCrypto.decodePublicKey(pubW)))
        assertArrayEquals(staticKey, fromWatch)
        assertArrayEquals(staticKey, fromIphone)
    }

    @Test
    fun `public keys round-trip in X9_63 form`() {
        assertArrayEquals(pubW, BridgeCrypto.encodePublicKey(BridgeCrypto.decodePublicKey(pubW)))
        val kp = BridgeCrypto.newKeyPair()
        assertEquals(65, BridgeCrypto.encodePublicKey(kp.public).size)
    }

    @Test
    fun `code, MACs and session keys match CryptoKit`() {
        assertEquals("874561", BridgeCrypto.pairingCode(staticKey, pubI, pubW))
        assertArrayEquals(hex("36483b7c836cf6e39d9fe2866d4fab3a"), BridgeCrypto.helloMac(staticKey, nonceI))
        assertArrayEquals(hex("bb1818177b9eef1094d227d276822ae5"), BridgeCrypto.okMac(staticKey, nonceI, nonceW))
        val (i2w, w2i) = BridgeCrypto.sessionKeys(staticKey, nonceI, nonceW)
        assertArrayEquals(hex("8b836042d868cbe31764f4a5ea3756ff"), i2w)
        assertArrayEquals(hex("d503c62d301bd9f04092a497f396e743"), w2i)
    }

    @Test
    fun `iPhone frame sealed by CryptoKit opens on the watch, and ours is byte-identical`() {
        val keys = BridgeCrypto.sessionKeys(staticKey, nonceI, nonceW)
        val cryptoKitFrame = hex("000000014c0eaaf23adca56a973cfe7ab1293541af")
        assertArrayEquals(byteArrayOf(2), SecureSession.forWatch(keys).open(MessageType.SET_MODE, cryptoKitFrame))
        assertArrayEquals(cryptoKitFrame, SecureSession.forIphone(keys).seal(MessageType.SET_MODE, byteArrayOf(2)))
    }

    @Test
    fun `replay, reorder, tampering and type confusion are rejected`() {
        val keys = BridgeCrypto.sessionKeys(staticKey, nonceI, nonceW)
        val phone = SecureSession.forIphone(keys)
        val watch = SecureSession.forWatch(keys)
        val m1 = phone.seal(MessageType.SET_MODE, byteArrayOf(1))
        val m2 = phone.seal(MessageType.SET_MODE, byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), watch.open(MessageType.SET_MODE, m2))
        assertNull("older seq after a newer one", watch.open(MessageType.SET_MODE, m1))
        assertNull("replay", watch.open(MessageType.SET_MODE, m2))
        val m3 = phone.seal(MessageType.SET_MODE, byteArrayOf(3))
        assertNull("moved to another message type", watch.open(MessageType.SET_PREFS, m3))
        val tampered = m3.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertNull("bit flip", watch.open(MessageType.SET_MODE, tampered))
        assertArrayEquals(byteArrayOf(3), watch.open(MessageType.SET_MODE, m3))
        assertNull("too short", watch.open(MessageType.SET_MODE, ByteArray(10)))
    }

    @Test
    fun `directions use different keys, so a watch frame can't be reflected back`() {
        val keys = BridgeCrypto.sessionKeys(staticKey, nonceI, nonceW)
        val watch = SecureSession.forWatch(keys)
        val fromWatch = watch.seal(MessageType.SETTINGS, byteArrayOf(9))
        assertNull(watch.open(MessageType.SETTINGS, fromWatch))
        assertArrayEquals(byteArrayOf(9), SecureSession.forIphone(keys).open(MessageType.SETTINGS, fromWatch))
    }

    @Test
    fun `a wrong key can't produce a valid hello`() {
        val other = BridgeCrypto.staticKey(BridgeCrypto.ecdh(BridgeCrypto.newKeyPair().private, BridgeCrypto.decodePublicKey(pubW)))
        assertEquals(false, BridgeCrypto.constantTimeEquals(BridgeCrypto.helloMac(other, nonceI), BridgeCrypto.helloMac(staticKey, nonceI)))
    }
}
