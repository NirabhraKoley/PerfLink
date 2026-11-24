package com.perflink.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =============================================================================== Social page
// The social / chat apps open on the PC and their unread notifications, in detail. It mirrors the
// PC's notification center: when a message is read or a call seen on the PC, it goes away here too.

private val SocialAccent = Color(0xFFFF8FB1)

private fun brand(key: String): Color = when (key) {
    "whatsapp" -> Color(0xFF25D366); "telegram" -> Color(0xFF2AABEE); "discord" -> Color(0xFF5865F2)
    "instagram" -> Color(0xFFE1306C); "messenger" -> Color(0xFF0084FF); "facebook" -> Color(0xFF1877F2)
    "signal" -> Color(0xFF3A76F0); "slack" -> Color(0xFFE01E5A); "teams" -> Color(0xFF6264A7)
    "skype" -> Color(0xFF00AFF0); "viber" -> Color(0xFF7360F2); "x" -> Color(0xFFE7E9EA)
    "linkedin" -> Color(0xFF0A66C2); "wechat" -> Color(0xFF07C160); "zoom" -> Color(0xFF2D8CFF)
    else -> SocialAccent
}

@Composable
fun SocialPage(state: UiState) {
    val apps = state.stats?.social
    var selected by remember { mutableStateOf<String?>(null) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF140C16), Color(0xFF0E0B14), Color(0xFF09090D)))
        )
    ) {
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = HomeButtonGap)) {
                Text("SOCIAL", color = SocialAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.width(10.dp))
                Text("apps open on ${state.stats?.host ?: "the PC"} · read it on the PC and it clears here",
                    color = Palette.dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            when {
                apps == null -> Empty("Update PerfLink on the PC to see social notifications.")
                apps.isEmpty() -> Empty("No social apps are open on the PC.\nOpen WhatsApp, Telegram, Discord, Instagram, Messenger, " +
                    "Signal, Slack, Teams, Skype… on the PC and their notifications show up here.")
                else -> {
                    val current = apps.firstOrNull { it.key == selected } ?: apps.first()
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(Modifier.weight(0.36f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(apps, key = { it.key }) { a -> AppRow(a, a.key == current.key) { selected = a.key } }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(0.64f).fillMaxHeight()) { Notes(current) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.dim, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 19.sp)
    }
}

@Composable
private fun AppBadge(a: SocialApp, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(brand(a.key)), contentAlignment = Alignment.Center) {
        Text(if (a.key == "x") "𝕏" else a.name.take(1), color = if (a.key == "x") Color.Black else Color.White,
            fontSize = (size * 0.45f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AppRow(a: SocialApp, selected: Boolean, onClick: () -> Unit) {
    val c = brand(a.key)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (selected) c.copy(alpha = 0.16f) else Color(0x0FFFFFFF))
            .border(1.dp, if (selected) c.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppBadge(a, 30)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.name, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(summary(a), color = Palette.dim, fontSize = 11.sp, maxLines = 1)
        }
        val total = a.messages + a.calls
        if (total > 0) {
            Text("$total", color = Color(0xFF0B0B0B), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(c).padding(horizontal = 7.dp, vertical = 1.dp))
        }
    }
}

private fun summary(a: SocialApp): String = when {
    a.messages == 0 && a.calls == 0 -> "No new notifications"
    else -> listOfNotNull(
        a.messages.takeIf { it > 0 }?.let { "$it ${if (it == 1) "message" else "messages"}" },
        a.calls.takeIf { it > 0 }?.let { "$it ${if (it == 1) "call" else "calls"}" },
    ).joinToString(" · ")
}

@Composable
private fun Notes(a: SocialApp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AppBadge(a, 22)
        Spacer(Modifier.width(8.dp))
        Text(a.name, color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        Text(summary(a), color = Palette.dim, fontSize = 11.sp)
    }
    Spacer(Modifier.height(6.dp))
    if (a.notes.isEmpty()) {
        Empty("All caught up — nothing unread in ${a.name} on the PC.")
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(a.notes, key = { it.id }) { n ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0x12FFFFFF))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(if (n.kind == "call") "📞" else "💬", fontSize = 15.sp, modifier = Modifier.padding(top = 1.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(n.title, color = Palette.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        n.t?.let { Text(ago(System.currentTimeMillis() / 1000.0 - it), color = Palette.dim, fontSize = 10.sp) }
                    }
                    if (n.text.isNotBlank()) {
                        Text(n.text, color = Palette.dim, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                            lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}
