package io.wearbridge.watch.tile

import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconButton
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import io.wearbridge.ams.NowPlaying
import io.wearbridge.ams.RemoteCommand
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.ble.LinkIssue
import io.wearbridge.watch.ble.LinkSnapshot
import io.wearbridge.watch.ble.LinkState
import io.wearbridge.watch.notifications.AppCatalog
import io.wearbridge.watch.notifications.AppInfo
import io.wearbridge.watch.ui.MainActivity

/**
 * The Bridge tile: "Bridge" as the title, a media pill tinted with the playing app's colour (logo, song,
 * previous · play/pause · next), then a status pill (iPhone battery, alerts, Find iPhone). The media controls work right on the tile (load
 * actions handled here), without opening the app. Updates are pushed by [TileRefresher] when something
 * changes, so the tile never polls.
 */
class BridgeTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            when (requestParams.currentState.lastClickableId) {
                ID_TOGGLE -> BridgeGraph.mediaCommand(RemoteCommand.TOGGLE_PLAY_PAUSE)
                ID_PREVIOUS -> BridgeGraph.mediaCommand(RemoteCommand.PREVIOUS_TRACK)
                ID_NEXT -> BridgeGraph.mediaCommand(RemoteCommand.NEXT_TRACK)
            }
            TileBuilders.Tile.Builder()
                .setResourcesVersion(resourcesVersion())
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(this@BridgeTileService, requestParams)))
                .build()
        }

    @Deprecated("Superseded upstream, still called by current Wear OS versions")
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            val resources = ResourceBuilders.Resources.Builder()
                .setVersion(resourcesVersion())
                .addIdToImageMapping(IMG_PLAY, image(R.drawable.ic_play))
                .addIdToImageMapping(IMG_PAUSE, image(R.drawable.ic_pause))
                .addIdToImageMapping(IMG_PREVIOUS, image(R.drawable.ic_skip_previous))
                .addIdToImageMapping(IMG_NEXT, image(R.drawable.ic_skip_next))
                .addIdToImageMapping(IMG_MUSIC, image(R.drawable.ic_music_note))
                .addIdToImageMapping(IMG_PHONE, image(R.drawable.ic_phone_iphone))
                .addIdToImageMapping(IMG_BOLT, image(R.drawable.ic_bolt))
                .addIdToImageMapping(IMG_BATTERY, image(R.drawable.ic_battery))
                .addIdToImageMapping(IMG_BELL, image(R.drawable.ic_notifications))
                .addIdToImageMapping(IMG_BLUETOOTH, image(R.drawable.ic_bluetooth))
            playingApp()?.let { resources.addIdToImageMapping(IMG_APP, appLogo(it)) }
            resources.build()
        }

    /** The app behind the current player, resolved the same way as the media screen. */
    private fun playingApp(): AppInfo? {
        val media = BridgeGraph.media.value?.takeIf { it.hasPlayer } ?: return null
        return AppCatalog.forPlayerName(media.playerName, BridgeGraph.policy.seenApps.value)
    }

    /** Changes with the playing app and its icon, so Wear OS asks for the new logo. */
    private fun resourcesVersion(): String {
        val app = playingApp() ?: return "3"
        return "3:" + "${app.bundleId}:${app.name}:${BridgeGraph.icons.version.value}".hashCode()
    }

    /** The app's logo exactly as notifications show it (App Store icon, bundled logo or monogram), as PNG. */
    private fun appLogo(app: AppInfo): ResourceBuilders.ImageResource {
        val bitmap = BridgeGraph.notifier.badge(app)
        val png = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return ResourceBuilders.ImageResource.Builder()
            .setInlineResource(
                ResourceBuilders.InlineImageResource.Builder()
                    .setData(png)
                    .setWidthPx(bitmap.width)
                    .setHeightPx(bitmap.height)
                    .setFormat(ResourceBuilders.IMAGE_FORMAT_UNDEFINED) // encoded bytes (PNG), decoded by the renderer
                    .build(),
            )
            .build()
    }

    /**
     * Laid out like Samsung's own tiles: a small title near the top, then two stadium pills that fill the
     * face (media, then iPhone status), with the bottom left free for the system's page indicator.
     * Sizes are from the Galaxy Watch4 44 mm (225 dp) and scale with the screen.
     */
    private fun layout(context: Context, request: RequestBuilders.TileRequest): LayoutElementBuilders.LayoutElement {
        val link = BridgeGraph.link.value
        val ready = link.state == LinkState.READY
        val battery = BridgeGraph.phoneBattery.value
        val charging = ready && BridgeGraph.phoneCharging.value == true
        val alerts = BridgeGraph.repository.items.value.size
        val media = BridgeGraph.media.value?.takeIf { it.hasPlayer }
        val app = playingApp()
        val screen = request.deviceConfiguration.screenWidthDp.toFloat().takeIf { it > 0f } ?: 225f
        val s = screen / 225f
        val pillWidth = screen * 0.845f
        val pillHeight = 74f * s
        // The app's brand colour, as in the media screen: a deep tint for the pill, the colour itself for play.
        val brand = app?.color?.toInt()
        val mediaTint = brand?.let { blend(it, PILL, 0.42f) } ?: PILL
        val accent = brand?.let(::liftIfDark) ?: ACCENT
        val onAccent = if (luminance(accent) > 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

        return materialScope(context, request.deviceConfiguration, allowDynamicTheme = false, defaultColorScheme = BRIDGE_NIGHT) {
            // Offline, the same two pills say what's wrong (where the media was) and show the alerts
            // saved on the watch (where the battery was, which is unknown without the link).
            val mediaPill = when {
                !ready -> pill(pillWidth, pillHeight, PILL, open(context, "home", "link"), padH = 12f * s, content = connection(link, s))
                media == null -> pill(pillWidth, pillHeight, PILL, open(context, "media", "media_card"), padH = 12f * s, content = nothingPlaying(s))
                else -> pill(
                    pillWidth, pillHeight, mediaTint, open(context, "media", "media_card"), padH = 26f * s,
                    content = mediaContent(media, s, ButtonColors(LayoutColor(accent), LayoutColor(onAccent), LayoutColor(onAccent), LayoutColor(onAccent))),
                )
            }
            val statusPill = pill(
                pillWidth, pillHeight, PILL, open(context, "notifications", "alerts"), padH = 12f * s,
                content = if (ready) status(context, s, battery, charging, alerts) else offlineStatus(s, alerts),
            )
            LayoutElementBuilders.Box.Builder()
                .setWidth(DimensionBuilders.expand())
                .setHeight(DimensionBuilders.expand())
                .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_TOP)
                .addContent(
                    LayoutElementBuilders.Column.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                        .addContent(vSpace(10f * s))
                        .addContent(text("Bridge".layoutString, typography = Typography.TITLE_SMALL, maxLines = 1))
                        .addContent(vSpace(6f * s))
                        .addContent(mediaPill)
                        .addContent(vSpace(GAP * s))
                        .addContent(statusPill)
                        .build(),
                )
                .build()
        }
    }

    /** A stadium-shaped pill: fully round ends, content centred. */
    private fun pill(
        width: Float,
        height: Float,
        color: Int,
        onClick: ModifiersBuilders.Clickable,
        padH: Float,
        content: LayoutElementBuilders.LayoutElement,
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(width))
            .setHeight(DimensionBuilders.dp(height))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(color))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(height / 2)).build())
                            .build(),
                    )
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(padH)).setEnd(DimensionBuilders.dp(padH))
                            .build(),
                    )
                    .setClickable(onClick)
                    .build(),
            )
            .addContent(content)
            .build()

    /** Logo + song (scrolls if long), then previous · play/pause · next. */
    private fun MaterialScope.mediaContent(media: NowPlaying, s: Float, playColors: ButtonColors): LayoutElementBuilders.LayoutElement {
        val song = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(IMG_APP)
                    .setWidth(DimensionBuilders.dp(18f * s))
                    .setHeight(DimensionBuilders.dp(18f * s))
                    .build(),
            )
            .addContent(hSpace(6f * s))
            .addContent(
                text(
                    media.title.ifBlank { "Unknown title" }.layoutString,
                    typography = Typography.TITLE_SMALL,
                    maxLines = 1,
                    overflow = LayoutElementBuilders.TEXT_OVERFLOW_MARQUEE,
                ),
            )
            .build()
        val clear = ButtonColors(LayoutColor(0x00000000), colorScheme.onSurface, colorScheme.onSurface, colorScheme.onSurface)
        val controls = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(control(ID_PREVIOUS, IMG_PREVIOUS, clear, 32f * s))
            .addContent(hSpace(14f * s))
            .addContent(control(ID_TOGGLE, if (media.isPlaying) IMG_PAUSE else IMG_PLAY, playColors, 40f * s))
            .addContent(hSpace(14f * s))
            .addContent(control(ID_NEXT, IMG_NEXT, clear, 32f * s))
            .build()
        return LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(song)
            .addContent(vSpace(3f * s))
            .addContent(controls)
            .build()
    }

    /** A media control that acts on the tile itself: a load action handled in [onTileRequest]. */
    private fun MaterialScope.control(id: String, img: String, colors: ButtonColors, size: Float) =
        iconButton(
            onClick = ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(ActionBuilders.LoadAction.Builder().build()).build(),
            iconContent = { icon(img) },
            width = DimensionBuilders.dp(size),
            height = DimensionBuilders.dp(size),
            colors = colors,
        )

    /** Same shape as the status pill: a round badge on the left, two lines of text beside it. */
    private fun MaterialScope.nothingPlaying(s: Float): LayoutElementBuilders.LayoutElement =
        badgeRow(
            s,
            badge = circle(50f * s, BADGE, icon(IMG_MUSIC, tintColor = colorScheme.onSurfaceVariant)),
            line1 = text("Not playing".layoutString, typography = Typography.TITLE_SMALL, maxLines = 1,
                alignment = LayoutElementBuilders.TEXT_ALIGN_START),
            line2 = text("Start on iPhone".layoutString, typography = Typography.LABEL_SMALL, color = colorScheme.onSurfaceVariant,
                maxLines = 1, alignment = LayoutElementBuilders.TEXT_ALIGN_START),
        )

    /** iPhone battery (bolt while charging) and alert count, with Find iPhone as a round button on the right. */
    private fun MaterialScope.status(context: Context, s: Float, battery: Int?, charging: Boolean, alerts: Int): LayoutElementBuilders.LayoutElement {
        val low = !charging && (battery ?: 100) <= 20
        val badge = circle(
            50f * s, BADGE,
            icon(
                if (charging) IMG_BOLT else IMG_BATTERY,
                tintColor = when {
                    charging -> colorScheme.tertiary
                    low -> colorScheme.error
                    else -> colorScheme.onSurface
                },
            ),
        )
        return badgeRow(
            s,
            badge = badge,
            line1 = text((battery?.let { "$it%" } ?: "–").layoutString, typography = Typography.TITLE_LARGE, maxLines = 1,
                alignment = LayoutElementBuilders.TEXT_ALIGN_START),
            line2 = text(alertsLabel(alerts).layoutString, typography = Typography.LABEL_SMALL, color = colorScheme.onSurfaceVariant, maxLines = 1,
                alignment = LayoutElementBuilders.TEXT_ALIGN_START),
            trailing = findButton(context, s),
        )
    }

    /** Why the iPhone isn't connected, in words short enough for the pill. Tapping opens Bridge (Reconnect is there). */
    private fun MaterialScope.connection(link: LinkSnapshot, s: Float): LayoutElementBuilders.LayoutElement {
        val (title, hint) = when {
            link.issue == LinkIssue.BLUETOOTH_OFF -> "Bluetooth off" to "Turn it on"
            link.issue == LinkIssue.PERMISSION_MISSING -> "Not connected" to "Open Bridge"
            link.state == LinkState.RECONNECTING -> "Not connected" to "Reconnecting…"
            link.state == LinkState.ADVERTISING -> "Not connected" to "Searching…"
            link.state == LinkState.PAIRING -> "Pairing" to "Check iPhone"
            link.state in setOf(LinkState.CONNECTED, LinkState.DISCOVERING_SERVICES, LinkState.SUBSCRIBING) -> "Connecting…" to "One moment"
            else -> "Not connected" to "Tap to fix"
        }
        val busy = link.issue == null && link.state != LinkState.ERROR && link.state != LinkState.DISCONNECTED
        return badgeRow(
            s,
            badge = circle(50f * s, BADGE, icon(IMG_BLUETOOTH, tintColor = if (busy) colorScheme.primary else LayoutColor(ATTENTION))),
            line1 = text(title.layoutString, typography = Typography.TITLE_SMALL, maxLines = 1,
                alignment = LayoutElementBuilders.TEXT_ALIGN_START),
            line2 = text(hint.layoutString, typography = Typography.LABEL_SMALL, color = colorScheme.onSurfaceVariant, maxLines = 1,
                alignment = LayoutElementBuilders.TEXT_ALIGN_START),
        )
    }

    /** Offline: the alerts kept on the watch. No Find iPhone here: it needs the link, so the text gets the room. */
    private fun MaterialScope.offlineStatus(s: Float, alerts: Int): LayoutElementBuilders.LayoutElement =
        badgeRow(
            s,
            badge = circle(50f * s, BADGE, icon(IMG_BELL, tintColor = colorScheme.onSurfaceVariant)),
            line1 = text((if (alerts > 99) "99+" else "$alerts").layoutString, typography = Typography.TITLE_LARGE, maxLines = 1,
                alignment = LayoutElementBuilders.TEXT_ALIGN_START),
            line2 = text((if (alerts == 1) "alert on watch" else "alerts on watch").layoutString, typography = Typography.LABEL_SMALL,
                color = colorScheme.onSurfaceVariant, maxLines = 1, alignment = LayoutElementBuilders.TEXT_ALIGN_START),
        )

    private fun alertsLabel(alerts: Int) = when {
        alerts == 1 -> "1 alert"
        alerts > 99 -> "99+ alerts"
        else -> "$alerts alerts"
    }

    /** Find iPhone as a round accent button. */
    private fun MaterialScope.findButton(context: Context, s: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(50f * s))
            .setHeight(DimensionBuilders.dp(50f * s))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(ACCENT))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(25f * s)).build())
                            .build(),
                    )
                    .setClickable(open(context, "find", "find"))
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription("Find iPhone").build())
                    .build(),
            )
            .addContent(icon(IMG_PHONE, tintColor = colorScheme.onPrimary))
            .build()

    private fun badgeRow(
        s: Float,
        badge: LayoutElementBuilders.LayoutElement,
        line1: LayoutElementBuilders.LayoutElement,
        line2: LayoutElementBuilders.LayoutElement,
        trailing: LayoutElementBuilders.LayoutElement? = null,
    ): LayoutElementBuilders.LayoutElement {
        val row = LayoutElementBuilders.Row.Builder()
            .setWidth(DimensionBuilders.expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(badge)
            .addContent(hSpace(10f * s))
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(DimensionBuilders.expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                    .addContent(line1)
                    .addContent(line2)
                    .build(),
            )
        trailing?.let { row.addContent(it) }
        return row.build()
    }

    private fun circle(size: Float, color: Int, content: LayoutElementBuilders.LayoutElement): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.dp(size))
            .setHeight(DimensionBuilders.dp(size))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(color))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(size / 2)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(content)
            .build()

    private fun vSpace(dp: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build()

    private fun hSpace(dp: Float) = LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(dp)).build()

    /** Opens Bridge on a screen (MainActivity accepts only its known screen names). */
    private fun open(context: Context, screen: String, id: String): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(id)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(context.packageName)
                            .setClassName(MainActivity::class.java.name)
                            .addKeyToExtraMapping(MainActivity.EXTRA_OPEN, ActionBuilders.AndroidStringExtra.Builder().setValue(screen).build())
                            .build(),
                    )
                    .build(),
            )
            .build()

    private fun image(res: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(res).build())
        .build()

    companion object {
        private const val IMG_PHONE = "phone"
        private const val IMG_BOLT = "bolt"
        private const val IMG_BATTERY = "battery"
        private const val IMG_BELL = "bell"
        private const val IMG_BLUETOOTH = "bluetooth"
        private const val IMG_PLAY = "play"
        private const val IMG_PAUSE = "pause"
        private const val IMG_PREVIOUS = "previous"
        private const val IMG_NEXT = "next"
        private const val IMG_MUSIC = "music"
        private const val IMG_APP = "app_logo"
        private const val ID_TOGGLE = "media_toggle"
        private const val ID_PREVIOUS = "media_previous"
        private const val ID_NEXT = "media_next"

        /** The gap between the two pills. */
        private const val GAP = 6f

        /** Pill and badge fills: lifted off the black face like Samsung's tiles, badges sunk into the pill. */
        private const val PILL = 0xFF2A3038.toInt()
        private const val BADGE = 0xFF14181C.toInt()
        private const val ACCENT = 0xFF8FC7FF.toInt()
        private const val ATTENTION = 0xFFFFB547.toInt()

        private fun blend(fg: Int, bg: Int, amount: Float): Int {
            fun ch(shift: Int) = ((((fg shr shift) and 0xFF) * amount + ((bg shr shift) and 0xFF) * (1 - amount)).toInt()) and 0xFF
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        private fun luminance(c: Int): Float =
            (0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF)) / 255f

        /** Near-black brands (e.g. Apple TV, X) are lifted toward white so play still reads on the dark card. */
        private fun liftIfDark(c: Int): Int = if (luminance(c) < 0.15f) blend(0xFFFFFFFF.toInt(), c, 0.75f) else c

        private fun c(argb: Long) = LayoutColor(argb.toInt())

        /** The app's Bridge Night palette, so the tile and the app look like one product. */
        private val BRIDGE_NIGHT = ColorScheme(
            primary = c(0xFF8FC7FF),
            onPrimary = c(0xFF00294A),
            primaryContainer = c(0xFF123C5E),
            onPrimaryContainer = c(0xFFD2E6FF),
            secondary = c(0xFFB7C8DB),
            onSecondary = c(0xFF203040),
            secondaryContainer = c(0xFF26323F),
            onSecondaryContainer = c(0xFFD5E4F5),
            tertiary = c(0xFF5EDB8A),
            onTertiary = c(0xFF003920),
            tertiaryContainer = c(0xFF0F3D27),
            onTertiaryContainer = c(0xFFA4F5C4),
            surfaceContainerLow = c(0xFF0E1114),
            surfaceContainer = c(0xFF191D22),
            surfaceContainerHigh = c(0xFF242A30),
            onSurface = c(0xFFE8EBF0),
            onSurfaceVariant = c(0xFFA9B1BB),
            outline = c(0xFF6F7882),
            outlineVariant = c(0xFF3B424A),
            background = c(0xFF000000),
            onBackground = c(0xFFE8EBF0),
            error = c(0xFFFF8A80),
            onError = c(0xFF4A0D0A),
            errorContainer = c(0xFF5C1A16),
            onErrorContainer = c(0xFFFFDAD5),
        )
    }
}
