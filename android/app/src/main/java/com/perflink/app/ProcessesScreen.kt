package com.perflink.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val ProcPink = Color(0xFFF472B6)
private val ProcBlue = Color(0xFF60A5FA)
private val ProcRowBg = Color(0x0FFFFFFF)
private val ProcRowBorder = Color(0x14FFFFFF)

@Composable
fun ProcessesPage(state: UiState) {
    val s = state.stats ?: return
    val list = s.processes
    var byRam by rememberSaveable { mutableStateOf(false) }
    var selectedKey by remember { mutableStateOf<String?>(null) }

    val apps = (list?.apps ?: emptyList()).let { a ->
        if (byRam) a.sortedByDescending { it.ramMb } else a.sortedWith(compareByDescending<ProcApp> { it.cpu }.thenByDescending { it.ramMb })
    }
    val maxCpu = apps.maxOfOrNull { it.cpu }?.coerceAtLeast(0.5) ?: 1.0
    val maxRam = apps.maxOfOrNull { it.ramMb }?.coerceAtLeast(1.0) ?: 1.0

    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF0E2422), Color(0xFF080B0E)), radius = 1500f)
        )
    ) {
        Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp, end = HomeButtonGap - 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("PROCESSES", color = ProcPink, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Total CPU ${f0(s.cpu.util)}% · ${list?.totalProcesses ?: 0} processes · ${list?.totalApps ?: 0} apps · tap one for details",
                    color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Segmented(byRam) { byRam = it }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(apps, key = { it.key }) { a ->
                    ProcRow(a, state.icons[a.iconId], byRam, maxCpu, maxRam) { selectedKey = a.key }
                }
            }
        }
    }
    // Look the app up again each frame so the details dialog stays live
    apps.firstOrNull { it.key == selectedKey }?.let { a ->
        ProcDetails(a, state.icons[a.iconId]) { selectedKey = null }
    }
}

@Composable
private fun Segmented(byRam: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF)).padding(3.dp)) {
        listOf(false to "CPU", true to "RAM").forEach { (v, label) ->
            val sel = byRam == v
            Text(
                label, color = if (sel) Palette.text else Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (sel) Color(0x26FFFFFF) else Color.Transparent)
                    .clickable { onChange(v) }.padding(horizontal = 16.dp, vertical = 5.dp),
            )
        }
    }
}

@Composable
private fun ProcRow(a: ProcApp, icon: ImageBitmap?, byRam: Boolean, maxCpu: Double, maxRam: Double, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(ProcRowBg)
            .border(1.dp, ProcRowBorder, RoundedCornerShape(10.dp)).clickable { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(a.name, icon, 26.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, color = Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (a.count > 1) Text(" ×${a.count}", color = Palette.dim, fontSize = 11.sp, maxLines = 1)
                if (a.locked) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Filled.Lock, contentDescription = "protected", tint = Palette.dim, modifier = Modifier.size(11.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            val frac = if (byRam) a.ramMb / maxRam else a.cpu / maxCpu
            UsageBar(frac * 100, if (byRam) ProcBlue else ProcPink, height = 3.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(58.dp)) {
            Text(if (byRam) gb(a.ramMb) else cpuPct(a.cpu), color = Palette.text, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
            Text(if (byRam) "${cpuPct(a.cpu)} CPU" else gb(a.ramMb), color = Palette.dim, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun AppIcon(name: String, icon: ImageBitmap?, size: Dp) {
    if (icon != null) {
        Image(icon, contentDescription = null, modifier = Modifier.size(size))
    } else {
        // Letter tile for protected processes whose exe icon can't be read
        val colors = listOf(Color(0xFFE5739F), Color(0xFF5B8DEF), Color(0xFF34B3A0), Color(0xFFE0A84F), Color(0xFF9B7BE8))
        val c = colors[(name.hashCode() and 0x7fffffff) % colors.size]
        Box(Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(c), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ProcDetails(a: ProcApp, icon: ImageBitmap?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.card,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(a.name, icon, 30.dp)
                Spacer(Modifier.width(12.dp))
                Text(a.name, color = Palette.text, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column {
                KV("CPU", cpuPct(a.cpu))
                KV("Memory", gb(a.ramMb))
                KV("Executable", a.exe)
                KV("Instances", "${a.count}")
                KV("Threads", if (a.threads > 0) "${a.threads}" else null)
                KV("Running for", a.started?.let { ago(System.currentTimeMillis() / 1000.0 - it) })
                KV("PIDs", a.pids.joinToString(", ") + if (a.count > a.pids.size) " …" else "")
                if (a.locked) Text("Some instances are protected (system or elevated).", color = Palette.dim, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp))
                a.path?.let {
                    Text(it, color = Palette.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun cpuPct(v: Double) = String.format(Locale.US, "%.1f%%", v)
