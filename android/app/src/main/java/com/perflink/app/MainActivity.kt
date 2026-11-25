package com.perflink.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

object Palette {
    val bg = Color(0xFF0B0E13)
    val card = Color(0xFF141A22)
    val cardBorder = Color(0xFF222B36)
    val grid = Color(0x1FFFFFFF)
    val track = Color(0xFF232C38)
    val text = Color(0xFFE8EDF3)
    val dim = Color(0xFF8A96A6)
    val accent = Color(0xFF4FC3F7)
    val good = Color(0xFF5BD68A)
    val warn = Color(0xFFF2C14E)
    val bad = Color(0xFFFF6B6B)
    val gpu = Color(0xFFFF7A59)
    val cpu = Color(0xFF7C9CFF)
    val down = Color(0xFF4FC3F7)
    val up = Color(0xFFB388FF)
    val ping = Color(0xFF5BD68A)
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
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.accent, background = Palette.bg, surface = Palette.card)) {
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
                    // Watermark, bottom-right on every screen (plain text, so taps pass through)
                    Text(
                        "- created by Nirabhra Koley", color = Palette.dim.copy(alpha = 0.55f), fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 4.dp),
                    )
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
fun ConnectScreen(state: UiState, onScan: () -> Unit, onConnect: (String) -> Unit) {
    var ip by rememberSaveable(state.lastIp) { mutableStateOf(state.lastIp) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text("PerfLink", color = Palette.text, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Live FPS, GPU, CPU and network stats from your PC.", color = Palette.dim, fontSize = 15.sp)

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
            else Modifier.size(w, h).border(1.dp, Color(0x66FFFFFF))
        ) {
            CompositionLocalProvider(LocalDensity provides density) { content() }
        }
    }
}

/** Set from the `sim_aspect` launch extra (testing other screen shapes); null = this phone's own screen. */
var simulatedAspect: Float? = null

private const val DESIGN_HEIGHT_DP = 360f
private const val DESIGN_MIN_WIDTH_DP = 600f

// =============================================================================== Dashboard
// Page order, in one place (the page number is the position here; `-ei page N` opens page N):
// Home, then using the PC (watch it, control it, quick actions), then gaming stats, then what's running
// and streaming, then devices. Pages loop, so Weather (last) is one swipe left of Home and Mirror one right.
enum class Page { Home, Mirror, Remote, Launcher, Performance, Overlay, Processes, Obs, Sessions, Bluetooth, Usb, Weather }

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
    Box(Modifier.fillMaxSize()) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { slot ->
            val page = PAGE_ORDER[slot % PAGES]
            when {
                state.stats == null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(if (state.conn == Conn.Reconnecting) "Reconnecting to the PC…" else "Connecting to the PC…",
                        color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("Make sure the PerfLink program is running on the PC. Retrying automatically.",
                        color = Palette.dim, fontSize = 12.sp)
                    state.error?.let { Text(it, color = Palette.dim.copy(alpha = 0.7f), fontSize = 11.sp, maxLines = 1) }
                    Spacer(Modifier.height(14.dp))
                    Chip("Choose another PC", Palette.accent) { vm.disconnect() }
                }
                else -> when (page) {
                    Page.Home -> HomePage(state, vm::media, vm::setVolume, vm::toggleMute, vm::setCity, vm::disconnect,
                        onWeatherAuto = vm::weatherAuto,
                        onOpenWeather = { pageScope.launch { pager.animateScrollToPage(nearestSlot(pager.currentPage, Page.Weather)) } })
                    Page.Mirror -> MirrorPage(state, vm)
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
        // Only ask the PC for OBS preview frames while the OBS page is showing
        LaunchedEffect(current) { vm.obsView(PAGE_ORDER[current] == Page.Obs) }
        // Home button, top-right, on every page except Home itself.
        // Goes to the nearest Home slot (forward or back), so it never scrolls more than half the loop.
        if (current != 0 && state.stats != null) {
            val scope = rememberCoroutineScope()
            HomeButton(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 8.dp)) {
                scope.launch { pager.animateScrollToPage(nearestSlot(pager.currentPage, Page.Home)) }
            }
        }
        // USB plug/unplug pop-up (shown on every page)
        state.usbAlert?.let { UsbAlertBanner(it, Modifier.align(Alignment.TopCenter).padding(top = 10.dp)) }
        // Page dots
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(PAGES) { i ->
                val active = current == i
                Box(
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
fun HomeButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(34.dp).clip(CircleShape).background(Color(0xCC1A222C))
            .border(1.dp, Color(0x33FFFFFF), CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Image(rememberVectorPainter(lineIcon(HOME_ICON)), contentDescription = "Home", modifier = Modifier.size(18.dp),
            colorFilter = ColorFilter.tint(Palette.text))
    }
}

/** Space each page leaves at its top-right so its own controls aren't hidden under the Home button. */
val HomeButtonGap = 44.dp

@Composable
fun StatsPage(state: UiState, compact: Boolean, onDisconnect: () -> Unit) {
    val s = state.stats
    Column(Modifier.fillMaxSize().padding(bottom = 10.dp)) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = HomeButtonGap + 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            val (dot, label) = when (state.conn) {
                Conn.Connected -> Palette.good to "Live"
                Conn.Connecting -> Palette.warn to "Connecting…"
                else -> Palette.bad to "Reconnecting…"
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(s?.host ?: state.target ?: "", color = Palette.text, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (state.conn != Conn.Connected && state.error != null) "$label ${state.error}" else label,
                    color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(if (compact) "OVERLAY" else "PERFORMANCE", color = Palette.dim, fontSize = 11.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.width(12.dp))
            Chip("Disconnect", Palette.bad) { onDisconnect() }
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
    // Same order as AMD's in-game overlay, then extras (CPU temp, network).
    // FRAME GEN LAG and UPSCALE UPGRADE only come from AMD's driver (Fluid Motion Frames / FSR upgrade);
    // no public interface exposes them, so they read N/A, like AMD's overlay when those features are off.
    val rows = listOf(
        Triple("FPS", f0(s.frames.fps), fpsColor(s.frames.fps)),
        Triple("FRAME TIME", s.frames.frameTimeMs?.let { "${f1(it)} ms" } ?: "N/A", Palette.text),
        Triple("99th% FPS", f0(s.frames.low1Fps), fpsColor(s.frames.low1Fps)),
        Triple("FRAME GEN LAG", "N/A", Palette.dim),
        Triple("GPU UTIL", pct(s.gpu.util), Palette.text),
        Triple("GPU PWR", s.gpu.powerW?.let { "${f0(it)} W" } ?: "N/A", Palette.text),
        Triple("GPU TEMP", temp(s.gpu.tempC), tempColor(s.gpu.tempC)),
        Triple("GPU MEM UTIL", s.gpu.vramUsedMb?.let { "${f0(it)} MB" } ?: "N/A", Palette.text),
        Triple("GPU MEM CLK", s.gpu.memClockMhz?.let { "${f0(it)} MHz" } ?: "N/A", Palette.text),
        Triple("CPU UTIL", pct(s.cpu.util), Palette.text),
        Triple("SYSTEM MEM UTIL", s.ram.usedMb?.let { String.format(Locale.US, "%.1f GB", it / 1024) } ?: "N/A", Palette.text),
        Triple("UPSCALE UPGRADE", "N/A", Palette.dim),
        Triple("CPU TEMP", temp(s.cpu.tempC), tempColor(s.cpu.tempC)),
        Triple("NET ↓", bps(s.net.downloadBps), Palette.down),
        Triple("NET ↑", bps(s.net.uploadBps), Palette.up),
        Triple("PING", s.net.pingMs?.let { "${f0(it)} ms" } ?: "N/A", pingColor(s.net.pingMs)),
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
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(v ?: "N/A", color = Palette.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
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
