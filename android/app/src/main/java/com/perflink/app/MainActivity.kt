package com.perflink.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

/**
 * The app's colours. [light] switches the whole app between the dark and the light theme (the sun / moon
 * button in the top bar; remembered on the phone): it's Compose state, so every screen redraws.
 * The Clock and Mirror pages stay dark on purpose.
 */
object Palette {
    var light by mutableStateOf(false)
    private fun pick(dark: Long, lite: Long) = Color(if (light) lite else dark)
    val bg get() = pick(0xFF0B0E13, 0xFFF1F4F8)
    val card get() = pick(0xFF141A22, 0xFFFFFFFF)
    val cardBorder get() = pick(0xFF222B36, 0xFFD9E0E8)
    val grid get() = pick(0x1FFFFFFF, 0x1F0B1220)
    val track get() = pick(0xFF232C38, 0xFFE2E8F0)
    val text get() = pick(0xFFE8EDF3, 0xFF0F172A)
    val dim get() = pick(0xFF8A96A6, 0xFF5A6678)
    val accent get() = pick(0xFF4FC3F7, 0xFF0284C7)
    val good get() = pick(0xFF5BD68A, 0xFF16A34A)
    val warn get() = pick(0xFFF2C14E, 0xFFD97706)
    val bad get() = pick(0xFFFF6B6B, 0xFFDC2626)
    val gpu get() = pick(0xFFFF7A59, 0xFFEA580C)
    val cpu get() = pick(0xFF7C9CFF, 0xFF4F46E5)
    val down get() = pick(0xFF4FC3F7, 0xFF0284C7)
    val up get() = pick(0xFFB388FF, 0xFF7C3AED)
    val ping get() = pick(0xFF5BD68A, 0xFF16A34A)
    /** The top bar. */
    val bar get() = pick(0xFF080B10, 0xFFFFFFFF)
    /** A see-through tint for fills and borders: white on the dark theme, black on the light one. */
    fun veil(alpha: Float) = (if (light) Color.Black else Color.White).copy(alpha = alpha)
}

/** A page's background colours for the current theme (light: the same hues, very pale). */
fun themed(colors: List<Color>): List<Color> =
    if (Palette.light) colors.map { androidx.compose.ui.graphics.lerp(it, Color.White, 0.9f) } else colors

/** A page's soft background glow ([darkTint] in the middle) in either theme. */
fun pageGlow(darkTint: Color, radius: Float): Brush =
    if (Palette.light) Brush.radialGradient(listOf(androidx.compose.ui.graphics.lerp(darkTint, Color.White, 0.88f), Palette.bg), radius = radius)
    else Brush.radialGradient(listOf(darkTint, Color(0xFF080B0E)), radius = radius)

/** The AMOLED screensaver (black screen, clock and date): the moon button in the top bar. */
object Screensaver {
    var on by mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    private val vm: PerfViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Full-screen desk display: hide status/nav bars (swipe from an edge to show them)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // `adb shell am start -n com.perflink.app/.MainActivity -e host 127.0.0.1` auto-connects
        // (used with `adb reverse tcp:47800 tcp:47800` for USB mode)
        if (savedInstanceState == null) intent?.getStringExtra("host")?.let { vm.connect(it) }
        val startPage = intent?.getIntExtra("page", 0) ?: 0  // `-ei page 1` opens on that page
        simulatedAspect = intent?.getFloatExtra("sim_aspect", 0f)?.takeIf { it > 0f }
        startClockTab = intent?.getIntExtra("clock_tab", -1)?.takeIf { it in 0..2 }   // `--ei clock_tab 2`: Stopwatch
        startClockDigital = intent?.takeIf { it.hasExtra("clock_digital") }?.getBooleanExtra("clock_digital", false)
        startClockStyle = intent?.getIntExtra("clock_style", -1)?.takeIf { it in 0..4 }
        Palette.light = getSharedPreferences("perflink", 0).getBoolean("theme_light", false)
        // (testing) `--es theme light|dark` shows a theme without saving it; `--ez saver true` opens the screensaver
        intent?.getStringExtra("theme")?.let { Palette.light = it == "light" }
        if (intent?.getBooleanExtra("saver", false) == true) Screensaver.on = true
        // (testing) a local stand-in for GitHub's release API; only ever this phone itself
        intent?.getStringExtra("update_api")?.takeIf { it.startsWith("http://127.0.0.1:") }?.let { AppUpdater.testApi = it }
        // Updates itself from GitHub over the internet (no PC needed): shortly after opening, then every 6 hours
        if (savedInstanceState == null && intent?.getBooleanExtra("no_update", false) != true) {
            lifecycleScope.launch {
                kotlinx.coroutines.delay(20_000)
                while (true) {
                    AppUpdater.checkAndUpdate(this@MainActivity)
                    kotlinx.coroutines.delay(6 * 3600_000L)
                }
            }
        }
        setContent {
            MaterialTheme(colorScheme = if (Palette.light)
                androidx.compose.material3.lightColorScheme(primary = Palette.accent, background = Palette.bg, surface = Palette.card)
            else darkColorScheme(primary = Palette.accent, background = Palette.bg, surface = Palette.card)) {
              ScaledToScreen {
                val state by vm.state.collectAsStateWithLifecycle()
                // Keep the screen awake while watching stats
                val connected = state.conn != Conn.Idle
                DisposableEffect(connected) {
                    if (connected) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { }
                }
                Box(Modifier.fillMaxSize().background(Palette.bg)) {
                    if (state.conn == Conn.Idle) Box(Modifier.safeDrawingPadding()) {
                        ConnectScreen(state, vm::discover, vm::connect)
                    }
                    else Dashboard(state, vm, startPage)
                    // a newer PerfLink is on GitHub: ask before updating, with a line about what's new
                    val update by AppUpdater.available.collectAsStateWithLifecycle()
                    update?.let { rel -> UpdateDialog(rel) }
                    // Watermark, bottom-right on every screen (plain text, so taps pass through)
                    Text(
                        "- created by Nirabhra Koley", color = Palette.dim.copy(alpha = 0.55f), fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 4.dp),
                    )
                    // AMOLED screensaver over everything, the watermark too (tap to go back)
                    if (Screensaver.on) AmoledSaver { Screensaver.on = false }
                }
              }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("host")?.let { vm.connect(it) }
    }
}

// =============================================================================== Connect
@Composable
private fun UpdateDialog(rel: AppUpdater.Release) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val have = remember { runCatching { AppUpdater.installedVersion(ctx) }.getOrDefault("") }
    AlertDialog(
        onDismissRequest = { AppUpdater.later(rel) },
        containerColor = Palette.card,
        title = { Text("Update available", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("PerfLink ${rel.version}" + if (have.isNotBlank()) "  (you have $have)" else "",
                    color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (rel.summary.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(rel.summary, color = Palette.text, fontSize = 14.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { scope.launch { AppUpdater.install(ctx, rel) } }) {
                Text("Update now", color = Palette.good, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = { AppUpdater.later(rel) }) { Text("Later", color = Palette.dim) } },
    )
}

@Composable
fun ConnectScreen(state: UiState, onScan: () -> Unit, onConnect: (String) -> Unit) {
    var ip by rememberSaveable(state.lastIp) { mutableStateOf(state.lastIp) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("PerfLink", color = Palette.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Live FPS, GPU, CPU and network stats from your PC.", color = Palette.dim, fontSize = 15.sp)
        // the PC refused this phone (blocked, or nobody clicked Allow)
        state.refused?.let { why ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.bad.copy(alpha = 0.16f))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("⛔", fontSize = 20.sp)
                Spacer(Modifier.width(12.dp))
                Text(why, color = Palette.text, fontSize = 13.sp)
            }
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PCs on this network", color = Palette.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (state.scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Scan", color = Palette.accent, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onScan() }.padding(8.dp, 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            if (state.found.isEmpty() && !state.scanning) {
                Text("No PC found. Make sure perflink_agent.py is running and the phone is on the same Wi-Fi.",
                    color = Palette.dim, fontSize = 13.sp)
            }
            state.found.forEach { pc ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                        .background(Palette.track).clickable { onConnect("${pc.ip}:${pc.port}") }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.good))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        // who uses it ("Nirabhra Koley"), then laptop or desktop, the computer's name and its IP
                        Text(pc.user ?: pc.name, color = Palette.text, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(pc.kind?.let { pcKindLabel(it) }, pc.name.takeIf { pc.user != null }, pc.ip)
                            .joinToString(" · "), color = Palette.dim, fontSize = 12.sp)
                    }
                    Text("Connect", color = Palette.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Card {
            Text("Connect manually", color = Palette.text, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ip, onValueChange = { ip = it }, singleLine = true,
                label = { Text("PC IP address") }, placeholder = { Text("192.168.1.x") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onConnect(ip) }),
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Palette.text, unfocusedTextColor = Palette.text),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onConnect(ip) }, enabled = ip.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Connect")
            }
        }
        Text("Port $WS_PORT · discovery UDP $DISCOVERY_PORT", color = Palette.dim, fontSize = 12.sp)
    }
}

/** "💻 Laptop" or "🖥️ PC", from the kind the PC reports. */
fun pcKindLabel(kind: String): String = if (kind.equals("Laptop", ignoreCase = true)) "💻 Laptop" else "🖥️ PC"

// =============================================================================== screen size
/** The pages are laid out for a 640×360 dp landscape screen (a typical 5–6" phone). On any other phone
 *  or tablet everything (sizes, text, spacing, icons) is scaled together to fit the screen's height,
 *  never more than its width allows, so nothing is cut off on small screens or tiny on big ones; extra
 *  width (long 20:9 phones) goes to the parts that stretch (graphs, lists, the touchpad…). A very large
 *  font-size setting is capped so it can't break the layouts. */
@Composable
fun ScaledToScreen(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val base = LocalDensity.current
        // testing: `--ef sim_aspect 2.22` draws the app as a screen of that shape (e.g. a 20:9 phone or a
        // 4:3 tablet), shrunk to fit this one, to check every layout without that device
        val sim = simulatedAspect
        var w = maxWidth; var h = maxHeight
        if (sim != null && sim > 0f) {   // the largest screen of that shape that fits on this one
            if (maxWidth / maxHeight < sim) h = maxWidth / sim else w = maxHeight * sim
        }
        val scale = minOf(h.value / DESIGN_HEIGHT_DP, w.value / DESIGN_MIN_WIDTH_DP).coerceIn(0.75f, 2f)
        val density = Density(base.density * scale, base.fontScale.coerceIn(0.85f, 1.15f))
        Box(
            if (sim == null) Modifier.fillMaxSize()
            else Modifier.size(w, h).border(1.dp, Palette.veil(0x66 / 255f))
        ) {
            CompositionLocalProvider(LocalDensity provides density) { content() }
        }
    }
}

/** Set from the `sim_aspect` launch extra (testing other screen shapes); null = this phone's own screen. */
var simulatedAspect: Float? = null

/** Set from the `clock_tab` launch extra: the Clock page opens on that tab (0 clock, 1 timers, 2 stopwatch). */
var startClockTab: Int? = null

/** Set from the `clock_digital` launch extra (testing): show the digital or analog clock without saving the choice. */
var startClockDigital: Boolean? = null

/** Set from the `clock_style` launch extra (testing): show clock design N (0-4) without saving the choice. */
var startClockStyle: Int? = null

private const val DESIGN_HEIGHT_DP = 360f
private const val DESIGN_MIN_WIDTH_DP = 600f

// =============================================================================== Dashboard
// Page order, in one place (the page number is the position here; `-ei page N` opens page N):
// Home, Mirror, then the phone-only Clock, then using the PC (control it, quick actions), then gaming stats,
// then what's running and streaming, then devices. Pages loop, so Weather (last) is one swipe left of Home
// and Mirror one right.
enum class Page(val title: String, val color: Long) {
    Home("HOME", 0xFF4FC3F7), Mirror("MIRROR", 0xFF4FC3F7), Clock("CLOCK", 0xFF2EE6C5),
    Remote("REMOTE", 0xFF7C9CFF), Launcher("LAUNCHER", 0xFFF2C14E),
    Performance("PERFORMANCE", 0xFFFF7A59), Overlay("OVERLAY", 0xFFF2C14E), Processes("PROCESSES", 0xFFFF6FA8),
    Obs("OBS STUDIO", 0xFFB57BFF), Sessions("SESSIONS", 0xFFFF9F5A), Bluetooth("BLUETOOTH & NETWORK", 0xFF5AA9FF),
    Usb("USB", 0xFF4DD0E1), Weather("WEATHER", 0xFF6CB8FF),
}

private val PAGE_ORDER = Page.entries
private val PAGES = PAGE_ORDER.size

// Endless swiping: the pager has a huge number of slots and each slot shows page (slot % PAGES).
// It starts in the middle, so you can swipe either way practically forever:
// past Overlay you're back on Home, and left of Home is Overlay.
private val LOOP_SLOTS = 10_000 * PAGES
private val LOOP_START = LOOP_SLOTS / 2  // a multiple of PAGES, so slot LOOP_START is Home

/** The pager slot for page [to] nearest to [slot] (forward or back), so a jump never scrolls more than half the loop. */
private fun nearestSlot(slot: Int, to: Page): Int {
    val here = slot % PAGES
    var diff = to.ordinal - here
    if (diff > PAGES / 2) diff -= PAGES
    if (diff < -PAGES / 2) diff += PAGES
    return slot + diff
}

@Composable
fun Dashboard(state: UiState, vm: PerfViewModel, startPage: Int = 0) {
    val pager = rememberPagerState(initialPage = LOOP_START + startPage.coerceIn(0, PAGES - 1)) { LOOP_SLOTS }
    val current = pager.currentPage % PAGES
    val pageScope = rememberCoroutineScope()
    // the Clock page's timer and stopwatch (kept here, so they keep going while you swipe)
    val clockVm: ClockViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    LaunchedEffect(Unit) { startClockTab?.let { clockVm.tab = it; startClockTab = null } }
    // keep clear of camera holes / notches (punch-hole phones in landscape)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
      Column(Modifier.fillMaxSize()) {
        // ---- top bar on every page: Disconnect · page name · Home
        var askDisconnect by remember { mutableStateOf(false) }
        TopBar(
            page = PAGE_ORDER[current],
            showHome = current != 0,
            onDisconnect = { askDisconnect = true },
            onHome = { pageScope.launch { pager.animateScrollToPage(nearestSlot(pager.currentPage, Page.Home)) } },
        )
        if (askDisconnect) {
            val st = state.stats
            val who = st?.user?.let { "$it's ${if (st.kind.equals("Laptop", true)) "laptop" else "PC"}" } ?: st?.host ?: "the PC"
            AlertDialog(
                onDismissRequest = { askDisconnect = false },
                containerColor = Palette.card,
                title = { Text("Disconnect from $who?", color = Palette.text, fontSize = 17.sp) },
                text = { Text("You can connect again from the list of PCs.", color = Palette.dim, fontSize = 13.sp) },
                confirmButton = { TextButton(onClick = { askDisconnect = false; vm.disconnect() }) { Text("Disconnect", color = Palette.bad) } },
                dismissButton = { TextButton(onClick = { askDisconnect = false }) { Text("Cancel") } },
            )
        }
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), beyondViewportPageCount = 0) { slot ->
            val page = PAGE_ORDER[slot % PAGES]
            when {
                // the Clock page is phone-only, so it works before the PC has answered
                state.stats == null && page != Page.Clock -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    if (state.auth == "pending") {   // the PC's user is being asked: Allow / Block
                        Text("Waiting for the PC to allow this phone", color = Palette.text, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("A window on the PC asks whether to allow this phone. Click Allow there.",
                            color = Palette.dim, fontSize = 12.sp)
                    } else {
                    Text(if (state.conn == Conn.Reconnecting) "Reconnecting to the PC…" else "Connecting to the PC…",
                        color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Make sure the PerfLink program is running on the PC. Retrying automatically.",
                        color = Palette.dim, fontSize = 12.sp)
                    }
                    state.error?.let { Text(it, color = Palette.dim.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1) }
                    Spacer(Modifier.height(14.dp))
                    Chip("Choose another PC", Palette.accent) { vm.disconnect() }
                }
                else -> when (page) {
                    Page.Home -> HomePage(state, vm::media, vm::setVolume, vm::toggleMute, vm::setCity, vm::disconnect,
                        onWeatherAuto = vm::weatherAuto,
                        onOpenWeather = { pageScope.launch { pager.animateScrollToPage(nearestSlot(pager.currentPage, Page.Weather)) } })
                    Page.Mirror -> MirrorPage(state, vm)
                    Page.Clock -> ClockPage(clockVm)
                    Page.Remote -> RemotePage(state, vm)
                    Page.Launcher -> LauncherPage(state, vm::action)
                    Page.Performance -> StatsPage(state, compact = false, onDisconnect = vm::disconnect)
                    Page.Overlay -> StatsPage(state, compact = true, onDisconnect = vm::disconnect)
                    Page.Processes -> ProcessesPage(state)
                    Page.Obs -> ObsPage(state, vm)
                    Page.Sessions -> SessionsPage(state)
                    Page.Bluetooth -> BluetoothPage(state)
                    Page.Usb -> UsbPage(state)
                    Page.Weather -> WeatherPage(state)
                }
            }
        }
      }
        // Only ask the PC for OBS preview frames while the OBS page is showing
        LaunchedEffect(current) { vm.obsView(PAGE_ORDER[current] == Page.Obs) }
        // Home button, top-right, on every page except Home itself.
        // Goes to the nearest Home slot (forward or back), so it never scrolls more than half the loop.
        // (the Home slide runs in the Dashboard's scope: the button disappears halfway to Home, and a scope of
        // its own would cancel the slide midway)
        // USB plug/unplug pop-up (shown on every page)
        state.usbAlert?.let { UsbAlertBanner(it, Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) }
        // the timer's "time's up" alarm can be stopped from any page (on the Timers tab it has its own button)
        if (clockVm.ringing && !(PAGE_ORDER[current] == Page.Clock && clockVm.tab == 1))
            TimerAlarmBanner(clockVm::stopAlarm, Modifier.align(Alignment.TopCenter).padding(top = TopBarHeight + 8.dp))
        // Page dots
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(PAGES) { i ->
                val active = current == i
                if (i == 0) {   // Home: a small house instead of a dot
                    Image(rememberVectorPainter(lineIcon(HOME_ICON)), contentDescription = "Home page", modifier = Modifier.size(10.dp),
                        colorFilter = ColorFilter.tint(if (active) Palette.text else Palette.dim.copy(alpha = 0.7f)))
                } else Box(
                    Modifier.height(5.dp).width(if (active) 16.dp else 5.dp).clip(RoundedCornerShape(50))
                        .background(if (active) Palette.text.copy(alpha = 0.8f) else Palette.dim.copy(alpha = 0.45f))
                )
            }
        }
    }
}

private const val HOME_ICON = "M3.5 11L12 4l8.5 7 M6 9.5V20h4.5v-5.5h3V20H18V9.5"

/** Small round house button that jumps back to the Home page. */
@Composable
fun HomeButton(modifier: Modifier = Modifier.size(34.dp), onClick: () -> Unit) {
    BarIconButton(HOME_ICON, "Home", modifier, onClick)
}

/** A round icon button in the top bar's style (Home, screensaver, theme). */
@Composable
fun BarIconButton(icon: String, label: String, modifier: Modifier = Modifier.size(30.dp), onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).background(Palette.card.copy(alpha = 0.8f))
            .border(1.dp, Palette.veil(0.2f), CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Image(rememberVectorPainter(lineIcon(icon)), contentDescription = label, modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(Palette.text))
    }
}

/** The bar at the top of every page: Disconnect (left), the page's name, Home (right). */
@Composable
fun TopBar(page: Page, showHome: Boolean, onDisconnect: () -> Unit, onHome: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(TopBarHeight).background(Palette.bar)
            .drawBehind { drawLine(Palette.veil(0.08f), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 1f) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DisconnectButton(Modifier.size(30.dp), onClick = onDisconnect)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(6.dp).clip(CircleShape).background(Color(page.color)))
        Spacer(Modifier.width(8.dp))
        Text(page.title, color = Color(page.color), fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp,
            maxLines = 1, modifier = Modifier.weight(1f))
        // AMOLED screensaver, then light / dark theme, then Home
        BarIconButton(SAVER_ICON, "AMOLED screensaver") { Screensaver.on = true }
        Spacer(Modifier.width(8.dp))
        val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("perflink", 0)
        BarIconButton(if (Palette.light) MOON_ICON else SUN_ICON, if (Palette.light) "Dark theme" else "Light theme") {
            Palette.light = !Palette.light
            prefs.edit().putBoolean("theme_light", Palette.light).apply()
        }
        Spacer(Modifier.width(8.dp))
        if (showHome) HomeButton(Modifier.size(30.dp), onClick = onHome) else Spacer(Modifier.size(30.dp))
    }
}

val TopBarHeight = 36.dp

/** Pages used to leave room for floating buttons; the buttons are in the top bar now. */
val HomeButtonGap = 16.dp

/** Space each page's top-left title leaves for the Disconnect button (on top of the page's own padding). */
val DisconnectGap = 0.dp

// a screen with "z z" (screensaver), a sun (light theme), a moon (dark theme)
private const val SAVER_ICON = "M3.5 5.5h17v11h-17z M9 20.5h6 M12 16.5v4 M8.5 9h2.2l-2.2 3h2.2 M13.3 8h2.2l-2.2 3h2.2"
private const val SUN_ICON = "M12 2.8v2 M12 19.2v2 M2.8 12h2 M19.2 12h2 M5.5 5.5l1.4 1.4 M17.1 17.1l1.4 1.4 M5.5 18.5l1.4-1.4 M17.1 6.9l1.4-1.4 M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z"
private const val MOON_ICON = "M19.5 14.6A7.8 7.8 0 1 1 9.4 4.5a6.2 6.2 0 0 0 10.1 10.1z"
private const val POWER_ICON = "M12 3.5v8 M7 6.6a7.2 7.2 0 1 0 10 0"

/** Small round power button that disconnects from the PC (after asking). */
@Composable
fun DisconnectButton(modifier: Modifier = Modifier.size(34.dp), onClick: () -> Unit) {
    Box(
        modifier.clip(CircleShape).background(Color(0xCC1A222C))
            .border(1.dp, Palette.bad.copy(alpha = 0.45f), CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Image(rememberVectorPainter(lineIcon(POWER_ICON)), contentDescription = "Disconnect", modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(Palette.bad))
    }
}

@Composable
fun StatsPage(state: UiState, compact: Boolean, onDisconnect: () -> Unit) {
    val s = state.stats
    Column(Modifier.fillMaxSize().padding(bottom = 10.dp)) {
        // Top bar
        // one line: "● DESKTOP-JCSV385 · Live" (the page's name is in the top bar)
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 6.dp, bottom = 6.dp, end = HomeButtonGap),
            verticalAlignment = Alignment.CenterVertically) {
            val (dot, label) = when (state.conn) {
                Conn.Connected -> Palette.good to "Live"
                Conn.Connecting -> Palette.warn to "Connecting…"
                else -> Palette.bad to "Reconnecting…"
            }
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(s?.user ?: s?.host ?: state.target ?: "", color = Palette.text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Text("  ·  " + if (state.conn != Conn.Connected && state.error != null) "$label ${state.error}" else label,
                color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        if (compact) CompactView(s) else FullView(s, state.history)
    }
}

@Composable
fun Chip(text: String, color: Color = Palette.accent, onClick: () -> Unit) {
    Text(
        text, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .clickable { onClick() }.padding(12.dp, 6.dp),
    )
}

// ---------------------------------------------------------------- compact (like the PC overlay)
@Composable
fun CompactView(s: Stats) {
    // AMD's in-game overlay order (RAM split added after system memory), then extras (CPU temp, network);
    // the two AMD-driver-only rows, always N/A here, come last.
    // FRAME GEN LAG and UPSCALE UPGRADE only come from AMD's driver (Fluid Motion Frames / FSR upgrade);
    // no public interface exposes them, so they read N/A, like AMD's overlay when those features are off.
    val rows = listOf(
        Triple("FPS", f0(s.frames.fps), fpsColor(s.frames.fps)),
        Triple("FRAME TIME", s.frames.frameTimeMs?.let { "${f1(it)} ms" } ?: "N/A", Palette.text),
        Triple("99th% FPS", f0(s.frames.low1Fps), fpsColor(s.frames.low1Fps)),
        Triple("GPU UTIL", pct(s.gpu.util), Palette.text),
        Triple("GPU PWR", s.gpu.powerW?.let { "${f0(it)} W" } ?: "N/A", Palette.text),
        Triple("GPU TEMP", temp(s.gpu.tempC), tempColor(s.gpu.tempC)),
        Triple("GPU MEM UTIL", s.gpu.vramUsedMb?.let { "${f0(it)} MB" } ?: "N/A", Palette.text),
        Triple("CPU UTIL", pct(s.cpu.util), Palette.text),
        Triple("SYSTEM MEM UTIL", s.ram.usedMb?.let { String.format(Locale.US, "%.1f GB", it / 1024) } ?: "N/A", Palette.text),
        Triple("RAM", s.ram.split() ?: "N/A", Palette.text),   // 8 GB + 8 GB, or 16 GB
        Triple("GPU MEM CLK", s.gpu.memClockMhz?.let { "${f0(it)} MHz" } ?: "N/A", Palette.text),
        Triple("CPU TEMP", temp(s.cpu.tempC), tempColor(s.cpu.tempC)),
        Triple("NET ↓", bps(s.net.downloadBps), Palette.down),
        Triple("NET ↑", bps(s.net.uploadBps), Palette.up),
        Triple("PING", s.net.pingMs?.let { "${f0(it)} ms" } ?: "N/A", pingColor(s.net.pingMs)),
        Triple("FRAME GEN LAG", "N/A", Palette.dim),      // only AMD's driver has these two
        Triple("UPSCALE UPGRADE", "N/A", Palette.dim),
    )
    LazyVerticalGrid(
        columns = GridCells.Adaptive(300.dp),
        contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(rows.size) { i ->
            val (k, v, c) = rows[i]
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).background(Palette.card).padding(16.dp, 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(k, color = Palette.dim, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(v, color = c, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
        if (s.frames.app != null) item {
            Text("Tracking: ${s.frames.app}", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(4.dp, 8.dp))
        }
    }
}

// ---------------------------------------------------------------- full dashboard
@Composable
fun FullView(s: Stats, h: History) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(290.dp),
        contentPadding = PaddingValues(12.dp, 0.dp, 12.dp, 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { PerformanceCard(s, h) }
        item { GpuCard(s, h) }
        item { CpuCard(s, h) }
        item { NetworkCard(s, h) }
        if (s.errors.isNotEmpty()) item {
            Card { s.errors.forEach { Text(it, color = Palette.warn, fontSize = 12.sp) } }
        }
    }
}

@Composable
fun PerformanceCard(s: Stats, h: History) {
    val f = s.frames
    Card {
        SectionTitle("Performance", f.app ?: "No game detected")
        Row(verticalAlignment = Alignment.Bottom) {
            Text(f0(f.fps), color = fpsColor(f.fps), fontSize = 56.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Text(" FPS", color = Palette.dim, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            Tile("Frame time", f.frameTimeMs?.let { "${f1(it)} ms" } ?: "N/A", Modifier.weight(1f))
            Tile("1% low", f0(f.low1Fps), Modifier.weight(1f), fpsColor(f.low1Fps))
            Tile("0.1% low", f0(f.low01Fps), Modifier.weight(1f), fpsColor(f.low01Fps))
        }
        Spacer(Modifier.height(10.dp))
        Caption("FPS (last ${HISTORY / 2}s)")
        LineChart(listOf(Series(h.fps, Palette.accent)))
        Spacer(Modifier.height(8.dp))
        Caption("Frame times (last second) — spikes = stutter")
        FrameTimeBars(f.frameTimes)
        Spacer(Modifier.height(8.dp))
        // AMD driver-only metrics (Fluid Motion Frames / FSR upgrade): not exposed to other apps
        KV("Frame gen lag", null)
        KV("Upscale upgrade", null)
    }
}

@Composable
fun GpuCard(s: Stats, h: History) {
    val g = s.gpu
    Card {
        SectionTitle("GPU", g.name ?: "")
        Row(Modifier.fillMaxWidth()) {
            Tile("Util", pct(g.util), Modifier.weight(1f), Palette.gpu)
            Tile("Power", g.powerW?.let { "${f0(it)} W" } ?: "N/A", Modifier.weight(1f))
            Tile("Temp", temp(g.tempC), Modifier.weight(1f), tempColor(g.tempC))
            Tile("Hotspot", temp(g.hotspotC), Modifier.weight(1f), tempColor(g.hotspotC?.minus(10)))
        }
        Spacer(Modifier.height(8.dp))
        LineChart(listOf(Series(h.gpuUtil, Palette.gpu)), fixedMax = 100f)
        Spacer(Modifier.height(8.dp))
        KV("Core clock", g.coreClockMhz?.let { "${f0(it)} MHz" })
        KV("GPU mem clock", g.memClockMhz?.let { "${f0(it)} MHz" })
        KV("GPU mem util", g.vramUsedMb?.let { "${f0(it)} MB" })
        KV("Fan", g.fanRpm?.let { if (it == 0.0) "0 RPM (zero-RPM mode)" else "${f0(it)} RPM" })
        KV("Voltage", g.voltageV?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.3f V", it) })
        val vramPct = if (g.vramUsedMb != null && g.vramTotalMb != null && g.vramTotalMb > 0) g.vramUsedMb / g.vramTotalMb * 100 else null
        KV("VRAM", if (g.vramUsedMb != null && g.vramTotalMb != null) "${gb(g.vramUsedMb)} / ${gb(g.vramTotalMb)}" else null)
        UsageBar(vramPct, Palette.gpu)
    }
}

@Composable
fun CpuCard(s: Stats, h: History) {
    val c = s.cpu
    Card {
        SectionTitle("CPU & Memory", c.name ?: "")
        Row(Modifier.fillMaxWidth()) {
            Tile("Util", pct(c.util), Modifier.weight(1f), Palette.cpu)
            Tile("Clock", c.clockMhz?.let { "${f0(it)} MHz" } ?: "N/A", Modifier.weight(1f))
            Tile("Temp", temp(c.tempC), Modifier.weight(1f), tempColor(c.tempC))
            Tile("Power", c.powerW?.let { "${f0(it)} W" } ?: "N/A", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        LineChart(listOf(Series(h.cpuUtil, Palette.cpu)), fixedMax = 100f)
        if (c.perCore.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Caption("Per thread")
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                c.perCore.forEach { v ->
                    Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(2.dp)).background(Palette.track), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().height((40 * (v / 100.0).coerceIn(0.0, 1.0)).dp).background(Palette.cpu))
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        KV("System mem util", s.ram.usedMb?.let { String.format(Locale.US, "%.1f GB", it / 1024) })
        KV("RAM", if (s.ram.usedMb != null && s.ram.totalMb != null) "${gb(s.ram.usedMb)} / ${gb(s.ram.totalMb)} (${pct(s.ram.util)})" else null)
        UsageBar(s.ram.util, Palette.cpu)
        RamModules(s.ram)
        if (c.tempC == null) {
            Spacer(Modifier.height(6.dp))
            Text("Run the agent as Administrator for CPU temp/power.", color = Palette.dim, fontSize = 11.sp)
        }
    }
}

@Composable
fun NetworkCard(s: Stats, h: History) {
    val n = s.net
    Card {
        SectionTitle("Network", listOfNotNull(n.iface, n.linkMbps?.let { "${f0(it)} Mbps link" }).joinToString(" · "))
        Row(Modifier.fillMaxWidth()) {
            Tile("Download", bps(n.downloadBps), Modifier.weight(1f), Palette.down)
            Tile("Upload", bps(n.uploadBps), Modifier.weight(1f), Palette.up)
        }
        Spacer(Modifier.height(8.dp))
        LineChart(listOf(Series(h.down, Palette.down), Series(h.up, Palette.up)))
        Row { Legend("Download", Palette.down); Spacer(Modifier.width(12.dp)); Legend("Upload", Palette.up) }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Tile("Ping", n.pingMs?.let { "${f0(it)} ms" } ?: "N/A", Modifier.weight(1f), pingColor(n.pingMs))
            Tile("Jitter", n.jitterMs?.let { "${f1(it)} ms" } ?: "N/A", Modifier.weight(1f))
            Tile("Loss", n.packetLossPct?.let { "${f0(it)}%" } ?: "N/A", Modifier.weight(1f),
                if ((n.packetLossPct ?: 0.0) > 0) Palette.bad else Palette.text)
        }
        Spacer(Modifier.height(8.dp))
        Caption("Ping to ${n.pingHost ?: "?"}")
        LineChart(listOf(Series(h.ping, Palette.ping)), height = 56.dp)
        Spacer(Modifier.height(8.dp))
        KV("Avg ping", n.pingAvgMs?.let { "${f1(it)} ms" })
        KV("Received (since boot)", n.totalRecvMb?.let { mb(it) })
        KV("Sent (since boot)", n.totalSentMb?.let { mb(it) })
        KV("PC IP", n.localIp)
        KV("Open connections", n.connections?.let { f0(it) })
    }
}

// ---------------------------------------------------------------- building blocks
@Composable
fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.card)
            .border(1.dp, Palette.cardBorder, RoundedCornerShape(16.dp)).padding(16.dp),
    ) { content() }
}

@Composable
fun SectionTitle(title: String, sub: String) {
    Text(title.uppercase(), color = Palette.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    if (sub.isNotBlank()) Text(sub, color = Palette.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Spacer(Modifier.height(8.dp))
}

@Composable
fun Tile(label: String, value: String, modifier: Modifier = Modifier, color: Color = Palette.text) {
    Column(modifier.padding(end = 6.dp)) {
        Text(label, color = Palette.dim, fontSize = 11.sp, maxLines = 1)
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Clip)
    }
}

@Composable
fun KV(k: String, v: String?) {
    // the label never gets squeezed; a long value wraps on the right instead
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Palette.dim, fontSize = 13.sp, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(12.dp))
        Text(v ?: "N/A", color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

/** The RAM sticks as a small table: one row each with its size, DDR type and the speed it runs at. */
@Composable
fun RamModules(ram: Ram) {
    if (ram.modules.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Modules", color = Palette.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(listOfNotNull(ram.split(), ram.channels()).joinToString(" · "), color = Palette.text, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(4.dp))
    ram.modules.forEachIndexed { i, m ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(6.dp)).background(Palette.veil(0x0D / 255f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("RAM ${i + 1}", color = Palette.dim, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
            Text("${m.gb} GB", color = Palette.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(0.9f), textAlign = TextAlign.End, maxLines = 1)
            Text(m.type ?: "-", color = Palette.cpu, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(0.9f), textAlign = TextAlign.End, maxLines = 1)
            Text(m.mts?.let { "$it MT/s" } ?: "-", color = Palette.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1.5f), textAlign = TextAlign.End, maxLines = 1)
        }
    }
}

@Composable
fun Caption(t: String) = Text(t, color = Palette.dim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))

@Composable
fun Legend(t: String, c: Color) = Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(4.dp))
    Text(t, color = Palette.dim, fontSize = 11.sp)
}

// ---------------------------------------------------------------- formatting
fun f0(v: Double?) = v?.let { String.format(Locale.US, "%.0f", it) } ?: "N/A"
fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
fun pct(v: Double?) = v?.let { "${f0(it)} %" } ?: "N/A"
fun temp(v: Double?) = v?.let { "${f0(it)} °C" } ?: "N/A"
fun gb(mb: Double) = if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024) else "${f0(mb)} MB"
fun mb(v: Double) = if (v >= 1000) String.format(Locale.US, "%.2f GB", v / 1000) else "${f1(v)} MB"
fun bps(v: Double?): String = when {
    v == null -> "N/A"
    v >= 1e9 -> String.format(Locale.US, "%.2f Gbps", v / 1e9)
    v >= 1e6 -> String.format(Locale.US, "%.1f Mbps", v / 1e6)
    v >= 1e3 -> String.format(Locale.US, "%.0f Kbps", v / 1e3)
    else -> String.format(Locale.US, "%.0f bps", v)
}

fun fpsColor(v: Double?) = when {
    v == null -> Palette.dim
    v >= 60 -> Palette.good
    v >= 30 -> Palette.warn
    else -> Palette.bad
}

fun tempColor(v: Double?) = when {
    v == null -> Palette.dim
    v >= 85 -> Palette.bad
    v >= 70 -> Palette.warn
    else -> Palette.text
}

fun pingColor(v: Double?) = when {
    v == null -> Palette.dim
    v <= 40 -> Palette.good
    v <= 90 -> Palette.warn
    else -> Palette.bad
}
