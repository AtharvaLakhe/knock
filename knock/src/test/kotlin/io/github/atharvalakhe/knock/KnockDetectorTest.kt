package io.github.atharvalakhe.knock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** Synthetic accelerometer data (phone upright, gravity on y) fed through the detector at a chosen sample rate. */
class KnockDetectorTest {
    private fun run(
        signal: (t: Long) -> Float, ms: Long = 7_000, stepMs: Long = 10, config: KnockConfig = KnockConfig(),
        handShake: Float = 0.3f, flat: Boolean = false, covered: Boolean? = null, events: MutableList<KnockEvent>? = null,
    ): Int {
        var fired = 0
        val d = KnockDetector(config) { e -> events?.add(e); if (e == KnockEvent.Pattern) fired++ }.also { it.covered = covered }
        val rnd = Random(7)
        var t = 0L
        while (t < ms) {
            val drift = if (flat) 0f else (0.8 * sin(t / 700.0)).toFloat()
            val n = { (rnd.nextFloat() - 0.5f) * handShake }
            if (flat) d.feed(n(), n(), 9.81f + signal(t) + n(), t)
            else d.feed(drift + n(), 9.81f + signal(t) + n(), n(), t)
            t += stepMs
        }
        return fired
    }

    /** A knock as a phone sensor reports it: a hit, a rebound, then a short fading ring. */
    private fun knocks(vararg at: Long, size: Float = 5f): (Long) -> Float = { t ->
        at.sumOf { k ->
            val d = t - k
            when {
                d < 0 -> 0.0
                d < 20 -> size.toDouble()
                d < 40 -> -0.45 * size
                d < 160 -> 0.25 * size * exp(-d / 40.0) * sin(d / 8.0)
                else -> 0.0
            }
        }.toFloat()
    }

    private val walking: (Long) -> Float = { t -> val p = t % 520; if (p < 180) (4.0 * sin(PI * p / 180)).toFloat() else 0f }

    @Test fun threeKnocksFire() = assertEquals(1, run(knocks(2_000, 2_500, 3_000)))
    @Test fun softerHandHeldKnocksFire() = assertEquals(1, run(knocks(2_000, 2_450, 2_900, size = 3.6f)))
    @Test fun slowerKnocksFire() = assertEquals(1, run(knocks(2_000, 2_900, 3_800)))
    @Test fun quickKnocksFire() = assertEquals(1, run(knocks(2_000, 2_250, 2_500)))
    @Test fun worksAt50Hz() = assertEquals(1, run(knocks(2_000, 2_500, 3_000), stepMs = 20))
    @Test fun worksAt200Hz() = assertEquals(1, run(knocks(2_000, 2_500, 3_000), stepMs = 5))
    @Test fun lightTapsDoNothing() = assertEquals(0, run(knocks(2_000, 2_500, 3_000, size = 1.8f)))
    @Test fun lightTapsCountOnLight() =
        assertEquals(1, run(knocks(2_000, 2_500, 3_000, size = 2.5f), config = KnockConfig(threshold = KnockConfig.LIGHT)))
    @Test fun twoKnocksDoNothing() = assertEquals(0, run(knocks(2_000, 2_500)))
    @Test fun fourKnocksDoNothing() = assertEquals(0, run(knocks(2_000, 2_400, 2_800, 3_200)))
    @Test fun knocksTooFarApartDoNothing() = assertEquals(0, run(knocks(2_000, 3_400, 4_800)))
    @Test fun holdingThePhoneDoesNothing() = assertEquals(0, run({ 0f }, ms = 15_000, handShake = 0.8f))
    @Test fun walkingDoesNothing() = assertEquals(0, run(walking, ms = 12_000))
    @Test fun walkingAt50HzDoesNothing() = assertEquals(0, run(walking, ms = 12_000, stepMs = 20))
    @Test fun runningDoesNothing() = assertEquals(0, run({ t -> if (t % 330 < 40) 12f else 0f }, ms = 10_000))
    @Test fun knocksWhileWalkingFire() = assertEquals(1, run({ walking(it) + knocks(2_050, 2_500, 2_950, size = 6f)(it) }))
    @Test fun aShoveOrFallDoesNothing() =
        assertEquals(0, run({ t -> if (t in 2_000..2_900) 20f * sin(t / 25.0).toFloat() else 0f }))

    private val tableNoise = 0.04f

    @Test fun tapsOnAPhoneOnATableDoNothing() =
        assertEquals(0, run(knocks(4_000, 4_500, 5_000), handShake = tableNoise, flat = true))
    @Test fun hardKnocksOnATableFire() =
        assertEquals(1, run(knocks(4_000, 4_500, 5_000, size = 12f), handShake = tableNoise, flat = true))
    @Test fun flatAndStillInAPocketFires() =
        assertEquals(1, run(knocks(4_000, 4_500, 5_000), handShake = tableNoise, flat = true, covered = true))
    @Test fun tableCheckCanBeTurnedOff() =
        assertEquals(1, run(knocks(4_000, 4_500, 5_000), handShake = tableNoise, flat = true, config = KnockConfig(tableFactor = 1f)))

    @Test fun twoKnockPattern() = assertEquals(1, run(knocks(2_000, 2_500), config = KnockConfig(count = 2)))
    @Test fun fourKnockPattern() = assertEquals(1, run(knocks(2_000, 2_400, 2_800, 3_200), config = KnockConfig(count = 4)))
    @Test fun fiveKnockPatternIgnoresThree() = assertEquals(0, run(knocks(2_000, 2_400, 2_800), config = KnockConfig(count = 5)))

    @Test fun eventsArriveInOrder() {
        val events = mutableListOf<KnockEvent>()
        run(knocks(2_000, 2_500, 3_000), events = events)
        val counted = events.filterIsInstance<KnockEvent.Knock>()
        assertEquals(listOf(1, 2, 3), counted.map { it.index })
        assertTrue(counted.all { it.strength > 4f })
        assertEquals(KnockEvent.Pattern, events.last { it is KnockEvent.Knock || it == KnockEvent.Pattern })
    }

    @Test fun aFourthKnockIsReportedAsTooMany() {
        val events = mutableListOf<KnockEvent>()
        run(knocks(2_000, 2_400, 2_800, 3_200), events = events)
        assertTrue(KnockEvent.Rejected(KnockEvent.Reason.TooMany) in events)
    }

    @Test(expected = IllegalArgumentException::class)
    fun countIsBounded() {
        KnockConfig(count = 9)
    }

    @Test fun calibrationSitsBelowTheUsualKnock() {
        assertEquals(6f * 0.6f, KnockCalibrator.threshold(listOf(5f, 6f, 7f, 6.5f, 5.5f)), 0.001f)
        assertEquals(KnockConfig.LIGHT, KnockCalibrator.threshold(listOf(1f, 1.2f, 1.1f)), 0.001f)
    }
}
