package com.perflink.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class Series(val values: List<Float>, val color: Color, val fill: Boolean = true)

/** Line chart for a rolling window. NaN = gap. [fixedMax] pins the y-axis (e.g. 100 for %). */
@Composable
fun LineChart(
    series: List<Series>,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
    fixedMax: Float? = null,
    capacity: Int = HISTORY,
    showGrid: Boolean = true,
) {
    Box(modifier.fillMaxWidth().height(height)) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val w = size.width
            val h = size.height
            // grid
            if (showGrid) for (i in 1..3) {
                val y = h * i / 4f
                drawLine(Palette.grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            val dataMax = series.flatMap { s -> s.values.filter { !it.isNaN() } }.maxOrNull() ?: 0f
            val max = fixedMax ?: (dataMax * 1.15f).coerceAtLeast(1f)
            val step = w / (capacity - 1).coerceAtLeast(1)
            series.forEach { s ->
                val offset = capacity - s.values.size
                val line = Path()
                val area = Path()
                var penDown = false
                var segStartX = 0f
                var lastX = 0f
                fun closeArea() {
                    if (penDown && s.fill) {
                        area.lineTo(lastX, h); area.lineTo(segStartX, h); area.close()
                    }
                }
                s.values.forEachIndexed { i, v ->
                    val x = (offset + i) * step
                    if (v.isNaN()) {
                        closeArea(); penDown = false
                    } else {
                        val y = h - (v.coerceIn(0f, max) / max) * h
                        if (!penDown) {
                            line.moveTo(x, y); area.moveTo(x, y); segStartX = x; penDown = true
                        } else {
                            line.lineTo(x, y); area.lineTo(x, y)
                        }
                        lastX = x
                    }
                }
                closeArea()
                if (s.fill) {
                    drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), s.color.copy(alpha = 0.02f))))
                }
                drawPath(line, s.color, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/** Frame-time bars: each bar is one frame, taller = slower. Spikes show stutter. */
@Composable
fun FrameTimeBars(frameTimes: List<Double>, modifier: Modifier = Modifier, height: Dp = 56.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (frameTimes.isEmpty()) return@Canvas
        val sorted = frameTimes.sorted()
        val median = sorted[sorted.size / 2].toFloat().coerceAtLeast(0.1f)
        val max = (sorted.last().toFloat()).coerceAtLeast(median * 2f)
        val n = 120
        val bw = size.width / n
        val start = (n - frameTimes.size).coerceAtLeast(0)
        frameTimes.takeLast(n).forEachIndexed { i, ms ->
            val v = ms.toFloat()
            val bh = (v / max) * size.height
            val color = when {
                v > median * 2.0f -> Palette.bad
                v > median * 1.4f -> Palette.warn
                else -> Palette.accent
            }
            drawRoundRect(
                color, topLeft = Offset((start + i) * bw, size.height - bh),
                size = Size((bw - 1.5f).coerceAtLeast(1f), bh), cornerRadius = CornerRadius(1.5f),
            )
        }
    }
}

/** Horizontal usage bar 0..100 */
@Composable
fun UsageBar(pct: Double?, color: Color, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Palette.track, cornerRadius = r)
        val p = ((pct ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
        if (p > 0f) drawRoundRect(color, size = Size(size.width * p, size.height), cornerRadius = r)
    }
}
