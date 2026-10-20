package io.wearbridge.watch.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.MotionScheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * Bridge's visual language on the watch: "Bridge Night".
 *
 * - True black background (OLED: black pixels are off, so it's also the most battery-friendly).
 * - One calm accent, a soft sky blue that nods to the iPhone without copying iOS, used sparingly for
 *   the one primary action per screen and for selection.
 * - Mint is reserved for "working" states (connected, success); amber for "needs you"; coral for
 *   destructive actions. Calls keep the platform-standard green/red, which people read without thinking.
 * - Neutral surfaces are cool greys stepped ~4% apart, so cards separate from the background without
 *   borders, and text contrast stays above WCAG AA (onSurface on surfaceContainer ≈ 13:1).
 */
object BridgePalette {
    val Accent = Color(0xFF8FC7FF)
    val Connected = Color(0xFF5EDB8A)
    val Attention = Color(0xFFFFB547)
    val Destructive = Color(0xFFFF8A80)
    val CallGreen = Color(0xFF34C759)
    val CallRed = Color(0xFFFF453A)
    val TextSecondary = Color(0xFFA9B1BB)
    val TextTertiary = Color(0xFF6F7882)
}

val BridgeColorScheme = ColorScheme(
    primary = BridgePalette.Accent,
    primaryDim = Color(0xFF6FA8E0),
    primaryContainer = Color(0xFF123C5E),
    onPrimary = Color(0xFF00294A),
    onPrimaryContainer = Color(0xFFD2E6FF),
    secondary = Color(0xFFB7C8DB),
    secondaryDim = Color(0xFF97A8BB),
    secondaryContainer = Color(0xFF26323F),
    onSecondary = Color(0xFF203040),
    onSecondaryContainer = Color(0xFFD5E4F5),
    tertiary = BridgePalette.Connected,
    tertiaryDim = Color(0xFF47B872),
    tertiaryContainer = Color(0xFF0F3D27),
    onTertiary = Color(0xFF003920),
    onTertiaryContainer = Color(0xFFA4F5C4),
    surfaceContainerLow = Color(0xFF0E1114),
    surfaceContainer = Color(0xFF191D22),
    surfaceContainerHigh = Color(0xFF242A30),
    onSurface = Color(0xFFE8EBF0),
    onSurfaceVariant = BridgePalette.TextSecondary,
    outline = BridgePalette.TextTertiary,
    outlineVariant = Color(0xFF3B424A),
    background = Color.Black,
    onBackground = Color(0xFFE8EBF0),
    error = BridgePalette.Destructive,
    errorDim = Color(0xFFE06A60),
    errorContainer = Color(0xFF5C1A16),
    onError = Color(0xFF4A0D0A),
    onErrorContainer = Color(0xFFFFDAD5),
)

/** The app theme: Bridge Night colours and Material 3 Expressive motion (springy, interruptible). */
@Composable
fun BridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = BridgeColorScheme, motionScheme = MotionScheme.expressive(), content = content)
}

/**
 * The standard scrolling screen: ScreenScaffold (clock, scroll indicator, optional edge button) over a
 * TransformingLazyColumn, whose items scale and morph as they reach the curved edges. The bezel/crown
 * scrolls it with haptic ticks. [content] gets the shared [TransformationSpec] for its items.
 */
@Composable
fun BridgeList(
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val spec = rememberTransformationSpec()
    if (edgeButton != null) {
        ScreenScaffold(scrollState = state, edgeButton = edgeButton) { padding ->
            TransformingLazyColumn(state = state, contentPadding = padding) { content(spec) }
        }
    } else {
        ScreenScaffold(scrollState = state) { padding ->
            TransformingLazyColumn(state = state, contentPadding = padding) { content(spec) }
        }
    }
}

/** Transformed size + surface morphing for one list item. */
fun TransformingLazyColumnItemScope.morph(spec: TransformationSpec): Modifier =
    Modifier.fillMaxWidth().transformedHeight(this, spec)

@Composable
fun TransformingLazyColumnItemScope.surface(spec: TransformationSpec): SurfaceTransformation = SurfaceTransformation(spec)

/** Section title. */
@Composable
fun TransformingLazyColumnItemScope.SectionHeader(text: String, spec: TransformationSpec) {
    ListHeader(modifier = morph(spec), transformation = surface(spec)) { Text(text) }
}

/** A navigation row: icon, label, optional secondary line. Tonal, so the screen's one primary action stands out. */
@Composable
fun TransformingLazyColumnItemScope.NavButton(
    spec: TransformationSpec,
    icon: Int,
    label: String,
    secondary: String? = null,
    iconTint: Color = Color.Unspecified,
    enabled: Boolean = true,
    labelMaxLines: Int = 1,
    secondaryMaxLines: Int = 1,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = morph(spec),
        transformation = surface(spec),
        icon = {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (iconTint == Color.Unspecified) MaterialTheme.colorScheme.primary else iconTint,
                modifier = Modifier.size(ButtonDefaults.IconSize),
            )
        },
        label = { Text(label, maxLines = labelMaxLines, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = secondaryMaxLines, overflow = TextOverflow.Ellipsis) } },
    )
}

/** The screen's primary action (filled accent). */
@Composable
fun TransformingLazyColumnItemScope.PrimaryButton(spec: TransformationSpec, label: String, icon: Int? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = morph(spec),
        transformation = surface(spec),
        icon = icon?.let { { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) } },
        label = { Text(label, maxLines = 2) },
    )
}

/** A setting with an icon and a one-line explanation. */
@Composable
fun TransformingLazyColumnItemScope.SettingSwitch(
    spec: TransformationSpec,
    icon: Int,
    label: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    SwitchButton(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        modifier = morph(spec),
        transformation = surface(spec),
        icon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize)) },
        label = { Text(label, maxLines = 2) },
        secondaryLabel = { Text(detail, maxLines = 3) },
    )
}

/** Quiet explanatory text, centred, kept inside the curve. */
@Composable
fun TransformingLazyColumnItemScope.Note(spec: TransformationSpec, text: String, color: Color = BridgePalette.TextSecondary) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        textAlign = TextAlign.Center,
        modifier = morph(spec).padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * Pastel avatar fills with a deep same-hue initial, as in Wear OS's Material 3 Expressive contact tiles.
 * Picked from the person's name, so the same person always looks the same on every screen.
 */
object Avatars {
    private val PAIRS = listOf(
        Color(0xFFCDB8F8) to Color(0xFF3B1F7A), // lavender
        Color(0xFFB8D8FF) to Color(0xFF0B3A66), // sky
        Color(0xFFB4EFC9) to Color(0xFF0D4D2A), // mint
        Color(0xFFFFC9B0) to Color(0xFF6B2A0F), // peach
        Color(0xFFF7B7DA) to Color(0xFF6B1446), // rose
        Color(0xFFF6D67A) to Color(0xFF5A4300), // butter
        Color(0xFFC3CCD8) to Color(0xFF26313D), // slate
    )

    fun colors(key: String): Pair<Color, Color> = PAIRS[(key.hashCode() and 0x7FFFFFFF) % PAIRS.size]

    /** Up to two initials, from words that start with a letter (so "+91 98…" gives none). */
    fun initials(name: String): String = name.split(' ').filter { w -> w.firstOrNull()?.isLetter() == true }
        .take(2).map { it.first().uppercaseChar() }.joinToString("")
}

/** A person: pastel circle (or rounded square for [square]) with initials; a phone glyph when there are none. */
@Composable
fun PastelAvatar(name: String, size: Dp, modifier: Modifier = Modifier, square: Boolean = false) {
    val initials = Avatars.initials(name)
    val (fill, ink) = if (initials.isEmpty()) Color(0xFF3A3F46) to Color(0xFFE8EBF0) else Avatars.colors(name)
    val shape = if (square) RoundedCornerShape(size * 0.32f) else CircleShape
    Box(modifier.size(size).background(fill, shape), contentAlignment = Alignment.Center) {
        if (initials.isNotEmpty()) {
            Text(
                initials,
                color = ink,
                style = if (size > 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        } else {
            Icon(painterResource(io.wearbridge.watch.R.drawable.ic_person), contentDescription = null, tint = ink, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/**
 * A progress ring that follows its value. Material's indicator keeps the first progress lambda it
 * receives, so passing `{ value }` directly freezes the ring at its first value (found twice on hardware:
 * media progress and iPhone battery). Always use this instead.
 */
@Composable
fun LiveRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant,
    strokeWidth: Dp = 4.dp,
) {
    val current = androidx.compose.runtime.rememberUpdatedState(progress.coerceIn(0f, 1f))
    androidx.wear.compose.material3.CircularProgressIndicator(
        progress = { current.value },
        modifier = modifier,
        strokeWidth = strokeWidth,
        colors = androidx.wear.compose.material3.ProgressIndicatorDefaults.colors(indicatorColor = color, trackColor = trackColor),
    )
}

/**
 * Expanding rings behind a circle of [size] (ringing calls, Find iPhone). Compose it only while it should
 * move: its animation clock exists only while it's in the tree, and values are read in the draw phase,
 * so nothing recomposes per frame.
 */
@Composable
fun Ripples(color: Color, size: Dp, spread: Float = 0.3f, maxAlpha: Float = 0.45f, periodMs: Int = 1_800) {
    val phase = rememberInfiniteTransition(label = "ripples")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "phase")
    for (offset in listOf(0f, 0.5f)) {
        Box(
            Modifier
                .size(size)
                .graphicsLayer {
                    val p = (phase.value + offset) % 1f
                    scaleX = 1f + spread * p
                    scaleY = scaleX
                    alpha = maxAlpha * (1f - p)
                }
                .background(color, CircleShape),
        )
    }
}
