package com.perflink.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

// =============================================================================== Mirror page
// The PC's screen, live, with its sound on the phone's speaker: e.g. play a video on the PC, switch the
// monitor off and keep watching (and listening) on the phone. Works over Wi-Fi or USB.
// Streams only while this page is on screen and the app is open.

@Composable
fun MirrorPage(state: UiState, vm: PerfViewModel) {
    val frame by vm.mirrorFrame.collectAsState()
    val monitors = state.stats?.monitors
    var monitor by remember { mutableStateOf(vm.mirrorMonitor) }
    var sound by remember { mutableStateOf(vm.mirrorSound) }

    // stream while this page is showing and the app is in front; stop when leaving either
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.mirror(true)
                Lifecycle.Event.ON_STOP -> vm.mirror(false)
                else -> {}
            }
        }
        lifecycle.addObserver(obs)   // also delivers ON_START right away when the app is already open
        onDispose {
            lifecycle.removeObserver(obs)
            vm.mirror(false)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val f = frame
        if (f != null) {
            Image(f, contentDescription = "PC screen", modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit, filterQuality = FilterQuality.Low)
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    monitors == null -> Text("Update PerfLink on the PC to mirror its screen.", color = Palette.dim, fontSize = 13.sp)
                    else -> {
                        CircularProgressIndicator(color = Palette.accent)
                        Spacer(Modifier.height(12.dp))
                        Text("Waiting for the PC screen…", color = Palette.dim, fontSize = 13.sp)
                    }
                }
            }
        }
        // top-right, left of the Home button: monitor picker (when there are several, including any
        // switched off) and sound on/off
        val on = monitors.orEmpty().filter { !it.off }
        val off = monitors.orEmpty().filter { it.off }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = HomeButtonGap + 8.dp),
            horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if ((monitors?.size ?: 0) > 1) {
                    MonitorPicker(monitors!!, monitor) { i -> monitor = i; vm.mirror(true, monitor = i) }
                    Spacer(Modifier.width(8.dp))
                }
                OverlayChip(if (sound) "🔊 Sound on phone" else "🔇 Sound off", active = sound) {
                    sound = !sound; vm.mirror(true, sound = sound)
                }
            }
            // a monitor switched off with its power button that Windows dropped: say where its windows went
            if (off.isNotEmpty() && on.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("${off.joinToString { it.name.substringBefore(" · switched off") }} is switched off — Windows moved its " +
                    "windows to ${on.first().name.substringBefore(" ·")}, shown here",
                    color = Palette.text, fontSize = 11.sp, maxLines = 2,
                    modifier = Modifier.width(300.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xB3141A22))
                        .padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun MonitorPicker(monitors: List<Monitor>, selected: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = monitors.firstOrNull { it.index == selected && !it.off } ?: monitors.firstOrNull { !it.off } ?: monitors.first()
    Box {
        OverlayChip("🖥 ${current.name}  ▾", active = true) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false },
            modifier = Modifier.background(Palette.card)) {
            monitors.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Text(m.name, fontSize = 13.sp, color = when {
                            m.off -> Palette.dim
                            m.index == current.index -> Palette.accent
                            else -> Palette.text
                        })
                    },
                    enabled = !m.off,   // a switched-off monitor isn't there to show
                    onClick = { open = false; onPick(m.index) },
                )
            }
        }
    }
}

@Composable
private fun OverlayChip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(label, color = if (active) Palette.text else Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xB3141A22))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(50)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp))
}
