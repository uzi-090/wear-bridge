package io.wearbridge.ancs

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** A fully fetched iOS notification. Action labels are exactly what iOS supplied. */
data class AncsNotification(
    val uid: Long,
    val category: Category,
    val flags: Int,
    val appIdentifier: String?,
    val appDisplayName: String?,
    val title: String,
    val subtitle: String,
    val message: String,
    /** Full message length as reported by iOS, or null if it wasn't provided. */
    val messageSize: Int?,
    /** Local wall-clock time on the iPhone (ANCS carries no timezone). */
    val date: LocalDateTime?,
    val positiveActionLabel: String?,
    val negativeActionLabel: String?,
) {
    val isMessageTruncated: Boolean
        get() = messageSize != null && messageSize > message.toByteArray(Charsets.UTF_8).size

    // Notification content must never reach logs.
    override fun toString(): String =
        "AncsNotification(uid=***, app=$appIdentifier, category=$category, flags=$flags)"

    companion object {
        fun from(
            event: NotificationSourceEvent,
            attributes: Map<Int, ByteArray>,
            appDisplayName: String?,
        ): AncsNotification {
            fun text(a: NotificationAttribute): String? = attributes[a.id]?.toString(Charsets.UTF_8)

            return AncsNotification(
                uid = event.uid,
                category = event.category,
                flags = event.flags,
                appIdentifier = text(NotificationAttribute.APP_IDENTIFIER)?.takeIf { it.isNotEmpty() },
                appDisplayName = appDisplayName,
                title = text(NotificationAttribute.TITLE).orEmpty(),
                subtitle = text(NotificationAttribute.SUBTITLE).orEmpty(),
                message = text(NotificationAttribute.MESSAGE).orEmpty(),
                messageSize = text(NotificationAttribute.MESSAGE_SIZE)?.trim()?.toIntOrNull(),
                date = text(NotificationAttribute.DATE)?.let(AncsDate::parse),
                positiveActionLabel = text(NotificationAttribute.POSITIVE_ACTION_LABEL)
                    ?.takeIf { event.hasPositiveAction && it.isNotBlank() },
                negativeActionLabel = text(NotificationAttribute.NEGATIVE_ACTION_LABEL)
                    ?.takeIf { event.hasNegativeAction && it.isNotBlank() },
            )
        }
    }
}

/** Parses ANCS dates (UTS #35 `yyyyMMdd'T'HHmmSS`). */
object AncsDate {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun parse(value: String): LocalDateTime? = try {
        LocalDateTime.parse(value.trim(), FORMAT)
    } catch (_: DateTimeParseException) {
        null
    }
}
