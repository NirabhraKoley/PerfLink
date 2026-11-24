"""
Screen mirroring for PerfLink's "Mirror" page: the PC's screen (any monitor) and its sound, live on
the phone, over Wi-Fi or USB (the same WebSocket as everything else).

  Video: Windows Desktop Duplication (dxcam), only changed frames, about 30 per second, scaled to
         the phone and sent as JPEG, with the mouse pointer drawn in. Flow control: at most two
         frames are on their way to a phone, so a slow link drops frames instead of lagging behind.
  Sound: WASAPI loopback of the PC's default output (exactly what the PC plays), 16-bit PCM.

The screen keeps streaming when the monitor itself is switched off with its power button, as long
as Windows still sees the monitor (most HDMI monitors; some DisplayPort monitors disappear from
Windows when switched off). Nothing is captured unless a phone has the Mirror page open.

Binary messages to the phone:
  0x01 + JPEG bytes                                  a video frame
  0x02 + rate (uint32 LE) + channels (uint8) + PCM   a chunk of sound (16-bit little-endian)
"""
from __future__ import annotations

import asyncio
import ctypes
import io
import struct
import threading
import time
from ctypes import wintypes

FPS = 30
JPEG_QUALITY = 62
MAX_IN_FLIGHT = 2


class _Cursor(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("flags", wintypes.DWORD), ("hCursor", wintypes.HANDLE),
                ("pt", wintypes.POINT)]


def _cursor_pos() -> tuple[int, int] | None:
    ci = _Cursor(cbSize=ctypes.sizeof(_Cursor))
    if ctypes.windll.user32.GetCursorInfo(ctypes.byref(ci)) and ci.flags & 1:  # CURSOR_SHOWING
        return ci.pt.x, ci.pt.y
    return None


def _monitor_rects() -> list[dict]:
    """Every monitor's desktop rectangle, from Windows (to place the mouse pointer)."""
    out = []
    proto = ctypes.WINFUNCTYPE(ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(wintypes.RECT), ctypes.c_double)

    class MONITORINFO(ctypes.Structure):
        _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT), ("rcWork", wintypes.RECT),
                    ("dwFlags", wintypes.DWORD)]

    def each(hmon, _hdc, _rect, _data):
        mi = MONITORINFO(cbSize=ctypes.sizeof(MONITORINFO))
        ctypes.windll.user32.GetMonitorInfoW(ctypes.c_void_p(hmon), ctypes.byref(mi))
        r = mi.rcMonitor
        out.append({"x": r.left, "y": r.top, "w": r.right - r.left, "h": r.bottom - r.top, "primary": bool(mi.dwFlags & 1)})
        return 1

    ctypes.windll.user32.EnumDisplayMonitors(None, None, proto(each), 0)
    return out


class Viewer:
    def __init__(self, ws) -> None:
        self.ws = ws
        self.monitor = 0
        self.audio = True
        self.width = 1280
        self.in_flight = 0


class Mirror:
    def __init__(self) -> None:
        self.loop: asyncio.AbstractEventLoop | None = None
        self.viewers: dict = {}  # ws -> Viewer
        self.monitors: list[dict] = []
        self.audio_format: dict | None = None
        self.error: str | None = None
        self._wake = threading.Event()
        self._lock = threading.Lock()
        try:
            self._list_monitors()
        except Exception as e:  # noqa: BLE001
            self.error = f"Mirror: {e}"
        threading.Thread(target=self._video_loop, daemon=True).start()
        threading.Thread(target=self._audio_loop, daemon=True).start()

    # ------------------------------------------------------------------ phone commands
    def command(self, ws, msg: dict) -> None:
        if msg.get("cmd") == "mirror_ack":
            v = self.viewers.get(ws)
            if v:
                v.in_flight = max(0, v.in_flight - 1)
            return
        if not msg.get("on"):
            self.viewers.pop(ws, None)
            return
        v = self.viewers.get(ws) or Viewer(ws)
        v.monitor = int(msg.get("monitor", v.monitor) or 0)
        v.audio = bool(msg.get("audio", v.audio))
        v.width = max(320, min(int(msg.get("width", v.width) or 1280), 1920))
        v.in_flight = 0
        with self._lock:
            self.viewers[ws] = v
        self._wake.set()

    def drop(self, ws) -> None:
        self.viewers.pop(ws, None)

    def snapshot(self) -> dict:
        return {"monitors": self.monitors, "error": self.error, "streaming": len(self.viewers)}

    # ------------------------------------------------------------------ video
    def _list_monitors(self) -> None:
        import dxcam  # noqa: PLC0415  (loaded on first use: needs COM, see _video_loop)
        info = dxcam.output_info()
        mons = []
        for line in info.strip().splitlines():
            # "Device[0] Output[1]: Res:(1366, 768) Rot:0 Primary:False"
            try:
                dev = int(line.split("Device[")[1].split("]")[0])
                out = int(line.split("Output[")[1].split("]")[0])
                w, h = (int(x) for x in line.split("Res:(")[1].split(")")[0].split(","))
                primary = "Primary:True" in line
            except (IndexError, ValueError):
                continue
            mons.append({"index": len(mons), "device": dev, "output": out, "w": w, "h": h, "primary": primary})
        mons.sort(key=lambda m: (not m["primary"], m["index"]))  # the main monitor first
        for i, m in enumerate(mons):
            m["index"] = i
            m["name"] = f"Monitor {i + 1}" + (" (main)" if m["primary"] else "") + f" · {m['w']}×{m['h']}"
        self.monitors = mons

    def _video_loop(self) -> None:
        try:
            import comtypes  # noqa: PLC0415
            comtypes.CoInitializeEx(comtypes.COINIT_MULTITHREADED)
        except Exception:  # noqa: BLE001
            pass
        cams: dict[int, object] = {}
        last: dict[int, object] = {}       # last frame per monitor (sent again to phones that just joined)
        last_sent: dict[int, float] = {}
        while True:
            if not self.viewers:
                for c in cams.values():
                    try:
                        c.release()
                    except Exception:  # noqa: BLE001
                        pass
                cams.clear(); last.clear()
                self._wake.wait(); self._wake.clear()
                continue
            start = time.time()
            try:
                if not self.monitors:
                    self._list_monitors()
                rects = _monitor_rects()
                wanted = {v.monitor for v in list(self.viewers.values())}
                for idx in wanted:
                    mon = next((m for m in self.monitors if m["index"] == idx), None) or (self.monitors or [None])[0]
                    if mon is None:
                        continue
                    cam = cams.get(mon["index"])
                    if cam is None:
                        import dxcam  # noqa: PLC0415
                        cam = dxcam.create(device_idx=mon["device"], output_idx=mon["output"], output_color="RGB")
                        cams[mon["index"]] = cam
                    frame = cam.grab()
                    fresh = frame is not None
                    if fresh:
                        last[mon["index"]] = frame
                    elif mon["index"] not in last:
                        continue
                    # an unchanged screen is still re-sent once a second (and the pointer may have moved)
                    if not fresh and time.time() - last_sent.get(mon["index"], 0) < 1.0 and not self._pointer_moved(mon):
                        continue
                    viewers = [v for v in list(self.viewers.values()) if v.monitor == idx and v.in_flight < MAX_IN_FLIGHT]
                    if not viewers:
                        continue
                    jpeg = self._encode(last[mon["index"]], max(v.width for v in viewers), mon, rects)
                    last_sent[mon["index"]] = time.time()
                    for v in viewers:
                        v.in_flight += 1
                        self._send(v.ws, b"\x01" + jpeg)
                self.error = None
            except Exception as e:  # noqa: BLE001  (monitor unplugged / switched off and gone, resolution change…)
                self.error = f"Mirror: {e}"
                for c in cams.values():
                    try:
                        c.release()
                    except Exception:  # noqa: BLE001
                        pass
                cams.clear(); last.clear(); self.monitors = []
                time.sleep(1)
            time.sleep(max(0.0, 1 / FPS - (time.time() - start)))

    _last_pointer: tuple | None = None

    def _pointer_moved(self, mon) -> bool:
        p = _cursor_pos()
        moved = p != self._last_pointer
        self._last_pointer = p
        return moved

    def _encode(self, frame, width: int, mon: dict, rects: list[dict]) -> bytes:
        from PIL import Image, ImageDraw  # noqa: PLC0415
        img = Image.fromarray(frame)
        w = min(width, img.width)
        if w < img.width:
            img = img.resize((w, round(img.height * w / img.width)), Image.BILINEAR)
        # draw the mouse pointer (Desktop Duplication leaves it out)
        rect = next((r for r in rects if r["w"] == mon["w"] and r["h"] == mon["h"] and r["primary"] == mon["primary"]), None)
        p = _cursor_pos()
        if rect and p and rect["x"] <= p[0] < rect["x"] + rect["w"] and rect["y"] <= p[1] < rect["y"] + rect["h"]:
            s = img.width / rect["w"]
            x, y = (p[0] - rect["x"]) * s, (p[1] - rect["y"]) * s
            k = max(10, img.width / 90)
            arrow = [(x, y), (x, y + k * 1.5), (x + k * 0.42, y + k * 1.1), (x + k * 0.75, y + k * 1.75),
                     (x + k * 0.95, y + k * 1.65), (x + k * 0.62, y + k * 1.0), (x + k * 1.1, y + k * 1.0)]
            ImageDraw.Draw(img).polygon(arrow, fill=(255, 255, 255), outline=(0, 0, 0))
        b = io.BytesIO()
        img.save(b, "JPEG", quality=JPEG_QUALITY)
        return b.getvalue()

    # ------------------------------------------------------------------ sound
    def _audio_loop(self) -> None:
        """Records what the PC plays while a phone wants sound; follows default-device changes."""
        try:
            import pyaudiowpatch as pyaudio  # noqa: PLC0415
        except Exception as e:  # noqa: BLE001
            self.error = f"Mirror sound: {e}"
            return
        while True:
            if not any(v.audio for v in list(self.viewers.values())):
                time.sleep(0.3)
                continue
            p = stream = None
            try:
                p = pyaudio.PyAudio()
                dev = p.get_default_wasapi_loopback()
                rate, ch = int(dev["defaultSampleRate"]), max(1, min(2, int(dev["maxInputChannels"])))
                self.audio_format = {"rate": rate, "channels": ch, "device": dev["name"].replace(" [Loopback]", "")}
                head = b"\x02" + struct.pack("<IB", rate, ch)

                def cb(data, frames, info, status):
                    for v in list(self.viewers.values()):
                        if v.audio:
                            self._send(v.ws, head + data, audio=True)
                    return None, pyaudio.paContinue

                stream = p.open(format=pyaudio.paInt16, channels=ch, rate=rate, input=True,
                                input_device_index=dev["index"], frames_per_buffer=rate // 50, stream_callback=cb)
                stream.start_stream()
                name = dev["name"]
                # keep going until nobody wants sound or the default output device changes
                while any(v.audio for v in list(self.viewers.values())) and stream.is_active():
                    time.sleep(1)
                    try:
                        if p.get_default_wasapi_loopback()["name"] != name:
                            break
                    except Exception:  # noqa: BLE001
                        break
            except Exception as e:  # noqa: BLE001  (no output device, device unplugged…)
                self.error = f"Mirror sound: {e}"
                time.sleep(2)
            finally:
                try:
                    if stream:
                        stream.stop_stream(); stream.close()
                    if p:
                        p.terminate()
                except Exception:  # noqa: BLE001
                    pass

    # ------------------------------------------------------------------ sending (on the agent's event loop)
    def _send(self, ws, data: bytes, audio: bool = False) -> None:
        if self.loop is None:
            return
        self.loop.call_soon_threadsafe(asyncio.ensure_future, self._send_async(ws, data, audio))

    async def _send_async(self, ws, data: bytes, audio: bool) -> None:
        try:
            # don't pile up sound behind a slow link: better a short gap than growing delay
            if audio and ws.transport is not None and ws.transport.get_write_buffer_size() > 256_000:
                return
            await ws.send(data)
        except Exception:  # noqa: BLE001  (phone went away)
            self.viewers.pop(ws, None)
