package com.perflink.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// =============================================================================== Weather page
// Detailed weather for where the PC is: now, the next 24 hours and 7 days, plus air quality.
// The PC finds its own location (Windows location, or its internet connection's), not the phone.

private val WxBlue = Color(0xFF6CB8FF)

@Composable
fun WeatherPage(state: UiState) {
    val d = state.stats?.weatherDetail
    val day = d?.now?.isDay ?: true
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(themed(
                if (day) listOf(Color(0xFF0D2236), Color(0xFF0A1622), Color(0xFF081018))
                else listOf(Color(0xFF0B0F24), Color(0xFF0A0D1A), Color(0xFF07090F))
            ))
        )
    ) {
        if (d == null) {
            Text(if (state.stats?.weather?.tempC == null) "Finding the PC's location and weather…"
                 else "Update PerfLink on the PC to see the detailed weather.",
                color = Palette.dim, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp)) {
            Header(d)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.weight(0.43f).fillMaxHeight()) { NowBlock(d) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(0.57f).fillMaxHeight()) { Forecast(d) }
            }
        }
    }
}

@Composable
private fun Header(d: WeatherDetail) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = DisconnectGap, end = HomeButtonGap)) {
        Text("📍 " + listOfNotNull(d.now.place ?: d.now.city, d.country).joinToString(", "), color = Palette.text,
            fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        val src = when (d.now.source) {
            "windows" -> "PC location (Windows)"
            "network" -> "PC location (approx.)"
            else -> "fixed city"
        }
        Text(src, color = Palette.dim, fontSize = 10.sp, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(Palette.veil(0x14 / 255f)).padding(horizontal = 7.dp, vertical = 2.dp))
        d.updated?.let {
            Spacer(Modifier.width(8.dp))
            Text("updated ${ago(System.currentTimeMillis() / 1000.0 - it)}", color = Palette.dim, fontSize = 10.sp, maxLines = 1)
        }
    }
}

// ------------------------------------------------------------------ now
@Composable
private fun ColumnScope.NowBlock(d: WeatherDetail) {
    val n = d.now
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(weatherEmoji(n.code, n.isDay), fontSize = 40.sp)
        Spacer(Modifier.width(10.dp))
        Text("${f0(n.tempC)}°", color = Palette.text, fontSize = 50.sp, fontWeight = FontWeight.Light, lineHeight = 52.sp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(n.condition ?: "", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text("Feels ${f0(n.feelsC)}° · ↑${f0(n.highC)}° ↓${f0(n.lowC)}°", color = Palette.dim, fontSize = 11.sp, maxLines = 1)
        }
    }
    Spacer(Modifier.height(8.dp))
    val tiles = listOf(
        "HUMIDITY" to (n.humidity?.let { "${f0(it)}%" } ?: "–") to d.dewPointC?.let { "dew point ${f0(it)}°" },
        "WIND" to (n.windKmh?.let { "${f0(it)} km/h ${d.windDir ?: ""}" } ?: "–") to d.gustKmh?.let { "gusts ${f0(it)} km/h" },
        "UV INDEX" to (d.uv?.let { f1(it) } ?: "–") to d.uv?.let { uvLabel(it) },
        "AIR QUALITY" to (d.aqi?.let { f0(it) } ?: "–") to (d.aqiLabel ?: d.pm25?.let { "PM2.5 ${f0(it)}" }),
        // 6 tiles (3 rows) so each has room for its 3 lines on any screen
        "VISIBILITY" to (d.visibilityKm?.let { "${f1(it)} km" } ?: "–") to
            listOfNotNull(d.pressureHpa?.let { "${f0(it)} hPa" }, n.rainPct?.let { "rain ${f0(it)}%" }).joinToString(" · ").ifBlank { null },
        "SUN" to "↑ ${d.sunrise ?: "–"}  ↓ ${d.sunset ?: "–"}" to d.cloudPct?.let { "clouds ${f0(it)}%" },
    )
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                pair.forEach { (lv, sub) -> Tile(lv.first, lv.second, sub, aqiColor(lv.first, d.aqi), Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, sub: String?, accent: Color?, modifier: Modifier) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(Palette.veil(0x12 / 255f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = Palette.dim, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            accent?.let { Box(Modifier.width(6.dp).height(6.dp).clip(RoundedCornerShape(50)).background(it)); Spacer(Modifier.width(4.dp)) }
            Text(value, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        sub?.let { Text(it, color = Palette.dim, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

private fun uvLabel(uv: Double) = when {
    uv < 3 -> "low"; uv < 6 -> "moderate"; uv < 8 -> "high"; uv < 11 -> "very high"; else -> "extreme"
}

private fun aqiColor(label: String, aqi: Double?): Color? = if (label != "AIR QUALITY" || aqi == null) null else when {
    aqi <= 50 -> Color(0xFF5BD68A); aqi <= 100 -> Color(0xFFF2C14E); aqi <= 150 -> Color(0xFFFF9F43)
    aqi <= 200 -> Color(0xFFFF6B6B); else -> Color(0xFFB36BFF)
}

// ------------------------------------------------------------------ forecast
@Composable
private fun ColumnScope.Forecast(d: WeatherDetail) {
    Text("NEXT 24 HOURS", color = Palette.dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(3.dp))
    LazyRow(Modifier.fillMaxWidth().height(84.dp).clip(RoundedCornerShape(10.dp)).background(Palette.veil(0x0F / 255f)),
        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(d.hourly) { h ->
            Column(Modifier.width(42.dp).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (h === d.hourly.first()) "Now" else h.time, color = Palette.dim, fontSize = 10.sp)
                Text(weatherEmoji(h.code, h.isDay), fontSize = 16.sp)
                Text("${f0(h.tempC)}°", color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(h.rainPct?.takeIf { it >= 10 }?.let { "${f0(it)}%" } ?: " ", color = WxBlue, fontSize = 9.sp)
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text("7 DAYS", color = Palette.dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Spacer(Modifier.height(3.dp))
    val lo = d.daily.mapNotNull { it.lowC }.minOrNull() ?: 0.0
    val hi = d.daily.mapNotNull { it.highC }.maxOrNull() ?: 1.0
    Column(Modifier.weight(1f).fillMaxWidth()) {
        d.daily.forEachIndexed { i, day ->
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(dayName(day.date, i), color = Palette.text, fontSize = 12.sp, modifier = Modifier.width(46.dp), maxLines = 1)
                Text(weatherEmoji(day.code, true), fontSize = 14.sp, modifier = Modifier.width(24.dp))
                Text(day.rainPct?.takeIf { it >= 10 }?.let { "☔${f0(it)}%" } ?: "", color = WxBlue, fontSize = 10.sp,
                    modifier = Modifier.width(44.dp), maxLines = 1)
                Text("${f0(day.lowC)}°", color = Palette.dim, fontSize = 11.sp, modifier = Modifier.width(28.dp))
                RangeBar(day.lowC, day.highC, lo, hi, Modifier.weight(1f).height(6.dp))
                Text("${f0(day.highC)}°", color = Palette.text, fontSize = 11.sp, modifier = Modifier.width(30.dp).padding(start = 6.dp))
            }
        }
    }
}

private fun dayName(date: String, i: Int): String = if (i == 0) "Today" else runCatching {
    LocalDate.parse(date).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}.getOrDefault(date)

/** The day's low-to-high span on a bar that covers the whole week's range. */
@Composable
private fun RangeBar(low: Double?, high: Double?, weekLo: Double, weekHi: Double, modifier: Modifier) {
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Palette.veil(0x1F / 255f), cornerRadius = r)
        if (low == null || high == null || weekHi <= weekLo) return@Canvas
        val x0 = ((low - weekLo) / (weekHi - weekLo)).toFloat() * size.width
        val x1 = ((high - weekLo) / (weekHi - weekLo)).toFloat() * size.width
        drawRoundRect(
            Brush.horizontalGradient(listOf(Color(0xFF6CB8FF), Color(0xFFF2C14E), Color(0xFFFF7A59)), 0f, size.width),
            topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(size.height), size.height), cornerRadius = r,
        )
    }
}
