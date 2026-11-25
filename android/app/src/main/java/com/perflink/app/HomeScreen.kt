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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
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
import kotlinx.coroutines.launch
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
    onWeatherAuto: () -> Unit = {},
    onOpenWeather: () -> Unit = {},
) {
    val s = state.stats ?: return
    var settings by remember { mutableStateOf(false) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF0A0F12), Color(0xFF0C1A1B), Color(0xFF091012)))
        )
    ) {
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp)) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                ClockBlock(state, s, Modifier.weight(1f).fillMaxHeight(), onOpenWeather) { settings = true }
                Spacer(Modifier.width(20.dp))
                MediaBlock(s.media, s.volume, state.art, onMedia, onVolume, onMute, Modifier.weight(1.05f).fillMaxHeight())
            }
            Spacer(Modifier.height(6.dp))
            StatRow(s, state.history, Modifier.fillMaxWidth().height(84.dp))
        }
    }
    if (settings) SettingsDialog(s.weather, onDismiss = { settings = false },
        onCity = { onCity(it); settings = false }, onAuto = { onWeatherAuto(); settings = false },
        onDisconnect = { settings = false; onDisconnect() })
}

// ------------------------------------------------------------------ clock + weather
@Composable
private fun ClockBlock(state: UiState, s: Stats, modifier: Modifier, onWeather: () -> Unit, onHostClick: () -> Unit) {
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - (System.currentTimeMillis() % 1000L))
        }
    }
    // 12- or 24-hour clock: the switch left of the time, remembered on this phone
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("perflink", 0)
    var use24 by remember { mutableStateOf(prefs.getBoolean("clock_24h", false)) }
    val hour12 = (now.hour % 12).let { if (it == 0) 12 else it }
    val hourText = if (use24) String.format(Locale.US, "%02d", now.hour) else "$hour12"
    val ampm = if (now.hour < 12) "AM" else "PM"
    val date = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault()))

    var waOpen by remember { mutableStateOf(false) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = DisconnectGap)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // may shrink (with …) so the battery and USB / Wi-Fi labels always fit
                modifier = Modifier.weight(1f, fill = false).clip(RoundedCornerShape(6.dp)).clickable { onHostClick() }
                    .padding(vertical = 2.dp),
            ) {
                val dot = if (state.conn == Conn.Connected) Palette.good else Palette.warn
                Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(6.dp))
                // laptop or desktop and who uses it ("🖥️ Nirabhra Koley"), with the PC's IP underneath
                Column {
                    Text((s.kind?.let { pcKindLabel(it).substringBefore(" ") + " " } ?: "") + (s.user ?: s.host ?: ""),
                        color = Palette.dim, fontSize = 11.sp, letterSpacing = 0.5.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    s.net.localIp?.let {
                        Text(it, color = Palette.dim.copy(alpha = 0.75f), fontSize = 10.sp, maxLines = 1, lineHeight = 11.sp)
                    }
                }
            }
            // This phone's battery (the app hides the phone's status bar)
            phoneBattery()?.let { (pct, charging) ->
                Spacer(Modifier.width(10.dp))
                BatteryChip("", pct, charging, null)
            }
            // how this phone reaches the PC: USB (the adb tunnel to 127.0.0.1) or Wi-Fi
            state.target?.let { url ->
                Spacer(Modifier.width(10.dp))
                LinkChip(usb = Regex("""//(127\.0\.0\.1|localhost)(:|/|$)""").containsMatchIn(url))
            }
            // this app's version (it updates itself from GitHub)
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val appVersion = remember { runCatching { AppUpdater.installedVersion(ctx) }.getOrNull() }
            appVersion?.let {
                Spacer(Modifier.width(10.dp))
                Text("v$it", color = Palette.dim, fontSize = 11.sp, maxLines = 1)
            }
        }
        // WhatsApp messages from WhatsApp Desktop on the PC
        s.whatsapp?.let { wa ->
            Spacer(Modifier.height(6.dp))
            WhatsAppCard(wa) { waOpen = true }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            // Scale the clock to the space left (date + two weather lines take ~100dp below it)
            // below the clock: date (~22dp) + gap + two-line weather row (~50dp) + slack = ~88dp; a line of digits is ~1.18x the font size.
            // across: the 12h/24h switch (~34dp) + seconds/AM-PM (~30dp); "12:59" is ~2.6x the font size wide.
            val clockSp = with(density) {
                minOf((maxHeight - 88.dp) / 1.18f, (maxWidth - 84.dp) / 2.6f).coerceIn(28.dp, 120.dp).toSp()
            }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClockFormatSwitch(use24) { use24 = it; prefs.edit().putBoolean("clock_24h", it).apply() }
                    Spacer(Modifier.width(10.dp))
                    Text(hourText, color = HomeColors.clock, fontSize = clockSp, fontWeight = FontWeight.ExtraLight, lineHeight = clockSp)
                    Text(":", color = HomeColors.colon, fontSize = clockSp, fontWeight = FontWeight.ExtraLight, lineHeight = clockSp)
                    Text(String.format(Locale.US, "%02d", now.minute), color = HomeColors.clock, fontSize = clockSp,
                        fontWeight = FontWeight.ExtraLight, lineHeight = clockSp)
                    Spacer(Modifier.width(6.dp))
                    // seconds over AM/PM, sized with the clock but never too small to read
                    Column {
                        Text(String.format(Locale.US, "%02d", now.second), color = HomeColors.colon,
                            fontSize = maxOf(clockSp.value * 0.24f, 12f).sp, fontWeight = FontWeight.Light,
                            lineHeight = maxOf(clockSp.value * 0.26f, 13f).sp)
                        if (!use24) Text(ampm, color = Palette.dim, fontSize = maxOf(clockSp.value * 0.2f, 11f).sp,
                            fontWeight = FontWeight.Medium, lineHeight = maxOf(clockSp.value * 0.22f, 12f).sp)
                    }
                }
                Text(date, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.height(8.dp))
                s.weather?.takeIf { it.tempC != null }?.let { w -> WeatherRow(w, onWeather) }
            }
        }
    }
    if (waOpen) s.whatsapp?.let { WhatsAppDialog(it) { waOpen = false } }
}

// ------------------------------------------------------------------ WhatsApp (mirrored from WhatsApp Desktop)
private val WaGreen = Color(0xFF25D366)

/** One compact row: WhatsApp mark, then the number of messages and of calls waiting on the PC. */
@Composable
private fun WhatsAppCard(wa: WhatsApp, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = wa.notes.isNotEmpty()) { onOpen() }
            .padding(vertical = 2.dp),
    ) {
        WaGlyph(30.dp)
        Spacer(Modifier.width(12.dp))
        if (!wa.installed || (!wa.running && wa.notes.isEmpty())) {
            Text(if (!wa.installed) "WhatsApp Desktop isn't set up on the PC" else "WhatsApp isn't open on the PC",
                color = Palette.dim, fontSize = 12.sp, maxLines = 1)
        } else {
            WaCount(wa.messages, if (wa.messages == 1) "message" else "messages") { c -> drawMessageIcon(c) }
            Spacer(Modifier.width(16.dp))
            WaCount(wa.calls, if (wa.calls == 1) "call" else "calls") { c -> drawCallIcon(c) }
        }
    }
}

@Composable
private fun WaCount(n: Int, label: String, icon: DrawScope.(Color) -> Unit) {
    val active = n > 0
    val tint = if (active) Palette.text else Palette.dim
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                .background(if (active) WaGreen.copy(alpha = 0.22f) else Color(0x14FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(16.dp)) { icon(if (active) WaGreen else Palette.dim) }
        }
        Spacer(Modifier.width(7.dp))
        Text("$n", color = tint, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(4.dp))
        Text(label, color = Palette.dim, fontSize = 12.sp)
    }
}

/** Speech bubble with two text lines. */
private fun DrawScope.drawMessageIcon(c: Color) {
    val w = size.width; val h = size.height
    drawRoundRect(c, topLeft = Offset(0f, 0f), size = Size(w, h * 0.78f), cornerRadius = CornerRadius(w * 0.22f))
    val tail = Path().apply { moveTo(w * 0.22f, h * 0.7f); lineTo(w * 0.2f, h); lineTo(w * 0.48f, h * 0.7f); close() }
    drawPath(tail, c)
    val bg = Color(0xFF10181B)
    drawRoundRect(bg, Offset(w * 0.22f, h * 0.24f), Size(w * 0.56f, h * 0.1f), CornerRadius(h * 0.05f))
    drawRoundRect(bg, Offset(w * 0.22f, h * 0.44f), Size(w * 0.38f, h * 0.1f), CornerRadius(h * 0.05f))
}

/** Phone handset. */
private fun DrawScope.drawCallIcon(c: Color) {
    val w = size.width; val h = size.height
    val s = Stroke(width = w * 0.2f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawArc(c, startAngle = 100f, sweepAngle = 160f, useCenter = false,
        topLeft = Offset(w * 0.12f, h * 0.12f), size = Size(w * 0.8f, h * 0.8f), style = s)
    drawCircle(c, radius = w * 0.17f, center = Offset(w * 0.3f, h * 0.2f))
    drawCircle(c, radius = w * 0.17f, center = Offset(w * 0.8f, h * 0.72f))
}

@Composable
private fun WaLine(n: WaNote, modifier: Modifier = Modifier, lines: Int = 1) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n.title, color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            n.t?.let { Text(ago(System.currentTimeMillis() / 1000.0 - it), color = Palette.dim, fontSize = 10.sp) }
        }
        if (n.text.isNotBlank()) {
            Text(n.text, color = Palette.dim, fontSize = 12.sp, maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WhatsAppDialog(wa: WhatsApp, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF12191C),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WaGlyph(18.dp)
                Spacer(Modifier.width(8.dp))
                Text("WhatsApp · ${wa.notes.size} new", color = Palette.text, fontSize = 16.sp)
            }
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(wa.notes, key = { it.id }) { n -> WaLine(n, Modifier.padding(vertical = 5.dp), lines = 3) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = WaGreen) } },
    )
}

/** Small WhatsApp-style mark: a green speech bubble with a phone handset. */
@Composable
private fun WaGlyph(size: Dp) {
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val c = Offset(r, r)
        drawCircle(WaGreen, radius = r * 0.92f, center = c)
        val tail = Path().apply {
            moveTo(r * 0.28f, r * 1.45f); lineTo(r * 0.05f, r * 1.98f); lineTo(r * 0.72f, r * 1.78f); close()
        }
        drawPath(tail, WaGreen)
        drawCircle(Color(0xFF0B1A12), radius = r * 0.62f, center = c)
        // handset: two ear pieces joined by an arc
        drawArc(WaGreen, startAngle = 100f, sweepAngle = 160f, useCenter = false,
            topLeft = Offset(r * 0.62f, r * 0.62f), size = Size(r * 0.76f, r * 0.76f), style = Stroke(width = r * 0.2f))
        drawCircle(WaGreen, radius = r * 0.13f, center = Offset(r * 0.78f, r * 0.72f))
        drawCircle(WaGreen, radius = r * 0.13f, center = Offset(r * 1.28f, r * 1.26f))
    }
}

/** "12h | 24h" switch: two pills side by side, the active one lit. */
@Composable
private fun ClockFormatSwitch(use24: Boolean, onChange: (Boolean) -> Unit) {
    // 12h over 24h: a slim pill left of the time
    Column(
        Modifier.width(34.dp).clip(RoundedCornerShape(9.dp)).background(Color(0x14FFFFFF)).padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        listOf(false to "12h", true to "24h").forEach { (v, label) ->
            val on = use24 == v
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(if (on) HomeColors.clock else Color.Transparent)
                    .clickable { onChange(v) }.padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) Color(0xFF0B1418) else Palette.dim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 12.sp)
            }
        }
    }
}

@Composable
private fun WeatherRow(w: Weather, onOpen: () -> Unit) {
    // tap: the detailed Weather page
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onOpen() }.padding(vertical = 2.dp)) {
        Text(weatherEmoji(w.code, w.isDay), fontSize = 24.sp)
        Spacer(Modifier.width(8.dp))
        Text("${f0(w.tempC)}°", color = HomeColors.clock, fontSize = 26.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("${w.condition ?: ""} · ${if (w.source == "manual") "" else "📍"}${w.city ?: ""}", color = Palette.text,
                fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull("↑${f0(w.highC)}° ↓${f0(w.lowC)}°", "Feels ${f0(w.feelsC)}°",
                w.humidity?.let { "💧${f0(it)}%" }, w.rainPct?.let { "☔${f0(it)}%" }).joinToString(" · "),
                color = Palette.dim, fontSize = 11.sp, maxLines = 1)
        }
    }
}

fun weatherEmoji(code: Int?, day: Boolean): String = when (code) {
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
            "${s.ram.usedMb?.let { String.format(Locale.US, "%.1f", it / 1024) } ?: "?"} / ${s.ram.totalMb?.let { f0(it / 1024) } ?: "?"} GB" +
                // the split, when there's more than one module: "· 8+8"
                (s.ram.modules.takeIf { it.size > 1 }?.joinToString("+", prefix = " · ") { "${it.gb}" } ?: ""),
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
private fun SettingsDialog(
    w: Weather?, onDismiss: () -> Unit, onCity: (String) -> Unit, onAuto: () -> Unit, onDisconnect: () -> Unit,
) {
    val manual = w?.source == "manual"
    var c by remember { mutableStateOf(if (manual) w?.city ?: "" else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        title = { Text("Settings", color = Palette.text) },
        text = {
            Column {
                Text(if (manual) "Weather: fixed city (${w?.city ?: ""})" else "Weather: PC location (${w?.city ?: "finding…"})",
                    color = Palette.dim, fontSize = 12.sp)
                if (manual) {
                    Text("Use PC location", color = Palette.accent, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onAuto() }.padding(vertical = 6.dp))
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = c, onValueChange = { c = it }, singleLine = true, label = { Text("Or a fixed city") },
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text),
                )
                Spacer(Modifier.height(12.dp))
                // the app updates itself from GitHub; this checks right now
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                Text("PerfLink ${AppUpdater.installedVersion(ctx)} · Check for updates", color = Palette.accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable {
                        scope.launch { AppUpdater.checkAndUpdate(ctx, manual = true) }
                    }.padding(vertical = 6.dp))
                Text("Disconnect from PC", color = Palette.bad, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onDisconnect() }.padding(vertical = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { if (c.isNotBlank()) onCity(c) else onDismiss() }) { Text("Save") } },
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

/** "USB" with a plug or "Wi-Fi" with signal arcs: how the phone is connected to the PC. */
@Composable
private fun LinkChip(usb: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(13.dp).height(11.dp)) {
            val c = Palette.dim
            val st = 1.3.dp.toPx()
            if (usb) {  // a plug: body, two prongs, cable
                val w = size.width; val h = size.height
                drawRoundRect(c, topLeft = Offset(w * 0.2f, h * 0.3f), size = Size(w * 0.6f, h * 0.42f),
                    cornerRadius = CornerRadius(1.5.dp.toPx()))
                drawLine(c, Offset(w * 0.38f, h * 0.3f), Offset(w * 0.38f, 0f), st)
                drawLine(c, Offset(w * 0.62f, h * 0.3f), Offset(w * 0.62f, 0f), st)
                drawLine(c, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h), st)
            } else {    // Wi-Fi: a dot and two arcs
                val cx = size.width / 2; val bottom = size.height
                drawCircle(c, radius = 1.4.dp.toPx(), center = Offset(cx, bottom - 1.4.dp.toPx()))
                for (r in listOf(size.height * 0.55f, size.height * 0.95f)) {
                    drawArc(c, startAngle = 225f, sweepAngle = 90f, useCenter = false,
                        topLeft = Offset(cx - r, bottom - r), size = Size(r * 2, r * 2), style = Stroke(width = st))
                }
            }
        }
        Spacer(Modifier.width(4.dp))
        Text(if (usb) "USB" else "Wi-Fi", color = Palette.dim, fontSize = 11.sp, maxLines = 1)
    }
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
        Text((if (label.isBlank()) "" else "$label ") + "$pct%" + if (charging) " ⚡" else "", color = Palette.dim, fontSize = 11.sp, maxLines = 1)
        secsLeft?.let {
            val m = (it / 60).toInt()
            Text(" · ${if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"} left", color = Palette.dim.copy(alpha = 0.7f),
                fontSize = 11.sp, maxLines = 1)
        }
    }
}
