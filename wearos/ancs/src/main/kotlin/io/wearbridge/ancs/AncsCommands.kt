package io.wearbridge.ancs

/** A notification attribute to request. [maxLength] only applies to Title, Subtitle and Message. */
data class AttributeRequest(val attribute: NotificationAttribute, val maxLength: Int = 0) {
    init {
        require(!attribute.hasMaxLength || maxLength in 1..0xFFFF) { "maxLength required for $attribute" }
    }
}

/** Encoders for Control Point commands. */
object AncsCommands {
    fun getNotificationAttributes(uid: Long, attributes: List<AttributeRequest>): ByteArray {
        val w = ByteWriter().u8(CommandId.GET_NOTIFICATION_ATTRIBUTES).u32Le(uid)
        for (req in attributes) {
            w.u8(req.attribute.id)
            if (req.attribute.hasMaxLength) w.u16Le(req.maxLength)
        }
        return w.toByteArray()
    }

    fun getAppAttributes(appIdentifier: String, attributeIds: List<Int> = listOf(AppAttribute.DISPLAY_NAME)): ByteArray {
        val w = ByteWriter()
            .u8(CommandId.GET_APP_ATTRIBUTES)
            .bytes(appIdentifier.toByteArray(Charsets.UTF_8))
            .u8(0)
        attributeIds.forEach { w.u8(it) }
        return w.toByteArray()
    }

    fun performNotificationAction(uid: Long, action: ActionId): ByteArray =
        ByteWriter().u8(CommandId.PERFORM_NOTIFICATION_ACTION).u32Le(uid).u8(action.id).toByteArray()
}
