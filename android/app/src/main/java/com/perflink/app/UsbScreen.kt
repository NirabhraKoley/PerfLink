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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val UsbCyan = Color(0xFF22D3EE)
private val UsbGreen = Color(0xFF22C55E)
private val UsbRed = Color(0xFFF87171)

/** 24×24 line icons for each device type. */
object UsbIcons {
    const val MOUSE = "M12 3a6 6 0 0 1 6 6v6a6 6 0 0 1-12 0V9a6 6 0 0 1 6-6z M12 7v4"
    const val GAMEPAD = "M7 8h10a4 4 0 0 1 4 4v1a4 4 0 0 1-7 2.6h-4A4 4 0 0 1 3 13v-1a4 4 0 0 1 4-4z M7.5 11v3 M6 12.5h3 M15.5 12h.01 M17.5 14h.01"
    const val WEBCAM = "M12 3a6 6 0 1 0 0.01 0z M12 6.5a2.5 2.5 0 1 0 0.01 0z M9 18l-2.5 3h11L15 18"
    const val PHONE = "M8 2.5h8a1.5 1.5 0 0 1 1.5 1.5v16a1.5 1.5 0 0 1-1.5 1.5H8A1.5 1.5 0 0 1 6.5 20V4A1.5 1.5 0 0 1 8 2.5z M11 18.5h2"
    const val BLUETOOTH = "M7 7l10 10-5 4V3l5 4L7 17"
    const val STORAGE = "M8 9h8v11a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1z M9.5 3h5v6h-5z M11 5.5h.01 M13 5.5h.01"
    const val USB = "M12 21V4 M9 6.5l3-3.5 3 3.5 M12 16l-5-3V9.5 M12 13l5-3V7.5 M5.8 8h2.4v2.4H5.8z M17 6.3a1.2 1.2 0 1 0 0.01 0z M12 18.5a1.6 1.6 0 1 0 0.01 0z"
    const val WIFI = "M5 12a10 10 0 0 1 14 0 M8.5 15.5a5 5 0 0 1 7 0 M12 19h.01"
    const val PRINTER = "M7 9V3h10v6 M5 9h14a2 2 0 0 1 2 2v5h-4 M7 16H3v-5a2 2 0 0 1 2-2 M7 14h10v7H7z"

    fun forCategory(c: String) = when (c) {
        "Mouse" -> MOUSE
        "Keyboard" -> LineIcons.KEYBOARD
        "Gamepad" -> GAMEPAD
        "Audio" -> LineIcons.HEADPHONES
        "Camera" -> WEBCAM
        "Phone" -> PHONE
        "Bluetooth" -> BLUETOOTH
        "Storage" -> STORAGE
        "Network" -> WIFI
        "Printer" -> PRINTER
        else -> USB
    }

    fun color(c: String) = when (c) {
        "Mouse" -> Color(0xFF34D399)
        "Keyboard" -> Color(0xFF60A5FA)
        "Gamepad" -> Color(0xFFF472B6)
        "Audio" -> Color(0xFFA78BFA)
        "Camera" -> Color(0xFFFBBF24)
        "Phone" -> Color(0xFF4ADE80)
        "Bluetooth" -> Color(0xFF3B82F6)
        "Storage" -> Color(0xFFFB923C)
        "Hub" -> Color(0xFF94A3B8)
        else -> UsbCyan
    }
}

@Composable
fun UsbPage(state: UiState) {
    val u = state.stats?.usb
    Box(
        Modifier.fillMaxSize().background(
            pageGlow(Color(0xFF0B2230), 1500f)
        )
    ) {
        if (u == null) {
            Text("Waiting for the PC…", color = Palette.dim, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 22.dp)) {
            // ---------------- left: the devices
            Column(Modifier.weight(2f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = DisconnectGap, bottom = 10.dp)) {
                    Text("plugged into this PC", color = Palette.dim, fontSize = 12.sp)
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(190.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(u.devices, key = { it.id }) { DeviceCard(it) }
                }
            }
            Spacer(Modifier.width(14.dp))
            // ---------------- right: count, drives, plug/unplug feed
            Column(Modifier.weight(1f).fillMaxHeight().padding(top = 30.dp).verticalScroll(rememberScrollState())) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.veil(0x14 / 255f))
                        .border(1.dp, UsbCyan.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("${u.deviceCount}", color = Palette.text, fontSize = 46.sp, fontWeight = FontWeight.Light, lineHeight = 48.sp)
                    Text(if (u.deviceCount == 1) "device connected" else "devices connected", color = Palette.text, fontSize = 13.sp)
                    Text("+ ${u.hubCount} USB hub${if (u.hubCount == 1) "" else "s"}", color = Palette.dim, fontSize = 11.sp)
                }
                if (u.drives.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("USB DRIVES", color = Palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    u.drives.forEach { d ->
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Row {
                                Text("${d.letter} ${d.fs ?: ""}", color = Palette.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                if (d.totalGb != null && d.usedGb != null) {
                                    Text(String.format(Locale.US, "%.1f / %.1f GB", d.usedGb, d.totalGb), color = Palette.dim, fontSize = 11.sp)
                                }
                            }
                            if (d.totalGb != null && d.usedGb != null && d.totalGb > 0) {
                                Spacer(Modifier.height(3.dp))
                                UsageBar(d.usedGb / d.totalGb * 100, Color(0xFFFB923C), height = 4.dp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("RECENT", color = Palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(4.dp))
                if (u.events.isEmpty()) Text("Plug or unplug something to see it here", color = Palette.dim, fontSize = 12.sp)
                u.events.take(10).forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (e.kind == "connected") UsbGreen else UsbRed))
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
private fun DeviceCard(d: UsbDevice) {
    val c = UsbIcons.color(d.category)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (d.hub) Palette.veil(0x0A / 255f) else Palette.veil(0x12 / 255f))
            .border(1.dp, Palette.veil(0x14 / 255f), RoundedCornerShape(12.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            UsbGlyph(UsbIcons.forCategory(d.category), 22.dp, c)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.name, color = if (d.hub) Palette.dim else Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(d.category, d.port).joinToString(" · "), color = Palette.dim, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            d.arrived?.let {
                Text("plugged in ${ago(System.currentTimeMillis() / 1000.0 - it)} ago", color = Palette.dim.copy(alpha = 0.8f),
                    fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun UsbGlyph(path: String, size: Dp, tint: Color) {
    Image(rememberVectorPainter(lineIcon(path)), null, modifier = Modifier.size(size), colorFilter = ColorFilter.tint(tint))
}

/** Pop-up on every page when a USB device is plugged in or removed. */
@Composable
fun UsbAlertBanner(e: UsbEvent, modifier: Modifier = Modifier) {
    val color = if (e.kind == "connected") UsbGreen else UsbRed
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Color(0xF0151B22)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UsbGlyph(UsbIcons.USB, 16.dp, color)
        Spacer(Modifier.width(8.dp))
        Text(e.text, color = Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
