package com.perflink.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BtBlue = Color(0xFF3B82F6)
private val BtGreen = Color(0xFF22C55E)
private val BtRed = Color(0xFFF87171)
private const val WATCH = "M8 7h8a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z M9 7l.7-4h4.6l.7 4 M9 17l.7 4h4.6l.7-4 M12 10v2.2l1.4 1"

private fun btIcon(type: String) = when (type) {
    "Audio" -> LineIcons.HEADPHONES
    "Mouse" -> UsbIcons.MOUSE
    "Keyboard", "Input" -> LineIcons.KEYBOARD
    "Gamepad" -> UsbIcons.GAMEPAD
    "Phone" -> UsbIcons.PHONE
    "Watch" -> WATCH
    else -> UsbIcons.BLUETOOTH
}

private fun btColor(type: String) = when (type) {
    "Audio" -> Color(0xFFA78BFA)
    "Mouse" -> Color(0xFF34D399)
    "Keyboard", "Input" -> Color(0xFF60A5FA)
    "Gamepad" -> Color(0xFFF472B6)
    "Phone" -> Color(0xFF4ADE80)
    "Watch" -> Color(0xFFFBBF24)
    else -> BtBlue
}

@Composable
fun BluetoothPage(state: UiState) {
    val b = state.stats?.bluetooth
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF0C1A33), Color(0xFF080B0E)), radius = 1500f)
        )
    ) {
        if (b == null) {
            Text("Waiting for the PC…", color = Palette.dim, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val radioOn = b.radio == null || b.radio.equals("On", ignoreCase = true)
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 22.dp)) {
            // ---------------- left: devices
            Column(Modifier.weight(2f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
                    Text("BLUETOOTH & WI-FI", color = BtBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                    Spacer(Modifier.width(10.dp))
                    Text("devices paired with this PC", color = Palette.dim, fontSize = 12.sp)
                }
                if (b.devices.isEmpty()) {
                    EmptyState(
                        if (!radioOn) "Bluetooth is off on this PC" else "No Bluetooth devices are paired",
                        if (!radioOn) "Turn it on in Windows: Settings → Devices → Bluetooth & other devices."
                        else "Pair headphones, a mouse, a controller… in Windows Bluetooth settings and they'll appear here.",
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(190.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(b.devices, key = { it.id }) { BtCard(it) }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            // ---------------- right: count + feed
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 30.dp).verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF))
                        .border(1.dp, BtBlue.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${b.connectedCount}", color = Palette.text, fontSize = 46.sp, fontWeight = FontWeight.Light, lineHeight = 48.sp)
                    Text(if (b.connectedCount == 1) "device connected" else "devices connected", color = Palette.text, fontSize = 13.sp)
                    Text("${b.pairedCount} paired", color = Palette.dim, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x14FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (radioOn) BtGreen else BtRed))
                        Spacer(Modifier.width(6.dp))
                        Text(if (radioOn) "Bluetooth on" else "Bluetooth off", color = Palette.text, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                state.stats?.wifi?.let { NetworkCard(it) }
                Spacer(Modifier.height(12.dp))
                Text("RECENT", color = Palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(4.dp))
                if (b.events.isEmpty()) Text("Connect or disconnect a device to see it here", color = Palette.dim, fontSize = 12.sp)
                b.events.take(10).forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (e.kind == "connected") BtGreen else BtRed))
                        Spacer(Modifier.width(8.dp))
                        Text(e.text, color = Palette.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(ago(System.currentTimeMillis() / 1000.0 - e.t), color = Palette.dim, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, sub: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(BtBlue.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            BtGlyph(UsbIcons.BLUETOOTH, 32.dp, BtBlue)
        }
        Spacer(Modifier.height(12.dp))
        Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(sub, color = Palette.dim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
    }
}

@Composable
private fun BtCard(d: BtDevice) {
    val c = btColor(d.type)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (d.connected) Color(0x14FFFFFF) else Color(0x08FFFFFF))
            .border(1.dp, if (d.connected) c.copy(alpha = 0.45f) else Color(0x14FFFFFF), RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = if (d.connected) 0.18f else 0.07f)),
                contentAlignment = Alignment.Center) {
                BtGlyph(btIcon(d.type), 22.dp, if (d.connected) c else Palette.dim)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, color = if (d.connected) Palette.text else Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (d.connected) BtGreen else Color(0xFF4B5563)))
                    Spacer(Modifier.width(5.dp))
                    Text(listOf(if (d.connected) "Connected" else "Paired", d.type).joinToString(" · "),
                        color = Palette.dim, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        d.battery?.let { pct ->
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Battery", color = Palette.dim, fontSize = 10.sp, modifier = Modifier.weight(1f))
                Text("$pct%", color = if (pct <= 20) BtRed else Palette.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(3.dp))
            UsageBar(pct.toDouble(), if (pct <= 20) BtRed else BtGreen, height = 4.dp)
        }
    }
}

@Composable
private fun BtGlyph(path: String, size: Dp, tint: Color) {
    Image(rememberVectorPainter(lineIcon(path)), null, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

// ------------------------------------------------------------------ Wi-Fi / wired connection
private const val WIFI_ICON = "M5 12.5a10 10 0 0 1 14 0 M8.5 16a5 5 0 0 1 7 0 M12 19.5h.01 M2 9a14.5 14.5 0 0 1 20 0"
private const val ETH_ICON = "M6 4h12a1 1 0 0 1 1 1v9a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M9 15v4h6v-4 M8.5 8v3 M11 8v3 M13.5 8v3 M16 8v3"
private val WifiTeal = Color(0xFF22D3EE)

@Composable
private fun NetworkCard(w: WifiState) {
    val link = w.wifi.firstOrNull { it.connected }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x14FFFFFF))
            .border(1.dp, WifiTeal.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BtGlyph(if (link != null || w.wired.isEmpty()) WIFI_ICON else ETH_ICON, 16.dp, WifiTeal)
            Spacer(Modifier.width(6.dp))
            Text(if (link != null || w.wired.isEmpty()) "WI-FI" else "WIRED", color = WifiTeal, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        }
        Spacer(Modifier.height(6.dp))
        when {
            link != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(link.ssid ?: "Hidden network", color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    link.signal?.let { SignalBars(it) }
                }
                link.signal?.let { Text("Signal $it%", color = Palette.dim, fontSize = 11.sp) }
                Spacer(Modifier.height(6.dp))
                NetRow("Standard", listOfNotNull(link.standard, wifiGen(link.standard)).joinToString(" · ").ifBlank { null })
                NetRow("Band", listOfNotNull(link.band, link.channel?.let { "ch $it" }).joinToString(" · ").ifBlank { null })
                NetRow("Speed", if (link.rxMbps != null || link.txMbps != null)
                    "↓ ${f0(link.rxMbps)} · ↑ ${f0(link.txMbps)} Mbps" else null)
                NetRow("Security", link.security)
                NetRow("IP", link.ip)
                NetRow("Adapter", link.adapter)
            }
            w.wired.isNotEmpty() -> {
                w.wired.forEach { e ->
                    Text(e.iface, color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    NetRow("Speed", e.speedMbps?.let { if (it >= 1000) "${f1(it / 1000)} Gbps" else "${f0(it)} Mbps" })
                    NetRow("IP", e.ip)
                }
                Spacer(Modifier.height(4.dp))
                Text(if (!w.available) "This PC has no Wi-Fi (or Wi-Fi is off)" else "Wi-Fi not connected",
                    color = Palette.dim, fontSize = 11.sp)
            }
            else -> Text(if (!w.available) "No Wi-Fi on this PC" else "Not connected to Wi-Fi", color = Palette.dim, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NetRow(k: String, v: String?) {
    if (v == null) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, color = Palette.dim, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Text(v, color = Palette.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SignalBars(pct: Int) {
    val lit = when { pct >= 80 -> 4; pct >= 60 -> 3; pct >= 35 -> 2; pct > 0 -> 1; else -> 0 }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(Modifier.width(4.dp).height((4 + i * 3).dp).clip(RoundedCornerShape(1.dp))
                .background(if (i <= lit) WifiTeal else Color(0x33FFFFFF)))
        }
    }
}

private fun wifiGen(std: String?): String? = when {
    std == null -> null
    std.contains("be", true) -> "Wi-Fi 7"
    std.contains("ax", true) -> "Wi-Fi 6"
    std.contains("ac", true) -> "Wi-Fi 5"
    std.endsWith("n", true) -> "Wi-Fi 4"
    else -> null
}
