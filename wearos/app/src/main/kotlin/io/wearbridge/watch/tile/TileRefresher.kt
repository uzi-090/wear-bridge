package io.wearbridge.watch.tile

import android.content.Context
import androidx.wear.tiles.TileService
import io.wearbridge.watch.BridgeGraph
import io.wearbridge.watch.ble.LinkState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Asks Wear OS to refresh the tile when what it shows changes (link up/down, iPhone battery, alert count,
 * play state). Event-driven and debounced; Wear OS also rate-limits requests, so this never polls.
 */
object TileRefresher {
    @OptIn(FlowPreview::class)
    fun start(context: Context) {
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            combine(
                BridgeGraph.link, BridgeGraph.phoneBattery, BridgeGraph.repository.items, BridgeGraph.media, BridgeGraph.phoneCharging,
            ) { link, battery, items, media, charging ->
                listOf(link.state == LinkState.READY, battery, items.size, media?.hasPlayer, media?.isPlaying, charging)
            }
                .distinctUntilChanged()
                .drop(1)
                .debounce(1_000)
                .collect { TileService.getUpdater(app).requestUpdate(BridgeTileService::class.java) }
        }
    }
}
