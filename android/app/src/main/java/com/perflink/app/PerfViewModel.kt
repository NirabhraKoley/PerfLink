package com.perflink.app

import android.app.Application
import android.content.Context
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

const val WS_PORT = 47800
const val DISCOVERY_PORT = 47801
const val HISTORY = 120

data class FoundPc(val name: String, val ip: String, val port: Int)

enum class Conn { Idle, Connecting, Connected, Reconnecting }

data class History(
    val fps: List<Float> = emptyList(),
    val frameTime: List<Float> = emptyList(),
    val gpuUtil: List<Float> = emptyList(),
    val cpuUtil: List<Float> = emptyList(),
    val gpuTemp: List<Float> = emptyList(),
    val down: List<Float> = emptyList(),
    val up: List<Float> = emptyList(),
    val ping: List<Float> = emptyList(),
    val ram: List<Float> = emptyList(),
)

data class UiState(
    val conn: Conn = Conn.Idle,
    val target: String? = null,
    val stats: Stats? = null,
    val history: History = History(),
    val scanning: Boolean = false,
    val found: List<FoundPc> = emptyList(),
    val error: String? = null,
    val lastIp: String = "",
    val artId: String? = null,
    val art: ImageBitmap? = null,
    val icons: Map<String, ImageBitmap> = emptyMap(),
    val toast: Toast? = null,
    val obsPreview: ImageBitmap? = null,
    val usbAlert: UsbEvent? = null,
)

data class Toast(val message: String, val ok: Boolean, val id: Long = System.nanoTime())

class PerfViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("perflink", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(UiState(lastIp = prefs.getString("last_ip", "") ?: ""))
    val state: StateFlow<UiState> = _state

    private val client = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)
        .connectTimeout(4, TimeUnit.SECONDS)
        .build()
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var wantConnected = false

    init {
        discover()
    }

    // ---------------------------------------------------------------- discovery
    fun discover() {
        if (_state.value.scanning) return
        _state.update { it.copy(scanning = true, found = emptyList()) }
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { scanLan() }
            _state.update { it.copy(scanning = false, found = found) }
        }
    }

    private fun scanLan(): List<FoundPc> {
        val wifi = getApplication<Application>().applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("perflink").apply { setReferenceCounted(false) }
        val results = linkedMapOf<String, FoundPc>()
        try {
            lock.acquire()
            DatagramSocket().use { sock ->
                sock.broadcast = true
                sock.soTimeout = 400
                val msg = "PERFLINK_DISCOVER".toByteArray()
                val targets = buildList {
                    add(InetAddress.getByName("255.255.255.255"))
                    subnetBroadcast(wifi)?.let { add(it) }
                }
                val deadline = System.currentTimeMillis() + 2500
                var nextSend = 0L
                val buf = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() >= nextSend) {
                        targets.forEach { runCatching { sock.send(DatagramPacket(msg, msg.size, it, DISCOVERY_PORT)) } }
                        nextSend = System.currentTimeMillis() + 800
                    }
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        sock.receive(pkt)
                        val o = JSONObject(String(pkt.data, 0, pkt.length))
                        if (o.optString("type") == "perflink") {
                            val ip = pkt.address.hostAddress ?: continue
                            results[ip] = FoundPc(o.optString("name", ip), ip, o.optInt("port", WS_PORT))
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { lock.release() }
        }
        return results.values.toList()
    }

    @Suppress("DEPRECATION")
    private fun subnetBroadcast(wifi: WifiManager): InetAddress? {
        val dhcp = wifi.dhcpInfo ?: return null
        if (dhcp.ipAddress == 0) return null
        val mask = if (dhcp.netmask != 0) dhcp.netmask else 0x00FFFFFF
        val bc = (dhcp.ipAddress and mask) or mask.inv()
        val b = ByteArray(4) { i -> (bc shr (i * 8) and 0xFF).toByte() }
        return InetAddress.getByAddress(b)
    }

    // ---------------------------------------------------------------- connection
    fun connect(hostInput: String) {
        val host = hostInput.trim().removePrefix("ws://").substringBefore("/")
        if (host.isEmpty()) return
        val url = if (host.contains(":")) "ws://$host" else "ws://$host:$WS_PORT"
        prefs.edit().putString("last_ip", host).apply()
        wantConnected = true
        _state.update {
            it.copy(conn = Conn.Connecting, target = url, error = null, lastIp = host,
                history = History(), stats = null)
        }
        open(url)
    }

    // Watchdog: the agent sends stats twice a second, so 8 s of silence means the link is dead
    // (USB cable replugged, Wi-Fi dropped, half-open socket, stuck handshake…). Reconnect then.
    @Volatile private var lastMessageAt = 0L
    private var currentUrl: String? = null
    private var watchdogJob: Job? = null

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = viewModelScope.launch {
            while (true) {
                delay(2000)
                val url = currentUrl ?: continue
                if (!wantConnected) continue
                if (System.currentTimeMillis() - lastMessageAt > 8000) {
                    _state.update { it.copy(conn = Conn.Reconnecting, error = "No data from the PC, retrying") }
                    open(url)
                }
            }
        }
    }

    private fun open(url: String) {
        currentUrl = url
        lastMessageAt = System.currentTimeMillis()  // give this attempt a full window before the watchdog retries
        startWatchdog()
        socket?.cancel()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)  // finish the close handshake so onClosed fires
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.update { it.copy(conn = Conn.Connected, error = null) }
                if (obsViewing) webSocket.send(JSONObject().put("cmd", "obs_view").put("on", true).toString())
                if (mirrorOn) webSocket.send(mirrorCommand(true).toString())
            }

            // Mirror page: 0x01 + JPEG = a screen frame, 0x02 + format + PCM = PC sound
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (webSocket != socket) return
                lastMessageAt = System.currentTimeMillis()
                val data = bytes.toByteArray()
                if (data.isEmpty() || !mirrorOn) return
                when (data[0].toInt()) {
                    1 -> {
                        mirrorFrames.decode(data, 1)?.let { mirrorFrame.value = it }
                        webSocket.send("{\"cmd\": \"mirror_ack\"}")  // ready for the next frame
                    }
                    2 -> if (mirrorSound) mirrorAudio.play(data)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket == socket) lastMessageAt = System.currentTimeMillis()
                if (text.startsWith("{\"type\": \"art\"")) {
                    handleArt(text); return
                }
                if (text.startsWith("{\"type\": \"icons\"")) {
                    handleIcons(text); return
                }
                if (text.startsWith("{\"type\": \"obs_preview\"")) {
                    runCatching {
                        val bytes = Base64.decode(JSONObject(text).optString("data"), Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }.getOrNull()?.let { bmp -> _state.update { it.copy(obsPreview = bmp) } }
                    return
                }
                if (text.startsWith("{\"type\": \"action_result\"")) {
                    runCatching { JSONObject(text) }.getOrNull()?.let { o ->
                        showToast(o.optString("message"), o.optBoolean("ok"))
                    }
                    return
                }
                val s = parseStats(text) ?: return
                _state.update { it.copy(conn = Conn.Connected, stats = s, history = it.history.push(s)) }
                checkUsbEvents(s.usb)
                checkBtEvents(s.bluetooth)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (webSocket != socket) return
                scheduleReconnect(url, t.message ?: "Connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (webSocket != socket) return
                scheduleReconnect(url, "Connection closed")
            }
        })
    }

    private fun handleArt(text: String) {
        try {
            val o = JSONObject(text)
            val id = if (o.isNull("id")) null else o.optString("id")
            val data = if (o.isNull("data")) null else o.optString("data")
            val bmp = data?.let {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
            _state.update { it.copy(artId = id, art = bmp) }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- USB plug/unplug pop-up
    private var lastUsbEvent = -1.0
    private var usbAlertJob: Job? = null

    /** Pop up "X connected / disconnected" on any page. Events from before we connected are skipped. */
    private fun checkUsbEvents(u: UsbState?) {
        val events = u?.events ?: return
        val newest = events.maxOfOrNull { it.t } ?: 0.0
        if (lastUsbEvent < 0) { lastUsbEvent = newest; return }
        val e = events.filter { it.t > lastUsbEvent }.maxByOrNull { it.t } ?: return
        lastUsbEvent = newest
        _state.update { it.copy(usbAlert = e) }
        usbAlertJob?.cancel()
        usbAlertJob = viewModelScope.launch {
            delay(4000)
            _state.update { if (it.usbAlert == e) it.copy(usbAlert = null) else it }
        }
    }

    // Bluetooth connect/disconnect pop-up (shares the USB banner slot)
    private var lastBtEvent = -1.0

    private fun checkBtEvents(b: BtState?) {
        val events = b?.events ?: return
        val newest = events.maxOfOrNull { it.t } ?: 0.0
        if (lastBtEvent < 0) { lastBtEvent = newest; return }
        val e = events.filter { it.t > lastBtEvent }.maxByOrNull { it.t } ?: return
        lastBtEvent = newest
        val tagged = e.copy(text = "Bluetooth: ${e.text}")
        _state.update { it.copy(usbAlert = tagged) }
        usbAlertJob?.cancel()
        usbAlertJob = viewModelScope.launch {
            delay(4000)
            _state.update { if (it.usbAlert == tagged) it.copy(usbAlert = null) else it }
        }
    }

    private fun handleIcons(text: String) {
        try {
            val items = JSONObject(text).optJSONObject("items") ?: return
            val decoded = mutableMapOf<String, ImageBitmap>()
            items.keys().forEach { id ->
                val bytes = Base64.decode(items.optString(id), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { decoded[id] = it.asImageBitmap() }
            }
            _state.update { it.copy(icons = it.icons + decoded) }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- commands to the PC
    private fun send(o: JSONObject) {
        socket?.send(o.toString())
    }

    // ---------------------------------------------------------------- Mirror page
    val mirrorFrame = MutableStateFlow<ImageBitmap?>(null)
    private val mirrorFrames = MirrorFrames()
    private val mirrorAudio = MirrorAudio()
    @Volatile private var mirrorOn = false
    @Volatile var mirrorSound = true
        private set
    var mirrorMonitor = 0
        private set

    private fun mirrorCommand(on: Boolean) = JSONObject().put("cmd", "mirror").put("on", on)
        .put("monitor", mirrorMonitor).put("audio", mirrorSound)
        .put("width", getApplication<Application>().resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) })

    /** Start / stop the PC streaming its screen (and sound) to this phone. */
    fun mirror(on: Boolean, monitor: Int = mirrorMonitor, sound: Boolean = mirrorSound) {
        if (monitor != mirrorMonitor) mirrorFrames.clear()
        mirrorOn = on; mirrorMonitor = monitor; mirrorSound = sound
        if (!on || !sound) mirrorAudio.stop()
        if (!on) { mirrorFrame.value = null; mirrorFrames.clear() }
        send(mirrorCommand(on))
    }

    /** Remote page: mouse / keyboard input for the PC ("move", "click", "button", "scroll", "text", "key"). */
    fun input(type: String, build: JSONObject.() -> Unit = {}) =
        send(JSONObject().put("cmd", "input").put("type", type).apply(build))

    fun media(action: String, value: Double? = null) =
        send(JSONObject().put("cmd", "media").put("action", action).apply { value?.let { put("value", it) } })

    private var obsViewing = false

    /** Tell the PC whether the OBS page is on screen (it only sends previews then). */
    fun obsView(on: Boolean) {
        if (on == obsViewing) return
        obsViewing = on
        socket?.send(JSONObject().put("cmd", "obs_view").put("on", on).toString())
        if (!on) _state.update { it.copy(obsPreview = null) }
    }

    fun obs(action: String, input: String? = null, value: Any? = null) {
        val o = JSONObject().put("cmd", "obs").put("action", action)
        input?.let { o.put("input", it) }
        value?.let { o.put("value", it) }
        socket?.send(o.toString())
    }

    fun action(id: String) {
        val ws = socket
        if (ws == null || _state.value.conn != Conn.Connected) {
            showToast("Not connected to the PC", false); return
        }
        ws.send(JSONObject().put("cmd", "action").put("id", id).toString())
    }

    private var toastJob: Job? = null
    private fun showToast(msg: String, ok: Boolean) {
        val t = Toast(msg, ok)
        _state.update { it.copy(toast = t) }
        toastJob?.cancel()
        toastJob = viewModelScope.launch {
            delay(2500)
            _state.update { if (it.toast?.id == t.id) it.copy(toast = null) else it }
        }
    }

    fun setVolume(pct: Double) = send(JSONObject().put("cmd", "volume").put("value", pct))
    fun toggleMute() = send(JSONObject().put("cmd", "mute"))
    /** Weather follows the PC's location again (after a city was typed in settings). */
    fun weatherAuto() = send(JSONObject().put("cmd", "weather_auto"))

    fun setCity(city: String) {
        if (city.isNotBlank()) send(JSONObject().put("cmd", "set_city").put("city", city.trim()))
    }

    private fun scheduleReconnect(url: String, err: String) {
        if (!wantConnected) return
        _state.update { it.copy(conn = Conn.Reconnecting, error = err) }
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(2000)
            if (wantConnected) open(url)
        }
    }

    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel()
        socket?.close(1000, "bye")
        socket = null
        _state.update { it.copy(conn = Conn.Idle, stats = null, target = null, error = null) }
        discover()
    }

    override fun onCleared() {
        wantConnected = false
        socket?.cancel()
        mirrorAudio.stop()
    }
}

private fun List<Float>.add(v: Double?): List<Float> =
    (this + (v?.toFloat() ?: Float.NaN)).takeLast(HISTORY)

private fun History.push(s: Stats) = History(
    fps = fps.add(s.frames.fps),
    frameTime = frameTime.add(s.frames.frameTimeMs),
    gpuUtil = gpuUtil.add(s.gpu.util),
    cpuUtil = cpuUtil.add(s.cpu.util),
    gpuTemp = gpuTemp.add(s.gpu.tempC),
    down = down.add(s.net.downloadBps),
    up = up.add(s.net.uploadBps),
    ping = ping.add(s.net.pingMs),
    ram = ram.add(s.ram.util),
)
