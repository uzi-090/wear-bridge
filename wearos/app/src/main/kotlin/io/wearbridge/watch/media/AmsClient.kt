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
            val r = queue.run(GattOpQueue.Kind.WRITE_CHARACTERISTIC) {
                gatt.writeCharacteristicCompat(eu, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            }
            if (!r.ok) return false
        }
        return true
    }

    /** Returns true if this client consumed the notification. */
    suspend fun onCharacteristicChanged(uuid: UUID, value: ByteArray): Boolean {
        when (uuid) {
            AmsUuids.REMOTE_COMMAND -> publish(state.copy(supported = AmsCommands.parseSupported(value)))
            AmsUuids.ENTITY_UPDATE -> {
                val u = EntityUpdate.parse(value) ?: return true
                publish(state.apply(u, SystemClock.elapsedRealtime()))
                if (u.truncated && u.attribute in FETCH_FULL[u.entity].orEmpty()) fetchFull(u.entity, u.attribute)
            }
            else -> return false
        }
        return true
    }

    suspend fun send(command: RemoteCommand): CommandResult {
        val rc = remoteCommand ?: return CommandResult.FAILED
        // iOS lists what the current player supports. An empty list means "unknown", so try anyway.
        if (state.supported.isNotEmpty() && command !in state.supported) return CommandResult.NOT_SUPPORTED
        val r = queue.run(GattOpQueue.Kind.WRITE_CHARACTERISTIC) {
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
        val selected = queue.run(GattOpQueue.Kind.WRITE_CHARACTERISTIC) {
            gatt.writeCharacteristicCompat(ea, AmsCommands.selectAttribute(entity, attribute), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
        if (!selected.ok) return
        val full = gatt.readValue(queue, ea) ?: return
        publish(state.withFullValue(entity, attribute, full.toString(Charsets.UTF_8), SystemClock.elapsedRealtime()))
    }

    private fun publish(next: NowPlaying) {
        state = next
        listener.onNowPlaying(next)
    }

    private companion object {
        val FETCH_FULL = mapOf(
            EntityId.TRACK to setOf(TrackAttribute.TITLE, TrackAttribute.ARTIST, TrackAttribute.ALBUM),
            EntityId.PLAYER to setOf(PlayerAttribute.NAME),
        )
    }
}
