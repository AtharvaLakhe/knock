package io.github.atharvalakhe.knock

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How a knock pattern is recognised.
 *
 * @property count knocks in the pattern, 2 to 5. A run longer than this is rejected, so a rhythm or a bumpy ride
 *   never counts.
 * @property threshold how hard a knock must be, as the jump in acceleration between two readings (m/s² per sample).
 *   [KnockConfig.LIGHT], [KnockConfig.NORMAL] and [KnockConfig.FIRM] are good starting points; [KnockCalibrator] can
 *   fit it to one person's knocks.
 * @property maxGapMs longest pause between two knocks of the same pattern.
 * @property quietMs how long to wait after the last knock, to be sure no extra knock follows.
 * @property tableFactor a phone lying flat and dead still is on a hard surface, where even a light tap spikes; knocks
 *   that start there must be this many times harder. Set to 1 to turn the check off.
 */
public data class KnockConfig(
    public val count: Int = 3,
    public val threshold: Float = NORMAL,
    public val maxGapMs: Long = 1_100,
    public val quietMs: Long = 700,
    public val tableFactor: Float = 2.2f,
) {
    init {
        require(count in 2..5) { "count must be between 2 and 5, was $count" }
        require(threshold > 0f) { "threshold must be positive" }
        require(maxGapMs in 250..3_000) { "maxGapMs must be between 250 and 3000" }
        require(quietMs in 200..3_000) { "quietMs must be between 200 and 3000" }
        require(tableFactor >= 1f) { "tableFactor must be at least 1" }
    }

    public companion object {
        public const val LIGHT: Float = 2.8f
        public const val NORMAL: Float = 4.5f
        public const val FIRM: Float = 7.5f
    }
}

/** What a [KnockDetector] noticed. */
public sealed interface KnockEvent {
    /** A knock that counted: the [index]th of the pattern, and how hard it was. */
    public data class Knock(val index: Int, val strength: Float) : KnockEvent

    /** A jolt that felt like a knock but was too soft to count. Useful for a "knock harder" hint. */
    public data class Soft(val strength: Float) : KnockEvent

    /** A pattern in progress was thrown away. */
    public data class Rejected(val reason: Reason) : KnockEvent

    /** The full pattern was knocked. */
    public data object Pattern : KnockEvent

    public enum class Reason {
        /** The next knock didn't come within [KnockConfig.maxGapMs]. */
        TooSlow,

        /** More knocks than [KnockConfig.count]: a rhythm, not the signal. */
        TooMany,

        /** The phone kept moving after the jolt: a shove or a drop, not a knock. */
        Moving,
    }
}

/**
 * Finds a deliberate knock pattern in raw accelerometer readings. Pure Kotlin, no Android types, so it can be fed
 * recorded data in a unit test.
 *
 * A knock is a sudden change in acceleration, so the detector watches the jump between one reading and the next.
 * Holding the phone, walking or turning it changes little from sample to sample; a knock's hit and ring change a lot.
 * Each jump opens an 80 ms window whose peak decides whether it was a knock. Nothing else counts for 200 ms after a
 * knock, and by then the phone must have settled, because a shove or a fall keeps moving. The bar rises with ongoing
 * movement, so knocking while walking still works but walking alone never does.
 *
 * Works at any sample rate from 50 to 200 Hz.
 */
public class KnockDetector(
    config: KnockConfig = KnockConfig(),
    private val onEvent: (KnockEvent) -> Unit,
) {
    /** Can be changed at any time, for example after [KnockCalibrator] has run. */
    @Volatile public var config: KnockConfig = config

    /**
     * Whether the screen is covered, from the proximity sensor: true in a pocket or bag, false in the open, null if
     * unknown. Covered phones skip the hard-surface check, since a pocket isn't a table.
     */
    @Volatile public var covered: Boolean? = null

    /** The latest jump, for drawing a live meter. */
    @Volatile public var level: Float = 0f
        private set

    /** True while part of a pattern has been knocked. */
    public val inProgress: Boolean get() = knocks.isNotEmpty() || fireAt != 0L

    private enum class Phase { Idle, Peak, Settle }

    private val prev = FloatArray(3)
    private val grav = FloatArray(3)
    private var primed = false
    private var lastT = 0L
    private var activity = 0f
    private var recent = 0f
    private var phase = Phase.Idle
    private var crossT = 0L
    private var peak = 0f
    private var bar = 0f
    private val knocks = ArrayList<Long>(6)
    private var fireAt = 0L
    private var blockedUntil = 0L
    private var stillSince = 0L
    private var onTable = false

    /** Forget any pattern in progress. */
    public fun reset() {
        knocks.clear(); fireAt = 0L; phase = Phase.Idle
    }

    /** Drop the jolt being measured, for example while the app itself is vibrating the phone. */
    public fun cancelJolt() {
        phase = Phase.Idle
    }

    /** One accelerometer reading in m/s², with its time in milliseconds (any monotonic clock). */
    public fun feed(x: Float, y: Float, z: Float, timeMs: Long) {
        if (!primed) {
            prev[0] = x; prev[1] = y; prev[2] = z
            grav[0] = x; grav[1] = y; grav[2] = z
            primed = true; lastT = timeMs; stillSince = timeMs
            return
        }
        val cfg = config
        val dt = (timeMs - lastT).coerceIn(1, 100).toFloat()
        lastT = timeMs
        val dx = x - prev[0]; val dy = y - prev[1]; val dz = z - prev[2]
        prev[0] = x; prev[1] = y; prev[2] = z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        level = d
        recent += (d - recent) * dt / (50f + dt)
        val g = dt / (400f + dt)
        grav[0] += (x - grav[0]) * g; grav[1] += (y - grav[1]) * g; grav[2] += (z - grav[2]) * g

        if (fireAt != 0L && timeMs >= fireAt) {
            fireAt = 0L; knocks.clear(); onEvent(KnockEvent.Pattern); return
        }
        if (fireAt == 0L && knocks.isNotEmpty() && timeMs - knocks.last() > cfg.maxGapMs) {
            knocks.clear(); onEvent(KnockEvent.Rejected(KnockEvent.Reason.TooSlow))
        }

        when (phase) {
            Phase.Idle -> {
                val resting = resting(timeMs)
                activity += (d - activity) * dt / (500f + dt)
                if (activity > STILL) stillSince = timeMs
                if (knocks.isEmpty() && fireAt == 0L) onTable = resting && covered != true
                val b = maxOf(cfg.threshold * (if (onTable) cfg.tableFactor else 1f), activity * 4f)
                if (d > maxOf(FLOOR, activity * 2.5f, b * 0.35f)) {
                    phase = Phase.Peak; crossT = timeMs; peak = d; bar = b
                }
            }
            Phase.Peak -> {
                if (d > peak) peak = d
                if (timeMs - crossT >= 80) {
                    if (peak >= bar) { phase = Phase.Settle; knock(crossT, peak, cfg) }
                    else { onEvent(KnockEvent.Soft(peak)); phase = Phase.Idle }
                }
            }
            Phase.Settle -> if (timeMs - crossT >= 200) {
                if (recent > bar * 0.4f) {
                    knocks.clear(); fireAt = 0L; blockedUntil = timeMs + 1_000
                    onEvent(KnockEvent.Rejected(KnockEvent.Reason.Moving))
                }
                phase = Phase.Idle
            }
        }
    }

    /** Flat, screen up or down, without even a hand's tremor for two seconds. */
    private fun resting(t: Long): Boolean {
        val flat = abs(grav[2]) > 8.8f && sqrt(grav[0] * grav[0] + grav[1] * grav[1]) < 3.5f
        return flat && activity < STILL && t - stillSince > 2_000
    }

    private fun knock(t: Long, strength: Float, cfg: KnockConfig) {
        if (t < blockedUntil) { blockedUntil = t + 1_000; return }
        if (fireAt != 0L) {
            knocks.clear(); fireAt = 0L; blockedUntil = t + 1_200
            onEvent(KnockEvent.Rejected(KnockEvent.Reason.TooMany)); return
        }
        knocks.lastOrNull()?.let { if (t - it > cfg.maxGapMs) knocks.clear() }
        knocks += t
        onEvent(KnockEvent.Knock(knocks.size, strength))
        if (knocks.size == cfg.count) fireAt = t + cfg.quietMs
    }

    private companion object {
        /** Smallest jump reported, as a soft knock. */
        const val FLOOR = 1.0f

        /** Movement below which the phone is lying still: under a hand's tremor, above sensor noise. */
        const val STILL = 0.12f
    }
}

/** Fits a knock threshold to one person's knocks. */
public object KnockCalibrator {
    /**
     * A threshold a little below the person's typical knock, so a slightly softer one still counts, and never below
     * [KnockConfig.LIGHT], under which ordinary bumps in a pocket start to count.
     *
     * @param strengths the [KnockEvent.Knock.strength] or [KnockEvent.Soft.strength] of five or so practice knocks.
     */
    public fun threshold(strengths: List<Float>, floor: Float = KnockConfig.LIGHT, ceiling: Float = 14f): Float {
        require(strengths.isNotEmpty()) { "Need at least one practice knock" }
        val median = strengths.sorted()[strengths.size / 2]
        return (median * 0.6f).coerceIn(floor, ceiling)
    }
}
