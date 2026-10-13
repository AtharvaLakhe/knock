package io.github.atharvalakhe.knock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeDetectorTest {
    private fun feed(d: ShakeDetector, samples: List<Pair<Float, Long>>) = samples.count { (g, t) -> d.feed(g, t) }

    @Test fun threeStrongPeaksTrigger() {
        val d = ShakeDetector()
        assertFalse(d.feed(3f, 1_000))
        assertFalse(d.feed(3f, 1_400))
        assertTrue(d.feed(3f, 1_800))
    }

    @Test fun walkingNeverTriggers() =
        assertEquals(0, feed(ShakeDetector(), (0 until 2_000).map { i -> (1f + (i % 7) * 0.2f) to i * 20L }))

    @Test fun peaksTooFarApartDoNotTrigger() =
        assertEquals(0, feed(ShakeDetector(), listOf(3f to 0L, 3f to 2_100L, 3f to 4_200L)))

    @Test fun oneJoltCountsOnce() =
        assertEquals(0, feed(ShakeDetector(), listOf(3f to 0L, 3.2f to 20L, 3.1f to 40L, 2.9f to 60L)))

    @Test fun cooldownStopsRepeats() {
        val d = ShakeDetector(ShakeConfig(cooldownMs = 10_000))
        assertEquals(1, feed(d, (0..5).map { 3f to it * 300L }))
        assertEquals(1, feed(d, listOf(3f to 11_000L, 3f to 11_300L, 3f to 11_600L)))
    }
}

class PowerPressDetectorTest {
    @Test fun threeCalmPressesTrigger() {
        val d = PowerPressDetector()
        assertFalse(d.press(0))
        assertFalse(d.press(900))
        assertTrue(d.press(1_800))
    }

    @Test fun slowPressesNeverAddUp() {
        val d = PowerPressDetector()
        assertFalse(d.press(0))
        assertFalse(d.press(2_500))
        assertFalse(d.press(5_000))
    }

    @Test fun duplicateEventsAreIgnored() {
        val d = PowerPressDetector()
        d.press(0); d.press(5); d.press(900)
        assertEquals(2, d.count)
    }

    @Test fun cooldownAbsorbsTheRestOfARun() {
        val d = PowerPressDetector()
        d.press(0); d.press(800)
        assertTrue(d.press(1_600))
        assertFalse(d.press(2_400))
        assertFalse(d.press(3_200))
        assertFalse(d.press(4_000))
    }

    @Test fun tickForgetsAnUnfinishedRun() {
        val d = PowerPressDetector()
        d.press(0); d.press(900); d.tick(3_500)
        assertEquals(0, d.count)
    }

    @Test fun twoPressPattern() {
        val d = PowerPressDetector(PowerPressConfig(presses = 2))
        assertFalse(d.press(0))
        assertTrue(d.press(700))
    }
}

class TwistDetectorTest {
    /** Rotation around x at [rate] rad/s, reversing every [strokeMs], sampled at 50 Hz. */
    private fun twist(d: TwistDetector, rate: Float, strokeMs: Long, strokes: Int, accel: Float = 1f): Boolean {
        var t = 1_000L
        var detected = false
        repeat(strokes) { i ->
            val sign = if (i % 2 == 0) 1f else -1f
            var s = 0L
            while (s < strokeMs) {
                if (d.feed(rate * sign, 0.05f, 0.02f, accel, t).detected) detected = true
                t += 20; s += 20
            }
        }
        return detected
    }

    @Test fun fourFullStrokesAreATwist() = assertTrue(twist(TwistDetector(), rate = 3f, strokeMs = 260, strokes = 4))
    @Test fun shortFlicksAreNot() = assertFalse(twist(TwistDetector(), rate = 3f, strokeMs = 120, strokes = 4))
    @Test fun threeStrokesAreNot() = assertFalse(twist(TwistDetector(), rate = 3f, strokeMs = 260, strokes = 3))
    @Test fun aSwingingArmResets() = assertFalse(twist(TwistDetector(), rate = 3f, strokeMs = 260, strokes = 4, accel = 2.2f))

    @Test fun firmNeedsMoreTurn() {
        assertTrue(twist(TwistDetector(TwistStrength.GENTLE), rate = 3f, strokeMs = 180, strokes = 4))
        assertFalse(twist(TwistDetector(TwistStrength.FIRM), rate = 3f, strokeMs = 180, strokes = 4))
    }
}
