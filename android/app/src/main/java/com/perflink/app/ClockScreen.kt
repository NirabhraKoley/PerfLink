package com.perflink.app

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JTextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// The Clock page: phone-only (no PC needed). Black background, three tabs: Clock & Calendar, Timers, Stopwatch.

private object ClockColors {
    val bg = Color.Black
    val accent = Color(Page.Clock.color)
    val panel = Color(0xFF101317)
    val panelBorder = Color(0xFF262C34)
    val off = Color(0xFF1A1D22)
    val text = Color(0xFFF2F4F7)
    val dim = Color(0xFF8D96A3)
    val second = Color(0xFFFF6B5A)
    val sunday = Color(0xFFFF8A80)
}

@Composable
fun ClockPage(vm: ClockViewModel) {
    Column(
        Modifier.fillMaxSize().background(ClockColors.bg).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 18.dp),
    ) {
        // ---- the three tabs (big white buttons, like the drawing)
        Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val busy = listOf(false, vm.timerState == TimerState.Running || vm.ringing, vm.swRunning)
            listOf("Clock & Calendar", "Timers", "Stopwatch").forEachIndexed { i, label ->
                TabButton(label, vm.tab == i, busy[i], Modifier.weight(1f).fillMaxHeight()) { vm.tab = i }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (vm.tab) {
                0 -> ClockCalendarTab()
                1 -> TimerTab(vm)
                else -> StopwatchTab(vm)
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.clip(shape).background(if (selected) Color.White else ClockColors.off)
            .border(1.dp, if (selected) Color.White else Color(0x33FFFFFF), shape).clickable { onClick() },
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        // a dot while the timer or stopwatch on that tab is running
        if (busy) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(ClockColors.accent))
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = if (selected) Color.Black else ClockColors.text, fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

/** Small pill switch button ("Analog", "Digital", "12h", "24h", "5 min"…). */
@Composable
private fun Pill(label: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, hPad: Dp = 14.dp,
                 onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.clip(shape).background(if (on) Color.White else ClockColors.off)
            .border(1.dp, if (on) Color.White else Color(0x33FFFFFF), shape)
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = hPad),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = when { on -> Color.Black; enabled -> ClockColors.text; else -> ClockColors.dim.copy(alpha = 0.5f) },
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Big rounded action button (Start, Pause, Lap…). */
@Composable
private fun ActionButton(label: String, color: Color, modifier: Modifier, filled: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val c = if (enabled) color else ClockColors.dim.copy(alpha = 0.4f)
    Box(
        modifier.clip(shape).background(if (filled && enabled) c else ClockColors.off)
            .border(1.5.dp, c.copy(alpha = if (filled) 1f else 0.7f), shape).clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled && enabled) Color.Black else c, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Recomposes every [stepMs] while [running]; returns elapsedRealtime. */
@Composable
private fun rememberElapsedTick(running: Boolean, stepMs: Long): Long {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) { while (running) { delay(stepMs); tick++ } }
    return tick.let { SystemClock.elapsedRealtime() }   // reads tick, so each tick recomposes the caller
}

// ================================================================== Clock & Calendar
/** A clock design: its name and accent colour (the calendar card takes the same accent, so they match). */
private data class ClockDesign(
    val name: String, val accent: Color,
    val cardBg: Color = ClockColors.panel, val cardText: Color = ClockColors.text,   // the calendar card's tint
)

// the old-school pair: warm sepia / brass calendar
private val Brass = Color(0xFFC9A25A)
private val NixieOrange = Color(0xFFFF8A2A)

private val AnalogDesigns = listOf(
    ClockDesign("Classic", Color(0xFF2EE6C5)),
    ClockDesign("Minimal", Color(0xFFFF7A45)),
    ClockDesign("Neon", Color(0xFFFF3DF2)),
    ClockDesign("Roman", Color(0xFFD9B45A)),
    ClockDesign("Vintage", Brass, cardBg = Color(0xFF1C160D), cardText = Color(0xFFF1E4C6)),
)
private val DigitalDesigns = listOf(
    ClockDesign("Thin", Color(0xFF2EE6C5)),
    ClockDesign("LCD", Color(0xFFFFB020)),
    ClockDesign("Neon", Color(0xFF3DF5FF)),
    ClockDesign("Flip", Color(0xFFFF8A4C)),
    ClockDesign("Nixie", NixieOrange, cardBg = Color(0xFF170E08), cardText = Color(0xFFF6DEC4)),
)
private val NeonCyan = Color(0xFF3DF5FF)

@Composable
private fun ClockCalendarTab() {
    val prefs = LocalContext.current.getSharedPreferences("perflink", 0)
    var analog by remember { mutableStateOf(startClockDigital?.not() ?: prefs.getBoolean("clock_analog", true)) }
    var use24 by remember { mutableStateOf(prefs.getBoolean("clock_24h", false)) }
    // the design chosen for each mode, remembered separately
    var analogStyle by remember { mutableIntStateOf(startClockStyle ?: prefs.getInt("clock_style_analog", 0)) }
    var digitalStyle by remember { mutableIntStateOf(startClockStyle ?: prefs.getInt("clock_style_digital", 0)) }
    val designs = if (analog) AnalogDesigns else DigitalDesigns
    val style = Math.floorMod(if (analog) analogStyle else digitalStyle, designs.size)
    val design = designs[style]
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - (System.currentTimeMillis() % 1000L))
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1.3f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Analog", analog, Modifier.fillMaxHeight(), hPad = 12.dp) { analog = true; prefs.edit().putBoolean("clock_analog", true).apply() }
                Pill("Digital", !analog, Modifier.fillMaxHeight(), hPad = 12.dp) { analog = false; prefs.edit().putBoolean("clock_analog", false).apply() }
                Spacer(Modifier.weight(1f))
                // tap to go to the next design
                StyleButton(design, style, designs.size, Modifier.fillMaxHeight()) {
                    val next = (style + 1) % designs.size
                    if (analog) { analogStyle = next; prefs.edit().putInt("clock_style_analog", next).apply() }
                    else { digitalStyle = next; prefs.edit().putInt("clock_style_digital", next).apply() }
                }
            }
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (analog) AnalogClock(now, minOf(maxWidth, maxHeight), style, design.accent)
                else Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    // the same 12h / 24h setting as the Home clock: a slim pill left of the time
                    Use24Switch(use24, design.accent) { use24 = it; prefs.edit().putBoolean("clock_24h", it).apply() }
                    Spacer(Modifier.width(8.dp))
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        DigitalClock(now, use24, maxWidth, maxHeight, style, design.accent)
                    }
                }
            }
        }
        CalendarCard(now.toLocalDate(), Modifier.weight(1f).fillMaxHeight(), design)
    }
}

/** "● Neon ⋮" — the current design; each tap shows the next one. */
@Composable
private fun StyleButton(design: ClockDesign, index: Int, count: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(ClockColors.off).border(1.dp, design.accent.copy(alpha = 0.7f), shape)
            .clickable { onClick() }.padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(design.accent))
        Spacer(Modifier.width(6.dp))
        Text(design.name, color = ClockColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        // which of the designs this is
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(count) { i ->
                Box(Modifier.size(3.dp).clip(CircleShape).background(if (i == index) design.accent else ClockColors.dim.copy(alpha = 0.5f)))
            }
        }
    }
}

/** 12h over 24h, a slim stacked pill (like the Home clock's). */
@Composable
private fun Use24Switch(use24: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Column(
        Modifier.width(38.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)).padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to "12h", true to "24h").forEach { (v, label) ->
            val on = use24 == v
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) accent else Color.Transparent)
                    .clickable { onChange(v) }.padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color.Black else ClockColors.dim, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 12.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- analog faces
@Composable
private fun AnalogClock(now: LocalDateTime, diameter: Dp, style: Int, accent: Color) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val angles = handAngles(now.hour, now.minute, now.second)
    val dateLabel = now.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())).uppercase()
    Canvas(Modifier.size(diameter)) {
        val r = size.minDimension / 2f
        val c = center
        fun at(deg: Float, len: Float): Offset {
            val rad = Math.toRadians(deg.toDouble())
            return Offset(c.x + (sin(rad) * len).toFloat(), c.y - (cos(rad) * len).toFloat())
        }
        fun sp(px: Float) = with(density) { px.toSp() }
        fun label(text: String, ts: TextStyle, at: Offset) {
            val l = measurer.measure(text, ts)
            drawText(l, topLeft = Offset(at.x - l.size.width / 2f, at.y - l.size.height / 2f))
        }
        when (style) {
            // ---- Minimal: no numbers, thin ticks, accent second hand
            1 -> {
                drawCircle(Color(0xFF0D0D0F), r)
                drawCircle(Color(0xFF2A2C30), r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                for (i in 0 until 12) {
                    val quarter = i % 3 == 0
                    drawLine(if (quarter) ClockColors.text else ClockColors.dim, at(i * 30f, r * 0.91f),
                        at(i * 30f, r * if (quarter) 0.76f else 0.84f), strokeWidth = r * if (quarter) 0.03f else 0.012f,
                        cap = StrokeCap.Round)
                }
                label(dateLabel, TextStyle(color = ClockColors.dim, fontSize = sp(r * 0.075f), fontWeight = FontWeight.Medium,
                    letterSpacing = sp(r * 0.02f)), Offset(c.x, c.y + r * 0.42f))
                hand(c, at(angles.hour, r * 0.50f), ClockColors.text, r * 0.04f)
                hand(c, at(angles.minute, r * 0.80f), ClockColors.text, r * 0.024f)
                drawLine(accent, at(angles.second + 180f, r * 0.12f), at(angles.second, r * 0.90f), strokeWidth = r * 0.01f,
                    cap = StrokeCap.Round)
                drawCircle(accent, r * 0.032f, c)
            }
            // ---- Neon: glowing ring, dots and hands
            2 -> {
                drawCircle(Color(0xFF07030A), r)
                glowRing(accent, c, r * 0.93f, r * 0.02f)
                for (i in 0 until 60) {
                    if (i % 5 == 0) {
                        drawCircle(NeonCyan.copy(alpha = 0.25f), r * 0.04f, at(i * 6f, r * 0.82f))
                        drawCircle(NeonCyan, r * 0.018f, at(i * 6f, r * 0.82f))
                    } else drawCircle(accent.copy(alpha = 0.35f), r * 0.006f, at(i * 6f, r * 0.82f))
                }
                val num = TextStyle(color = Color.White, fontSize = sp(r * 0.17f), fontWeight = FontWeight.Medium,
                    shadow = Shadow(accent, Offset.Zero, r * 0.07f))
                listOf(12 to 0f, 3 to 90f, 6 to 180f, 9 to 270f).forEach { (n, a) -> label("$n", num, at(a, r * 0.64f)) }
                label(dateLabel, TextStyle(color = NeonCyan, fontSize = sp(r * 0.08f), fontWeight = FontWeight.Bold,
                    letterSpacing = sp(r * 0.015f), shadow = Shadow(NeonCyan, Offset.Zero, r * 0.05f)), Offset(c.x, c.y + r * 0.36f))
                glowLine(NeonCyan, c, at(angles.hour, r * 0.48f), r * 0.035f)
                glowLine(NeonCyan, c, at(angles.minute, r * 0.74f), r * 0.024f)
                glowLine(accent, at(angles.second + 180f, r * 0.14f), at(angles.second, r * 0.86f), r * 0.012f)
                drawCircle(accent, r * 0.04f, c)
                drawCircle(Color.White, r * 0.015f, c)
            }
            // ---- Roman: Roman numerals, slim gold rings and leaf hands
            3 -> {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF221C12), Color(0xFF0B0906)), c, r), r)
                drawCircle(accent, r * 0.965f, style = Stroke(r * 0.014f))
                drawCircle(accent.copy(alpha = 0.7f), r * 0.90f, style = Stroke(r * 0.006f))
                drawCircle(accent.copy(alpha = 0.7f), r * 0.84f, style = Stroke(r * 0.006f))
                for (i in 0 until 60) {   // railway minute track between the two inner rings
                    drawLine(accent.copy(alpha = if (i % 5 == 0) 1f else 0.6f), at(i * 6f, r * 0.84f), at(i * 6f, r * 0.90f),
                        strokeWidth = r * if (i % 5 == 0) 0.016f else 0.006f)
                }
                val romans = listOf("XII", "I", "II", "III", "IIII", "V", "VI", "VII", "VIII", "IX", "X", "XI")
                val num = TextStyle(color = accent, fontSize = sp(r * 0.13f), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Medium)
                romans.forEachIndexed { i, t -> label(t, num, at(i * 30f, r * 0.70f)) }
                label(dateLabel, TextStyle(color = accent.copy(alpha = 0.85f), fontSize = sp(r * 0.075f), fontFamily = FontFamily.Serif,
                    letterSpacing = sp(r * 0.015f)), Offset(c.x, c.y + r * 0.38f))
                leafHand(c, angles.hour, r * 0.48f, r * 0.055f, accent)
                leafHand(c, angles.minute, r * 0.76f, r * 0.04f, accent)
                drawLine(Color(0xFFB8443E), at(angles.second + 180f, r * 0.16f), at(angles.second, r * 0.84f), strokeWidth = r * 0.008f,
                    cap = StrokeCap.Round)
                drawCircle(accent, r * 0.04f, c)
                drawCircle(Color(0xFF0B0906), r * 0.014f, c)
            }
            // ---- Vintage: aged ivory dial, brass bezel, serif numerals, Breguet hands, red seconds
            4 -> {
                val inkC = Color(0xFF1C1712)
                // brass bezel with a sweep of shine
                drawCircle(Brush.sweepGradient(listOf(Color(0xFFB8893B), Color(0xFFF3D98B), Color(0xFF8A6424), Color(0xFFD9B45A),
                    Color(0xFF6E4C1E), Color(0xFFE6C677), Color(0xFFB8893B)), c), r)
                drawCircle(Color(0xFF4A3414), r, style = Stroke(r * 0.012f))
                drawCircle(Color(0xFF5A4220), r * 0.905f, style = Stroke(r * 0.02f))
                // aged paper dial: light in the middle, darker toward the rim
                drawCircle(Brush.radialGradient(listOf(Color(0xFFF7EED6), Color(0xFFEBDCB6), Color(0xFFCDB585)), c, r * 0.9f), r * 0.895f)
                // fine minute track with dots
                drawCircle(inkC, r * 0.84f, style = Stroke(r * 0.006f))
                drawCircle(inkC, r * 0.77f, style = Stroke(r * 0.004f))
                for (i in 0 until 60) {
                    val p = at(i * 6f, r * 0.805f)
                    if (i % 5 == 0) drawCircle(inkC, r * 0.02f, p) else drawCircle(inkC.copy(alpha = 0.8f), r * 0.007f, p)
                }
                val num = TextStyle(color = inkC, fontSize = sp(r * 0.15f), fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal)
                for (n in 1..12) label(n.toString(), num, at(n * 30f, r * 0.62f))
                // small framed date window
                val dl = measurer.measure(dateLabel, TextStyle(color = inkC, fontSize = sp(r * 0.07f), fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold, letterSpacing = sp(r * 0.01f)))
                val boxW = dl.size.width + r * 0.08f; val boxH = dl.size.height + r * 0.02f
                val boxTL = Offset(c.x - boxW / 2f, c.y + r * 0.34f)
                drawRoundRect(Color(0xFFFBF6E8), boxTL, Size(boxW, boxH), CornerRadius(r * 0.015f))
                drawRoundRect(Color(0xFF8A6424), boxTL, Size(boxW, boxH), CornerRadius(r * 0.015f), style = Stroke(r * 0.012f))
                drawText(dl, topLeft = Offset(c.x - dl.size.width / 2f, boxTL.y + (boxH - dl.size.height) / 2f))
                // hands
                breguetHand(c, angles.hour, r * 0.50f, r * 0.028f, inkC)
                breguetHand(c, angles.minute, r * 0.74f, r * 0.02f, inkC)
                val red = Color(0xFFB3261E)
                drawLine(red, at(angles.second + 180f, r * 0.20f), at(angles.second, r * 0.80f), strokeWidth = r * 0.008f,
                    cap = StrokeCap.Round)
                drawCircle(red, r * 0.03f, at(angles.second + 180f, r * 0.16f))
                drawCircle(inkC, r * 0.045f, c)
                drawCircle(Color(0xFFD9B45A), r * 0.02f, c)
            }
            // ---- Classic
            else -> {
                drawCircle(Color(0xFF15181D), r)
                drawCircle(Color(0xFF3A414B), r - 1.5.dp.toPx(), style = Stroke(3.dp.toPx()))
                for (i in 0 until 60) {
                    val major = i % 5 == 0
                    val outer = r * 0.93f
                    drawLine(
                        if (major) ClockColors.text else ClockColors.dim.copy(alpha = 0.6f),
                        at(i * 6f, outer), at(i * 6f, outer - if (major) r * 0.09f else r * 0.04f),
                        strokeWidth = if (major) r * 0.022f else r * 0.009f, cap = StrokeCap.Round,
                    )
                }
                val num = TextStyle(color = ClockColors.text, fontSize = sp(r * 0.15f), fontWeight = FontWeight.Medium)
                for (n in 1..12) label(n.toString(), num, at(n * 30f, r * 0.70f))
                label(dateLabel, TextStyle(color = accent, fontSize = sp(r * 0.085f), fontWeight = FontWeight.Bold,
                    letterSpacing = sp(r * 0.01f)), Offset(c.x, c.y + r * 0.40f))
                hand(c, at(angles.hour, r * 0.50f), ClockColors.text, r * 0.055f)
                hand(c, at(angles.minute, r * 0.76f), ClockColors.text, r * 0.035f)
                drawLine(ClockColors.second, at(angles.second + 180f, r * 0.16f), at(angles.second, r * 0.84f),
                    strokeWidth = r * 0.014f, cap = StrokeCap.Round)
                drawCircle(ClockColors.second, r * 0.045f, c)
                drawCircle(Color(0xFF15181D), r * 0.018f, c)
            }
        }
    }
}

private fun DrawScope.hand(from: Offset, to: Offset, color: Color, width: Float) =
    drawLine(color, from, to, strokeWidth = width, cap = StrokeCap.Round)

/** A line with a soft glow around it and a bright core (a neon tube). */
private fun DrawScope.glowLine(color: Color, a: Offset, b: Offset, w: Float) {
    listOf(5f to 0.07f, 3f to 0.14f, 1.8f to 0.3f).forEach { (m, al) ->
        drawLine(color.copy(alpha = al), a, b, strokeWidth = w * m, cap = StrokeCap.Round)
    }
    drawLine(color, a, b, strokeWidth = w, cap = StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.75f), a, b, strokeWidth = w * 0.35f, cap = StrokeCap.Round)
}

private fun DrawScope.glowRing(color: Color, c: Offset, radius: Float, w: Float) {
    listOf(5f to 0.06f, 3f to 0.12f, 1.8f to 0.28f).forEach { (m, al) ->
        drawCircle(color.copy(alpha = al), radius, c, style = Stroke(w * m))
    }
    drawCircle(color, radius, c, style = Stroke(w))
    drawCircle(Color.White.copy(alpha = 0.6f), radius, c, style = Stroke(w * 0.3f))
}

/** An old-school Breguet hand: a slim shaft, an open ring ("moon") near the end, then a fine point. */
private fun DrawScope.breguetHand(c: Offset, deg: Float, len: Float, w: Float, color: Color) {
    val rad = Math.toRadians(deg.toDouble())
    val dir = Offset(sin(rad).toFloat(), -cos(rad).toFloat())
    val side = Offset(-dir.y, dir.x)
    val ringR = len * 0.085f
    val ringC = c + dir * (len * 0.70f)
    // counterweight tail, shaft up to the ring
    drawLine(color, c - dir * (len * 0.16f), ringC - dir * ringR, strokeWidth = w, cap = StrokeCap.Round)
    drawCircle(color, w * 1.6f, c - dir * (len * 0.16f))
    drawCircle(color, ringR, ringC, style = Stroke(w * 0.95f))
    // tapered tip from the ring to the end
    val base = ringC + dir * ringR
    val l = base + side * (w * 0.9f)
    val rt = base - side * (w * 0.9f)
    val tip = c + dir * len
    drawPath(Path().apply { moveTo(l.x, l.y); lineTo(tip.x, tip.y); lineTo(rt.x, rt.y); close() }, color)
}

/** A tapered (leaf-shaped) hand for the Roman face. */
private fun DrawScope.leafHand(c: Offset, deg: Float, len: Float, halfWidth: Float, color: Color) {
    val rad = Math.toRadians(deg.toDouble())
    val dir = Offset(sin(rad).toFloat(), -cos(rad).toFloat())
    val side = Offset(-dir.y, dir.x)
    val tail = c - dir * (len * 0.14f)
    val mid = c + dir * (len * 0.28f)
    val tip = c + dir * len
    val l = mid + side * halfWidth
    val rt = mid - side * halfWidth
    val path = Path().apply {
        moveTo(tail.x, tail.y); lineTo(l.x, l.y); lineTo(tip.x, tip.y); lineTo(rt.x, rt.y); close()
    }
    drawPath(path, color)
}

// ---------------------------------------------------------------- digital faces
@Composable
private fun DigitalClock(now: LocalDateTime, use24: Boolean, maxW: Dp, maxH: Dp, style: Int, accent: Color) {
    val h = maxH - 36.dp   // room for the date line below
    val neon = style == 2
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        when (style) {
            1 -> LcdTime(now, use24, maxW, h, accent)
            3 -> FlipTime(now, use24, maxW, h, accent)
            4 -> NixieTime(now, use24, maxW, h, accent)
            else -> TextTime(now, use24, maxW, h, neon = neon, accent = accent)
        }
        Spacer(Modifier.height(6.dp))
        Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())),
            color = when { neon -> accent; style == 4 -> Color(0xFFF1D9B8); else -> ClockColors.text }, fontSize = 16.sp, fontWeight = FontWeight.Light, maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (neon) TextStyle(shadow = Shadow(accent, Offset.Zero, 12f)) else TextStyle.Default)
    }
}

/** Thin (the first design) and Neon: big text, with seconds and AM/PM beside it. */
@Composable
private fun TextTime(now: LocalDateTime, use24: Boolean, w: Dp, h: Dp, neon: Boolean, accent: Color) {
    val density = LocalDensity.current
    // "12:59" is ~2.6x the font size wide, plus seconds / AM-PM beside it (~0.5x)
    val sizeDp = minOf(h / 1.12f, w / 3.25f).coerceIn(28.dp, 170.dp)
    val sizeSp = with(density) { sizeDp.toSp() }
    val small = (sizeSp.value * 0.26f).coerceAtLeast(13f).sp
    val glow = if (neon) TextStyle(shadow = Shadow(accent, Offset.Zero, with(density) { (sizeDp * 0.22f).toPx() })) else TextStyle.Default
    val main = if (neon) lerp(accent, Color.White, 0.55f) else ClockColors.text
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(digitalTime(now.hour, now.minute, use24), color = main, fontSize = sizeSp,
            fontWeight = if (neon) FontWeight.Light else FontWeight.ExtraLight, lineHeight = sizeSp, maxLines = 1, style = glow)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(String.format(Locale.US, "%02d", now.second), color = accent, fontSize = small,
                fontWeight = FontWeight.Light, lineHeight = small, style = glow)
            if (!use24) Text(amPm(now.hour), color = if (neon) accent.copy(alpha = 0.8f) else ClockColors.dim,
                fontSize = small * 0.8f, fontWeight = FontWeight.Medium, lineHeight = small * 0.9f)
        }
    }
}

// segments a b c d e f g (top, top-right, bottom-right, bottom, bottom-left, top-left, middle) lit for 0–9
private val SEGMENTS = listOf("abcdef", "bc", "abged", "abgcd", "fgbc", "afgcd", "afgedc", "abc", "abcdefg", "abcdfg")

/** LCD: seven-segment digits (unlit segments faintly visible) on a dark panel; the colon blinks. */
@Composable
private fun LcdTime(now: LocalDateTime, use24: Boolean, w: Dp, h: Dp, accent: Color) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // in units of the digit height H: HH (1.1) + colon (0.3) + MM (1.1) + gap (0.15) + small SS (0.5) = ~3.15
    val digitH = minOf((w - 28.dp) / 3.2f, h - 24.dp).coerceIn(30.dp, 150.dp)
    val hour = if (use24) now.hour else (now.hour % 12).let { if (it == 0) 12 else it }
    val hh: List<Int?> = if (!use24 && hour < 10) listOf(null, hour) else listOf(hour / 10, hour % 10)
    val panel = RoundedCornerShape(16.dp)
    Box(Modifier.clip(panel).background(Color(0xFF100C05)).border(1.dp, accent.copy(alpha = 0.3f), panel)
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        Canvas(Modifier.width(digitH * 3.15f).height(digitH)) {
            val big = size.height
            val dw = big * 0.5f
            val off = accent.copy(alpha = 0.07f)
            var x = 0f
            fun digit(d: Int?, x0: Float, y0: Float, dh: Float) = segDigit(d, x0, y0, dh * 0.5f, dh, accent, off)
            digit(hh[0], x, 0f, big); x += dw + big * 0.1f
            digit(hh[1], x, 0f, big); x += dw
            // colon, blinking each second
            val colonOn = now.second % 2 == 0
            listOf(0.32f, 0.68f).forEach { fy -> drawCircle(if (colonOn) accent else off, big * 0.045f, Offset(x + big * 0.15f, big * fy)) }
            x += big * 0.3f
            digit(now.minute / 10, x, 0f, big); x += dw + big * 0.1f
            digit(now.minute % 10, x, 0f, big); x += dw + big * 0.15f
            // seconds, small and at the bottom; AM/PM above them
            val sh = big * 0.45f
            digit(now.second / 10, x, big - sh, sh)
            digit(now.second % 10, x + sh * 0.6f, big - sh, sh)
            if (!use24) {
                val l = measurer.measure(amPm(now.hour), TextStyle(color = accent, fontSize = with(density) { (big * 0.2f).toSp() },
                    fontWeight = FontWeight.Bold))
                drawText(l, topLeft = Offset(x, big * 0.04f))
            }
        }
    }
}

private fun DrawScope.segDigit(d: Int?, x: Float, y: Float, dw: Float, dh: Float, on: Color, off: Color) {
    val lit = d?.let { SEGMENTS[it] } ?: ""
    val t = dw * 0.17f
    val skew = 0.08f   // LCD digits lean slightly right
    fun p(px: Float, py: Float) = Offset(x + px + (dh - py) * skew, y + py)
    val m = dh / 2f
    val segs = listOf(
        'a' to (p(t, t / 2) to p(dw - t, t / 2)),
        'b' to (p(dw - t / 2, t) to p(dw - t / 2, m - t * 0.6f)),
        'c' to (p(dw - t / 2, m + t * 0.6f) to p(dw - t / 2, dh - t)),
        'd' to (p(t, dh - t / 2) to p(dw - t, dh - t / 2)),
        'e' to (p(t / 2, m + t * 0.6f) to p(t / 2, dh - t)),
        'f' to (p(t / 2, t) to p(t / 2, m - t * 0.6f)),
        'g' to (p(t, m) to p(dw - t, m)),
    )
    segs.forEach { (k, ab) ->
        val isOn = k in lit
        if (isOn) drawLine(on.copy(alpha = 0.2f), ab.first, ab.second, strokeWidth = t * 2f, cap = StrokeCap.Round)
        drawLine(if (isOn) on else off, ab.first, ab.second, strokeWidth = t, cap = StrokeCap.Round)
    }
}

/** Nixie: each digit glows warm orange inside its own dark glass tube, with the unlit digits faintly behind it
 *  and a fine mesh over the glass; the colon is two glowing dots. Seconds and AM/PM in smaller tubes. */
@Composable
private fun NixieTime(now: LocalDateTime, use24: Boolean, w: Dp, h: Dp, glow: Color) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // in tube widths: HH 2.1 + colon 0.4 + MM 2.1 + gap 0.2 + small SS 1.16 = ~5.96; tube height 1.7
    val tw = minOf(w / 6.05f, h / 1.75f).coerceIn(16.dp, 90.dp)
    val hour = if (use24) now.hour else (now.hour % 12).let { if (it == 0) 12 else it }
    val hh: List<Int?> = if (!use24 && hour < 10) listOf(null, hour) else listOf(hour / 10, hour % 10)
    Canvas(Modifier.width(tw * 5.96f).height(tw * 1.7f)) {
        val t = size.height / 1.7f
        val big = t * 1.7f
        fun tube(d: Int?, x: Float, y: Float, tW: Float, tH: Float) {
            val corner = CornerRadius(tW * 0.42f)
            // glass
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF241B15), Color(0xFF120D0A), Color(0xFF0A0807)), y, y + tH),
                Offset(x, y), Size(tW, tH), corner)
            // mesh
            val step = tW * 0.13f
            val inset = tW * 0.16f
            var gx = x + inset
            while (gx < x + tW - inset) { drawLine(Color(0x16FFB070), Offset(gx, y + inset), Offset(gx, y + tH * 0.86f), 1f); gx += step }
            var gy = y + inset
            while (gy < y + tH * 0.86f) { drawLine(Color(0x16FFB070), Offset(x + inset, gy), Offset(x + tW - inset, gy), 1f); gy += step }
            // ghost digits: the unlit cathodes stacked behind
            val fs = with(density) { (tH * 0.62f).toSp() }
            for (g in 0..9) {
                val l = measurer.measure("$g", TextStyle(color = Color(0x12FFB07A), fontSize = fs, fontWeight = FontWeight.Light))
                drawText(l, topLeft = Offset(x + (tW - l.size.width) / 2f, y + (tH * 0.92f - l.size.height) / 2f))
            }
            // the lit digit, glowing
            if (d != null) {
                val l = measurer.measure("$d", TextStyle(color = lerp(glow, Color(0xFFFFE6C2), 0.35f), fontSize = fs,
                    fontWeight = FontWeight.Light, shadow = Shadow(glow, Offset.Zero, tW * 0.35f)))
                val p = Offset(x + (tW - l.size.width) / 2f, y + (tH * 0.92f - l.size.height) / 2f)
                drawText(l, topLeft = p)
                drawText(l, topLeft = p)   // twice, for a stronger glow
            }
            // glass edge, highlight and the socket at the bottom
            drawRoundRect(Color(0x40FFD9B0), Offset(x, y), Size(tW, tH), corner, style = Stroke(1.2f))
            drawLine(Color(0x1FFFFFFF), Offset(x + tW * 0.18f, y + tH * 0.14f), Offset(x + tW * 0.18f, y + tH * 0.7f),
                tW * 0.05f, cap = StrokeCap.Round)
            drawRoundRect(Color(0xFF2E2620), Offset(x - tW * 0.04f, y + tH * 0.9f), Size(tW * 1.08f, tH * 0.1f), CornerRadius(tW * 0.05f))
        }
        fun dot(p: Offset, rad: Float) {
            drawCircle(glow.copy(alpha = 0.18f), rad * 3f, p)
            drawCircle(glow.copy(alpha = 0.35f), rad * 1.8f, p)
            drawCircle(lerp(glow, Color(0xFFFFE6C2), 0.4f), rad, p)
        }
        var x = 0f
        tube(hh[0], x, 0f, t, big); x += t * 1.1f
        tube(hh[1], x, 0f, t, big); x += t
        dot(Offset(x + t * 0.2f, big * 0.36f), t * 0.07f)
        dot(Offset(x + t * 0.2f, big * 0.64f), t * 0.07f)
        x += t * 0.4f
        tube(now.minute / 10, x, 0f, t, big); x += t * 1.1f
        tube(now.minute % 10, x, 0f, t, big); x += t * 1.2f
        val st = t * 0.55f
        val sH = big * 0.55f
        tube(now.second / 10, x, big - sH, st, sH)
        tube(now.second % 10, x + st * 1.1f, big - sH, st, sH)
        if (!use24) {
            val l = measurer.measure(amPm(now.hour), TextStyle(color = lerp(glow, Color(0xFFFFE6C2), 0.3f),
                fontSize = with(density) { (big * 0.17f).toSp() }, fontWeight = FontWeight.Bold, shadow = Shadow(glow, Offset.Zero, t * 0.2f)))
            drawText(l, topLeft = Offset(x + (st * 2.1f - l.size.width) / 2f, big * 0.08f))
        }
    }
}

/** Flip: each pair of digits on a dark split card, like a flip clock. */
@Composable
private fun FlipTime(now: LocalDateTime, use24: Boolean, w: Dp, h: Dp, accent: Color) {
    val density = LocalDensity.current
    // in units of the font size f: card 1.3 + colon 0.32 + card 1.3 + gap 0.12 + seconds card 0.66 = ~3.7; card height 1.3
    val f = minOf(w / 3.75f, h / 1.32f).coerceIn(24.dp, 130.dp)
    val hour = if (use24) now.hour else (now.hour % 12).let { if (it == 0) 12 else it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlipCard(String.format(Locale.US, "%02d", hour), f, f * 1.3f, f * 1.3f, ClockColors.text)
        Column(Modifier.width(f * 0.32f), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(f * 0.28f)) {
            repeat(2) { Box(Modifier.size(f * 0.11f).clip(CircleShape).background(accent)) }
        }
        FlipCard(String.format(Locale.US, "%02d", now.minute), f, f * 1.3f, f * 1.3f, ClockColors.text)
        Spacer(Modifier.width(f * 0.12f))
        Column(Modifier.height(f * 1.3f), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
            if (!use24) Text(amPm(now.hour), color = accent, fontSize = with(density) { (f * 0.22f).toSp() },
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = f * 0.08f))
            FlipCard(String.format(Locale.US, "%02d", now.second), f * 0.42f, f * 0.66f, f * 0.62f, accent)
        }
    }
}

@Composable
private fun FlipCard(text: String, font: Dp, w: Dp, h: Dp, color: Color) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(font * 0.14f)
    Box(
        Modifier.size(w, h).clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0xFF2A2B31), Color(0xFF1C1D21), Color(0xFF141518), Color(0xFF101114)))),
        contentAlignment = Alignment.Center,
    ) {
        val sp = with(density) { font.toSp() }
        Text(text, color = color, fontSize = sp, lineHeight = sp, fontWeight = FontWeight.Bold, maxLines = 1,
            style = TextStyle(fontFeatureSettings = "tnum"))
        // the split across the middle
        Box(Modifier.fillMaxWidth().height(maxOf(font * 0.035f, 1.5.dp)).background(Color(0xE6000000)))
    }
}

@Composable
private fun CalendarCard(today: LocalDate, modifier: Modifier, design: ClockDesign) {
    val accent = design.accent
    val ink = design.cardText
    var shown by remember { mutableStateOf(YearMonth.from(today)) }
    val firstDay: DayOfWeek = remember { WeekFields.of(Locale.getDefault()).firstDayOfWeek }
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.clip(shape).background(design.cardBg).border(1.dp, accent.copy(alpha = 0.35f), shape).padding(10.dp)) {
        // ‹  November 2026  ›   (tap the month to come back to this month)
        Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            ArrowButton("‹", accent) { shown = shown.minusMonths(1) }
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { shown = YearMonth.from(today) },
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${shown.month.getDisplayName(JTextStyle.FULL, Locale.getDefault())} ${shown.year}",
                    color = ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            ArrowButton("›", accent) { shown = shown.plusMonths(1) }
        }
        Spacer(Modifier.height(4.dp))
        val cols = weekdayColumns(firstDay)
        Row(Modifier.fillMaxWidth()) {
            weekdayHeaders(firstDay).forEachIndexed { i, h ->
                Text(h, color = if (cols[i] == DayOfWeek.SUNDAY) ClockColors.sunday else accent.copy(alpha = 0.75f), fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(2.dp))
        val weeks = monthGrid(shown, firstDay).chunked(7)
        val thisMonth = shown == YearMonth.from(today)
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f)) {
                week.forEachIndexed { i, day ->
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (day != null) {
                            val isToday = thisMonth && day == today.dayOfMonth
                            Box(
                                Modifier.size(minOf(maxWidth, maxHeight) * 0.92f)
                                    .clip(CircleShape).background(if (isToday) accent else Color.Transparent),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(day.toString(), fontSize = 15.sp, maxLines = 1,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isToday -> Color.Black
                                        cols[i] == DayOfWeek.SUNDAY -> ClockColors.sunday
                                        else -> ink
                                    })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArrowButton(symbol: String, accent: Color, onClick: () -> Unit) {
    Box(Modifier.size(32.dp).clip(CircleShape).background(ClockColors.off).border(1.dp, accent.copy(alpha = 0.35f), CircleShape)
        .clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(symbol, color = accent, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 22.sp)
    }
}

// ================================================================== Timers
@Composable
private fun TimerTab(vm: ClockViewModel) {
    val state = vm.timerState
    val now = rememberElapsedTick(state == TimerState.Running, 100)
    val left = vm.timerRemaining(now)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // ---- progress ring with the time left
        BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val d = minOf(maxWidth, maxHeight)
            val total = if (state == TimerState.Idle) vm.timerSetMs else vm.timerTotalMs
            val frac = if (total > 0) (left.toFloat() / total).coerceIn(0f, 1f) else 0f
            val ringColor = if (state == TimerState.Done) ClockColors.second else ClockColors.accent
            Canvas(Modifier.size(d)) {
                val w = 12.dp.toPx()
                val inset = w / 2f
                val arcSize = Size(size.width - w, size.height - w)
                drawArc(Color(0xFF1E2329), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
                drawArc(ringColor, -90f, 360f * frac, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
            val density = LocalDensity.current
            val text = timerText(left)
            val sp = with(density) { (d * if (text.length > 5) 0.17f else 0.22f).toSp() }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text, color = if (state == TimerState.Done) ClockColors.second else ClockColors.text, fontSize = sp,
                    fontWeight = FontWeight.Light, lineHeight = sp, maxLines = 1)
                val status = when (state) {
                    TimerState.Idle -> "Set a time"
                    TimerState.Running -> "Ends " + LocalDateTime.now().plusNanos(left * 1_000_000)
                        .format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
                    TimerState.Paused -> "Paused"
                    TimerState.Done -> "Time's up!"
                }
                Text(status, color = if (state == TimerState.Done) ClockColors.second else ClockColors.dim,
                    fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        // ---- setter, presets and buttons
        Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val idle = state == TimerState.Idle
            val (h, m, s) = hms(if (idle) vm.timerSetMs else vm.timerTotalMs)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple("Hours", h, 0), Triple("Minutes", m, 1), Triple("Seconds", s, 2)).forEach { (label, v, field) ->
                    TimeField(label, v, idle, Modifier.weight(1f).fillMaxHeight(),
                        onUp = { vm.timerSetMs = stepTimer(vm.timerSetMs, field, 1) },
                        onDown = { vm.timerSetMs = stepTimer(vm.timerSetMs, field, -1) })
                }
            }
            Row(Modifier.fillMaxWidth().height(32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 30).forEach { min ->
                    Pill("$min min", idle && vm.timerSetMs == min * 60_000L, Modifier.weight(1f).fillMaxHeight(), enabled = idle, hPad = 2.dp) {
                        vm.timerSetMs = min * 60_000L
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    TimerState.Idle -> ActionButton("Start", Palette.good, Modifier.weight(1f).fillMaxHeight(),
                        enabled = vm.timerSetMs > 0) { vm.startTimer() }
                    TimerState.Running -> ActionButton("Pause", Palette.warn, Modifier.weight(1f).fillMaxHeight()) { vm.pauseTimer() }
                    TimerState.Paused -> ActionButton("Resume", Palette.good, Modifier.weight(1f).fillMaxHeight()) { vm.resumeTimer() }
                    TimerState.Done -> if (vm.ringing) ActionButton("Stop alarm", ClockColors.second, Modifier.weight(1f).fillMaxHeight()) { vm.stopAlarm() }
                        else ActionButton("Start again", Palette.good, Modifier.weight(1f).fillMaxHeight()) { vm.resetTimer(); vm.startTimer() }
                }
                ActionButton("Reset", ClockColors.text, Modifier.weight(1f).fillMaxHeight(), filled = false, enabled = !idle) { vm.resetTimer() }
            }
        }
    }
}

@Composable
private fun TimeField(label: String, value: Int, enabled: Boolean, modifier: Modifier, onUp: () -> Unit, onDown: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.clip(shape).background(ClockColors.panel).border(1.dp, ClockColors.panelBorder, shape),
        horizontalAlignment = Alignment.CenterHorizontally) {
        StepButton("▲", enabled, Modifier.fillMaxWidth().weight(1f), onUp)
        Text(String.format(Locale.US, "%02d", value), color = if (enabled) ClockColors.text else ClockColors.dim,
            fontSize = 34.sp, fontWeight = FontWeight.Light, lineHeight = 36.sp)
        Text(label, color = ClockColors.dim, fontSize = 11.sp, lineHeight = 12.sp)
        StepButton("▼", enabled, Modifier.fillMaxWidth().weight(1f), onDown)
    }
}

@Composable
private fun StepButton(symbol: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clickable(enabled = enabled) { onClick() }, contentAlignment = Alignment.Center) {
        Text(symbol, color = if (enabled) ClockColors.accent else ClockColors.dim.copy(alpha = 0.3f), fontSize = 16.sp)
    }
}

// ================================================================== Stopwatch
@Composable
private fun StopwatchTab(vm: ClockViewModel) {
    val now = rememberElapsedTick(vm.swRunning, 31)
    val elapsed = vm.swElapsed(now)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1.25f).fillMaxHeight()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val text = stopwatchText(elapsed)
                val main = text.substringBeforeLast('.')
                val hund = "." + text.substringAfterLast('.')
                val density = LocalDensity.current
                // "00:00" is ~2.7x the font size wide, ".00" in a smaller size ~0.9x more
                val widthFactor = if (main.length > 5) 4.5f else 3.6f
                val sp = with(density) { minOf(maxWidth / widthFactor, (maxHeight - 30.dp) / 1.2f).coerceIn(24.dp, 150.dp).toSp() }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(main, color = ClockColors.text, fontSize = sp, fontWeight = FontWeight.ExtraLight, lineHeight = sp,
                            maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
                        Text(hund, color = ClockColors.accent, fontSize = sp * 0.55f, fontWeight = FontWeight.Light,
                            lineHeight = sp * 0.55f, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"),
                            modifier = Modifier.padding(bottom = with(density) { (sp * 0.1f).toDp() }))
                    }
                    val current = elapsed - (vm.laps.lastOrNull() ?: 0L)
                    Text(if (vm.laps.isEmpty()) " " else "Lap ${vm.laps.size + 1}   ${stopwatchText(current)}",
                        color = ClockColors.dim, fontSize = 16.sp, style = TextStyle(fontFeatureSettings = "tnum"))
                }
            }
            Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // left: Lap while running, Reset when stopped; right: Start / Stop
                if (vm.swRunning) ActionButton("Lap", ClockColors.text, Modifier.weight(1f).fillMaxHeight(), filled = false) { vm.swLap() }
                else ActionButton("Reset", ClockColors.text, Modifier.weight(1f).fillMaxHeight(), filled = false,
                    enabled = elapsed > 0) { vm.swReset() }
                if (vm.swRunning) ActionButton("Stop", Palette.bad, Modifier.weight(1f).fillMaxHeight()) { vm.swStop() }
                else ActionButton(if (elapsed > 0) "Resume" else "Start", Palette.good, Modifier.weight(1f).fillMaxHeight()) { vm.swStart() }
            }
        }
        LapsCard(vm.laps, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun LapsCard(totals: List<Long>, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val splits = lapSplits(totals)
    val fastest = if (splits.size >= 3) splits.min() else null
    val slowest = if (splits.size >= 3) splits.max() else null
    Column(modifier.clip(shape).background(ClockColors.panel).border(1.dp, ClockColors.panelBorder, shape).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Text("Lap", color = ClockColors.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(0.6f))
            Text("Lap time", color = ClockColors.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), textAlign = TextAlign.End)
            Text("Total", color = ClockColors.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ClockColors.panelBorder))
        if (totals.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Tap Lap while the stopwatch runs\nto record lap times here.", color = ClockColors.dim, fontSize = 14.sp,
                    textAlign = TextAlign.Center)
            }
        } else LazyColumn(Modifier.fillMaxSize()) {
            items(totals.indices.reversed().toList()) { i ->
                val color = when (splits[i]) { fastest -> Palette.good; slowest -> Palette.bad; else -> ClockColors.text }
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", color = ClockColors.dim, fontSize = 16.sp, modifier = Modifier.weight(0.6f))
                    Text(stopwatchText(splits[i]), color = color, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.End, style = TextStyle(fontFeatureSettings = "tnum"))
                    Text(stopwatchText(totals[i]), color = ClockColors.text, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Clip,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.End, style = TextStyle(fontFeatureSettings = "tnum"))
                }
            }
        }
    }
}

/** A bar shown on other pages while the timer's alarm rings, to stop it from anywhere. */
@Composable
fun TimerAlarmBanner(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.clip(shape).background(Color(0xF0201416)).border(1.dp, ClockColors.second, shape).padding(start = 16.dp, end = 6.dp)
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⏰  Time's up!", color = ClockColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(14.dp))
        Box(Modifier.height(30.dp).clip(shape).background(ClockColors.second).clickable { onStop() }.padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center) {
            Text("Stop", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
