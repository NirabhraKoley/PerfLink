package com.perflink.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val ClaudeOrange = Color(0xFFE07A5F)
private val GhBlue = Color(0xFF8AB4F8)
private val RowBg = Color(0x12FFFFFF)

@Composable
fun SessionsPage(state: UiState) {
    val ai = state.stats?.ai
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Color(0xFF0D2526), Color(0xFF080B0E)), radius = 1400f)
        )
    ) {
        Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 22.dp)) {
            // ---------------- left: sessions + AI apps
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                val sessions = ai?.sessions ?: emptyList()
                val working = sessions.count { it.busy }
                Header("SESSIONS", ClaudeOrange,
                    when {
                        sessions.isEmpty() -> "none open"
                        working == 0 -> "${sessions.size} idle"
                        else -> "$working working · ${sessions.size - working} idle"
                    })
                if (sessions.isEmpty()) Dim("No Claude Code sessions running")
                sessions.forEach { SessionRow(it) }

                Spacer(Modifier.height(16.dp))
                val apps = ai?.apps ?: emptyList()
                Header("AI APPS", Color(0xFF5EEAD4), "${apps.count { it.running }} running · ${apps.size} installed")
                if (apps.isEmpty()) Dim("No desktop AI apps found")
                apps.forEach { AppRow(it) }
            }
            Spacer(Modifier.width(28.dp))
            // ---------------- right: GitHub
            Column(Modifier.weight(1f).fillMaxHeight()) {
                GitHubPanel(ai?.github, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Header(title: String, color: Color, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        Text(title, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
        Spacer(Modifier.width(10.dp))
        Text(sub, color = Palette.dim, fontSize = 12.sp)
    }
}

@Composable
private fun Dim(t: String) = Text(t, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 4.dp))

@Composable
private fun PulseDot(active: Boolean, color: Color) {
    val a = if (active) {
        val t = rememberInfiniteTransition(label = "pulse")
        val v by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
        v
    } else 1f
    Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(if (active) color else Color(0xFF4B5563)))
}

@Composable
private fun SessionRow(s: AiSession) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(RowBg)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(s.busy, Palette.warn)
        Spacer(Modifier.width(10.dp))
        Text("✳", color = ClaudeOrange, fontSize = 14.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(s.status, s.source, s.folder).joinToString(" · "),
                color = if (s.busy) Palette.warn else Palette.dim, fontSize = 11.sp, maxLines = 1)
        }
        Text(ago(s.sinceS), color = Palette.dim, fontSize = 12.sp)
    }
}

@Composable
private fun AppRow(a: AiApp) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(RowBg)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (a.running) Palette.good else Color(0xFF4B5563)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, color = if (a.running) Palette.text else Palette.dim, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            val line = if (!a.running) "Not running" else listOfNotNull(
                "Running",
                a.ramMb?.let { gb(it) },
                a.cpu?.let { "${f0(it)}% CPU" },
                a.detail,
            ).joinToString(" · ")
            Text(line, color = Palette.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.models.isNotEmpty()) {
                Text(a.models.joinToString(", "), color = Color(0xFF5EEAD4), fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        if (a.running && a.uptimeS != null) Text("up ${ago(a.uptimeS)}", color = Palette.dim, fontSize = 12.sp)
    }
}

@Composable
private fun GitHubPanel(g: GitHub?, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp, end = HomeButtonGap - 16.dp)) {
            Text("GITHUB", color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
                modifier = Modifier.weight(1f))
            Text("🔔 ${g?.notifications ?: 0}", color = Palette.text, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(RowBg).padding(horizontal = 10.dp, vertical = 3.dp))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            PrSection("REVIEW REQUESTS", g?.reviewRequests ?: emptyList(), "No pending reviews")
            Spacer(Modifier.height(12.dp))
            PrSection("MY PULL REQUESTS", g?.myPrs ?: emptyList(), "No open pull requests")
        }
        if (g != null && !g.available && g.message != null) {
            Text(g.message, color = Color(0xFFFF8A80), fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun PrSection(title: String, prs: List<PullRequest>, empty: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(title, color = Palette.dim, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
            modifier = Modifier.weight(1f))
        Text("${prs.size}", color = Palette.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
    if (prs.isEmpty()) Text(empty, color = Palette.text, fontSize = 13.sp)
    prs.forEach { p ->
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(p.title, color = Palette.text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.repo} #${p.number}", color = GhBlue, fontSize = 11.sp, maxLines = 1)
        }
    }
}

/** 45 → "45s", 130 → "2m", 3900 → "1h 5m", 200000 → "2d" */
fun ago(sec: Double): String {
    val s = sec.toLong()
    return when {
        s < 5 -> "now"
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        s < 86400 -> String.format(Locale.US, "%dh %dm", s / 3600, s / 60 % 60)
        else -> "${s / 86400}d"
    }
}
