package io.wearbridge.watch.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.wear.compose.foundation.LocalReduceMotion
import androidx.wear.compose.foundation.LocalScreenIsActive
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.material3.MaterialTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A screen on the back stack. [id] is unique within the stack's lifetime and keys the screen's saved state. */
@Immutable
data class NavEntry(val route: String, val id: Int)

/** The back stack. Routes are plain strings such as `notification/42`. */
@Stable
class BridgeNavigator internal constructor(initial: List<NavEntry>) {
    internal val stack = mutableStateListOf<NavEntry>().apply { addAll(initial) }
    private var nextId = (initial.maxOfOrNull { it.id } ?: 0) + 1

    internal val top: NavEntry get() = stack.last()

    fun navigate(route: String) {
        stack.add(NavEntry(route, nextId++))
    }

    fun popBackStack(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    companion object {
        internal val Saver = listSaver<BridgeNavigator, Any>(
            save = { nav -> nav.stack.flatMap { listOf(it.route, it.id) } },
            restore = { flat -> BridgeNavigator(flat.chunked(2).map { NavEntry(it[0] as String, it[1] as Int) }) },
        )
    }
}

@Composable
fun rememberBridgeNavigator(start: String): BridgeNavigator =
    rememberSaveable(saver = BridgeNavigator.Saver) { BridgeNavigator(listOf(NavEntry(start, 0))) }

/**
 * Wear-style navigation without the cross-fade. Screens slide in from the right and back out to it,
 * and the system back swipe drags the screen with the finger (predictive back), as in Wear's own nav host.
 *
 * Wear's SwipeDismissableNavHost also fades both screens on every transition. A fade renders each
 * screen into its own full-screen offscreen buffer every frame, and on the Galaxy Watch4 those two
 * buffers made transitions miss frames (traced: "alpha caused saveLayer 450x450"). Pure slides are
 * just translations, so the GPU draws each screen once.
 */
@Composable
fun BridgeNavHost(nav: BridgeNavigator, content: @Composable (route: String) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val saved = remember { HashSet<Int>() } // ids whose state the holder may hold
    val top = nav.top
    val state = remember { SeekableTransitionState(top) }
    val transition = rememberTransition(state, label = "nav")
    val reduceMotion = LocalReduceMotion.current
    // Non-null while the user is dragging a back swipe: 0 → 1 across the screen.
    var backProgress by remember { mutableStateOf<Float?>(null) }

    PredictiveBackHandler(enabled = nav.stack.size > 1) { events ->
        try {
            events.collect { backProgress = it.progress }
            backProgress = null
            nav.popBackStack()
        } catch (_: CancellationException) {
            backProgress = null // cancelled: the screen settles back (below)
        }
    }

    val progress = backProgress
    if (progress != null) {
        LaunchedEffect(progress) {
            val previous = nav.stack.getOrNull(nav.stack.lastIndex - 1) ?: return@LaunchedEffect
            state.seekTo(progress, previous)
        }
    } else {
        LaunchedEffect(top) {
            if (state.currentState != top) {
                if (reduceMotion) state.snapTo(top) else state.animateTo(top)
            } else if (state.fraction > 0f) {
                // A back swipe was let go before it committed: slide the screen back into place.
                val total = (transition.totalDurationNanos / 1_000_000).toInt()
                animate(state.fraction, 0f, animationSpec = tween((state.fraction * total).toInt())) { value, _ ->
                    this@LaunchedEffect.launch {
                        if (value > 0f) state.seekTo(value) else state.snapTo(top)
                    }
                }
            }
        }
    }

    val background = MaterialTheme.colorScheme.background
    transition.AnimatedContent(
        transitionSpec = {
            val forward = targetState.id > initialState.id
            // While dragging, the screen tracks the finger linearly; otherwise an emphasized ease.
            val spec = tween<IntOffset>(DURATION_MS, easing = if (backProgress != null) LinearEasing else EASING)
            if (forward) {
                ContentTransform(
                    targetContentEnter = slideInHorizontally(spec) { it },
                    initialContentExit = slideOutHorizontally(spec) { -it / PARALLAX },
                    sizeTransform = null,
                )
            } else {
                ContentTransform(
                    targetContentEnter = slideInHorizontally(spec) { -it / PARALLAX },
                    initialContentExit = slideOutHorizontally(spec) { it },
                    targetContentZIndex = -1f, // the leaving screen stays on top as it slides away
                    sizeTransform = null,
                )
            }
        },
        contentKey = { it.id },
    ) { entry ->
        val active = entry == transition.targetState
        CompositionLocalProvider(LocalScreenIsActive provides active) {
            // Opaque, so the screen underneath never shows through mid-slide. Only the active screen
            // gets rotary (bezel) input.
            Box(Modifier.fillMaxSize().background(background).hierarchicalFocusGroup(active)) {
                holder.SaveableStateProvider(entry.id) { content(entry.route) }
            }
        }
    }

    // Forget the saved state (scroll position etc.) of screens that left the stack, once they're gone.
    LaunchedEffect(transition.isRunning, nav.stack.size) {
        if (transition.isRunning) return@LaunchedEffect
        val live = nav.stack.mapTo(HashSet()) { it.id }
        val gone = saved.filter { it !in live }
        gone.forEach { holder.removeState(it); saved.remove(it) }
        saved += live
    }
}

private const val DURATION_MS = 300
private const val PARALLAX = 4
private val EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)
