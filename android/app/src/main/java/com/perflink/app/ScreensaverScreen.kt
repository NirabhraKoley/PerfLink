package com.perflink.app

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * AMOLED screensaver: a pure black screen (on AMOLED phones black pixels are switched off) with a big,
 * soft-grey digital clock and the date. The clock moves to a new spot every minute and the screen is
 * dimmed, so nothing burns in. Tap anywhere (or Back) to return.
 */
@Composable
fun AmoledSaver(onExit: () -> Unit) {
    val ctx = LocalContext.current
    val use24 = remember { ctx.getSharedPreferences("perflink", 0).getBoolean("clock_24h", false) }
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay(1000L - (System.currentTimeMillis() % 1000L))
        }
    }
    // a new spot every minute (-1..1 across and down), eased so it drifts rather than jumps
    var spot by remember { mutableStateOf(0f to 0f) }
    LaunchedEffect(now.minute) { spot = (Random.nextFloat() * 2 - 1) to (Random.nextFloat() * 2 - 1) }

    // dim the screen while it shows, and put the brightness back afterwards
    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        val before = w?.attributes?.screenBrightness
        w?.attributes = w?.attributes?.apply { screenBrightness = 0.12f }
        onDispose { w?.attributes = w?.attributes?.apply { screenBrightness = before ?: -1f } }
    }
    BackHandler { onExit() }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onExit() },
    ) {
        val dx by animateDpAsState(maxWidth * 0.16f * spot.first, tween(4000), label = "x")
        val dy by animateDpAsState(maxHeight * 0.14f * spot.second, tween(4000), label = "y")
        val digits = with(LocalDensity.current) { (maxHeight * 0.34f).toSp() }
        val hour = if (use24) String.format(Locale.US, "%02d", now.hour) else "${(now.hour % 12).let { if (it == 0) 12 else it }}"
        val minute = String.format(Locale.US, "%02d", now.minute)
        val grey = Color(0xFFB4BCC6)
        Column(
            Modifier.align(Alignment.Center).offset(dx, dy),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hour, color = grey, fontSize = digits, fontWeight = FontWeight.ExtraLight, lineHeight = digits,
                    style = TextStyle(fontFeatureSettings = "tnum"))
                // the colon blinks once a second
                Text(":", color = grey.copy(alpha = if (now.second % 2 == 0) 0.85f else 0.25f), fontSize = digits,
                    fontWeight = FontWeight.ExtraLight, lineHeight = digits, modifier = Modifier.padding(bottom = 6.dp))
                Text(minute, color = grey, fontSize = digits, fontWeight = FontWeight.ExtraLight, lineHeight = digits,
                    style = TextStyle(fontFeatureSettings = "tnum"))
                if (!use24) {
                    Spacer(Modifier.width(10.dp))
                    Text(if (now.hour < 12) "AM" else "PM", color = Color(0xFF6B7380), fontSize = 20.sp,
                        fontWeight = FontWeight.Medium)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())),
                color = Color(0xFF7A838F), fontSize = 20.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(18.dp))
            Text("Tap to return", color = Color(0xFF3A414B), fontSize = 12.sp)
        }
    }
}
