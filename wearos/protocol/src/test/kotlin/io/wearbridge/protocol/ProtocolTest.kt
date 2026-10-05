package io.wearbridge.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    private val snapshot = SettingsSnapshot(
        mode = ModeCode.MESSAGES_AND_CALLS,
        apps = listOf(
            AppEntry("net.whatsapp.WhatsApp", "‎WhatsApp", true, true, null, false, 0xFF25D366, "whatsapp", hasIcon = true),
            AppEntry("com.kotak811.x", "Kotak 811", true, false, null, true, 0xFFED1C24, ""),
            AppEntry("com.example.مرحبا", "تطبيق 🙂", false, true, true, false, 0xFF5B8DEF, ""),
        ),
    )

    @Test
    fun `snapshot round-trips, including Unicode and tri-state`() {
        assertEquals(snapshot, SettingsCodec.decodeSnapshot(SettingsCodec.encodeSnapshot(snapshot)))
    }

    @Test
    fun `snapshot with watch prefs round-trips, and older snapshots decode without them`() {
        val withPrefs = snapshot.copy(prefs = WatchPrefs(PopupCode.SUBTLE, history = true, endCall = false))
        assertEquals(withPrefs, SettingsCodec.decodeSnapshot(SettingsCodec.encodeSnapshot(withPrefs)))
        assertEquals(null, SettingsCodec.decodeSnapshot(SettingsCodec.encodeSnapshot(snapshot)).prefs)
        val p = WatchPrefs(PopupCode.STANDARD, history = false, endCall = true)
        assertEquals(p, SettingsCodec.decodeSetPrefs(SettingsCodec.encodeSetPrefs(p)))
    }

    @Test
    fun `set app rule and mode round-trip`() {
        val rule = SetAppRule("net.whatsapp.WhatsApp", enabled = true, vibrate = false, hideContent = false)
        assertEquals(rule, SettingsCodec.decodeSetAppRule(SettingsCodec.encodeSetAppRule(rule)))
        assertEquals(ModeCode.CALLS_ONLY, SettingsCodec.decodeSetMode(SettingsCodec.encodeSetMode(ModeCode.CALLS_ONLY)))
    }

    @Test
    fun `app icon message round-trips and splits`() {
        val image = ByteArray(5_000) { (it * 31).toByte() }
        val payload = SettingsCodec.encodeSetAppIcon("com.appflipkart.flipkart", image)
        val (id, bytes) = SettingsCodec.decodeSetAppIcon(payload)
        assertEquals("com.appflipkart.flipkart", id)
        assertArrayEquals(image, bytes)
        val r = Reassembler()
        val done = Framing.split(MessageType.SET_APP_ICON, 3, payload, 290).map(r::accept).last() as Reassembler.Result.Complete
        assertArrayEquals(payload, done.message.payload)
    }

    @Test
    fun `long strings are cut on a character boundary`() {
        val s = "é".repeat(200) // 400 bytes
        val cut = SettingsCodec.utf8Prefix(s, 255)
        assertEquals(254, cut.size)
        assertEquals("é".repeat(127), String(cut, Charsets.UTF_8))
    }

    @Test
    fun `split and reassemble at every chunk size`() {
        val payload = SettingsCodec.encodeSnapshot(snapshot)
        for (chunkSize in listOf(5, 20, 23, 64, 182, 512)) {
            val chunks = Framing.split(MessageType.SETTINGS, 7, payload, chunkSize)
            assertTrue(chunks.all { it.size <= chunkSize })
            val r = Reassembler()
            val results = chunks.map(r::accept)
            results.dropLast(1).forEach { assertEquals(Reassembler.Result.Incomplete, it) }
            val done = results.last() as Reassembler.Result.Complete
            assertEquals(MessageType.SETTINGS, done.message.type)
            assertEquals(7, done.message.messageId)
            assertArrayEquals(payload, done.message.payload)
        }
    }

    @Test
    fun `empty payload is a single chunk`() {
        val chunks = Framing.split(MessageType.GET_SETTINGS, 1, ByteArray(0), 20)
        assertEquals(1, chunks.size)
        val done = Reassembler().accept(chunks.single()) as Reassembler.Result.Complete
        assertEquals(0, done.message.payload.size)
    }

    @Test
    fun `a new message abandons an unfinished one`() {
        val r = Reassembler()
        val first = Framing.split(MessageType.SETTINGS, 1, ByteArray(100), 20)
        r.accept(first[0])
        val second = Framing.split(MessageType.SET_MODE, 2, byteArrayOf(3), 20).single()
        val done = r.accept(second) as Reassembler.Result.Complete
        assertEquals(MessageType.SET_MODE, done.message.type)
    }

    @Test
    fun `wrong version and oversize are rejected`() {
        val chunk = Framing.split(MessageType.SET_MODE, 1, byteArrayOf(0), 20).single().also { it[0] = 9 }
        assertTrue(Reassembler().accept(chunk) is Reassembler.Result.Error)
        val big = Framing.split(MessageType.SETTINGS, 1, ByteArray(200), 64)
        val r = Reassembler(maxMessageBytes = 100)
        assertTrue(big.map(r::accept).any { it is Reassembler.Result.Error })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `truncated snapshot fails loudly`() {
        val bytes = SettingsCodec.encodeSnapshot(snapshot)
        SettingsCodec.decodeSnapshot(bytes.copyOf(bytes.size - 3))
    }
}
