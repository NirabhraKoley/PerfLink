package com.perflink.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private object HomeColors {
    val cpu = Color(0xFF2DD4BF)
    val gpu = Color(0xFFA3E635)
    val ram = Color(0xFFA78BFA)
    val net = Color(0xFFF59E0B)
    val teal = Color(0xFF5EEAD4)
    val clock = Color(0xFFF3F6F9)
    val colon = Color(0xFF7D8794)
}

@Composable
fun HomePage(
    state: UiState,
    onMedia: (String, Double?) -> Unit,
    onVolume: (Double) -> Unit,
    onMute: () -> Unit,
    onCity: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val s = state.stats ?: return
    var settings by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF0A0F12), Color(0xFF0C1A1B), Color(0xFF091012)))
        )
    ) {
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 18.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                ClockBlock(state, s, Modifier.weight(1f).fillMaxHeight()) { settings = true }
                Spacer(Modifier.width(20.dp))
                MediaBlock(s.media, s.volume, state.art, onMedia, onVolume, onMute, Modifier.weight(1.05f).fillMaxHeight())
            }
            Spacer(Modifier.height(10.dp))
            StatRow(s, state.history, Modifier.fillMaxWidth().height(86.dp))
        }
    }
    if (settings) SettingsDialog(s.weather?.city ?: "", onDismiss = { settings = false },
        onCity = { onCity(it); settings = false }, onDisconnect = { settings = false; onDisconnect() })
}

// ------------------------------------------------------------------ clock + weather
@Composable
private fun ClockBlock(state: UiState, s: Stats, modifier: Modifier, onHostClick: () -> Unit) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - (System.currentTimeMillis() % 1000L))
        }
    }
    val hour12 = (now.hour % 12).let { if (it == 0) 12 else it }
    val ampm = if (now.hour < 12) "AM" else "PM"
    val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        // Scale the clock to the space available (narrow 5" phones → large tablets)
        val clockSp = with(density) { minOf(maxHeight * 0.44f, maxWidth / 2.75f).toSp() }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onHostClick() }.padding(vertical = 2.dp),
                ) {
                    val dot = if (state.conn == Conn.Connected) Palette.good else Palette.warn
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(s.host ?: "", color = Palette.dim, fontSize = 11.sp, letterSpacing = 0.5.sp, maxLines = 1)
                }
                // This phone's battery (the app hides the phone's status bar)
                phoneBattery()?.let { (pct, charging) ->
                    Spacer(Modifier.width(12.dp))
                    BatteryChip("Phone", pct, charging, null)
                }
            }
            Row(verticalAlignment = Alignment.Top) {
                Text("$hour12", color = HomeColors.clock, fontSize = clockSp, fontWeight = FontWeight.ExtraLight, lineHeight = clockSp)
                Text(":", color = HomeColors.colon, fontSize = clockSp, fontWeight = FontWeight.ExtraLight, lineHeight = clockSp)
                Text(String.format(Locale.US, "%02d", now.minute), color = HomeColors.clock, fontSize = clockSp,
                    fontWeight = FontWeight.ExtraLight, lineHeight = clockSp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.padding(top = with(density) { (clockSp * 0.22f).toDp() })) {
                    Text(String.format(Locale.US, "%02d", now.second), color = HomeColors.colon,
                        fontSize = clockSp * 0.2f, fontWeight = FontWeight.Light)
                    Text(ampm, color = Palette.dim, fontSize = clockSp * 0.17f, fontWeight = FontWeight.Medium)
                }
            }
            Text(date, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(10.dp))
            s.weather?.takeIf { it.tempC != null }?.let { w -> WeatherRow(w) }
        }
    }
}

@Composable
private fun WeatherRow(w: Weather) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(weatherEmoji(w.code, w.isDay), fontSize = 24.sp)
        Spacer(Modifier.width(8.dp))
        Text("${f0(w.tempC)}°", color = HomeColors.clock, fontSize = 26.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("${w.condition ?: ""} · ${w.city ?: ""}", color = Palette.text, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("↑${f0(w.highC)}° ↓${f0(w.lowC)}° · Feels like ${f0(w.feelsC)}°", color = Palette.dim,
                fontSize = 11.sp, maxLines = 1)
        }
    }
}

private fun weatherEmoji(code: Int?, day: Boolean): String = when (code) {
    null -> "🌡️"
    0 -> if (day) "☀️" else "🌙"
    1, 2 -> if (day) "🌤️" else "☁️"
    3 -> "☁️"
    45, 48 -> "🌫️"
    in 51..57 -> "🌦️"
    in 61..67, in 80..82 -> "🌧️"
    in 71..77, 85, 86 -> "❄️"
    in 95..99 -> "⛈️"
    else -> "🌡️"
}

// ------------------------------------------------------------------ media
@Composable
private fun MediaBlock(
    m: Media?, vol: Volume?, art: ImageBitmap?,
    onMedia: (String, Double?) -> Unit, onVolume: (Double) -> Unit, onMute: () -> Unit,
    modifier: Modifier,
) {
    val hasTrack = m?.title != null
    Column(modifier, verticalArrangement = Arrangement.Center) {
        Row(Modifier.fillMaxWidth().height(92.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1F5F57), Color(0xFF123A37)))),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null && hasTrack) {
                    Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("♪", color = Color(0xFFCFE9E4), fontSize = 34.sp)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(if (m?.app != null) "MEDIA · ${m.app.uppercase()}" else "MEDIA", color = HomeColors.teal,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, maxLines = 1)
                Text(m?.title ?: "Nothing playing", color = Palette.text, fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
                Text(
                    when {
                        !hasTrack -> "Play something on the PC"
                        m?.artist != null -> m.artist
                        m?.playing == false -> "Paused"
                        else -> ""
                    },
                    color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SeekBar(m) { frac -> m?.durationS?.let { onMedia("seek", it * frac) } }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            Text(mmss(m?.positionS), color = Palette.dim, fontSize = 10.sp, modifier = Modifier.weight(1f))
            Text(mmss(m?.durationS), color = Palette.dim, fontSize = 10.sp)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(34.dp, { onMedia("prev", null) }) { drawSkip(forward = false, color = Palette.dim) }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFF3F6F9)).clickable { onMedia("play_pause", null) },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    if (m?.playing == true) drawPause(Color(0xFF0B0E13)) else drawPlay(Color(0xFF0B0E13))
                }
            }
            Spacer(Modifier.width(8.dp))
            Glyph(34.dp, { onMedia("next", null) }) { drawSkip(forward = true, color = Palette.dim) }
            Spacer(Modifier.width(14.dp))
            Glyph(28.dp, onMute) { drawSpeaker(Palette.dim, muted = vol?.muted == true) }
            VolumeSlider(vol, onVolume, Modifier.weight(1f))
            Text(vol?.level?.let { f0(it) } ?: "–", color = Palette.dim, fontSize = 12.sp,
                modifier = Modifier.width(28.dp).padding(start = 4.dp))
        }
    }
}

@Composable
private fun VolumeSlider(vol: Volume?, onVolume: (Double) -> Unit, modifier: Modifier) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    var lastSent by remember { mutableStateOf(0L) }
    val serverValue = ((vol?.level ?: 0.0) / 100.0).toFloat()
    ThinSlider(
        fraction = dragging ?: serverValue,
        enabled = vol?.level != null,
        thumbRadius = 7.dp,
        modifier = modifier.padding(horizontal = 6.dp),
        onDrag = { v ->
            dragging = v
            val t = System.currentTimeMillis()
            if (t - lastSent > 80) { onVolume(v * 100.0); lastSent = t }
        },
        onRelease = { v -> onVolume(v * 100.0); dragging = null },
    )
}

@Composable
private fun SeekBar(m: Media?, onSeek: (Float) -> Unit) {
    val dur = m?.durationS
    val serverFrac = if (dur != null && dur > 0) ((m.positionS ?: 0.0) / dur).toFloat().coerceIn(0f, 1f) else 0f
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(m?.title) { drag = null }
    ThinSlider(
        fraction = drag ?: serverFrac, enabled = dur != null, thumbRadius = 5.dp,
        modifier = Modifier.fillMaxWidth(),
        onDrag = { drag = it }, onRelease = { onSeek(it); drag = null },
    )
}

/** Thin track + round thumb, like the mockup. Tap or drag; [onRelease] gets the final value. */
@Composable
fun ThinSlider(
    fraction: Float, enabled: Boolean, thumbRadius: Dp, modifier: Modifier,
    onDrag: (Float) -> Unit, onRelease: (Float) -> Unit,
) {
    var last by remember { mutableStateOf(fraction) }
    Canvas(
        modifier.height(24.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { o -> onRelease((o.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { o -> last = (o.x / size.width).coerceIn(0f, 1f); onDrag(last) },
                    onDragEnd = { onRelease(last) },
                    onDragCancel = { onRelease(last) },
                ) { change, _ -> last = (change.position.x / size.width).coerceIn(0f, 1f); onDrag(last) }
            },
    ) {
        val y = size.height / 2
        val th = 3.dp.toPx()
        val f = fraction.coerceIn(0f, 1f)
        drawRoundRect(Color(0xFF26303A), topLeft = Offset(0f, y - th / 2), size = Size(size.width, th), cornerRadius = CornerRadius(th))
        drawRoundRect(Color(0xFF9FB0BE), topLeft = Offset(0f, y - th / 2), size = Size(size.width * f, th), cornerRadius = CornerRadius(th))
        drawCircle(Color(0xFFE6EBF0), radius = thumbRadius.toPx(), center = Offset(size.width * f, y))
    }
}

private fun mmss(sec: Double?): String {
    if (sec == null) return "0:00"
    val t = sec.toInt()
    return if (t >= 3600) String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
    else String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}

// ------------------------------------------------------------------ glyphs
@Composable
private fun Glyph(size: Dp, onClick: () -> Unit, draw: DrawScope.() -> Unit) {
    Box(Modifier.size(size).clip(CircleShape).clickable { onClick() }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.5f)) { draw() }
    }
}

private fun DrawScope.drawPlay(color: Color) {
    val p = Path().apply {
        moveTo(size.width * 0.18f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * 0.18f, size.height); close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawPause(color: Color) {
    val w = size.width * 0.3f
    drawRoundRect(color, topLeft = Offset(size.width * 0.12f, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(2f))
    drawRoundRect(color, topLeft = Offset(size.width * 0.58f, 0f), size = Size(w, size.height), cornerRadius = CornerRadius(2f))
}

private fun DrawScope.drawSkip(forward: Boolean, color: Color) {
    val w = size.width; val h = size.height; val bar = w * 0.14f
    val p = Path()
    if (forward) {
        p.moveTo(0f, 0f); p.lineTo(w - bar, h / 2); p.lineTo(0f, h); p.close()
        drawRect(color, topLeft = Offset(w - bar, 0f), size = Size(bar, h))
    } else {
        p.moveTo(w, 0f); p.lineTo(bar, h / 2); p.lineTo(w, h); p.close()
        drawRect(color, topLeft = Offset(0f, 0f), size = Size(bar, h))
    }
    drawPath(p, color)
}

private fun DrawScope.drawSpeaker(color: Color, muted: Boolean) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(0f, h * 0.35f); lineTo(w * 0.25f, h * 0.35f); lineTo(w * 0.55f, h * 0.08f)
        lineTo(w * 0.55f, h * 0.92f); lineTo(w * 0.25f, h * 0.65f); lineTo(0f, h * 0.65f); close()
    }
    drawPath(p, color)
    val st = Stroke(width = 1.6.dp.toPx())
    if (muted) {
        drawLine(Palette.bad, Offset(w * 0.68f, h * 0.32f), Offset(w, h * 0.68f), strokeWidth = st.width)
        drawLine(Palette.bad, Offset(w, h * 0.32f), Offset(w * 0.68f, h * 0.68f), strokeWidth = st.width)
    } else {
        drawArc(color, -45f, 90f, false, topLeft = Offset(w * 0.4f, h * 0.25f), size = Size(w * 0.4f, h * 0.5f), style = st)
    }
}

// ------------------------------------------------------------------ stat tiles
@Composable
private fun StatRow(s: Stats, h: History, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val (dv, du) = byteRate(s.net.downloadBps)
        StatTile("CPU", HomeColors.cpu, f0(s.cpu.util), "%", shortCpu(s.cpu.name), h.cpuUtil, 100f, Modifier.weight(1f))
        StatTile("GPU", HomeColors.gpu, f0(s.gpu.util), "%",
            "${temp(s.gpu.tempC)} · ${s.gpu.powerW?.let { "${f0(it)} W" } ?: "N/A"}", h.gpuUtil, 100f, Modifier.weight(1f))
        StatTile("RAM (${f0(s.ram.util)}%)", HomeColors.ram,
            s.ram.usedMb?.let { String.format(Locale.US, "%.1f", it / 1024) } ?: "N/A", "GB",
            "${s.ram.usedMb?.let { String.format(Locale.US, "%.1f", it / 1024) } ?: "?"} / ${s.ram.totalMb?.let { f0(it / 1024) } ?: "?"} GB",
            h.ram, 100f, Modifier.weight(1f))
        val (uv, uu) = byteRate(s.net.uploadBps)
        StatTile("NETWORK", HomeColors.net, dv, du,
            "↑ $uv $uu · ${s.net.pingMs?.let { "${f0(it)} ms" } ?: "– ms"}", h.down, null, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(
    label: String, color: Color, value: String, unit: String, sub: String,
    history: List<Float>, max: Float?, modifier: Modifier,
) {
    Box(modifier.fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0x0FFFFFFF))) {
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(34.dp)) {
            LineChart(listOf(Series(history, color.copy(alpha = 0.55f))), height = 34.dp, fixedMax = max, showGrid = false)
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Light, maxLines = 1, lineHeight = 26.sp)
                Text(unit, color = Palette.dim, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
            }
            Text(sub, color = Palette.dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun shortCpu(name: String?): String =
    name?.replace("(R)", "")?.replace("(TM)", "")?.replace("12th Gen ", "")?.replace("CPU", "")
        ?.replace(Regex("\\s+"), " ")?.trim() ?: ""

/** bits/s → (value, unit) in bytes/s like Windows Task Manager */
private fun byteRate(bps: Double?): Pair<String, String> {
    val b = (bps ?: return "–" to "KB/s") / 8.0
    return when {
        b >= 1e6 -> String.format(Locale.US, "%.1f", b / 1e6) to "MB/s"
        b >= 1e3 -> String.format(Locale.US, "%.0f", b / 1e3) to "KB/s"
        else -> String.format(Locale.US, "%.0f", b) to "B/s"
    }
}

// ------------------------------------------------------------------ settings
@Composable
private fun SettingsDialog(city: String, onDismiss: () -> Unit, onCity: (String) -> Unit, onDisconnect: () -> Unit) {
    var c by remember { mutableStateOf(city) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        title = { Text("Settings", color = Palette.text) },
        text = {
            Column {
                OutlinedTextField(
                    value = c, onValueChange = { c = it }, singleLine = true, label = { Text("Weather city") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text),
                )
                Spacer(Modifier.height(12.dp))
                Text("Disconnect from PC", color = Palette.bad, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onDisconnect() }.padding(vertical = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onCity(c) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ batteries
/** This phone's battery (percent, charging), refreshed every 30 s. The app hides the status bar, so show it here. */
@Composable
private fun phoneBattery(): Pair<Int, Boolean>? {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by produceState<Pair<Int, Boolean>?>(null) {
        while (true) {
            value = runCatching {
                val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    ?: return@runCatching null
                val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                val status = i.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
                val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == android.os.BatteryManager.BATTERY_STATUS_FULL
                if (level < 0 || scale <= 0) null else (level * 100 / scale) to charging
            }.getOrNull()
            delay(30_000)
        }
    }
    return state
}

@Composable
private fun BatteryChip(label: String, pct: Int, charging: Boolean, secsLeft: Double?) {
    val color = when {
        charging -> Palette.good
        pct <= 20 -> Palette.bad
        pct <= 40 -> Palette.warn
        else -> Palette.good
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(18.dp).height(9.dp)) {
            val nub = 2.dp.toPx()
            val bodyW = size.width - nub
            val st = 1.2.dp.toPx()
            drawRoundRect(Palette.dim, size = Size(bodyW, size.height), cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(width = st))
            drawRect(Palette.dim, topLeft = Offset(bodyW, size.height * 0.3f), size = Size(nub, size.height * 0.4f))
            val inner = (bodyW - st * 3) * (pct.coerceIn(0, 100) / 100f)
            drawRoundRect(color, topLeft = Offset(st * 1.5f, st * 1.5f), size = Size(inner, size.height - st * 3),
                cornerRadius = CornerRadius(1.dp.toPx()))
        }
        Spacer(Modifier.width(4.dp))
        Text("$label $pct%" + if (charging) " ⚡" else "", color = Palette.dim, fontSize = 11.sp, maxLines = 1)
        secsLeft?.let {
            val m = (it / 60).toInt()
            Text(" · ${if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"} left", color = Palette.dim.copy(alpha = 0.7f),
                fontSize = 11.sp, maxLines = 1)
        }
    }
}
