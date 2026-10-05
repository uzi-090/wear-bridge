package io.wearbridge.ancs

import java.util.UUID

/** GATT identifiers from Apple's ANCS specification. */
object AncsUuids {
    val SERVICE: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
    val NOTIFICATION_SOURCE: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
    val CONTROL_POINT: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
    val DATA_SOURCE: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")

    /** Client Characteristic Configuration Descriptor (Bluetooth SIG 0x2902). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
}

enum class EventId(val code: Int) {
    ADDED(0),
    MODIFIED(1),
    REMOVED(2);

    companion object {
        fun fromCode(code: Int): EventId? = entries.firstOrNull { it.code == code }
    }
}

enum class Category(val code: Int) {
    OTHER(0),
    INCOMING_CALL(1),
    MISSED_CALL(2),
    VOICEMAIL(3),
    SOCIAL(4),
    SCHEDULE(5),
    EMAIL(6),
    NEWS(7),
    HEALTH_AND_FITNESS(8),
    BUSINESS_AND_FINANCE(9),
    LOCATION(10),
    ENTERTAINMENT(11),

    /**
     * Not in Apple's published ANCS spec. Observed on hardware (iOS on iPhone 16 Pro, 2026-10-05):
     * posted by com.apple.mobilephone while a call is connected, removed when it ends, with flags
     * Important + NegativeAction. The meaning of its action is still being verified.
     */
    ACTIVE_CALL(12),

    /** A category ID this build doesn't know about (reserved values). */
    UNKNOWN(-1);

    companion object {
        fun fromCode(code: Int): Category = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** Bit masks for the EventFlags byte. */
object EventFlags {
    const val SILENT = 1 shl 0
    const val IMPORTANT = 1 shl 1
    const val PRE_EXISTING = 1 shl 2
    const val POSITIVE_ACTION = 1 shl 3
    const val NEGATIVE_ACTION = 1 shl 4
}

object CommandId {
    const val GET_NOTIFICATION_ATTRIBUTES = 0
    const val GET_APP_ATTRIBUTES = 1
    const val PERFORM_NOTIFICATION_ACTION = 2
}

enum class NotificationAttribute(val id: Int, val hasMaxLength: Boolean) {
    APP_IDENTIFIER(0, false),
    TITLE(1, true),
    SUBTITLE(2, true),
    MESSAGE(3, true),
    MESSAGE_SIZE(4, false),
    DATE(5, false),
    POSITIVE_ACTION_LABEL(6, false),
    NEGATIVE_ACTION_LABEL(7, false),
}

object AppAttribute {
    const val DISPLAY_NAME = 0
}

enum class ActionId(val id: Int) {
    POSITIVE(0),
    NEGATIVE(1),
}

/** ATT error codes ANCS returns on Control Point writes. */
object AncsError {
    const val UNKNOWN_COMMAND = 0xA0
    const val INVALID_COMMAND = 0xA1
    const val INVALID_PARAMETER = 0xA2
    const val ACTION_FAILED = 0xA3
}
