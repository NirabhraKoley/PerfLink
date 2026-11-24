package com.perflink.app

import org.json.JSONArray
import org.json.JSONObject

data class Frames(
    val app: String?, val fps: Double?, val frameTimeMs: Double?,
    val low1Fps: Double?, val low01Fps: Double?, val frameTimes: List<Double>,
)

data class Gpu(
    val name: String?, val util: Double?, val powerW: Double?, val tempC: Double?,
    val hotspotC: Double?, val coreClockMhz: Double?, val memClockMhz: Double?,
    val vramUsedMb: Double?, val vramTotalMb: Double?, val fanRpm: Double?, val voltageV: Double?,
)

data class Cpu(
    val name: String?, val util: Double?, val perCore: List<Double>,
    val clockMhz: Double?, val tempC: Double?, val powerW: Double?,
)

data class Ram(val usedMb: Double?, val totalMb: Double?, val util: Double?)

data class Net(
    val downloadBps: Double?, val uploadBps: Double?, val totalRecvMb: Double?,
    val totalSentMb: Double?, val pingMs: Double?, val pingAvgMs: Double?,
    val jitterMs: Double?, val packetLossPct: Double?, val pingHost: String?,
    val iface: String?, val localIp: String?, val linkMbps: Double?, val connections: Double?,
)

data class Media(
    val playing: Boolean, val title: String?, val artist: String?, val album: String?,
    val app: String?, val positionS: Double?, val durationS: Double?, val artId: String?,
)

data class Volume(val level: Double?, val muted: Boolean)

data class Weather(
    val city: String?, val tempC: Double?, val feelsC: Double?, val highC: Double?,
    val lowC: Double?, val code: Int?, val isDay: Boolean, val condition: String?,
)

data class AiApp(
    val name: String, val running: Boolean, val ramMb: Double?, val cpu: Double?,
    val uptimeS: Double?, val detail: String?, val models: List<String>,
)

data class AiSession(
    val name: String, val status: String, val busy: Boolean, val sinceS: Double,
    val folder: String?, val source: String?,
)

data class PullRequest(val number: Int, val title: String, val repo: String)

data class GitHub(
    val available: Boolean, val message: String?, val notifications: Int,
    val reviewRequests: List<PullRequest>, val myPrs: List<PullRequest>,
)

data class ProcApp(
    val key: String, val name: String, val exe: String, val path: String?, val iconId: String?,
    val count: Int, val cpu: Double, val ramMb: Double, val locked: Boolean,
    val pids: List<Int>, val threads: Int, val started: Double?,
)

data class ObsInput(val name: String, val muted: Boolean, val mul: Double?, val db: Double?)

data class ObsState(
    val status: String, val message: String?,
    val scenes: List<String>, val currentScene: String?,
    val recActive: Boolean, val recPaused: Boolean, val recTime: String?,
    val streamActive: Boolean, val streamTime: String?, val streamKbps: Double?,
    val streamDropped: Int, val streamTotal: Int, val congestion: Double?,
    val virtualcam: Boolean, val replayAvailable: Boolean, val replayActive: Boolean,
    val inputs: List<ObsInput>,
    val fps: Double?, val cpu: Double?, val renderSkipped: Int, val renderTotal: Int, val frameMs: Double?,
    val diskFreeMb: Double?,
)

private fun parseObs(o: JSONObject): ObsState {
    val r = o.optJSONObject("record") ?: JSONObject()
    val st = o.optJSONObject("stream") ?: JSONObject()
    val rp = o.optJSONObject("replay") ?: JSONObject()
    val s = o.optJSONObject("stats") ?: JSONObject()
    return ObsState(
        status = o.optString("status"), message = o.s("message"),
        scenes = o.optJSONArray("scenes")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
        currentScene = o.s("current_scene"),
        recActive = r.optBoolean("active"), recPaused = r.optBoolean("paused"), recTime = r.s("timecode"),
        streamActive = st.optBoolean("active"), streamTime = st.s("timecode"), streamKbps = st.d("kbps"),
        streamDropped = st.optInt("dropped"), streamTotal = st.optInt("total"), congestion = st.d("congestion"),
        virtualcam = o.optBoolean("virtualcam"),
        replayAvailable = rp.optBoolean("available"), replayActive = rp.optBoolean("active"),
        inputs = o.optJSONArray("inputs").mapObj { i -> ObsInput(i.optString("name"), i.optBoolean("muted"), i.d("mul"), i.d("db")) },
        fps = s.d("fps"), cpu = s.d("cpu"), renderSkipped = s.optInt("render_skipped"),
        renderTotal = s.optInt("render_total"), frameMs = s.d("frame_ms"), diskFreeMb = s.d("disk_free_mb"),
    )
}

data class UsbDevice(
    val id: String, val name: String, val category: String, val hub: Boolean,
    val vid: String, val pid: String, val arrived: Double?, val port: String?, val functions: List<String>,
)
data class UsbDrive(val letter: String, val fs: String?, val totalGb: Double?, val usedGb: Double?)
data class UsbEvent(val t: Double, val kind: String, val text: String)
data class UsbState(
    val devices: List<UsbDevice>, val deviceCount: Int, val hubCount: Int,
    val drives: List<UsbDrive>, val events: List<UsbEvent>, val error: String?,
)

private fun parseUsb(o: JSONObject) = UsbState(
    devices = o.optJSONArray("devices").mapObj { d ->
        UsbDevice(d.optString("id"), d.optString("name"), d.optString("category", "Device"), d.optBoolean("hub"),
            d.optString("vid"), d.optString("pid"), d.d("arrived"), d.s("port"),
            d.optJSONArray("functions")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList())
    },
    deviceCount = o.optInt("device_count"), hubCount = o.optInt("hub_count"),
    drives = o.optJSONArray("drives").mapObj { r -> UsbDrive(r.optString("letter"), r.s("fs"), r.d("total_gb"), r.d("used_gb")) },
    events = o.optJSONArray("events").mapObj { e -> UsbEvent(e.optDouble("t"), e.optString("kind"), e.optString("text")) },
    error = o.s("error"),
)

data class BtDevice(
    val id: String, val name: String, val type: String, val connected: Boolean, val battery: Int?, val le: Boolean,
)
data class BtState(
    val radio: String?, val devices: List<BtDevice>, val connectedCount: Int, val pairedCount: Int,
    val events: List<UsbEvent>, val error: String?,
)

data class WifiLink(
    val iface: String?, val adapter: String?, val connected: Boolean, val ssid: String?, val signal: Int?,
    val standard: String?, val band: String?, val channel: String?, val security: String?,
    val rxMbps: Double?, val txMbps: Double?, val ip: String?,
)
data class WiredLink(val iface: String, val speedMbps: Double?, val ip: String?)
data class WifiState(val available: Boolean, val wifi: List<WifiLink>, val wired: List<WiredLink>)

private fun parseWifi(o: JSONObject) = WifiState(
    available = o.optBoolean("available"),
    wifi = o.optJSONArray("wifi").mapObj { w ->
        WifiLink(w.s("interface"), w.s("adapter"), w.optBoolean("connected"), w.s("ssid"), w.d("signal")?.toInt(),
            w.s("standard"), w.s("band"), w.s("channel"), w.s("security"), w.d("rx_mbps"), w.d("tx_mbps"), w.s("ip"))
    },
    wired = o.optJSONArray("wired").mapObj { w -> WiredLink(w.optString("interface"), w.d("speed_mbps"), w.s("ip")) },
)

private fun parseBt(o: JSONObject) = BtState(
    radio = o.s("radio"),
    devices = o.optJSONArray("devices").mapObj { d ->
        BtDevice(d.optString("id"), d.optString("name"), d.optString("type", "Device"), d.optBoolean("connected"),
            d.d("battery")?.toInt(), d.optBoolean("le"))
    },
    connectedCount = o.optInt("connected_count"), pairedCount = o.optInt("paired_count"),
    events = o.optJSONArray("events").mapObj { e -> UsbEvent(e.optDouble("t"), e.optString("kind"), e.optString("text")) },
    error = o.s("error"),
)

data class WaNote(val id: String, val title: String, val text: String, val t: Double?)
data class WhatsApp(val installed: Boolean, val running: Boolean, val notes: List<WaNote>,
                    val messages: Int, val calls: Int)

private fun parseWhatsApp(o: JSONObject) = WhatsApp(
    installed = o.optBoolean("installed"),
    running = o.optBoolean("running"),
    notes = o.optJSONArray("notifications").mapObj { n ->
        WaNote(n.optString("id"), n.optString("title"), n.optString("text"), n.d("t"))
    },
    messages = o.optInt("messages"),
    calls = o.optInt("calls"),
)

data class ProcList(val apps: List<ProcApp>, val totalProcesses: Int, val totalApps: Int)

data class AiStatus(val apps: List<AiApp>, val sessions: List<AiSession>, val github: GitHub?)

data class Stats(
    val host: String?, val frames: Frames, val gpu: Gpu, val cpu: Cpu,
    val ram: Ram, val net: Net, val errors: List<String>,
    val media: Media? = null, val volume: Volume? = null, val weather: Weather? = null,
    val ai: AiStatus? = null,
    val processes: ProcList? = null,
    val launcherHints: Map<String, String?> = emptyMap(),
    val obs: ObsState? = null,
    val usb: UsbState? = null,
    val bluetooth: BtState? = null,
    val wifi: WifiState? = null,
    val whatsapp: WhatsApp? = null,
)

private fun parseProcs(p: JSONObject) = ProcList(
    apps = p.optJSONArray("apps").mapObj { o ->
        ProcApp(o.optString("key"), o.optString("name"), o.optString("exe"), o.s("path"), o.s("icon_id"),
            o.optInt("count"), o.optDouble("cpu", 0.0), o.optDouble("ram_mb", 0.0), o.optBoolean("locked"),
            o.optJSONArray("pids")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList(),
            o.optInt("threads"), o.d("started"))
    },
    totalProcesses = p.optInt("total_processes"),
    totalApps = p.optInt("total_apps"),
)

private fun <T> JSONArray?.mapObj(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }

private fun parseAi(a: JSONObject): AiStatus = AiStatus(
    apps = a.optJSONArray("apps").mapObj { o ->
        AiApp(o.optString("name"), o.optBoolean("running"), o.d("ram_mb"), o.d("cpu"), o.d("uptime_s"),
            o.s("detail"), o.optJSONArray("models")?.let { m -> (0 until m.length()).map { m.optString(it) } } ?: emptyList())
    },
    sessions = a.optJSONArray("sessions").mapObj { o ->
        AiSession(o.optString("name"), o.optString("status"), o.optBoolean("busy"), o.optDouble("since_s", 0.0),
            o.s("folder"), o.s("source"))
    },
    github = a.optJSONObject("github")?.let { g ->
        fun prs(k: String) = g.optJSONArray(k).mapObj { p -> PullRequest(p.optInt("number"), p.optString("title"), p.optString("repo")) }
        GitHub(g.optBoolean("available"), g.s("message"), g.optInt("notifications"), prs("review_requests"), prs("my_prs"))
    },
)

private fun JSONObject.d(k: String): Double? =
    if (!has(k) || isNull(k)) null else optDouble(k).takeIf { !it.isNaN() }

private fun JSONObject.s(k: String): String? =
    if (!has(k) || isNull(k)) null else optString(k)

private fun JSONArray?.doubles(): List<Double> =
    if (this == null) emptyList() else (0 until length()).map { optDouble(it) }

private fun JSONObject.obj(k: String): JSONObject = optJSONObject(k) ?: JSONObject()

fun parseStats(text: String): Stats? = try {
    val o = JSONObject(text)
    if (o.optString("type") != "stats") null else {
        val f = o.obj("frames"); val g = o.obj("gpu"); val c = o.obj("cpu")
        val r = o.obj("ram"); val n = o.obj("net")
        Stats(
            host = o.s("host"),
            frames = Frames(f.s("app"), f.d("fps"), f.d("frame_time_ms"), f.d("low1_fps"),
                f.d("low01_fps"), f.optJSONArray("frame_times").doubles()),
            gpu = Gpu(g.s("name"), g.d("util"), g.d("power_w"), g.d("temp_c"), g.d("hotspot_c"),
                g.d("core_clock_mhz"), g.d("mem_clock_mhz"), g.d("vram_used_mb"),
                g.d("vram_total_mb"), g.d("fan_rpm"), g.d("voltage_v")),
            cpu = Cpu(c.s("name"), c.d("util"), c.optJSONArray("per_core").doubles(),
                c.d("clock_mhz"), c.d("temp_c"), c.d("power_w")),
            ram = Ram(r.d("used_mb"), r.d("total_mb"), r.d("util")),
            net = Net(n.d("download_bps"), n.d("upload_bps"), n.d("total_recv_mb"),
                n.d("total_sent_mb"), n.d("ping_ms"), n.d("ping_avg_ms"), n.d("jitter_ms"),
                n.d("packet_loss_pct"), n.s("ping_host"), n.s("interface"), n.s("local_ip"),
                n.d("link_mbps"), n.d("connections")),
            errors = o.optJSONArray("errors")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?: emptyList(),
            media = o.optJSONObject("media")?.let { m ->
                Media(m.optBoolean("playing"), m.s("title"), m.s("artist"), m.s("album"), m.s("app"),
                    m.d("position_s"), m.d("duration_s"), m.s("art_id"))
            },
            volume = o.optJSONObject("volume")?.let { v -> Volume(v.d("level"), v.optBoolean("muted")) },
            weather = o.optJSONObject("weather")?.let { w ->
                Weather(w.s("city"), w.d("temp_c"), w.d("feels_c"), w.d("high_c"), w.d("low_c"),
                    w.d("code")?.toInt(), w.optBoolean("is_day", true), w.s("condition"))
            },
            ai = o.optJSONObject("ai")?.let { parseAi(it) },
            processes = o.optJSONObject("processes")?.let { parseProcs(it) },
            launcherHints = o.optJSONObject("launcher")?.let { l -> l.keys().asSequence().associateWith { l.s(it) } }
                ?: emptyMap(),
            obs = o.optJSONObject("obs")?.let { parseObs(it) },
            usb = o.optJSONObject("usb")?.let { parseUsb(it) },
            bluetooth = o.optJSONObject("bluetooth")?.let { parseBt(it) },
            wifi = o.optJSONObject("wifi")?.let { parseWifi(it) },
            whatsapp = o.optJSONObject("whatsapp")?.let { parseWhatsApp(it) },
        )
    }
} catch (e: Exception) {
    null
}
