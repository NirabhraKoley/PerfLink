package com.perflink.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// =============================================================================== Remote page
// Left half: touchpad (move, tap = click, two-finger tap = right click, two-finger drag = scroll).
// Right half: keyboard (letters, numbers/symbols, arrows, Esc/Tab/Del, Ctrl/Alt/Win/Shift shortcuts).
// Works over Wi-Fi or USB: it uses the same connection as the rest of the app.

private val RemoteAccent = Color(0xFF7C9CFF)

@Composable
fun RemotePage(state: UiState, vm: PerfViewModel) {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF0B0F17), Color(0xFF10162A), Color(0xFF0A0E14)))
        )
    ) {
        Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = DisconnectGap, end = HomeButtonGap)) {
                Text("mouse and keyboard for ${state.stats?.host ?: "the PC"} · swipe here to change page",
                    color = Palette.dim, fontSize = 11.sp, maxLines = 1)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Touchpad(vm, Modifier.weight(1f).fillMaxHeight())
                Spacer(Modifier.width(10.dp))
                Keyboard(vm, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

// ------------------------------------------------------------------ touchpad
@Composable
private fun Touchpad(vm: PerfViewModel, modifier: Modifier) {
    var dragLock by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Column(modifier) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x12FFFFFF))
                .border(1.dp, RemoteAccent.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                .pointerInput(Unit) { touchpadGestures(vm) },
            contentAlignment = Alignment.Center,
        ) {
            Text("Touchpad\n1 finger: move · tap: click\n2 fingers: tap = right click · drag = scroll",
                color = Palette.dim.copy(alpha = 0.55f), fontSize = 11.sp, lineHeight = 16.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PadButton("Left click", Modifier.weight(1f)) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.input("click") { put("button", "left") } }
            PadButton(if (dragLock) "Release" else "Hold", Modifier.weight(0.8f), active = dragLock) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                dragLock = !dragLock
                vm.input("button") { put("button", "left"); put("down", dragLock) }
            }
            PadButton("Right click", Modifier.weight(1f)) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); vm.input("click") { put("button", "right") } }
        }
    }
}

/** One gesture at a time: move with one finger, tap to click, two fingers to scroll or right-click. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.touchpadGestures(vm: PerfViewModel) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        first.consume()
        val start = first.uptimeMillis
        var maxFingers = 1
        var travel = 0f
        var restX = 0f; var restY = 0f    // sub-pixel movement carried to the next event
        var scrollRest = 0f
        while (true) {
            val ev = awaitPointerEvent()
            val down = ev.changes.filter { it.pressed }
            maxFingers = max(maxFingers, down.size)
            if (down.size == 1 && maxFingers == 1) {
                val d = down[0].positionChange()
                travel += d.getDistance()
                // gentle acceleration: slow moves stay precise, fast flicks cross the screen
                val gain = 1.2f + min(d.getDistance() * 0.06f, 2.4f)
                restX += d.x * gain; restY += d.y * gain
                val sx = restX.toInt(); val sy = restY.toInt()
                if (sx != 0 || sy != 0) {
                    vm.input("move") { put("dx", sx); put("dy", sy) }
                    restX -= sx; restY -= sy
                }
            } else if (down.size >= 2) {
                val dy = down.map { it.positionChange().y }.average().toFloat()
                travel += abs(dy)
                scrollRest += dy * 4f           // natural scrolling: content follows the fingers
                if (abs(scrollRest) >= 40f) {
                    vm.input("scroll") { put("dy", scrollRest.toInt()) }
                    scrollRest = 0f
                }
            }
            ev.changes.forEach { it.consume() }
            if (ev.changes.none { it.pressed }) {
                val quick = ev.changes.first().uptimeMillis - start < 260
                if (quick && travel < slop) vm.input("click") { put("button", if (maxFingers >= 2) "right" else "left") }
                break
            }
        }
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) {
    val click by rememberUpdatedState(onClick)
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp))
            .background(if (active) RemoteAccent.copy(alpha = 0.35f) else Color(0x1AFFFFFF))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    click()
                    waitForUpOrCancellation()?.consume()
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Palette.text, fontSize = 12.sp, maxLines = 1) }
}

// ------------------------------------------------------------------ keyboard
private data class K(val label: String, val send: String = label, val w: Float = 1f, val kind: Kind = Kind.Char)
private enum class Kind { Char, Key, Mod, Layer, Shift }

private val SPECIAL = listOf(
    K("Esc", "esc", kind = Kind.Key), K("Tab", "tab", kind = Kind.Key),
    K("Ctrl", "ctrl", kind = Kind.Mod), K("Alt", "alt", kind = Kind.Mod), K("Win", "win", kind = Kind.Mod),
    K("←", "left", kind = Kind.Key), K("↑", "up", kind = Kind.Key), K("↓", "down", kind = Kind.Key),
    K("→", "right", kind = Kind.Key), K("Del", "delete", kind = Kind.Key),
)
private val LETTERS = listOf(
    "qwertyuiop".map { K(it.toString()) },
    "asdfghjkl".map { K(it.toString()) },
    listOf(K("⇧", "shift", 1.4f, Kind.Shift)) + "zxcvbnm".map { K(it.toString()) } + K("⌫", "backspace", 1.4f, Kind.Key),
    listOf(K("?123", "", 1.5f, Kind.Layer), K(","), K("space", " ", 4f), K("."), K("↵", "enter", 1.5f, Kind.Key)),
)
private val SYMBOLS = listOf(
    "1234567890".map { K(it.toString()) },
    "@#$%&*-+()".map { K(it.toString()) },
    "!?\"':;/\\_=".map { K(it.toString()) } + K("⌫", "backspace", 1.4f, Kind.Key),
    listOf(K("ABC", "", 1.5f, Kind.Layer), K("<"), K(">"), K("space", " ", 3f), K("["), K("]"), K("↵", "enter", 1.5f, Kind.Key)),
)

@Composable
private fun Keyboard(vm: PerfViewModel, modifier: Modifier) {
    var symbols by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var mods by remember { mutableStateOf(setOf<String>()) }  // one-shot Ctrl / Alt / Win
    val haptic = LocalHapticFeedback.current

    fun press(k: K) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        when (k.kind) {
            Kind.Layer -> symbols = !symbols
            Kind.Shift -> shift = !shift
            Kind.Mod -> mods = if (k.send in mods) mods - k.send else mods + k.send
            Kind.Key -> {
                val all = mods + if (shift) setOf("shift") else emptySet()
                vm.input("key") { put("key", k.send); put("mods", org.json.JSONArray(all.toList())) }
                mods = emptySet(); shift = false
            }
            Kind.Char -> {
                if (mods.isEmpty()) {
                    val t = if (shift) k.send.uppercase() else k.send
                    vm.input("text") { put("text", t) }
                } else {   // a shortcut: Ctrl+C, Alt+F4, Win+D, Ctrl+Shift+T…
                    val all = mods + if (shift) setOf("shift") else emptySet()
                    vm.input("key") { put("key", k.send); put("mods", org.json.JSONArray(all.toList())) }
                }
                mods = emptySet(); shift = false
            }
        }
    }

    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF)).padding(5.dp)
            // keep the page from swiping when a finger slides across the keys
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        ev.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        val rows = listOf(SPECIAL) + if (symbols) SYMBOLS else LETTERS
        rows.forEachIndexed { i, row ->
            Row(Modifier.weight(if (i == 0) 0.85f else 1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (i == 2 && !symbols) Spacer(Modifier.weight(0.5f))   // stagger the middle letter row
                row.forEach { k ->
                    val on = (k.kind == Kind.Mod && k.send in mods) || (k.kind == Kind.Shift && shift)
                    val label = if (k.kind == Kind.Char && shift && !symbols) k.label.uppercase() else k.label
                    Key(label, k, on, small = i == 0 || k.kind != Kind.Char) { press(k) }
                }
                if (i == 2 && !symbols) Spacer(Modifier.weight(0.5f))
            }
        }
    }
}

@Composable
private fun RowScope.Key(label: String, k: K, active: Boolean, small: Boolean, onPress: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    var held by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Backspace, Delete and the arrows repeat while held, like a real keyboard
    val repeats = k.send in setOf("backspace", "delete", "left", "right", "up", "down")
    Box(
        Modifier.weight(k.w).fillMaxHeight().clip(RoundedCornerShape(7.dp))
            .background(
                when {
                    active -> RemoteAccent.copy(alpha = 0.55f)
                    held -> Color(0x40FFFFFF)
                    k.kind == Kind.Char -> Color(0x1FFFFFFF)
                    else -> Color(0x12FFFFFF)
                }
            )
            .pointerInput(k) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    held = true
                    press()
                    var repeat: Job? = null
                    if (repeats) repeat = scope.launch { delay(420); while (true) { press(); delay(55) } }
                    waitForUpOrCancellation()
                    repeat?.cancel()
                    held = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Color.White else Palette.text, fontSize = if (small) 11.sp else 15.sp, maxLines = 1)
    }
}
