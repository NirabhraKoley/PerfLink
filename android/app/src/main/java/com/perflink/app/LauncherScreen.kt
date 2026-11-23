package com.perflink.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class Tile(val id: String, val label: String, val tint: Color, val icon: String)

/** 24×24 line icons (stroked, round caps), drawn for PerfLink. */
object LineIcons {
    const val KEYBOARD = "M4 6h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1z M7 10h.01 M10.3 10h.01 M13.7 10h.01 M17 10h.01 M7 14h.01 M17 14h.01 M10 14h4"
    const val FOLDER = "M3 7.5A2 2 0 0 1 5 5.5h4l2 2.2h8a2 2 0 0 1 2 2v7.8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"
    const val CODE = "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z M14 3v5h5 M10 12.5l-2 2 2 2 M14 12.5l2 2-2 2"
    const val GLOBE = "M12 3a9 9 0 1 0 0.01 0z M3 12h18 M12 3c2.4 2.5 3.7 5.5 3.7 9s-1.3 6.5-3.7 9 M12 3c-2.4 2.5-3.7 5.5-3.7 9s1.3 6.5 3.7 9"
    const val HEADPHONES = "M4 15v-3a8 8 0 0 1 16 0v3 M4 15h3v5H5a1 1 0 0 1-1-1z M20 15h-3v5h2a1 1 0 0 0 1-1z"
    const val CALCULATOR = "M6.5 3h11a1.5 1.5 0 0 1 1.5 1.5v15a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 5 19.5v-15A1.5 1.5 0 0 1 6.5 3z M8 6.5h8v3.5H8z M8.5 14h.01 M12 14h.01 M15.5 14h.01 M8.5 17.5h.01 M12 17.5h.01 M15.5 17.5h.01"
    const val GEAR = "M12 9a3 3 0 1 0 0.01 0z M12 5.2a6.8 6.8 0 1 0 0.01 0z M12 2.5v2.7 M12 18.8v2.7 M2.5 12h2.7 M18.8 12h2.7 M5.3 5.3l1.9 1.9 M16.8 16.8l1.9 1.9 M5.3 18.7l1.9-1.9 M16.8 7.2l1.9-1.9"
    const val SCISSORS = "M6 3a3 3 0 1 0 0.01 0z M6 15a3 3 0 1 0 0.01 0z M8.2 8.2L20 20 M8.2 15.8L20 4 M14.5 12.2l.01 0"
    const val CHART = "M4 4v16h16 M8 16v-3 M12 16V8 M16 16v-5"
    const val MUTE = "M11 5L6.5 9H3v6h3.5L11 19z M16 9.5l5 5 M21 9.5l-5 5"
    const val SPEAKER = "M11 5L6.5 9H3v6h3.5L11 19z M15.5 8.5a5 5 0 0 1 0 7 M18.5 5.5a9 9 0 0 1 0 13"
    const val MOON = "M20 14.5A8.5 8.5 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"
    const val LOCK = "M6 11h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1z M8 11V8a4 4 0 0 1 8 0v3"
}

private val TILES = listOf(
    Tile("terminal", "Terminal", Color(0xFF6B7280), LineIcons.KEYBOARD),
    Tile("files", "Files", Color(0xFFD99A2B), LineIcons.FOLDER),
    Tile("vscode", "VS Code", Color(0xFF3B82F6), LineIcons.CODE),
    Tile("browser", "Browser", Color(0xFF06B6D4), LineIcons.GLOBE),
    Tile("spotify", "Spotify", Color(0xFF22C55E), LineIcons.HEADPHONES),
    Tile("calculator", "Calculator", Color(0xFF64748B), LineIcons.CALCULATOR),
    Tile("settings", "Settings", Color(0xFF94A3B8), LineIcons.GEAR),
    Tile("snip", "Snip", Color(0xFF14B8A6), LineIcons.SCISSORS),
    Tile("taskmgr", "Task Manager", Color(0xFF16A34A), LineIcons.CHART),
    Tile("mute", "Mute", Color(0xFFF59E0B), LineIcons.MUTE),
    Tile("screen_off", "Screen Off", Color(0xFF6366F1), LineIcons.MOON),
    Tile("lock", "Lock", Color(0xFFEF4444), LineIcons.LOCK),
)

@Composable
fun lineIcon(path: String): ImageVector = remember(path) {
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(path), fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()
}

@Composable
fun LauncherPage(state: UiState, onAction: (String) -> Unit) {
    val s = state.stats
    val muted = s?.volume?.muted == true
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF0D201F), Color(0xFF080B0E)), radius = 1500f)
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 46.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TILES.chunked(4).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { t ->
                        val label = if (t.id == "mute" && muted) "Unmute" else t.label
                        val icon = if (t.id == "mute" && muted) LineIcons.SPEAKER else t.icon
                        val hint = s?.launcherHints?.get(t.id)
                        LauncherTile(t.copy(label = label, icon = icon), hint, active = t.id == "mute" && muted,
                            modifier = Modifier.weight(1f).fillMaxHeight()) { onAction(t.id) }
                    }
                }
            }
        }
        state.toast?.let { t ->
            Text(
                t.message, color = if (t.ok) Palette.text else Color(0xFFFFB4AB), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xE6151B22))
                    .border(1.dp, if (t.ok) Color(0x33FFFFFF) else Color(0x66FF6B6B), RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun LauncherTile(t: Tile, hint: String?, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.scale(scale).clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(t.tint.copy(alpha = if (active) 0.42f else 0.22f), t.tint.copy(alpha = 0.05f), Color(0x08FFFFFF))
                )
            )
            .border(1.dp, t.tint.copy(alpha = if (active) 0.9f else 0.45f), shape)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(rememberVectorPainter(lineIcon(t.icon)), contentDescription = t.label, modifier = Modifier.size(24.dp),
                colorFilter = ColorFilter.tint(Color(0xFFF1F5F9)))
            Spacer(Modifier.height(8.dp))
            Text(t.label, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (hint != null) Text(hint, color = Palette.dim, fontSize = 10.sp)
        }
    }
}
