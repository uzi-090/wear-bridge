package io.wearbridge.watch.media

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.SystemClock
import io.wearbridge.ams.AmsCommands
import io.wearbridge.ams.AmsUuids
import io.wearbridge.ams.EntityId
import io.wearbridge.ams.EntityUpdate
import io.wearbridge.ams.NowPlaying
import io.wearbridge.ams.PlayerAttribute
import io.wearbridge.ams.RemoteCommand
import io.wearbridge.ams.TrackAttribute
import io.wearbridge.watch.ble.GattOpQueue
import io.wearbridge.watch.ble.enableNotifications
import io.wearbridge.watch.ble.readValue
import io.wearbridge.watch.ble.writeCharacteristicCompat
import java.util.UUID

/**
 * Apple Media Service client: the iPhone's Now Playing state and remote commands, straight from iOS
 * (no iPhone app involved). Confined to the BLE scope, like [io.wearbridge.watch.ancs.AncsClient].
 * Failure here never affects notifications: AMS is optional on top of a READY ANCS link.
 */
@SuppressLint("MissingPermission")
class AmsClient(
    private val gatt: BluetoothGatt,
    private val queue: GattOpQueue,
    private val listener: Listener,
) {
    interface Listener {
        fun onNowPlaying(state: NowPlaying)
    }

    enum class CommandResult { SENT, NOT_SUPPORTED, FAILED }

    private var remoteCommand: BluetoothGattCharacteristic? = null
    private var entityAttribute: BluetoothGattCharacteristic? = null
    private var state = NowPlaying()

    /** Subscribes and registers for player and track attributes. Returns false if AMS isn't usable. */
    suspend fun start(): Boolean {
        val service = gatt.getService(AmsUuids.SERVICE) ?: return false
        val rc = service.getCharacteristic(AmsUuids.REMOTE_COMMAND) ?: return false
        val eu = service.getCharacteristic(AmsUuids.ENTITY_UPDATE) ?: return false
        remoteCommand = rc
        entityAttribute = service.getCharacteristic(AmsUuids.ENTITY_ATTRIBUTE)
        if (gatt.enableNotifications(queue, rc) != 0) return false
        if (gatt.enableNotifications(queue, eu) != 0) return false
        // One registration write per entity. iOS answers each with the current values.
        val registrations = listOf(
            AmsCommands.register(EntityId.PLAYER, PlayerAttribute.NAME, PlayerAttribute.PLAYBACK_INFO, PlayerAttribute.VOLUME),
            AmsCommands.register(EntityId.TRACK, TrackAttribute.ARTIST, TrackAttribute.ALBUM, TrackAttribute.TITLE, TrackAttribute.DURATION),
        )
        for (payload in registrations) {
            val r = queue.run(GattOpQueue.Kind.WRITE_CHARACTERISTIC, target = eu.uuid.toString()) {
                gatt.writeCharacteristicCompat(eu, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }
            if (!r.ok) return false
        }
        // iOS doesn't reliably send current values on registration (seen on hardware: sometimes the track
        // arrived without the player name, sometimes nothing at all after a reconnect while paused). Read
        // them directly once, so the screen is complete straight away. Later changes arrive as updates.
        for ((entity, attribute) in INITIAL_READS) fetchFull(entity, attribute)
        return true
    }

    /** Returns true if this client consumed the notification. */
    suspend fun onCharacteristicChanged(uuid: UUID, value: ByteArray): Boolean {
        when (uuid) {
            AmsUuids.REMOTE_COMMAND -> publish(state.copy(supported = AmsCommands.parseSupported(value), commandsKnown = true))
            AmsUuids.ENTITY_UPDATE -> {
                val u = EntityUpdate.parse(value) ?: return true
                if (u.entity == EntityId.PLAYER && u.attribute == PlayerAttribute.NAME) {
                    // The playing app's display name (not personal data): needed to verify logo matching.
                    io.wearbridge.watch.diagnostics.DiagLog.i("ams player name='${u.value}' truncated=${u.truncated}")
                }
                val playerChanged = u.entity == EntityId.PLAYER && u.attribute == PlayerAttribute.NAME &&
                    u.value.isNotBlank() && u.value != state.playerName
                publish(state.apply(u, SystemClock.elapsedRealtime()))
                if (playerChanged) {
                    for ((entity, attribute) in INITIAL_READS) {
                        if (entity != EntityId.PLAYER || attribute != PlayerAttribute.NAME) fetchFull(entity, attribute)
                    }
                }
                if (u.truncated && u.attribute in FETCH_FULL[u.entity].orEmpty()) fetchFull(u.entity, u.attribute)
                // A new track: iOS doesn't always send every attribute with it (seen on hardware: an Apple Music
                // track arrived without its duration). Read the duration, and the player name if unknown.
                if (u.entity == EntityId.TRACK && u.attribute == TrackAttribute.TITLE) {
                    fetchFull(EntityId.TRACK, TrackAttribute.DURATION)
                    if (state.playerName.isBlank()) fetchFull(EntityId.PLAYER, PlayerAttribute.NAME)
                }
            }
            else -> return false
        }
        return true
    }

    suspend fun send(command: RemoteCommand): CommandResult {
        val rc = remoteCommand ?: return CommandResult.FAILED
        // An explicit empty supported-command update means no controls are available.
        if (!state.can(command)) return CommandResult.NOT_SUPPORTED
        val r = queue.run(GattOpQueue.Kind.WRITE_CHARACTERISTIC, target = rc.uuid.toString()) {
            gatt.writeCharacteristicCompat(rc, AmsCommands.command(command), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        return when {
            r.ok -> CommandResult.SENT
            r.status == io.wearbridge.ams.AmsError.COMMAND_NOT_SUPPORTED -> CommandResult.NOT_SUPPORTED
            else -> CommandResult.FAILED
        }
    }

    /** A long title or artist arrives truncated to one packet: read the whole value through Entity Attribute. */
    private suspend fun fetchFull(entity: Int, attribute: Int) {
        val ea = entityAttribute ?: return
        val selected = queue.run(GattOpQueue.Kind.WRITE_CHARACTERISTIC, target = ea.uuid.toString()) {
            gatt.writeCharacteristicCompat(ea, AmsCommands.selectAttribute(entity, attribute), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        if (!selected.ok) return
        val full = gatt.readValue(queue, ea) ?: return
        if (entity == EntityId.PLAYER && attribute == PlayerAttribute.NAME) {
            io.wearbridge.watch.diagnostics.DiagLog.i("ams player name read='${full.toString(Charsets.UTF_8)}'")
        }
        publish(state.withFullValue(entity, attribute, full.toString(Charsets.UTF_8), SystemClock.elapsedRealtime()))
    }

    private fun publish(next: NowPlaying) {
        state = next
        listener.onNowPlaying(next)
    }

    private companion object {
        val INITIAL_READS = listOf(
            EntityId.PLAYER to PlayerAttribute.NAME,
            EntityId.PLAYER to PlayerAttribute.PLAYBACK_INFO,
            EntityId.PLAYER to PlayerAttribute.VOLUME,
            EntityId.TRACK to TrackAttribute.TITLE,
            EntityId.TRACK to TrackAttribute.ARTIST,
            EntityId.TRACK to TrackAttribute.DURATION,
        )
        val FETCH_FULL = mapOf(
            EntityId.TRACK to setOf(TrackAttribute.TITLE, TrackAttribute.ARTIST, TrackAttribute.ALBUM),
            EntityId.PLAYER to setOf(PlayerAttribute.NAME),
        )
    }
}
