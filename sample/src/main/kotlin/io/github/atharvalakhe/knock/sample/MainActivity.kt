package io.github.atharvalakhe.knock.sample

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.atharvalakhe.knock.Gestures
import io.github.atharvalakhe.knock.KnockCalibrator
import io.github.atharvalakhe.knock.KnockConfig
import io.github.atharvalakhe.knock.KnockEvent
import io.github.atharvalakhe.knock.PowerPressEvent
import io.github.atharvalakhe.knock.TwistStrength
import kotlinx.coroutines.delay

private val Bg = Color(0xFF0E0F11)
private val Panel = Color(0xFF16181B)
private val Line = Color(0xFF26292D)
private val Ink = Color(0xFFF1EEE8)
private val Muted = Color(0xFF8C9096)
private val Accent = Color(0xFFFF7A45)
private val Mono = FontFamily.Monospace

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { Demo() }
    }
}

private data class LogLine(val at: Long, val text: String)

@Composable
private fun Demo() {
    val context = LocalContext.current
    val log = remember { mutableStateListOf<LogLine>() }
    fun note(text: String) {
        log.add(0, LogLine(SystemClock.elapsedRealtime(), text))
        if (log.size > 30) log.removeAt(log.lastIndex)
    }

    var threshold by remember { mutableFloatStateOf(KnockConfig.NORMAL) }
    var count by remember { mutableIntStateOf(3) }
    var knocked by remember { mutableIntStateOf(0) }
    var level by remember { mutableFloatStateOf(0f) }
    var flash by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    val practice = remember { mutableStateListOf<Float>() }

    // knocks: the whole library call is this one collect
    LaunchedEffect(threshold, count) {
        var shown = 0L
        Gestures.knocks(context, KnockConfig(count = count, threshold = threshold), onLevel = { l ->
            val now = SystemClock.elapsedRealtime()
            if (now - shown > 33 || l > level) { level = l; shown = now }
        }).collect { e ->
            when (e) {
                is KnockEvent.Knock -> {
                    knocked = e.index
                    if (calibrating) practice += e.strength
                    note("knock ${e.index} of $count  ·  %.1f".format(e.strength))
                }
                is KnockEvent.Soft -> {
                    if (calibrating) practice += e.strength
                    note("too soft  ·  %.1f".format(e.strength))
                }
                is KnockEvent.Rejected -> { knocked = 0; note("dropped: ${e.reason.name.lowercase()}") }
                KnockEvent.Pattern -> { knocked = 0; flash = true; note("PATTERN: $count knocks") }
            }
            if (calibrating && practice.size >= 5) {
                threshold = KnockCalibrator.threshold(practice)
                calibrating = false
                practice.clear()
                note("calibrated: threshold %.1f".format(threshold))
            }
        }
    }
    LaunchedEffect(flash) { if (flash) { delay(900); flash = false } }

    var shakes by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { Gestures.shakes(context).collect { shakes++; note("shake") } }

    var presses by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        Gestures.powerPresses(context).collect { e ->
            when (e) {
                is PowerPressEvent.Press -> presses = e.count
                PowerPressEvent.Pattern -> { presses = 0; note("power button: 3 presses") }
            }
        }
    }

    val hasGyro = remember { Gestures.isSupported(context, Gestures.Gesture.Twist) }
    var strokes by remember { mutableIntStateOf(0) }
    if (hasGyro) LaunchedEffect(Unit) {
        Gestures.twists(context, TwistStrength.STANDARD).collect { p ->
            strokes = if (p.detected) 0 else p.strokes
            if (p.detected) note("twist")
        }
    }

    val bg by animateColorAsState(if (flash) Accent else Bg, tween(if (flash) 60 else 500), label = "flash")
    Column(
        Modifier.fillMaxSize().background(bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text("Knock", color = Ink, fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
        Text("Gesture triggers for Android. Knock on the back of this phone.", color = Muted, fontSize = 15.sp)
        Spacer(Modifier.height(22.dp))

        Section("KNOCK") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(count) { i ->
                    val on = i < knocked
                    val size by animateFloatAsState(if (on) 30f else 22f, label = "dot")
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(size.dp).clip(CircleShape).background(if (on) Accent else Color.Transparent)
                                .border(2.dp, if (on) Accent else Line, CircleShape),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                Stepper(count, onMinus = { if (count > 2) count-- }, onPlus = { if (count < 5) count++ })
            }
            Spacer(Modifier.height(16.dp))
            Meter(level, threshold)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Light" to KnockConfig.LIGHT, "Normal" to KnockConfig.NORMAL, "Firm" to KnockConfig.FIRM).forEach { (name, t) ->
                    Chip(name, selected = kotlin.math.abs(threshold - t) < .01f) { threshold = t }
                }
                Chip(if (calibrating) "Knock 5 times… ${practice.size}/5" else "Calibrate", selected = calibrating) {
                    calibrating = !calibrating; practice.clear()
                }
            }
        }

        Section("SHAKE") {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$shakes", color = Ink, fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = Mono)
                Spacer(Modifier.width(10.dp))
                Text("deliberate shakes. Walking never counts.", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
            }
        }

        Section("POWER BUTTON") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { i -> Pip(i < presses); Spacer(Modifier.width(6.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Press it 3 times, about once a second.", color = Muted, fontSize = 14.sp)
            }
        }

        if (hasGyro) Section("TWIST") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(4) { i -> Pip(i < strokes); Spacer(Modifier.width(6.dp)) }
                Spacer(Modifier.width(10.dp))
                Text("Twist out, back, out, back.", color = Muted, fontSize = 14.sp)
            }
        }

        Section("EVENTS") {
            if (log.isEmpty()) Text("Nothing yet. Try three firm knocks on the back of the phone.", color = Muted, fontSize = 14.sp)
            log.take(12).forEach { line ->
                val pattern = line.text.startsWith("PATTERN") || line.text == "shake" || line.text == "twist" || line.text.startsWith("power")
                Text(
                    line.text, color = if (pattern) Accent else Ink, fontSize = 13.sp,
                    style = TextStyle(fontFamily = Mono), modifier = Modifier.padding(vertical = 3.dp),
                )
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(14.dp)).background(Panel).padding(16.dp)) {
        Text(title, color = Muted, fontSize = 11.sp, letterSpacing = 1.6.sp, fontFamily = Mono)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** The live jolt, with the threshold drawn as a dashed line. */
@Composable
private fun Meter(level: Float, threshold: Float) {
    val max = maxOf(threshold * 2.2f, 10f)
    val shown by animateFloatAsState((level / max).coerceIn(0f, 1f), tween(60), label = "meter")
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val r = CornerRadius(8.dp.toPx())
        drawRoundRect(Line, cornerRadius = r)
        drawRoundRect(if (level >= threshold) Accent else Ink.copy(alpha = .75f), size = Size(size.width * shown, size.height), cornerRadius = r)
        val x = size.width * threshold / max
        drawLine(Accent, Offset(x, -4f), Offset(x, size.height + 4f), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text("jolt %.1f".format(level), color = Muted, fontSize = 12.sp, fontFamily = Mono)
        Spacer(Modifier.weight(1f))
        Text("threshold %.1f".format(threshold), color = Accent, fontSize = 12.sp, fontFamily = Mono)
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, color = if (selected) Bg else Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Accent else Color.Transparent)
            .border(1.dp, if (selected) Accent else Line, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Stepper(value: Int, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("−", color = Ink, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onMinus).padding(horizontal = 10.dp))
        Text("$value", color = Ink, fontSize = 18.sp, fontFamily = Mono)
        Text("+", color = Ink, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onPlus).padding(horizontal = 10.dp))
    }
}

@Composable
private fun Pip(on: Boolean) {
    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(if (on) Accent else Line))
}
