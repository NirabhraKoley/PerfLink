"""
Screen mirroring for PerfLink's "Mirror" page: the PC's screen and its sound, live on the phone, over
Wi-Fi or USB (the same WebSocket as everything else).

The capture itself runs in two small helper processes ("python mirror.py --helper video|audio"),
started only while a phone has the Mirror page open. Screen and sound capture use native Windows
libraries (Desktop Duplication, WASAPI loopback); if one of them crashes on some PC, only its helper
stops: PerfLink keeps running, logs the crash, restarts the helper, and after repeated crashes turns
that part off and says so on the phone.

  Video helper: Desktop Duplication (dxcam), only changed frames, about 30 per second, scaled to the
         phone and sent as JPEG with the mouse pointer drawn in. If Desktop Duplication crashes on a PC
         (some laptop graphics setups), the helper is restarted in compatibility mode ("--gdi"): plain
         GDI screen capture (mss), a little slower but works practically everywhere. Falls back to GDI capture (mss) when
         Duplication stops, and follows monitors being switched off / on. Flow control: it only
         encodes a frame when a phone is ready for one (at most two on their way).
  Audio helper: WASAPI loopback of the PC's default output (exactly what the PC plays), 16-bit PCM.

Binary messages to the phone:
  0x01 + JPEG bytes                                  a video frame
  0x02 + rate (uint32 LE) + channels (uint8) + PCM   a chunk of sound (16-bit little-endian)

Helper -> PerfLink (stdout): kind byte + payload length (uint32 LE) + payload
  M  monitors (JSON)     V  monitor index byte + JPEG     A  a phone-ready sound message     E  error text
PerfLink -> video helper (stdin, JSON lines): {"monitors": [0], "width": 1280}  {"ready": 1}  {"refresh": 1}
"""
from __future__ import annotations

import asyncio
import ctypes
import io
import json
import os
import struct
import subprocess
import sys
import threading
import time
from ctypes import wintypes

FPS = 30
JPEG_QUALITY = 62
MAX_IN_FLIGHT = 2
HERE = os.path.dirname(os.path.abspath(__file__))
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


# ============================================================================ shared helpers
class _Cursor(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("flags", wintypes.DWORD), ("hCursor", wintypes.HANDLE),
                ("pt", wintypes.POINT)]


def _cursor_pos() -> tuple[int, int] | None:
    ci = _Cursor(cbSize=ctypes.sizeof(_Cursor))
    if ctypes.windll.user32.GetCursorInfo(ctypes.byref(ci)) and ci.flags & 1:  # CURSOR_SHOWING
        return ci.pt.x, ci.pt.y
    return None


class _MONITORINFO(ctypes.Structure):
    _fields_ = [("cbSize", wintypes.DWORD), ("rcMonitor", wintypes.RECT), ("rcWork", wintypes.RECT),
                ("dwFlags", wintypes.DWORD)]


_MonitorEnumProc = ctypes.WINFUNCTYPE(ctypes.c_int, wintypes.HMONITOR, wintypes.HDC, ctypes.POINTER(wintypes.RECT),
                                      wintypes.LPARAM)


def _monitor_rects() -> list[dict]:
    """Every monitor's desktop rectangle, from Windows."""
    out = []

    def each(hmon, _hdc, _rect, _data):
        mi = _MONITORINFO(cbSize=ctypes.sizeof(_MONITORINFO))
        ctypes.windll.user32.GetMonitorInfoW(hmon, ctypes.byref(mi))
        r = mi.rcMonitor
        out.append({"x": r.left, "y": r.top, "w": r.right - r.left, "h": r.bottom - r.top, "primary": bool(mi.dwFlags & 1)})
        return 1

    ctypes.windll.user32.EnumDisplayMonitors(None, None, _MonitorEnumProc(each), 0)
    return out


# ============================================================================ in PerfLink
class Viewer:
    def __init__(self, ws) -> None:
        self.ws = ws
        self.monitor = 0
        self.audio = True
        self.width = 1280
        self.in_flight = 0


class _Helper:
    """One capture helper process: started when wanted, restarted if it crashes, given up on (for a
    few minutes) if it keeps crashing, so a native crash can never take PerfLink down."""

    def __init__(self, kind: str, on_message) -> None:
        self.kind = kind
        self.on_message = on_message
        self.proc: subprocess.Popen | None = None
        self.wanted = False
        self.crashes: list[float] = []
        self.disabled_until = 0.0
        self.error: str | None = None
        self.on_start = lambda: None
        self.extra: list[str] = []     # extra helper arguments ("--gdi" once the fast capture has crashed)
        self._lock = threading.Lock()

    def want(self, on: bool) -> None:
        self.wanted = on
        with self._lock:
            running = self.proc is not None and self.proc.poll() is None
        if on and not running:
            self._start()
        elif not on and running:
            self._stop()

    def send(self, obj: dict) -> None:
        p = self.proc
        if p is None or p.poll() is not None:
            return
        try:
            p.stdin.write((json.dumps(obj) + "\n").encode())
            p.stdin.flush()
        except OSError:
            pass

    def _python(self) -> str:
        exe = sys.executable
        console = os.path.join(os.path.dirname(exe), "python.exe")  # not pythonw: its pipes are more reliable
        return console if exe.lower().endswith("pythonw.exe") and os.path.exists(console) else exe

    def _start(self) -> None:
        with self._lock:
            if time.time() < self.disabled_until or (self.proc is not None and self.proc.poll() is None):
                return
            try:
                self.proc = subprocess.Popen([self._python(), os.path.join(HERE, "mirror.py"), "--helper", self.kind,
                                              *self.extra],
                                             stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                             cwd=os.path.expanduser("~"), creationflags=NO_WINDOW)
            except OSError as e:
                self.error = f"Mirror {self.kind}: couldn't start ({e})"
                return
            proc = self.proc
        threading.Thread(target=self._read, args=(proc,), daemon=True).start()
        threading.Thread(target=self._read_err, args=(proc,), daemon=True).start()
        self.on_start()

    def _stop(self) -> None:
        with self._lock:
            p, self.proc = self.proc, None
        if p is not None:
            try:
                p.kill()
            except OSError:
                pass

    def _read_err(self, proc) -> None:
        for line in proc.stderr:
            text = line.decode("utf-8", "replace").rstrip()
            if text:
                print(f"[mirror-{self.kind}] {text}")

    def _read(self, proc) -> None:
        out = proc.stdout
        try:
            while True:
                head = out.read(5)
                if len(head) < 5:
                    break
                n = struct.unpack("<I", head[1:5])[0]
                payload = out.read(n)
                if len(payload) < n:
                    break
                self.on_message(head[:1], payload)
        except Exception:  # noqa: BLE001
            pass
        code = proc.wait()
        with self._lock:
            ours = self.proc is proc
            if ours:
                self.proc = None
        if not ours or not self.wanted:
            return  # stopped on purpose
        # it died on its own: a crash in the capture library on this PC
        if self.kind == "video" and "--gdi" not in self.extra:
            # Desktop Duplication crashed here: use the simpler GDI capture from now on (no strike)
            print(f"[mirror] the fast screen capture crashed on this PC (exit code 0x{code & 0xFFFFFFFF:08X}); "
                  "switching to compatibility capture")
            self.extra = ["--gdi"]
            time.sleep(0.5)
            if self.wanted:
                self._start()
            return
        now = time.time()
        self.crashes = [t for t in self.crashes if now - t < 120] + [now]
        print(f"[mirror] the {self.kind} helper stopped unexpectedly (exit code 0x{code & 0xFFFFFFFF:08X}); "
              f"{'restarting' if len(self.crashes) < 3 else 'turning it off for 5 minutes'}")
        if len(self.crashes) >= 3:
            self.disabled_until = now + 300
            self.error = (f"Mirror {self.kind} keeps crashing on this PC (0x{code & 0xFFFFFFFF:08X}); "
                          "it's off for 5 minutes. The rest of PerfLink works normally.")
            return
        time.sleep(1)
        if self.wanted:
            self._start()


class Mirror:
    def __init__(self) -> None:
        self.loop: asyncio.AbstractEventLoop | None = None
        self.viewers: dict = {}  # ws -> Viewer
        self.monitors: list[dict] = []
        self.error: str | None = None
        self.video = _Helper("video", self._on_message)
        self.audio = _Helper("audio", self._on_message)
        self.video.on_start = self._configure_video

    # ------------------------------------------------------------------ phone commands
    def command(self, ws, msg: dict) -> None:
        if msg.get("cmd") == "mirror_ack":
            v = self.viewers.get(ws)
            if v:
                v.in_flight = max(0, v.in_flight - 1)
                self.video.send({"ready": 1})
            return
        if not msg.get("on"):
            self.viewers.pop(ws, None)
        else:
            v = self.viewers.get(ws) or Viewer(ws)
            v.monitor = int(msg.get("monitor", v.monitor) or 0)
            v.audio = bool(msg.get("audio", v.audio))
            v.width = max(320, min(int(msg.get("width", v.width) or 1280), 1920))
            v.in_flight = 0
            self.viewers[ws] = v
        self._sync()

    def drop(self, ws) -> None:
        if self.viewers.pop(ws, None) is not None:
            self._sync()

    def _sync(self) -> None:
        """Run the helpers only while some phone needs them."""
        viewers = list(self.viewers.values())
        self.video.want(bool(viewers))
        self.audio.want(any(v.audio for v in viewers))
        self._configure_video()

    def _configure_video(self) -> None:
        viewers = list(self.viewers.values())
        if not viewers:
            return
        self.video.send({"monitors": sorted({v.monitor for v in viewers}), "width": max(v.width for v in viewers),
                         "ready": sum(max(0, MAX_IN_FLIGHT - v.in_flight) for v in viewers), "refresh": 1})

    def to_desktop(self, x: float, y: float) -> tuple[int, int] | None:
        """A spot on the phone's picture (0..1 across and down) -> desktop pixels on the monitor shown
        (the main monitor)."""
        mon = next((m for m in self.monitors if not m.get("off")), None)
        r = (mon or {}).get("rect") or next((r for r in _monitor_rects() if r["primary"]), None)
        if not r:
            return None
        x, y = min(max(float(x), 0.0), 0.9999), min(max(float(y), 0.0), 0.9999)
        return r["x"] + int(x * r["w"]), r["y"] + int(y * r["h"])

    def snapshot(self) -> dict:
        errors = [e for e in (self.error, self.video.error, self.audio.error) if e]
        return {"monitors": self.monitors, "error": "; ".join(errors) or None, "streaming": len(self.viewers)}

    # ------------------------------------------------------------------ from the helpers
    def _on_message(self, kind: bytes, payload: bytes) -> None:
        if kind == b"V":
            idx, jpeg = payload[0], payload[1:]
            for v in list(self.viewers.values()):
                if v.in_flight < MAX_IN_FLIGHT and (v.monitor == idx or not any(m["index"] == v.monitor for m in self.monitors)):
                    v.in_flight += 1
                    self._send(v.ws, b"\x01" + jpeg)
        elif kind == b"A":
            for v in list(self.viewers.values()):
                if v.audio:
                    self._send(v.ws, payload, audio=True)
        elif kind == b"M":
            try:
                self.monitors = json.loads(payload)
            except ValueError:
                pass
        elif kind == b"E":
            self.error = payload.decode("utf-8", "replace") or None

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


# ============================================================================ helper processes
_out_lock = threading.Lock()
_steps_done: set[str] = set()


def _step(name: str) -> None:
    """Note each step in PerfLink's log the first time it happens: if a native library crashes
    the helper, the last note shows where."""
    if name not in _steps_done:
        _steps_done.add(name)
        print(f"step: {name}", file=sys.stderr, flush=True)


def _emit(kind: bytes, payload: bytes) -> None:
    with _out_lock:
        sys.stdout.buffer.write(kind + struct.pack("<I", len(payload)) + payload)
        sys.stdout.buffer.flush()


def _watch_parent(on_line=None) -> None:
    """Read commands from PerfLink; when PerfLink goes away (stdin closes), quit."""
    def run():
        for line in sys.stdin.buffer:
            if on_line:
                try:
                    on_line(json.loads(line))
                except ValueError:
                    pass
        os._exit(0)
    threading.Thread(target=run, daemon=True).start()


class _VideoHelper:
    def __init__(self, gdi_only: bool = False) -> None:
        self.gdi_only = gdi_only           # compatibility mode: GDI capture only, no Desktop Duplication
        self.want = {"monitors": [0], "width": 1280}
        self.credits = 0
        self.refresh = False
        self.monitors: list[dict] = []
        self._seen: dict[str, dict] = {}  # every monitor seen, by Windows name (\\.\DISPLAY1…)
        self._last_pointer = None
        self._wake = threading.Event()

    def command(self, msg: dict) -> None:
        if "monitors" in msg:
            self.want = {"monitors": [int(m) for m in msg["monitors"]] or [0], "width": int(msg.get("width", 1280))}
        if msg.get("ready"):
            self.credits = min(MAX_IN_FLIGHT * 4, self.credits + int(msg["ready"]))
        if msg.get("refresh"):
            self.refresh = True
        self._wake.set()

    def list_monitors(self, refresh: bool = False) -> None:
        if self.gdi_only:  # no dxcam: Windows' own monitor list, the main one first
            rects = sorted(_monitor_rects(), key=lambda r: not r["primary"])
            mons = []
            for i, r in enumerate(rects):
                mons.append({"index": i, "id": f"{r['x']},{r['y']}", "w": r["w"], "h": r["h"], "primary": r["primary"],
                             "rect": r, "name": f"Monitor {i + 1}" + (" (main)" if r["primary"] else "") + f" · {r['w']}×{r['h']}"})
            self.monitors = mons
            _emit(b"M", json.dumps(mons).encode())
            return
        _step("list monitors (dxcam)")
        import dxcam  # noqa: PLC0415
        if refresh:  # monitors changed (one switched off / on): make dxcam look again
            factory = dxcam.__dict__.get("__factory")
            if factory is not None:
                try:
                    factory.clean_up()
                except Exception:  # noqa: BLE001
                    pass
                factory.__init__()
        rects = _monitor_rects()
        mons = []
        for line in dxcam.output_info().strip().splitlines():
            # "Device[0] Output[1]: Res:(1366, 768) Rot:0 Primary:False"
            try:
                dev = int(line.split("Device[")[1].split("]")[0])
                out = int(line.split("Output[")[1].split("]")[0])
                w, h = (int(x) for x in line.split("Res:(")[1].split(")")[0].split(","))
                primary = "Primary:True" in line
            except (IndexError, ValueError):
                continue
            mons.append({"index": len(mons), "device": dev, "output": out, "w": w, "h": h, "primary": primary})
        factory = dxcam.__dict__.get("__factory")
        for m in mons:
            try:
                out = factory.outputs[m["device"]][m["output"]]
                m["id"] = out.devicename
                meta = factory.output_metadata.get(out.devicename) or []
                model = meta[2][0][1] if len(meta) > 2 and meta[2] else ""
                m["model"] = "" if "generic" in model.lower() else model.replace("(Analog)", "").replace("(Digital)", "").strip()
            except Exception:  # noqa: BLE001
                m["id"], m["model"] = f"{m['device']}:{m['output']}", ""
        for m in sorted(mons, key=lambda m: m["id"]):
            if m["id"] not in self._seen:
                self._seen[m["id"]] = {"num": len(self._seen) + 1}
        mons.sort(key=lambda m: (not m["primary"], self._seen[m["id"]]["num"]))  # the main monitor first
        used = set()
        for i, m in enumerate(mons):
            m["index"] = i
            label = f"Monitor {self._seen[m['id']]['num']}" + (f" · {m['model']}" if m["model"] else "")
            m["name"] = label + (" (main)" if m["primary"] else "") + f" · {m['w']}×{m['h']}"
            r = next((k for k, r in enumerate(rects) if k not in used and r["w"] == m["w"] and r["h"] == m["h"]
                      and r["primary"] == m["primary"]), None)
            if r is not None:
                used.add(r)
                m["rect"] = rects[r]
        self.monitors = mons
        _emit(b"M", json.dumps(mons).encode())

    def run(self) -> None:
        """Desktop Duplication (fast) with two safety nets, so the phone never freezes:
        - the monitor layout is checked every frame: when a monitor is switched off (many DisplayPort
          monitors then vanish from Windows) or on, everything is rebuilt for the monitors left;
        - if Desktop Duplication stops delivering while the picture is still changing (a monitor
          that's off but still listed), it switches to GDI capture (mss) until Duplication works again."""
        _step(f"start ({'compatibility GDI capture' if self.gdi_only else 'Desktop Duplication'})")
        import numpy as np  # noqa: PLC0415
        if not self.gdi_only:
            try:
                import comtypes  # noqa: PLC0415
                comtypes.CoInitializeEx(comtypes.COINIT_MULTITHREADED)
            except Exception:  # noqa: BLE001
                pass
        _watch_parent(self.command)
        cams: dict[int, object] = {}
        last: dict[int, object] = {}
        last_sent: dict[int, float] = {}
        last_fresh: dict[int, float] = {}
        gdi: dict[int, bool] = {}
        gdi_probe: dict[int, float] = {}
        layout = None
        grabber = None

        def release_all():
            for c in cams.values():
                try:
                    c.release()
                except Exception:  # noqa: BLE001
                    pass
            cams.clear(); last.clear(); last_fresh.clear(); gdi.clear()

        def gdi_grab(mon):
            nonlocal grabber
            r = mon.get("rect")
            if not r:
                return None
            if grabber is None:
                _step("GDI capture (mss)")
                import mss  # noqa: PLC0415
                grabber = mss.MSS() if hasattr(mss, "MSS") else mss.mss()
            shot = grabber.grab({"left": r["x"], "top": r["y"], "width": r["w"], "height": r["h"]})
            return np.frombuffer(shot.bgra, np.uint8).reshape(shot.height, shot.width, 4)[:, :, 2::-1]

        def same(a, b) -> bool:
            return a is not None and b is not None and a.shape == b.shape and np.array_equal(a[::16, ::16], b[::16, ::16])

        while True:
            start = time.time()
            try:
                rects = _monitor_rects()
                now_layout = tuple((r["x"], r["y"], r["w"], r["h"], r["primary"]) for r in rects)
                if now_layout != layout or not self.monitors:
                    if layout is not None:
                        print(f"monitors changed: {len(rects)} now", file=sys.stderr, flush=True)
                    release_all()
                    self.list_monitors(refresh=layout is not None)
                    layout = now_layout
                for idx in list(self.want["monitors"]):
                    mon = next((m for m in self.monitors if m["index"] == idx), None) or (self.monitors or [None])[0]
                    if mon is None:
                        continue
                    key, now = mon["index"], time.time()
                    frame, fresh = None, False
                    if self.gdi_only:
                        frame = gdi_grab(mon)
                        fresh = frame is not None and not same(frame, last.get(key))
                    elif not gdi.get(key):
                        cam = cams.get(key)
                        if cam is None:
                            _step("create Desktop Duplication camera")
                            import dxcam  # noqa: PLC0415
                            cam = dxcam.create(device_idx=mon["device"], output_idx=mon["output"], output_color="RGB")
                            cams[key] = cam
                            last_fresh[key] = now
                        _step("first Desktop Duplication grab")
                        frame = cam.grab()
                        fresh = frame is not None
                        if fresh:
                            last_fresh[key] = now
                        elif now - last_fresh.get(key, now) > 1.5 and now - gdi_probe.get(key, 0) > 1.5:
                            gdi_probe[key] = now
                            probe = gdi_grab(mon)
                            if probe is not None and not same(probe, last.get(key)):
                                print(f"{mon['name']}: Desktop Duplication stopped, using GDI capture", file=sys.stderr, flush=True)
                                gdi[key] = True
                                frame, fresh = probe, True
                    else:
                        frame = gdi_grab(mon)
                        fresh = frame is not None and not same(frame, last.get(key))
                        if now - gdi_probe.get(key, 0) > 3:
                            gdi_probe[key] = now
                            cam = cams.get(key)
                            try:
                                if cam is not None and cam.grab() is not None:
                                    print(f"{mon['name']}: Desktop Duplication is back", file=sys.stderr, flush=True)
                                    gdi[key] = False
                                    last_fresh[key] = now
                            except Exception:  # noqa: BLE001
                                pass
                    if fresh:
                        last[key] = frame
                    elif key not in last:
                        continue
                    pointer = _cursor_pos()
                    moved = pointer != self._last_pointer
                    if not fresh and not self.refresh and now - last_sent.get(key, 0) < 1.0 and not moved:
                        continue
                    if self.credits <= 0:  # every phone still has two frames on the way: skip this one
                        continue
                    self.credits -= 1
                    self.refresh = False
                    self._last_pointer = pointer
                    _step("encode JPEG")
                    jpeg = self.encode(last[key], self.want["width"], mon, pointer)
                    _step("first frame sent")
                    last_sent[key] = now
                    _emit(b"V", bytes([idx & 0xFF]) + jpeg)
                _emit(b"E", b"")
            except Exception as e:  # noqa: BLE001  (monitor gone mid-capture, resolution change…)
                print(f"capture error, starting over: {e}", file=sys.stderr, flush=True)
                _emit(b"E", f"Mirror: {e}".encode())
                release_all()
                layout = None
                self.monitors = []
                time.sleep(0.5)
            time.sleep(max(0.0, 1 / FPS - (time.time() - start)))

    @staticmethod
    def encode(frame, width: int, mon: dict, pointer) -> bytes:
        from PIL import Image, ImageDraw  # noqa: PLC0415
        img = Image.fromarray(frame)
        w = min(width, img.width)
        if w < img.width:
            img = img.resize((w, round(img.height * w / img.width)), Image.BILINEAR)
        rect = mon.get("rect")  # draw the mouse pointer (Desktop Duplication leaves it out)
        if rect and pointer and rect["x"] <= pointer[0] < rect["x"] + rect["w"] and rect["y"] <= pointer[1] < rect["y"] + rect["h"]:
            s = img.width / rect["w"]
            x, y = (pointer[0] - rect["x"]) * s, (pointer[1] - rect["y"]) * s
            k = max(10, img.width / 90)
            arrow = [(x, y), (x, y + k * 1.5), (x + k * 0.42, y + k * 1.1), (x + k * 0.75, y + k * 1.75),
                     (x + k * 0.95, y + k * 1.65), (x + k * 0.62, y + k * 1.0), (x + k * 1.1, y + k * 1.0)]
            ImageDraw.Draw(img).polygon(arrow, fill=(255, 255, 255), outline=(0, 0, 0))
        b = io.BytesIO()
        img.save(b, "JPEG", quality=JPEG_QUALITY)
        return b.getvalue()


def _audio_helper() -> None:
    """Records what the PC plays and sends it on; follows default-device changes."""
    _step("start sound capture")
    import pyaudiowpatch as pyaudio  # noqa: PLC0415
    _watch_parent()
    while True:
        p = stream = None
        try:
            p = pyaudio.PyAudio()
            dev = p.get_default_wasapi_loopback()
            rate, ch = int(dev["defaultSampleRate"]), max(1, min(2, int(dev["maxInputChannels"])))
            head = b"\x02" + struct.pack("<IB", rate, ch)

            def cb(data, frames, info, status):
                _emit(b"A", head + data)
                return None, pyaudio.paContinue

            stream = p.open(format=pyaudio.paInt16, channels=ch, rate=rate, input=True,
                            input_device_index=dev["index"], frames_per_buffer=rate // 50, stream_callback=cb)
            stream.start_stream()
            _step(f"sound capture running ({dev['name']})")
            name = dev["name"]
            _emit(b"E", b"")
            while stream.is_active():  # until the default output device changes
                time.sleep(1)
                try:
                    if p.get_default_wasapi_loopback()["name"] != name:
                        break
                except Exception:  # noqa: BLE001
                    break
        except Exception as e:  # noqa: BLE001  (no output device, device unplugged…)
            _emit(b"E", f"Mirror sound: {e}".encode())
            time.sleep(2)
        finally:
            try:
                if stream:
                    stream.stop_stream(); stream.close()
                if p:
                    p.terminate()
            except Exception:  # noqa: BLE001
                pass


if __name__ == "__main__" and len(sys.argv) >= 3 and sys.argv[1] == "--helper":
    sys.coinit_flags = 0  # multithreaded COM, like PerfLink
    import faulthandler
    faulthandler.enable(sys.stderr)  # a Python-level trace in the log for crashes that allow one
    if sys.argv[2] == "video":
        _VideoHelper(gdi_only="--gdi" in sys.argv).run()
    elif sys.argv[2] == "audio":
        _audio_helper()
