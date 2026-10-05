package io.wearbridge.ancs

internal fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

internal fun nsPacket(event: EventId, flags: Int, category: Category, uid: Long, count: Int = 1): ByteArray =
    ByteWriter().u8(event.code).u8(flags).u8(category.code).u8(count).u32Le(uid).toByteArray()

/** Builds a Get Notification Attributes response as iOS would send it (before fragmentation). */
internal fun notificationResponse(uid: Long, vararg attributes: Pair<Int, String>): ByteArray {
    val w = ByteWriter().u8(CommandId.GET_NOTIFICATION_ATTRIBUTES).u32Le(uid)
    for ((id, value) in attributes) {
        val v = value.toByteArray(Charsets.UTF_8)
        w.u8(id).u16Le(v.size).bytes(v)
    }
    return w.toByteArray()
}

internal fun appResponse(appId: String, displayName: String): ByteArray {
    val v = displayName.toByteArray(Charsets.UTF_8)
    return ByteWriter()
        .u8(CommandId.GET_APP_ATTRIBUTES)
        .bytes(appId.toByteArray(Charsets.UTF_8)).u8(0)
        .u8(AppAttribute.DISPLAY_NAME).u16Le(v.size).bytes(v)
        .toByteArray()
}

internal fun ByteArray.chunked(size: Int): List<ByteArray> =
    (indices step size).map { copyOfRange(it, minOf(it + size, this.size)) }
