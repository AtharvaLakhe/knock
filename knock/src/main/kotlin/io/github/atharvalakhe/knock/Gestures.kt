package io.github.atharvalakhe.knock

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/** A completed shake. */
public data object Shake

/** What a run of power-button presses is doing. */
public sealed interface PowerPressEvent {
    /** The [count]th press of the current run. */
    public data class Press(val count: Int) : PowerPressEvent

    /** The full pattern was pressed. */
    public data object Pattern : PowerPressEvent
}

/**
 * Gesture triggers as cold [Flow]s. Sensors are registered when you start collecting and released when you stop, so
 * tie collection to a lifecycle (for example `repeatOnLifecycle`) or to a foreground service for background use.
 */
public object Gestures {

    /**
     * Knock patterns on the phone, read from the accelerometer.
     *
     * @param screenOffOnly ignore everything while the screen is on, so taps and typing can never count. This is what
     *   you want for a phone in a pocket or bag. On phones where knocking wakes the screen, a pattern that woke it may
     *   finish within 1.5 s while the phone is still locked.
     * @param keepCpuAwake hold a partial wake lock while collecting, because many phones stop the accelerometer with the
     *   screen off. Needs the `WAKE_LOCK` permission (this library declares it). Costs battery, so only use it while a
     *   listening feature is actually switched on.
     * @param onLevel called with every reading's jump in acceleration, for drawing a live meter. Called from the
     *   sensor thread up to 200 times a second, so keep it cheap.
     */
    public fun knocks(
        context: Context,
        config: KnockConfig = KnockConfig(),
        screenOffOnly: Boolean = false,
        keepCpuAwake: Boolean = false,
        onLevel: ((Float) -> Unit)? = null,
    ): Flow<KnockEvent> = callbackFlow {
        val app = context.applicationContext
        val sm = app.getSystemService(SensorManager::class.java)
        // the wake-up variant keeps arriving on phones that pause ordinary sensors with the screen off
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || accel == null) {
            close(UnsupportedOperationException("This device has no accelerometer")); return@callbackFlow
        }
        val proximity = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val power = app.getSystemService(PowerManager::class.java)
        val keyguard = app.getSystemService(KeyguardManager::class.java)
        val detector = KnockDetector(config) { trySend(it) }
        var wasInteractive = power?.isInteractive ?: true
        var screenOnAt = 0L
        var wokeMidPattern = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.sensor.type == Sensor.TYPE_PROXIMITY) {
                    detector.covered = e.values[0] < minOf(5f, e.sensor.maximumRange); return
                }
                val now = SystemClock.elapsedRealtime()
                if (screenOffOnly) {
                    val interactive = power?.isInteractive ?: false
                    if (interactive != wasInteractive) {
                        wasInteractive = interactive
                        if (interactive) { screenOnAt = now; wokeMidPattern = detector.inProgress }
                    }
                    val wokeByKnocks = interactive && wokeMidPattern && now - screenOnAt < 1_500 && keyguard?.isKeyguardLocked == true
                    if (interactive && !wokeByKnocks) { detector.reset(); return }
                }
                detector.feed(e.values[0], e.values[1], e.values[2], e.timestamp / 1_000_000)
                onLevel?.invoke(detector.level)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val wakeLock = if (keepCpuAwake && hasPermission(app, Manifest.permission.WAKE_LOCK))
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "knock:listen")?.apply { setReferenceCounted(false); acquire() }
        else null
        // 200 Hz if the phone can, unbatched: a knock lasts 20 to 40 ms
        sm.registerListener(listener, accel, 5_000, 0)
        proximity?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        awaitClose {
            sm.unregisterListener(listener)
            runCatching { wakeLock?.release() }
        }
    }

    /** Deliberate shakes, read from the accelerometer. */
    public fun shakes(context: Context, config: ShakeConfig = ShakeConfig()): Flow<Shake> = callbackFlow {
        val sm = context.applicationContext.getSystemService(SensorManager::class.java)
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || accel == null) {
            close(UnsupportedOperationException("This device has no accelerometer")); return@callbackFlow
        }
        val detector = ShakeDetector(config)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (x, y, z) = e.values
                val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                if (detector.feed(g, e.timestamp / 1_000_000)) trySend(Shake)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }

    /**
     * Presses of the power button, seen as the screen turning on and off. Needs no permission and no sensor, and works
     * with the phone locked. Calls and alarms also switch the screen; pause collection during a call if that matters.
     */
    public fun powerPresses(context: Context, config: PowerPressConfig = PowerPressConfig()): Flow<PowerPressEvent> = callbackFlow {
        val app = context.applicationContext
        val detector = PowerPressDetector(config)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (detector.press(SystemClock.elapsedRealtime())) trySend(PowerPressEvent.Pattern)
                else if (detector.count > 0) trySend(PowerPressEvent.Press(detector.count))
            }
        }
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        val ticker = launch {
            while (isActive) { delay(500); detector.tick(SystemClock.elapsedRealtime()) }
        }
        awaitClose {
            ticker.cancel()
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    /**
     * Twists: out, back, out, back. Needs a gyroscope; on Wear OS it's a flick of the wrist. Emits progress as the
     * strokes are counted, with [TwistProgress.detected] true once the twist is complete.
     */
    public fun twists(context: Context, strength: TwistStrength = TwistStrength.STANDARD): Flow<TwistProgress> = callbackFlow {
        val sm = context.applicationContext.getSystemService(SensorManager::class.java)
        val accel = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (sm == null || accel == null || gyro == null) {
            close(UnsupportedOperationException("This device has no gyroscope")); return@callbackFlow
        }
        val detector = TwistDetector(strength)
        var accelerationG = 1f
        var accelerationAt = 0L
        var lastStrokes = -1
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val now = e.timestamp / 1_000_000
                when (e.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val (x, y, z) = e.values
                        accelerationG = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
                        accelerationAt = now
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        if (accelerationAt == 0L || abs(now - accelerationAt) > 250) return
                        val p = detector.feed(e.values[0], e.values[1], e.values[2], accelerationG, now)
                        if (p.detected || p.strokes != lastStrokes) { lastStrokes = p.strokes; trySend(p) }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
        sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_GAME)
        awaitClose { sm.unregisterListener(listener) }
    }

    /** Whether this device has the sensors a gesture needs. */
    public fun isSupported(context: Context, gesture: Gesture): Boolean {
        val sm = context.applicationContext.getSystemService(SensorManager::class.java) ?: return gesture == Gesture.PowerPress
        return when (gesture) {
            Gesture.Knock, Gesture.Shake -> sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            Gesture.Twist -> sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null && sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
            Gesture.PowerPress -> true
        }
    }

    public enum class Gesture { Knock, Shake, PowerPress, Twist }

    @SuppressLint("InlinedApi")
    private fun hasPermission(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
