package io.github.atharvalakhe.knock

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A deliberate shake: [peaks] jolts above [thresholdG] within [windowMs], at least [minGapMs] apart, then a
 * [cooldownMs] pause before the next shake can count.
 */
public data class ShakeConfig(
    public val thresholdG: Float = 2.5f,
    public val peaks: Int = 3,
    public val windowMs: Long = 2_000,
    public val minGapMs: Long = 150,
    public val cooldownMs: Long = 3_000,
) {
    init {
        require(thresholdG > 1f) { "thresholdG must be above 1 g, or gravity alone would count" }
        require(peaks in 2..8) { "peaks must be between 2 and 8" }
    }
}

/** Pure shake recognition. Feed it the total acceleration in g. */
public class ShakeDetector(private val config: ShakeConfig = ShakeConfig()) {
    private val peaks = ArrayDeque<Long>()
    private var lastPeakAt = Long.MIN_VALUE / 2
    private var lastShakeAt = Long.MIN_VALUE / 2

    /** One reading: the magnitude of acceleration in g and its time in ms. Returns true when a shake completes. */
    public fun feed(g: Float, timeMs: Long): Boolean {
        val c = config
        if (timeMs - lastShakeAt < c.cooldownMs) return false
        if (g < c.thresholdG || timeMs - lastPeakAt < c.minGapMs) return false
        lastPeakAt = timeMs
        peaks.addLast(timeMs)
        while (peaks.isNotEmpty() && timeMs - peaks.first() > c.windowMs) peaks.removeFirst()
        if (peaks.size >= c.peaks) {
            peaks.clear()
            lastShakeAt = timeMs
            return true
        }
        return false
    }
}

/**
 * Presses of the power button, which Android only reveals as the screen turning on and off.
 *
 * The default rhythm, about one press a second, is deliberately slower than the half-second presses that Android's own
 * emergency SOS and camera shortcut listen for, so the two never clash. Checking the time, a notification lighting the
 * screen or the screen timing out are too slow to add up.
 */
public data class PowerPressConfig(
    public val presses: Int = 3,
    public val maxGapMs: Long = 2_000,
    public val windowMs: Long = 6_000,
    public val cooldownMs: Long = 3_000,
) {
    init { require(presses in 2..6) { "presses must be between 2 and 6" } }
}

public class PowerPressDetector(private val config: PowerPressConfig = PowerPressConfig()) {
    private val times = ArrayList<Long>(8)
    private var quietUntil = 0L

    /** Presses counted in the current run. */
    public val count: Int get() = times.size

    public fun reset(): Unit = times.clear()

    /** The screen turned on or off at [timeMs]. Returns true when the pattern completes. */
    public fun press(timeMs: Long): Boolean {
        if (timeMs < quietUntil) return false
        times.lastOrNull()?.let { last ->
            if (timeMs - last < DUPLICATE_MS) return false
            if (timeMs - last > config.maxGapMs) times.clear()
        }
        times += timeMs
        if (times.size >= config.presses && timeMs - times.first() <= config.windowMs) {
            times.clear(); quietUntil = timeMs + config.cooldownMs
            return true
        }
        return false
    }

    /** Forgets an unfinished run once it has gone quiet. Call it now and then, or after [PowerPressConfig.maxGapMs]. */
    public fun tick(timeMs: Long) {
        val last = times.lastOrNull() ?: return
        if (timeMs - last > config.maxGapMs) times.clear()
    }

    private companion object {
        /** Android sometimes delivers the same screen event twice. */
        const val DUPLICATE_MS = 20L
    }
}

/** How far each stroke of a twist has to turn. */
public enum class TwistStrength(public val radians: Float) {
    GENTLE(0.42f), STANDARD(0.56f), FIRM(0.73f)
}

/** Progress through a twist, for showing a hint while someone practises. */
public data class TwistProgress(val strokes: Int, val detected: Boolean)

/**
 * Twist out, back, out, back: four alternating rotations around one axis, each turning at least
 * [TwistStrength.radians]. Built for a wrist on Wear OS, but it works with any phone that has a gyroscope.
 *
 * Rotation is integrated along the axis of the first movement, whatever way the device is held, so a quick flick that
 * is fast but short never counts.
 */
public class TwistDetector(private val strength: TwistStrength = TwistStrength.STANDARD) {
    private var axis: FloatArray? = null
    private var previousAt = 0L
    private var startedAt = 0L
    private var movingAt = 0L
    private var direction = 1
    private var angle = 0f
    private var counted = false
    private var strokes = 0
    private var restUntil = 0L

    /**
     * One gyroscope reading in rad/s, the total acceleration in g at about the same moment, and the time in ms.
     * Acceleration far from 1 g means the arm itself is swinging, which resets the twist.
     */
    public fun feed(x: Float, y: Float, z: Float, accelerationG: Float, timeMs: Long): TwistProgress {
        val gap = timeMs - previousAt
        previousAt = timeMs
        if (timeMs < restUntil) return TwistProgress(0, false)
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !accelerationG.isFinite() || gap !in 1..120 ||
            accelerationG !in 0.65f..1.7f
        ) {
            reset(); return TwistProgress(0, false)
        }
        if (axis != null && (timeMs - startedAt > 4_500 || timeMs - movingAt > 1_100)) {
            reset(); return TwistProgress(0, false)
        }
        val speed = sqrt(x * x + y * y + z * z)
        if (axis == null) {
            if (speed < 0.8f) return TwistProgress(0, false)
            axis = floatArrayOf(x / speed, y / speed, z / speed)
            startedAt = timeMs; movingAt = timeMs
        }
        val a = axis!!
        val rate = x * a[0] + y * a[1] + z * a[2]
        if (speed > 1.2f && abs(rate) < speed * 0.55f) { reset(); return TwistProgress(0, false) }
        if (abs(rate) < 0.35f) return TwistProgress(strokes, false)
        movingAt = timeMs
        val sign = if (rate > 0) 1 else -1
        if (sign != direction) {
            if (abs(rate) < 0.65f) return TwistProgress(strokes, false)
            if (!counted) { reset(); return TwistProgress(0, false) }
            direction = sign; angle = 0f; counted = false
        }
        angle += abs(rate) * gap / 1_000f
        if (!counted && angle >= strength.radians) {
            strokes++
            counted = true
            if (strokes == 4) {
                reset(); restUntil = timeMs + 1_500
                return TwistProgress(4, true)
            }
        }
        return TwistProgress(strokes, false)
    }

    private fun reset() {
        axis = null; strokes = 0; direction = 1; angle = 0f; counted = false
    }
}
