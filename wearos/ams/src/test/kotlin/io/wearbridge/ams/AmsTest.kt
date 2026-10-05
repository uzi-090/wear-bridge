package io.wearbridge.ams

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmsTest {

    private fun update(entity: Int, attribute: Int, value: String, truncated: Boolean = false) =
        byteArrayOf(entity.toByte(), attribute.toByte(), (if (truncated) 1 else 0).toByte()) + value.toByteArray()

    @Test
    fun parsesEntityUpdateWithUnicode() {
        val u = EntityUpdate.parse(update(EntityId.TRACK, TrackAttribute.TITLE, "Café ☕ 日本", truncated = true))!!
        assertEquals(EntityId.TRACK, u.entity)
        assertEquals(TrackAttribute.TITLE, u.attribute)
        assertTrue(u.truncated)
        assertEquals("Café ☕ 日本", u.value)
    }

    @Test
    fun emptyValueIsAllowedAndShortPacketsAreRejected() {
        assertEquals("", EntityUpdate.parse(update(EntityId.PLAYER, PlayerAttribute.NAME, ""))!!.value)
        assertNull(EntityUpdate.parse(byteArrayOf(0, 1)))
    }

    @Test
    fun encodesRegistrationAndCommands() {
        assertArrayEquals(byteArrayOf(2, 0, 1, 2, 3), AmsCommands.register(EntityId.TRACK, 0, 1, 2, 3))
        assertArrayEquals(byteArrayOf(2), AmsCommands.command(RemoteCommand.TOGGLE_PLAY_PAUSE))
        assertArrayEquals(byteArrayOf(2, 2), AmsCommands.selectAttribute(EntityId.TRACK, TrackAttribute.TITLE))
    }

    @Test
    fun parsesSupportedCommandsIgnoringUnknownIds() {
        assertEquals(
            setOf(RemoteCommand.PLAY, RemoteCommand.NEXT_TRACK, RemoteCommand.VOLUME_UP),
            AmsCommands.parseSupported(byteArrayOf(0, 3, 5, 99.toByte())),
        )
    }

    @Test
    fun parsesPlaybackInfo() {
        assertEquals(PlaybackInfo(PlaybackState.PLAYING, 1.0, 12.5), PlaybackInfo.parse("1,1.0,12.5"))
        assertEquals(PlaybackState.PAUSED, PlaybackInfo.parse("0,0.0,3")!!.state)
        assertNull(PlaybackInfo.parse(""))
    }

    @Test
    fun nowPlayingTracksStateAndExtrapolatesPosition() {
        var np = NowPlaying()
        assertFalse(np.hasPlayer)
        np = np.apply(EntityUpdate.parse(update(0, 0, "Music"))!!, 0)
        np = np.apply(EntityUpdate.parse(update(2, 2, "Song"))!!, 0)
        np = np.apply(EntityUpdate.parse(update(2, 3, "200.5"))!!, 0)
        np = np.apply(EntityUpdate.parse(update(0, 1, "1,1.0,10"))!!, 1_000)
        np = np.apply(EntityUpdate.parse(update(0, 2, "0.75"))!!, 1_000)
        assertTrue(np.hasPlayer && np.isPlaying)
        assertEquals(0.75f, np.volume)
        assertEquals(15.0, np.positionSeconds(6_000), 0.001)
        assertEquals(200.5, np.positionSeconds(10_000_000), 0.001) // clamped to duration
        np = np.apply(EntityUpdate.parse(update(0, 1, "0,0.0,20"))!!, 7_000)
        assertEquals(20.0, np.positionSeconds(60_000), 0.001) // paused: no drift
    }

    @Test
    fun truncatedAttributesAreTrackedUntilTheFullValueArrives() {
        var np = NowPlaying().apply(EntityUpdate.parse(update(2, 2, "Very long ti", truncated = true))!!, 0)
        assertEquals(setOf(2 to 2), np.truncated)
        np = np.withFullValue(2, 2, "Very long title indeed", 0)
        assertTrue(np.truncated.isEmpty())
        assertEquals("Very long title indeed", np.title)
    }

    @Test
    fun toStringNeverContainsTrackData() {
        val np = NowPlaying(title = "Secret Song", artist = "Someone")
        assertFalse(np.toString().contains("Secret"))
    }
}
