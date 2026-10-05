package io.wearbridge.watch.calls

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ShakeDetectorTest {

    /** 50 Hz samples of gravity on z plus a sinusoid of [amplitude] m/s² at [hz] on x, for [ms]. Returns shakes. */
    private fun run(d: ShakeDetector, amplitude: Float, hz: Float, ms: Long, startMs: Long = 0): Int {
        var shakes = 0
        var t = startMs
        while (t < startMs + ms) {
            val x = amplitude * sin(2 * PI * hz * t / 1000.0).toFloat()
            if (d.feed(x, 0.4f, 9.81f, t)) shakes++
            t += 20
        }
        return shakes
    }

    private fun detector() = ShakeDetector().also { it.reset(0) }

    @Test
    fun deliberateShakeFiresOnce() {
        assertEquals(1, run(detector(), amplitude = 26f, hz = 5f, ms = 1_400, startMs = 800))
    }

    @Test
    fun walkingAndRunningNeverFire() {
        assertEquals(0, run(detector(), amplitude = 6f, hz = 1.8f, ms = 30_000))
        assertEquals(0, run(detector(), amplitude = 11f, hz = 2.8f, ms = 30_000))
    }

    @Test
    fun aSingleKnockDoesNotFire() {
        val d = detector()
        var shakes = 0
        for (t in 1_000L..3_000L step 20) {
            val x = if (t in 1_500L..1_540L) 35f else 0f
            if (d.feed(x, 0f, 9.81f, t)) shakes++
        }
        assertEquals(0, shakes)
    }

    @Test
    fun shakingWhileRaisingTheWristIsIgnoredDuringWarmup() {
        assertEquals(0, run(detector(), amplitude = 26f, hz = 5f, ms = 600, startMs = 0))
    }

    @Test
    fun cooldownPreventsDoubleFiring() {
        // Three seconds of continuous shaking: one shake, a 2 s cooldown, then at most one more.
        val shakes = run(detector(), amplitude = 26f, hz = 5f, ms = 3_000, startMs = 800)
        assertEquals(true, shakes in 1..2)
    }

    @Test
    fun slowStrongSwingsAreNotAShake() {
        // Big but slow movement (e.g. swinging the arm while dropping it): peaks too far apart.
        assertEquals(0, run(detector(), amplitude = 24f, hz = 1.2f, ms = 10_000, startMs = 800))
    }
}
