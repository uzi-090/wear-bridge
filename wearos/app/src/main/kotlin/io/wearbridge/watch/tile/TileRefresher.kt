package io.wearbridge.watch.tile

import android.content.Context
import androidx.wear.tiles.TileService
import io.wearbridge.watch.BridgeGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Asks Wear OS to refresh the tile when what it shows changes (link state, iPhone battery, alert count,
 * player, song and play state). Event-driven and debounced; Wear OS also rate-limits requests, so this never polls.
 */
object TileRefresher {
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        val app = context.applicationContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            // Link loss must invalidate a cached percentage without the media debounce.
            combine(BridgeGraph.link, BridgeGraph.pairing.state) { link, pairing -> listOf(link.state, link.issue, pairing.phase) }.distinctUntilChanged().collect {
                TileService.getUpdater(app).requestUpdate(BridgeTileService::class.java)
            }
        }
        scope.launch {
            combine(
                BridgeGraph.link, BridgeGraph.phoneBattery, BridgeGraph.repository.items, BridgeGraph.media, BridgeGraph.phoneCharging,
            ) { link, battery, items, media, charging ->
                listOf(
                    link.state, link.issue, battery, items.size, charging,
                    media?.hasMediaSession, media?.state, media?.playerName, media?.title, media?.artist,
                    media?.supported, media?.commandsKnown,
                )
            }
                .distinctUntilChanged()
                .debounce(1_000)
                .collect { TileService.getUpdater(app).requestUpdate(BridgeTileService::class.java) }
        }
    }
}
