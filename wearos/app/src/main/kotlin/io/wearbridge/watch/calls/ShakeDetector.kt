package io.wearbridge.watch.calls

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Recognises a deliberate wrist shake from raw accelerometer samples (m/s², including gravity), and
 * ignores walking, running, raising the wrist and single knocks. Pure logic, so it's unit-tested.
 *
 * A shake is [minPeaks] strong movements (linear acceleration above [thresholdMs2], about 1.4 g) that
 * alternate direction along the dominant axis, all within [windowMs]. Samples in the first [warmupMs]
 * after [reset] are ignored (that's when people raise the wrist to look), and after a shake the detector
 * stays quiet for [cooldownMs] so one gesture fires once.
 */
class ShakeDetector(
    private val thresholdMs2: Float = 14f,
    private val minPeaks: Int = 4,
    private val windowMs: Long = 1_100,
    private val warmupMs: Long = 700,
    private val cooldownMs: Long = 2_000,
) {
    private val gravity = FloatArray(3)
    private var hasGravity = false
    private var startedAt = 0L
    private var quietUntil = 0L
    private var lastSign = 0
    private var lastPeakAt = 0L
    private val peaks = ArrayDeque<Long>()

    /** Diagnostics: the strongest linear acceleration since the last read, and peaks in the window. */
    var maxSinceRead = 0f
        private set
    val peaksInWindow: Int get() = peaks.size

    fun readMax(): Float = maxSinceRead.also { maxSinceRead = 0f }

    /** Starts (or restarts) detection at [nowMs]. */
    fun reset(nowMs: Long) {
        hasGravity = false
        startedAt = nowMs
        quietUntil = nowMs + warmupMs
        lastSign = 0
        peaks.clear()
    }

    /** Feeds one sample. Returns true exactly when a shake completes. */
    fun feed(x: Float, y: Float, z: Float, nowMs: Long): Boolean {
        if (!hasGravity) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
            hasGravity = true
            return false
        }
        // Low-pass to track gravity; what's left is the wrist's own movement.
        gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * x
        gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * y
        gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * z
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        if (nowMs < quietUntil) return false

        val magnitude = sqrt(lx * lx + ly * ly + lz * lz)
        if (magnitude > maxSinceRead) maxSinceRead = magnitude
        if (magnitude < thresholdMs2) return false
        val dominant = when {
            abs(lx) >= abs(ly) && abs(lx) >= abs(lz) -> lx
            abs(ly) >= abs(lz) -> ly
            else -> lz
        }
        val sign = if (dominant >= 0) 1 else -1
        // A new peak must reverse direction (a shake goes back and forth) and not be the same swing.
        if (sign == lastSign || nowMs - lastPeakAt < MIN_PEAK_GAP_MS) return false
        lastSign = sign
        lastPeakAt = nowMs
        peaks.addLast(nowMs)
        while (peaks.isNotEmpty() && nowMs - peaks.first() > windowMs) peaks.removeFirst()
        if (peaks.size < minPeaks) return false

        peaks.clear()
        lastSign = 0
        quietUntil = nowMs + cooldownMs
        return true
    }

    private companion object {
        const val ALPHA = 0.8f
        const val MIN_PEAK_GAP_MS = 70L
    }
}
