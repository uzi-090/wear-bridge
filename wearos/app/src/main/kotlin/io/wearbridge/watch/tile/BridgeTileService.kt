package io.wearbridge.watch.tile

import android.content.Context
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.CardDefaults.filledTonalCardColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledButtonColors
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.ProgressIndicatorColors
import androidx.wear.protolayout.material3.circularProgressIndicator
import androidx.wear.protolayout.material3.compactButton
import androidx.wear.protolayout.material3.graphicDataCard
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import io.wearbridge.ams.RemoteCommand
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.R
import io.wearbridge.watch.ble.LinkState
import io.wearbridge.watch.ui.MainActivity

/**
 * The Bridge tile, in the Material 3 Expressive tile style: status as the title, three tiles (iPhone
 * battery, alerts, play/pause) and Find iPhone as the edge button. Play/pause works right on the tile
 * (a load action handled here), without opening the app. Updates are pushed by [TileRefresher] when
 * something changes, so the tile never polls.
 */
class BridgeTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        SuspendToFutureAdapter.launchFuture {
            if (requestParams.currentState.lastClickableId == ID_MEDIA_TOGGLE) {
                BridgeGraph.mediaCommand(RemoteCommand.TOGGLE_PLAY_PAUSE)
            }
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(this@BridgeTileService, requestParams)))
                .build()
        }

    @Deprecated("Superseded upstream, still called by current Wear OS versions")
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        SuspendToFutureAdapter.launchFuture {
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(IMG_PLAY, image(R.drawable.ic_play))
                .addIdToImageMapping(IMG_PAUSE, image(R.drawable.ic_pause))
                .addIdToImageMapping(IMG_MUSIC, image(R.drawable.ic_music_note))
                .addIdToImageMapping(IMG_PHONE, image(R.drawable.ic_phone_iphone))
                .addIdToImageMapping(IMG_BOLT, image(R.drawable.ic_bolt))
                .build()
        }

    private fun layout(context: Context, request: RequestBuilders.TileRequest): LayoutElementBuilders.LayoutElement {
        val ready = BridgeGraph.link.value.state == LinkState.READY
        val battery = BridgeGraph.phoneBattery.value
        val charging = BridgeGraph.phoneCharging.value == true
        val alerts = BridgeGraph.repository.items.value.size
        val media = BridgeGraph.media.value?.takeIf { it.hasPlayer }
        val mediaIcon = when {
            media == null -> IMG_MUSIC
            media.isPlaying -> IMG_PAUSE
            else -> IMG_PLAY
        }
        val name = io.wearbridge.watch.ble.PhoneServicesClient.marketingName(BridgeGraph.phoneModel.value)
        return materialScope(context, request.deviceConfiguration, allowDynamicTheme = false, defaultColorScheme = BRIDGE_NIGHT) {
            primaryLayout(
                titleSlot = { text((if (ready) "Bridge" else "Offline").layoutString) },
                mainSlot = {
                    // Like Samsung's gauge tiles: ring + big number + qualifier, then a compact pair below.
                    LayoutElementBuilders.Column.Builder()
                        .setWidth(DimensionBuilders.expand())
                        .addContent(
                            graphicDataCard(
                                onClick = open(context, "home", "battery"),
                                title = { text((if (ready && battery != null) "$battery%" else "–").layoutString) },
                                content = {
                                    text(
                                        (if (ready && charging) "Charging" else if (ready) name else "Not connected").layoutString,
                                        color = if (ready && charging) colorScheme.tertiary else colorScheme.onSurfaceVariant,
                                    )
                                },
                                graphic = {
                                    LayoutElementBuilders.Box.Builder()
                                        .addContent(
                                            circularProgressIndicator(
                                                staticProgress = if (ready && battery != null) battery / 100f else 0f,
                                                colors = ProgressIndicatorColors(
                                                    indicatorColor = if (charging || (battery ?: 100) > 20) colorScheme.tertiary else colorScheme.error,
                                                    trackColor = colorScheme.outlineVariant,
                                                ),
                                            ),
                                        )
                                        .addContent(
                                            icon(
                                                if (ready && charging) IMG_BOLT else IMG_PHONE,
                                                tintColor = if (ready && charging) colorScheme.tertiary else colorScheme.onSurface,
                                            ),
                                        )
                                        .build()
                                },
                                colors = filledTonalCardColors(),
                            ),
                        )
                        .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(6f)).build())
                        .addContent(
                            buttonGroup {
                                buttonGroupItem {
                                    compactButton(
                                        onClick = open(context, "notifications", "alerts"),
                                        labelContent = { text((if (alerts > 99) "99+ alerts" else if (alerts == 1) "1 alert" else "$alerts alerts").layoutString) },
                                        colors = filledTonalButtonColors(),
                                    )
                                }
                                buttonGroupItem {
                                    compactButton(
                                        onClick = if (media != null) mediaToggle() else open(context, "media", "media"),
                                        iconContent = { icon(mediaIcon) },
                                        colors = filledTonalButtonColors(),
                                    )
                                }
                            },
                        )
                        .build()
                },
                bottomSlot = {
                    textEdgeButton(
                        onClick = open(context, "find", "find"),
                        labelContent = { text("Find iPhone".layoutString) },
                        colors = filledButtonColors(),
                    )
                },
            )
        }
    }

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

    /** Toggles playback on the iPhone, handled in [onTileRequest], then the tile refreshes. */
    private fun mediaToggle(): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder().setId(ID_MEDIA_TOGGLE).setOnClick(ActionBuilders.LoadAction.Builder().build()).build()

    private fun image(res: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(res).build())
        .build()

    companion object {
        private const val RESOURCES_VERSION = "2"
        private const val IMG_PHONE = "phone"
        private const val IMG_BOLT = "bolt"
        private const val ID_MEDIA_TOGGLE = "media_toggle"
        private const val IMG_PLAY = "play"
        private const val IMG_PAUSE = "pause"
        private const val IMG_MUSIC = "music"

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
