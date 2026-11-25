package com.perflink.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cbrt
import kotlin.math.pow

private val RecRed = Color(0xFFEF4444)
private val LivePurple = Color(0xFFA855F7)
private val CamBlue = Color(0xFF3B82F6)
private val ReplayTeal = Color(0xFF14B8A6)
private val PauseAmber = Color(0xFFF59E0B)
private val ObsPanel = Color(0x0FFFFFFF)

@Composable
fun ObsPage(state: UiState, vm: PerfViewModel) {
    val obs = state.stats?.obs
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF1A1426), Color(0xFF080B0E)), radius = 1500f)
        )
    ) {
        if (obs == null || obs.status != "connected") {
            ObsOffline(obs) { vm.action("obs") }
        } else {
            ObsConnected(obs, state, vm)
        }
        state.toast?.let { t ->
            Text(
                t.message, color = if (t.ok) Palette.text else Color(0xFFFFB4AB), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xE6151B22)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ObsOffline(obs: ObsState?, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        ObsLogo(56)
        Spacer(Modifier.height(14.dp))
        Text("OBS Studio", color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(obs?.message ?: "Waiting for the PC…", color = Palette.dim, fontSize = 14.sp,
            textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp))
        if (obs?.status == "not_running") {
            Spacer(Modifier.height(18.dp))
            Text("Open OBS", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(LivePurple).clickable { onOpen() }
                    .padding(horizontal = 26.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun ObsLogo(size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(0xFF2A2A35)).border(2.dp, Color(0xFF6B6B7B), CircleShape),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size((size * 0.36).dp).clip(CircleShape).background(Color(0xFFDADAE3)))
    }
}

@Composable
private fun ObsConnected(obs: ObsState, state: UiState, vm: PerfViewModel) {
    var confirmLive by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().padding(start = 20.dp + DisconnectGap, end = 20.dp, top = 14.dp, bottom = 20.dp)) {
        // ---------------- left: preview + outputs
        Column(Modifier.weight(1.15f).fillMaxHeight()) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                    .border(1.dp, if (obs.streamActive) LivePurple else if (obs.recActive) RecRed else Color(0x22FFFFFF), RoundedCornerShape(12.dp)),
            ) {
                val p = state.obsPreview
                if (p != null) Image(p, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                else Text("Preview", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
                Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (obs.streamActive) Badge("LIVE ${obs.streamTime ?: ""}", LivePurple)
                    if (obs.recActive) Badge(if (obs.recPaused) "PAUSED ${obs.recTime ?: ""}" else "REC ${obs.recTime ?: ""}",
                        if (obs.recPaused) PauseAmber else RecRed)
                }
                Text(obs.currentScene ?: "", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp))
                        .background(Color(0x99000000)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Spacer(Modifier.height(6.dp))
            val skippedPct = if (obs.renderTotal > 0) obs.renderSkipped * 100.0 / obs.renderTotal else 0.0
            val parts = mutableListOf(
                "${f0(obs.fps)} fps", "CPU ${obs.cpu?.let { String.format(Locale.US, "%.1f", it) } ?: "–"}%",
                "render ${obs.frameMs?.let { String.format(Locale.US, "%.1f", it) } ?: "–"} ms",
                String.format(Locale.US, "%.1f%% skipped", skippedPct),
            )
            if (obs.streamActive) {
                parts += "${obs.streamKbps?.let { f0(it) } ?: "–"} kbps"
                parts += "${obs.streamDropped} dropped"
            }
            obs.diskFreeMb?.let { parts += "${gb(it)} free" }
            Text(parts.joinToString(" · "), color = Palette.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputButton(if (obs.recActive) "Stop" else "Record", obs.recTime.takeIf { obs.recActive }, RecRed, obs.recActive,
                    Modifier.weight(1f)) { vm.obs("toggle_record") }
                OutputButton(if (obs.recPaused) "Resume" else "Pause", null, PauseAmber, obs.recPaused, Modifier.weight(1f),
                    enabled = obs.recActive) { vm.obs("toggle_pause") }
                OutputButton(if (obs.streamActive) "End live" else "Go live", obs.streamTime.takeIf { obs.streamActive }, LivePurple,
                    obs.streamActive, Modifier.weight(1f)) { if (obs.streamActive) vm.obs("toggle_stream") else confirmLive = true }
                OutputButton("Virtual cam", if (obs.virtualcam) "On" else "Off", CamBlue, obs.virtualcam, Modifier.weight(1f)) {
                    vm.obs("toggle_virtualcam")
                }
                if (obs.replayAvailable) {
                    OutputButton(if (obs.replayActive) "Save replay" else "Replay", if (obs.replayActive) "Buffer on" else "Start buffer",
                        ReplayTeal, obs.replayActive, Modifier.weight(1f)) {
                        vm.obs(if (obs.replayActive) "save_replay" else "toggle_replay")
                    }
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        // ---------------- right: scenes + audio
        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 30.dp).verticalScroll(rememberScrollState())) {
            SectionLabel("SCENES", "${obs.scenes.size}")
            obs.scenes.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { name ->
                        val cur = name == obs.currentScene
                        Text(
                            name, color = if (cur) Color.White else Palette.text, fontSize = 13.sp,
                            fontWeight = if (cur) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (cur) RecRed.copy(alpha = 0.28f) else ObsPanel)
                                .border(1.dp, if (cur) RecRed else Color(0x14FFFFFF), RoundedCornerShape(10.dp))
                                .clickable { if (!cur) vm.obs("scene", value = name) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (obs.scenes.isEmpty()) Text("No scenes", color = Palette.dim, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
            SectionLabel("AUDIO", "${obs.inputs.size}")
            obs.inputs.forEach { AudioRow(it, vm) }
            if (obs.inputs.isEmpty()) Text("No audio sources", color = Palette.dim, fontSize = 13.sp)
        }
    }
    if (confirmLive) {
        AlertDialog(
            onDismissRequest = { confirmLive = false },
            containerColor = Palette.card,
            title = { Text("Go live?", color = Palette.text) },
            text = { Text("This starts streaming from OBS to your configured service.", color = Palette.dim) },
            confirmButton = { TextButton(onClick = { confirmLive = false; vm.obs("toggle_stream") }) { Text("Go live") } },
            dismissButton = { TextButton(onClick = { confirmLive = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xB3000000)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(5.dp))
        Text(text.trim(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun SectionLabel(title: String, count: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(title, color = Palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp,
            modifier = Modifier.weight(1f))
        Text(count, color = Palette.dim, fontSize = 11.sp)
    }
}

@Composable
private fun OutputButton(
    label: String, sub: String?, color: Color, active: Boolean, modifier: Modifier,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxHeight().clip(shape)
            .background(if (active) color.copy(alpha = 0.35f) else color.copy(alpha = if (enabled) 0.1f else 0.04f))
            .border(1.dp, color.copy(alpha = if (active) 0.95f else if (enabled) 0.45f else 0.15f), shape)
            .clickable(enabled = enabled) { onClick() }.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(label, color = if (enabled) Palette.text else Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, color = if (active) Color.White else Palette.dim, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, maxLines = 1)
    }
}

@Composable
private fun AudioRow(i: ObsInput, vm: PerfViewModel) {
    var drag by remember(i.name) { mutableStateOf<Float?>(null) }
    var lastSent by remember { mutableStateOf(0L) }
    // OBS faders feel linear in loudness; a cubic curve approximates that from the 0..1 multiplier
    val pos = drag ?: cbrt((i.mul ?: 0.0).coerceIn(0.0, 1.0)).toFloat()
    Column(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(ObsPanel)
            .padding(start = 10.dp, end = 10.dp, top = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(i.name, color = if (i.muted) Palette.dim else Palette.text, fontSize = 13.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(
                if (i.muted) "muted" else i.db?.let { if (it <= -100) "-inf dB" else String.format(Locale.US, "%.1f dB", it) } ?: "",
                color = if (i.muted) RecRed else Palette.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(if (i.muted) RecRed.copy(alpha = 0.25f) else Color(0x14FFFFFF))
                    .clickable { vm.obs("mute", input = i.name) },
                contentAlignment = Alignment.Center,
            ) {
                Image(rememberVectorPainter(lineIcon(if (i.muted) LineIcons.MUTE else LineIcons.SPEAKER)), contentDescription = "mute",
                    modifier = Modifier.size(16.dp), colorFilter = ColorFilter.tint(if (i.muted) RecRed else Palette.text))
            }
        }
        ThinSlider(
            fraction = pos, enabled = true, thumbRadius = 6.dp, modifier = Modifier.fillMaxWidth(),
            onDrag = { f ->
                drag = f
                val t = System.currentTimeMillis()
                if (t - lastSent > 80) { vm.obs("volume", input = i.name, value = f.toDouble().pow(3)); lastSent = t }
            },
            onRelease = { f -> vm.obs("volume", input = i.name, value = f.toDouble().pow(3)); drag = null },
        )
    }
}
