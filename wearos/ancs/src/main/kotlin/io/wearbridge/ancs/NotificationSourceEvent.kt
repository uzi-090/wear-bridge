package io.wearbridge.ancs

/** One 8-byte packet from the ANCS Notification Source characteristic. */
data class NotificationSourceEvent(
    val eventId: EventId,
    val flags: Int,
    val category: Category,
    val categoryCount: Int,
    /** Unsigned 32-bit notification UID, widened to Long. */
    val uid: Long,
) {
    val isSilent: Boolean get() = flags and EventFlags.SILENT != 0
    val isImportant: Boolean get() = flags and EventFlags.IMPORTANT != 0
    val isPreExisting: Boolean get() = flags and EventFlags.PRE_EXISTING != 0
    val hasPositiveAction: Boolean get() = flags and EventFlags.POSITIVE_ACTION != 0
    val hasNegativeAction: Boolean get() = flags and EventFlags.NEGATIVE_ACTION != 0

    companion object {
        const val SIZE = 8

        /** Returns null for packets that are too short or carry an unknown EventID. */
        fun parse(bytes: ByteArray): NotificationSourceEvent? {
            if (bytes.size < SIZE) return null
            val eventId = EventId.fromCode(bytes[0].u8()) ?: return null
            return NotificationSourceEvent(
                eventId = eventId,
                flags = bytes[1].u8(),
                category = Category.fromCode(bytes[2].u8()),
                categoryCount = bytes[3].u8(),
                uid = bytes.readU32Le(4),
            )
        }
    }
}
