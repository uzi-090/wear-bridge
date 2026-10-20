package io.wearbridge.ams

import org.junit.Assert.*
import org.junit.Test

class MediaCapabilitiesTest {
    @Test fun selectsStateAppropriateCommandWithoutToggleSupport() {
        val n = NowPlaying(playerName = "Player", supported = setOf(RemoteCommand.PLAY, RemoteCommand.PAUSE), commandsKnown = true)
        assertEquals(RemoteCommand.PLAY, n.playPauseCommand)
        assertEquals(RemoteCommand.PAUSE, n.copy(state = PlaybackState.PLAYING).playPauseCommand)
    }
    @Test fun explicitlyEmptyCommandsDisableControls() {
        val n = NowPlaying(playerName = "Player", commandsKnown = true)
        assertNull(n.playPauseCommand)
        assertFalse(n.can(RemoteCommand.NEXT_TRACK))
        assertFalse(n.hasMediaSession)
    }
    @Test fun volumeOnlyPlayerDoesNotRetainPlaybackSurface() {
        val n = NowPlaying(playerName = "Music", title = "Old track", commandsKnown = true,
            supported = setOf(RemoteCommand.VOLUME_UP, RemoteCommand.VOLUME_DOWN))
        assertFalse(n.hasMediaSession)
    }
    @Test fun playerChangeDiscardsPreviousMetadataAndProgress() {
        val n = NowPlaying(playerName = "First", title = "Old", artist = "Old artist", durationSeconds = 120.0, elapsedSeconds = 40.0)
        val next = n.apply(EntityUpdate(EntityId.PLAYER, PlayerAttribute.NAME, false, "Second"), 100)
        assertEquals("", next.title); assertEquals("", next.artist)
        assertEquals(0.0, next.durationSeconds, 0.0); assertEquals(0.0, next.elapsedSeconds, 0.0)
    }
    @Test fun emptyPlayerClearsStaleTrack() {
        val n = NowPlaying(playerName = "First", title = "Old")
        assertFalse(n.apply(EntityUpdate(EntityId.PLAYER, PlayerAttribute.NAME, false, ""), 100).hasPlayer)
    }
}
