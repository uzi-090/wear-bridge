package io.wearbridge.ams

import java.util.UUID

/**
 * Apple Media Service (AMS), from Apple's published *Apple Media Service Specification*. iOS hosts it
 * for any bonded accessory: it exposes the system Now Playing state (Apple Music, Spotify, podcasts,
 * anything that uses MPNowPlayingInfoCenter) and accepts remote commands. No iPhone app is involved.
 */
object AmsUuids {
    val SERVICE: UUID = UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")
    /** write (one command ID byte) / notify (the list of commands the current player supports). */
    val REMOTE_COMMAND: UUID = UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")
    /** write-with-response (register for attributes) / notify (attribute values). */
    val ENTITY_UPDATE: UUID = UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")
    /** write (select an attribute) then read (its full, untruncated value). */
    val ENTITY_ATTRIBUTE: UUID = UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7")
}

enum class RemoteCommand(val id: Int) {
    PLAY(0), PAUSE(1), TOGGLE_PLAY_PAUSE(2), NEXT_TRACK(3), PREVIOUS_TRACK(4), VOLUME_UP(5), VOLUME_DOWN(6),
    ADVANCE_REPEAT_MODE(7), ADVANCE_SHUFFLE_MODE(8), SKIP_FORWARD(9), SKIP_BACKWARD(10),
    LIKE_TRACK(11), DISLIKE_TRACK(12), BOOKMARK_TRACK(13);

    companion object {
        fun fromId(id: Int): RemoteCommand? = entries.firstOrNull { it.id == id }
    }
}

object EntityId {
    const val PLAYER = 0
    const val QUEUE = 1
    const val TRACK = 2
}

object PlayerAttribute {
    const val NAME = 0
    /** "state,rate,elapsed", e.g. "1,1.0,12.345". */
    const val PLAYBACK_INFO = 1
    /** "0.0"–"1.0". */
    const val VOLUME = 2
}

object QueueAttribute {
    const val INDEX = 0
    const val COUNT = 1
    const val SHUFFLE_MODE = 2
    const val REPEAT_MODE = 3
}

object TrackAttribute {
    const val ARTIST = 0
    const val ALBUM = 1
    const val TITLE = 2
    /** Seconds, as a decimal string. */
    const val DURATION = 3
}

/** ATT errors AMS returns. */
object AmsError {
    const val INVALID_STATE = 0xA0
    const val COMMAND_NOT_SUPPORTED = 0xA1
    const val ABSENT_ATTRIBUTE = 0xA2
}

enum class PlaybackState { PAUSED, PLAYING, REWINDING, FAST_FORWARDING, UNKNOWN }

/** One Entity Update notification: | EntityID u8 | AttributeID u8 | Flags u8 (bit0 truncated) | UTF-8 value |. */
data class EntityUpdate(val entity: Int, val attribute: Int, val truncated: Boolean, val value: String) {
    companion object {
        fun parse(bytes: ByteArray): EntityUpdate? {
            if (bytes.size < 3) return null
            return EntityUpdate(
                entity = bytes[0].toInt() and 0xFF,
                attribute = bytes[1].toInt() and 0xFF,
                truncated = bytes[2].toInt() and 0x01 != 0,
                value = String(bytes, 3, bytes.size - 3, Charsets.UTF_8),
            )
        }
    }
}

object AmsCommands {
    /** Entity Update registration: the entity followed by the attributes wanted (one write per entity). */
    fun register(entity: Int, vararg attributes: Int): ByteArray =
        ByteArray(1 + attributes.size).also { b ->
            b[0] = entity.toByte()
            attributes.forEachIndexed { i, a -> b[i + 1] = a.toByte() }
        }

    /** Selects an attribute on Entity Attribute, before reading its full value. */
    fun selectAttribute(entity: Int, attribute: Int): ByteArray = byteArrayOf(entity.toByte(), attribute.toByte())

    fun command(c: RemoteCommand): ByteArray = byteArrayOf(c.id.toByte())

    /** A Remote Command notification lists the commands the active player supports, one byte each. */
    fun parseSupported(bytes: ByteArray): Set<RemoteCommand> =
        bytes.asList().mapNotNull { RemoteCommand.fromId(it.toInt() and 0xFF) }.toSet()
}

data class PlaybackInfo(val state: PlaybackState, val rate: Double, val elapsedSeconds: Double) {
    companion object {
        /** "state,rate,elapsed". Any part may be missing or empty when no player is active. */
        fun parse(value: String): PlaybackInfo? {
            val parts = value.split(',')
            if (parts.size < 3) return null
            val state = when (parts[0].trim().toIntOrNull()) {
                0 -> PlaybackState.PAUSED
                1 -> PlaybackState.PLAYING
                2 -> PlaybackState.REWINDING
                3 -> PlaybackState.FAST_FORWARDING
                else -> PlaybackState.UNKNOWN
            }
            return PlaybackInfo(state, parts[1].trim().toDoubleOrNull() ?: 0.0, parts[2].trim().toDoubleOrNull() ?: 0.0)
        }
    }
}

/**
 * The iPhone's Now Playing state as the watch knows it. [elapsedAtMillis] is the monotonic time the
 * elapsed value was reported, so the position can be extrapolated without polling the iPhone.
 */
data class NowPlaying(
    val playerName: String = "",
    val state: PlaybackState = PlaybackState.PAUSED,
    val rate: Double = 0.0,
    val elapsedSeconds: Double = 0.0,
    val elapsedAtMillis: Long = 0,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Double = 0.0,
    /** 0.0–1.0, or null until iOS reports it. */
    val volume: Float? = null,
    val supported: Set<RemoteCommand> = emptySet(),
    /** Distinguishes initial unknown capabilities from an explicitly empty command list. */
    val commandsKnown: Boolean = false,
    /** Attributes iOS truncated to fit a packet, as (entity, attribute). The client fetches the full value. */
    val truncated: Set<Pair<Int, Int>> = emptySet(),
) {
    /** iOS reports an empty player name when nothing has played since the last reset. */
    val hasPlayer: Boolean get() = playerName.isNotEmpty() || title.isNotEmpty()
    val isPlaying: Boolean get() = state == PlaybackState.PLAYING

    fun can(command: RemoteCommand): Boolean = hasPlayer &&
        (command in supported || (!commandsKnown && supported.isEmpty()))

    val playPauseCommand: RemoteCommand?
        get() = when {
            isPlaying && can(RemoteCommand.PAUSE) -> RemoteCommand.PAUSE
            !isPlaying && can(RemoteCommand.PLAY) -> RemoteCommand.PLAY
            can(RemoteCommand.TOGGLE_PLAY_PAUSE) -> RemoteCommand.TOGGLE_PLAY_PAUSE
            else -> null
        }

    val hasMediaSession: Boolean get() = hasPlayer && (isPlaying || playPauseCommand != null ||
        can(RemoteCommand.NEXT_TRACK) || can(RemoteCommand.PREVIOUS_TRACK))

    fun positionSeconds(nowMillis: Long): Double {
        val p = elapsedSeconds + if (isPlaying) rate * (nowMillis - elapsedAtMillis) / 1000.0 else 0.0
        return if (durationSeconds > 0) p.coerceIn(0.0, durationSeconds) else p.coerceAtLeast(0.0)
    }

    /** Applies one Entity Update. Pure, so it's unit-testable. Unknown attributes are ignored. */
    fun apply(u: EntityUpdate, nowMillis: Long): NowPlaying {
        val key = u.entity to u.attribute
        val next = when (u.entity) {
            EntityId.PLAYER -> when (u.attribute) {
                PlayerAttribute.NAME -> if (u.value != playerName && (playerName.isNotBlank() || u.value.isBlank())) {
                    copy(playerName = u.value, title = "", artist = "", album = "", durationSeconds = 0.0,
                        elapsedSeconds = 0.0, rate = 0.0, state = PlaybackState.PAUSED, truncated = emptySet())
                } else copy(playerName = u.value)
                PlayerAttribute.PLAYBACK_INFO -> PlaybackInfo.parse(u.value)?.let {
                    copy(state = it.state, rate = it.rate, elapsedSeconds = it.elapsedSeconds, elapsedAtMillis = nowMillis)
                } ?: copy(state = PlaybackState.PAUSED, rate = 0.0)
                PlayerAttribute.VOLUME -> copy(volume = u.value.trim().toFloatOrNull()?.coerceIn(0f, 1f))
                else -> this
            }
            EntityId.TRACK -> when (u.attribute) {
                TrackAttribute.ARTIST -> copy(artist = u.value)
                TrackAttribute.ALBUM -> copy(album = u.value)
                TrackAttribute.TITLE -> copy(title = u.value)
                TrackAttribute.DURATION -> copy(durationSeconds = u.value.trim().toDoubleOrNull() ?: 0.0)
                else -> this
            }
            else -> this
        }
        return next.copy(truncated = if (u.truncated) next.truncated + key else next.truncated - key)
    }

    /** The full value of a previously truncated attribute, read through Entity Attribute. */
    fun withFullValue(entity: Int, attribute: Int, value: String, nowMillis: Long): NowPlaying =
        apply(EntityUpdate(entity, attribute, truncated = false, value = value), nowMillis)

    // Track titles are personal listening data: keep them out of logs.
    override fun toString(): String = "NowPlaying(state=$state, hasPlayer=$hasPlayer, supported=${supported.size})"
}
