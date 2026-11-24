package com.perflink.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// =============================================================================== Mirror page
// The PC's main monitor, live, with its sound on the phone's speaker: e.g. play a video on the PC,
// switch the monitor off and keep watching (and listening) on the phone. Works over Wi-Fi or USB.
// Touch control: tap = click there, hold = right-click, drag = drag, two fingers = scroll.
// Streams only while this page is on screen and the app is open.

@Composable
fun MirrorPage(state: UiState, vm: PerfViewModel) {
    val frame by vm.mirrorFrame.collectAsState()
    var sound by remember { mutableStateOf(vm.mirrorSound) }
    var touch by remember { mutableStateOf(false) }   // off by default: just watch (and swipe pages); tap the chip to control
    val haptic = LocalHapticFeedback.current

    // stream while this page is showing and the app is in front; stop when leaving either
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.mirror(true, monitor = 0)
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
        val frameSize by rememberUpdatedState(f?.let { IntSize(it.width, it.height) })
        if (f != null) {
            Image(f, contentDescription = "PC screen", contentScale = ContentScale.Fit, filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize().then(
                    // touches go to the PC (and so don't swipe pages: use the Home button, or switch touch off)
                    if (touch) Modifier.pointerInput(Unit) { mirrorTouch(vm, { frameSize }) { haptic.performHapticFeedback(it) } }
                    else Modifier
                ))
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.stats?.monitors == null) {
                    Text("Update PerfLink on the PC to mirror its screen.", color = Palette.dim, fontSize = 13.sp)
                } else {
                    CircularProgressIndicator(color = Palette.accent)
                    Spacer(Modifier.height(12.dp))
                    Text("Waiting for the PC screen…", color = Palette.dim, fontSize = 13.sp)
                }
            }
        }
        // top-right, left of the Home button
        Row(Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = HomeButtonGap + 8.dp)) {
            OverlayChip(if (touch) "👆 Touch controls PC" else "👆 Touch off", active = touch) { touch = !touch }
            Spacer(Modifier.width(8.dp))
            OverlayChip(if (sound) "🔊 Sound on phone" else "🔇 Sound off", active = sound) {
                sound = !sound; vm.mirror(true, monitor = 0, sound = sound)
            }
        }
    }
}

/** Touches on the PC's picture: tap = left click there, hold = right click, one-finger drag = mouse
 *  drag (move windows, select text), two fingers = scroll (tap with two = right click). */
private suspend fun PointerInputScope.mirrorTouch(
    vm: PerfViewModel, frameSize: () -> IntSize?, feedback: (HapticFeedbackType) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    // where the picture sits inside this view (it's letter-boxed to keep the PC's shape)
    fun toScreen(o: Offset): Pair<Double, Double>? {
        val fs = frameSize() ?: return null
        val scale = min(size.width / fs.width.toFloat(), size.height / fs.height.toFloat())
        val w = fs.width * scale; val h = fs.height * scale
        val x = (o.x - (size.width - w) / 2) / w; val y = (o.y - (size.height - h) / 2) / h
        return if (x in 0f..1f && y in 0f..1f) x.toDouble() to y.toDouble() else null
    }
    fun send(type: String, p: Pair<Double, Double>, extra: org.json.JSONObject.() -> Unit = {}) =
        vm.input(type) { put("x", p.first); put("y", p.second); extra() }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val start = down.uptimeMillis
        val p0 = down.position
        var last = p0
        var maxFingers = 1
        var dragging = false
        var scrolling = false
        var travel = 0f
        var scrollRest = 0f
        while (true) {
            val ev = awaitPointerEvent()
            val pressed = ev.changes.filter { it.pressed }
            maxFingers = max(maxFingers, pressed.size)
            if (pressed.size == 1 && maxFingers == 1) {
                val c = pressed[0]
                last = c.position
                travel = max(travel, (c.position - p0).getDistance())
                if (!dragging && travel > slop) {
                    toScreen(p0)?.let { send("drag", it) { put("phase", "start") }; dragging = true }
                }
                if (dragging && c.positionChange() != Offset.Zero) toScreen(c.position)?.let { send("drag", it) { put("phase", "move") } }
            } else if (pressed.size >= 2) {
                if (dragging) { toScreen(last)?.let { send("drag", it) { put("phase", "end") } }; dragging = false }
                if (!scrolling) {  // put the pointer where the fingers are, so that window scrolls
                    scrolling = true
                    toScreen(pressed.map { it.position }.reduce { a, b -> a + b } / pressed.size.toFloat())
                        ?.let { send("drag", it) { put("phase", "move") } }
                }
                val dy = pressed.map { it.positionChange().y }.average().toFloat()
                travel += abs(dy)
                scrollRest += dy * 4f              // natural scrolling: content follows the fingers
                if (abs(scrollRest) >= 40f) {
                    vm.input("scroll") { put("dy", scrollRest.toInt()) }
                    scrollRest = 0f
                }
            }
            ev.changes.forEach { it.consume() }
            if (ev.changes.none { it.pressed }) {
                val held = ev.changes.first().uptimeMillis - start
                when {
                    dragging -> toScreen(last)?.let { send("drag", it) { put("phase", "end") } }
                    maxFingers == 1 && travel <= slop -> toScreen(p0)?.let {
                        val right = held >= 500                         // press and hold = right click
                        if (right) feedback(HapticFeedbackType.LongPress)
                        send("tap", it) { put("button", if (right) "right" else "left") }
                    }
                    maxFingers >= 2 && travel <= slop && held < 300 -> toScreen(p0)?.let {
                        send("tap", it) { put("button", "right") }
                    }
                }
                break
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
