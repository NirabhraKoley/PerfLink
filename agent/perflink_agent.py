"""
PerfLink PC agent
-----------------
Collects live PC performance stats and streams them to the PerfLink Android app.

  * FPS / frame time / 1% low  -> PresentMon (ETW), for the foreground game/app
  * GPU load / power / temp / clocks / VRAM / fan -> LibreHardwareMonitorLib
  * CPU load / clock / temp (temp needs admin), RAM -> psutil + LibreHardwareMonitorLib
  * Network: download/upload speed, totals, ping + jitter + packet loss

Transport:
  * WebSocket  ws://<pc-ip>:47800  -> JSON snapshot pushed every INTERVAL seconds
  * UDP 47801 discovery: phone broadcasts "PERFLINK_DISCOVER", agent replies with JSON

Run:  python perflink_agent.py [--interval 0.5] [--ping-host 1.1.1.1]
"""
from __future__ import annotations

import argparse
import asyncio
import collections
import ctypes
import ctypes.wintypes as wt
import json
import os
import re
import socket
import statistics
import subprocess
import sys
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
TRAY_MODE = "--tray" in sys.argv
# Work from outside the install folder: every helper PerfLink starts (adb's background server,
# PowerShell checks, PresentMon) inherits this folder, and a program running "in" a folder stops
# Windows from renaming or deleting it, which blocked updates. All paths here are absolute.
if HERE not in sys.path:
    sys.path.insert(0, HERE)
try:
    os.chdir(os.path.expanduser("~"))
except OSError:
    pass

# Background (tray) mode runs under pythonw.exe, which has no console: send output to a log file.
if TRAY_MODE or sys.stdout is None:
    _log = os.path.join(HERE, "perflink.log")
    try:
        if os.path.exists(_log) and os.path.getsize(_log) > 1_000_000:  # keep the log small
            os.replace(_log, _log + ".old")
    except OSError:
        pass
    sys.stdout = sys.stderr = open(_log, "a", encoding="utf-8", buffering=1)
    print(f"\n===== PerfLink started {time.strftime('%Y-%m-%d %H:%M:%S')} =====")
    try:  # show "PerfLink" (not "Python") as the sender of tray notifications
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("PerfLink")
    except Exception:  # noqa: BLE001
        pass

import psutil
import websockets

from ai_status import AiStatus
from launcher import Launcher
from obs_control import Obs, open_obs
from processes import Processes
from bluetooth import BluetoothDevices
from wifi import WifiStatus
from whatsapp import WhatsAppNotifications
from usb_devices import UsbDevices
from usb_tunnel import UsbTunnel
from extras import Media, Volume, Weather, load_config, save_config

HERE = os.path.dirname(os.path.abspath(__file__))
BIN = os.path.join(HERE, "bin")
WS_PORT = 47800
DISCOVERY_PORT = 47801
VERSION = "1.4"

# Processes that present frames but are never "the game"
IGNORED_APPS = {
    "dwm.exe", "<unknown>", "explorer.exe", "searchhost.exe", "startmenuexperiencehost.exe",
    "textinputhost.exe", "shellexperiencehost.exe", "applicationframehost.exe",
    "radeonsoftware.exe", "amdrsserv.exe", "nvidia overlay.exe", "gamebar.exe",
    "presentmon.exe", "python.exe", "pythonw.exe",
}


# ----------------------------------------------------------------------------- FPS
class FrameTracker:
    """Runs PresentMon and keeps a rolling window of frame times per process."""

    WINDOW = 2.0  # seconds of frames kept for stats

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.frames: dict[int, collections.deque] = {}  # pid -> deque[(t, ms)]
        self.names: dict[int, str] = {}
        self.available = False
        self.error: str | None = None
        self.proc: subprocess.Popen | None = None

    def start(self) -> None:
        exe = os.path.join(BIN, "PresentMon.exe")
        if not os.path.exists(exe):
            self.error = "PresentMon.exe not found in agent/bin"
            return
        threading.Thread(target=self._run, args=(exe,), daemon=True).start()

    def _run(self, exe: str) -> None:
        args = [exe, "--output_stdout", "--no_console_stats", "--v1_metrics",
                "--stop_existing_session", "--session_name", "PerfLink",
                "--no_track_input"]
        while True:
            try:
                self.proc = subprocess.Popen(
                    args, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                    creationflags=subprocess.CREATE_NO_WINDOW, text=True, bufsize=1)
                header = None
                for line in self.proc.stdout:
                    parts = line.rstrip("\n").split(",")
                    if header is None:
                        if parts and parts[0] == "Application":
                            header = {n: i for i, n in enumerate(parts)}
                            i_app, i_pid = header["Application"], header["ProcessID"]
                            i_ms = header["msBetweenPresents"]
                            i_drop = header.get("Dropped")
                            self.available = True
                        continue
                    try:
                        if i_drop is not None and parts[i_drop] == "1":
                            continue
                        pid = int(parts[i_pid])
                        ms = float(parts[i_ms])
                    except (ValueError, IndexError):
                        continue
                    if ms <= 0 or ms > 1000:
                        continue
                    now = time.monotonic()
                    with self.lock:
                        dq = self.frames.get(pid)
                        if dq is None:
                            dq = self.frames[pid] = collections.deque()
                        dq.append((now, ms))
                        self.names[pid] = parts[i_app]
            except Exception as e:  # noqa: BLE001
                self.error = f"PresentMon: {e}"
            self.available = False
            time.sleep(3)  # restart PresentMon if it dies

    def _prune(self, now: float) -> None:
        cutoff = now - self.WINDOW
        for pid in list(self.frames):
            dq = self.frames[pid]
            while dq and dq[0][0] < cutoff:
                dq.popleft()
            if not dq:
                del self.frames[pid]
                self.names.pop(pid, None)

    def snapshot(self, fg_pid: int | None) -> dict:
        now = time.monotonic()
        with self.lock:
            self._prune(now)
            candidates = {p: list(d) for p, d in self.frames.items()
                          if self.names.get(p, "").lower() not in IGNORED_APPS}
            names = dict(self.names)
        if not candidates:
            return {"app": None, "fps": None, "frame_time_ms": None,
                    "low1_fps": None, "low01_fps": None, "frame_times": []}
        # Prefer the foreground window's process; else the busiest presenter
        pid = fg_pid if fg_pid in candidates else max(candidates, key=lambda p: len(candidates[p]))
        frames = candidates[pid]
        last1 = [ms for t, ms in frames if t >= now - 1.0] or [ms for _, ms in frames]
        avg_ms = statistics.fmean(last1)
        all_ms = sorted(ms for _, ms in frames)
        p99 = all_ms[min(len(all_ms) - 1, int(len(all_ms) * 0.99))]
        p999 = all_ms[min(len(all_ms) - 1, int(len(all_ms) * 0.999))]
        return {
            "app": names.get(pid, "?"),
            "fps": round(1000.0 / avg_ms, 1),
            "frame_time_ms": round(avg_ms, 2),
            "low1_fps": round(1000.0 / p99, 1),      # "99th% FPS" in AMD's overlay
            "low01_fps": round(1000.0 / p999, 1),
            "frame_times": [round(ms, 2) for ms in last1[-120:]],
        }


def foreground_pid() -> int | None:
    try:
        hwnd = ctypes.windll.user32.GetForegroundWindow()
        pid = wt.DWORD()
        ctypes.windll.user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        return pid.value or None
    except Exception:  # noqa: BLE001
        return None


# ----------------------------------------------------------------------------- Hardware sensors
class Sensors:
    """GPU + CPU sensors through LibreHardwareMonitorLib (pythonnet)."""

    def __init__(self) -> None:
        self.ok = False
        self.error: str | None = None
        self.computer = None
        try:
            import clr  # type: ignore
            sys.path.append(BIN)
            from System import AppDomain, ResolveEventHandler  # type: ignore
            from System.Reflection import Assembly, AssemblyName  # type: ignore

            def resolve(_sender, args):
                p = os.path.join(BIN, AssemblyName(args.Name).Name + ".dll")
                return Assembly.LoadFrom(p) if os.path.exists(p) else None

            AppDomain.CurrentDomain.AssemblyResolve += ResolveEventHandler(resolve)
            clr.AddReference("LibreHardwareMonitorLib")
            from LibreHardwareMonitor.Hardware import Computer  # type: ignore
            c = Computer()
            c.IsGpuEnabled = True
            c.IsCpuEnabled = True
            c.Open()
            self.computer = c
            self.ok = True
        except Exception as e:  # noqa: BLE001
            self.error = f"LibreHardwareMonitor: {e}"

    @staticmethod
    def _val(s):
        v = s.Value
        return None if v is None else round(float(v), 1)

    def read(self) -> tuple[dict, dict]:
        gpu = {"name": None, "util": None, "power_w": None, "temp_c": None, "hotspot_c": None,
               "core_clock_mhz": None, "mem_clock_mhz": None, "vram_used_mb": None,
               "vram_total_mb": None, "fan_rpm": None, "voltage_v": None}
        cpu = {"temp_c": None, "power_w": None, "clock_mhz": None}
        if not self.ok:
            return gpu, cpu
        best_gpu = None
        for hw in self.computer.Hardware:
            hw.Update()
            htype = str(hw.HardwareType)
            if htype.startswith("Gpu"):
                # Prefer the discrete GPU (Amd/Nvidia) over the iGPU
                rank = 0 if htype == "GpuIntel" else 1
                if best_gpu is None or rank > best_gpu[0]:
                    best_gpu = (rank, hw)
            elif htype == "Cpu":
                clocks = []
                for s in hw.Sensors:
                    st, n, v = str(s.SensorType), s.Name, self._val(s)
                    if v is None:
                        continue
                    if st == "Temperature" and n in ("CPU Package", "Core (Tctl/Tdie)", "Core Max"):
                        cpu["temp_c"] = cpu["temp_c"] or v
                    elif st == "Power" and n == "CPU Package" and v > 0:
                        cpu["power_w"] = v
                    elif st == "Clock" and n.startswith("CPU Core") and v > 0:
                        clocks.append(v)
                if clocks:
                    cpu["clock_mhz"] = round(max(clocks))
        if best_gpu:
            hw = best_gpu[1]
            gpu["name"] = hw.Name
            for s in hw.Sensors:
                st, n, v = str(s.SensorType), s.Name, self._val(s)
                if v is None:
                    continue
                if st == "Load" and n == "GPU Core":
                    gpu["util"] = v
                elif st == "Load" and n == "D3D 3D" and gpu["util"] is None:
                    gpu["util"] = v
                elif st == "Power" and n in ("GPU Package", "GPU Power", "GPU Core"):
                    gpu["power_w"] = gpu["power_w"] or v
                elif st == "Temperature" and n == "GPU Core":
                    gpu["temp_c"] = v
                elif st == "Temperature" and n == "GPU Hot Spot":
                    gpu["hotspot_c"] = v
                elif st == "Clock" and n == "GPU Core":
                    gpu["core_clock_mhz"] = round(v)
                elif st == "Clock" and n == "GPU Memory":
                    gpu["mem_clock_mhz"] = round(v)
                elif st == "SmallData" and n == "GPU Memory Used":
                    gpu["vram_used_mb"] = round(v)
                elif st == "SmallData" and n == "GPU Memory Total":
                    gpu["vram_total_mb"] = round(v)
                elif st == "Fan" and n == "GPU Fan":
                    gpu["fan_rpm"] = round(v)
                elif st == "Voltage" and n == "GPU Core":
                    gpu["voltage_v"] = round(v, 3)
        return gpu, cpu


# ----------------------------------------------------------------------------- Network
class Network:
    def __init__(self, ping_host: str) -> None:
        self.ping_host = ping_host
        self.prev = psutil.net_io_counters()
        self.prev_t = time.monotonic()
        self.pings: collections.deque = collections.deque(maxlen=20)  # ms or None (lost)
        self.iface, self.local_ip = self._primary_iface()
        threading.Thread(target=self._ping_loop, daemon=True).start()

    @staticmethod
    def _primary_iface() -> tuple[str | None, str | None]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
        except OSError:
            return None, None
        for name, addrs in psutil.net_if_addrs().items():
            if any(a.address == ip for a in addrs):
                return name, ip
        return None, ip

    def _ping_loop(self) -> None:
        rx = re.compile(r"[=<](\d+)\s*ms", re.I)
        while True:
            try:
                out = subprocess.run(["ping", "-n", "1", "-w", "1000", self.ping_host],
                                     capture_output=True, text=True, timeout=3,
                                     creationflags=subprocess.CREATE_NO_WINDOW).stdout
                m = rx.search(out)
                self.pings.append(int(m.group(1)) if m else None)
            except Exception:  # noqa: BLE001
                self.pings.append(None)
            time.sleep(1)

    def read(self) -> dict:
        now = time.monotonic()
        cur = psutil.net_io_counters()
        dt = max(now - self.prev_t, 1e-3)
        down = (cur.bytes_recv - self.prev.bytes_recv) / dt
        up = (cur.bytes_sent - self.prev.bytes_sent) / dt
        self.prev, self.prev_t = cur, now
        samples = list(self.pings)
        ok = [p for p in samples if p is not None]
        link = None
        if self.iface:
            st = psutil.net_if_stats().get(self.iface)
            link = st.speed if st else None
        return {
            "download_bps": round(down * 8),
            "upload_bps": round(up * 8),
            "total_recv_mb": round(cur.bytes_recv / 1e6, 1),
            "total_sent_mb": round(cur.bytes_sent / 1e6, 1),
            "ping_ms": ok[-1] if ok and samples[-1] is not None else None,
            "ping_avg_ms": round(statistics.fmean(ok), 1) if ok else None,
            "jitter_ms": round(statistics.fmean(abs(a - b) for a, b in zip(ok, ok[1:])), 1)
            if len(ok) > 1 else None,
            "packet_loss_pct": round(100 * (len(samples) - len(ok)) / len(samples), 1) if samples else None,
            "ping_host": self.ping_host,
            "interface": self.iface,
            "local_ip": self.local_ip,
            "link_mbps": link,
            "connections": len(psutil.net_connections(kind="inet")) if hasattr(psutil, "net_connections") else None,
        }


# ----------------------------------------------------------------------------- Snapshot + server
class Agent:
    def __init__(self, interval: float, ping_host: str, city: str | None) -> None:
        self.interval = interval
        self.frames = FrameTracker()
        self.frames.start()
        self.sensors = Sensors()
        self.net = Network(ping_host)
        psutil.cpu_percent(None)
        self.clients: set = set()
        self.latest: dict = {}
        self.config = load_config()
        if city:
            self.config["city"] = city
            save_config(self.config)
        self.media = Media()
        self.media.on_art = self._broadcast_art
        self.volume = Volume()
        self.weather = Weather(self.config.get("city", "Kolkata"))
        self.ai = AiStatus()
        self.procs = Processes()  # after Sensors: reuses its pythonnet setup
        self.launcher = Launcher(self.volume)
        self.obs = Obs()
        self.usb = UsbDevices()  # after Sensors: reuses its pythonnet setup
        self.bluetooth = BluetoothDevices()
        self.wifi = WifiStatus()
        self.whatsapp = WhatsAppNotifications()
        self.obs_viewers: set = set()  # phones currently showing the OBS page (get previews)
        self._last_preview: str | None = None

    def _art_msg(self) -> str:
        return json.dumps({"type": "art", "id": self.media.art_id, "data": self.media.art_b64})

    def _broadcast_art(self, _id, _b64) -> None:
        if self.clients:
            websockets.broadcast(self.clients, self._art_msg())

    async def handle_command(self, msg: dict) -> None:
        cmd = msg.get("cmd")
        if cmd == "media":
            await self.media.command(msg.get("action", ""), msg.get("value"))
        elif cmd == "volume":
            self.volume.set_level(float(msg["value"]))
        elif cmd == "mute":
            self.volume.toggle_mute()
        elif cmd == "set_city" and str(msg.get("city", "")).strip():
            self.weather.set_city(str(msg["city"]))
            self.config["city"] = self.weather.city
            save_config(self.config)
        else:
            return
        await self.push()

    def collect(self) -> dict:
        gpu, cpu_extra = self.sensors.read()
        vm = psutil.virtual_memory()
        freq = psutil.cpu_freq()
        cpu = {
            "util": psutil.cpu_percent(None),
            "per_core": psutil.cpu_percent(None, percpu=True),
            "clock_mhz": cpu_extra["clock_mhz"] or (round(freq.current) if freq else None),
            "temp_c": cpu_extra["temp_c"],
            "power_w": cpu_extra["power_w"],
            "name": CPU_NAME,
        }
        errors = [e for e in (self.frames.error, self.sensors.error, self.media.error,
                              self.volume.error, self.weather.error) if e]
        return {
            "type": "stats",
            "ts": time.time(),
            "host": socket.gethostname(),
            "frames": self.frames.snapshot(foreground_pid()),
            "gpu": gpu,
            "cpu": cpu,
            "ram": {"used_mb": round(vm.used / 2**20), "total_mb": round(vm.total / 2**20),
                    "util": vm.percent},
            "net": self.net.read(),
            "errors": errors,
        }

    def _with_extras(self, snap: dict) -> dict:
        snap = dict(snap)
        snap["media"] = dict(self.media.state)
        snap["volume"] = self.volume.read()  # COM: main thread only
        snap["weather"] = dict(self.weather.state)
        snap["ai"] = self.ai.snapshot()
        snap["processes"] = self.procs.snapshot()
        snap["launcher"] = self.launcher.info()
        snap["obs"] = self.obs.snapshot()
        snap["usb"] = self.usb.snapshot()
        snap["bluetooth"] = self.bluetooth.snapshot()
        snap["wifi"] = self.wifi.snapshot()
        snap["whatsapp"] = self.whatsapp.snapshot()
        return snap

    async def push(self) -> None:
        """Re-send the latest snapshot right away (after a command) with fresh extras."""
        if self.latest and self.clients:
            await self.media.poll()
            self.latest = self._with_extras(self.latest)
            websockets.broadcast(self.clients, json.dumps(self.latest))

    async def producer(self) -> None:
        loop = asyncio.get_running_loop()
        while True:
            t0 = time.monotonic()
            await self.media.poll()
            snap = await loop.run_in_executor(None, self.collect)
            self.latest = self._with_extras(snap)
            new_icons = self.procs.take_new_icons()
            if self.clients:
                if new_icons:
                    websockets.broadcast(self.clients, json.dumps({"type": "icons", "items": new_icons}))
                websockets.broadcast(self.clients, json.dumps(self.latest))
            prev = self.obs.preview_b64
            if self.obs_viewers and prev and prev != self._last_preview:
                self._last_preview = prev
                websockets.broadcast(self.obs_viewers, json.dumps({"type": "obs_preview", "data": prev}))
            await asyncio.sleep(max(0.05, self.interval - (time.monotonic() - t0)))

    tray = None
    _last_connect_notice = 0.0

    def update_tray(self, connected_ip: str | None = None) -> None:
        """Refresh the tray status; pop a notice when the first phone connects (at most once a minute)."""
        if self.tray is None:
            return
        n = len(self.clients)
        ip = self.net.local_ip or "?"
        phones = "no phone connected" if n == 0 else ("1 phone connected" if n == 1 else f"{n} phones connected")
        self.tray.set_status(f"PC {ip} · {phones}", f"PerfLink · {phones}")
        if connected_ip and n == 1 and time.monotonic() - self._last_connect_notice > 60:
            self._last_connect_notice = time.monotonic()
            where = "over USB" if connected_ip.startswith("127.") else f"from {connected_ip}"
            self.tray.notify("PerfLink", f"Phone connected {where}")

    async def handler(self, ws) -> None:
        self.clients.add(ws)
        print(f"[+] phone connected: {ws.remote_address[0]}  ({len(self.clients)} client(s))")
        self.update_tray(connected_ip=ws.remote_address[0])
        try:
            icons = self.procs.all_icons()
            if icons:
                await ws.send(json.dumps({"type": "icons", "items": icons}))
            if self.latest:
                await ws.send(json.dumps(self.latest))
            if self.media.art_id:
                await ws.send(self._art_msg())
            async for raw in ws:
                if isinstance(raw, bytes):
                    continue  # the agent takes no binary messages
                try:
                    msg = json.loads(raw)
                    if msg.get("cmd") == "obs_view":
                        (self.obs_viewers.add if msg.get("on") else self.obs_viewers.discard)(ws)
                        self.obs.preview_wanted = bool(self.obs_viewers)
                        self._last_preview = None
                        continue
                    if msg.get("cmd") == "obs":
                        try:
                            await self.obs.command(str(msg.get("action", "")), msg)
                        except Exception as e:  # noqa: BLE001
                            await ws.send(json.dumps({"type": "action_result", "id": "obs", "ok": False,
                                                      "message": f"OBS: {e}"}))
                        await self.push()
                        continue
                    if msg.get("cmd") == "action":
                        action = str(msg.get("id", ""))
                        try:
                            text, ok = (open_obs() if action == "obs" else self.launcher.run(action)), True
                        except Exception as e:  # noqa: BLE001
                            text, ok = str(e), False
                        print(f"[>] action {action}: {text}")
                        await ws.send(json.dumps({"type": "action_result", "id": action, "ok": ok, "message": text}))
                        if action == "mute":
                            await self.push()
                        continue
                    await self.handle_command(msg)
                except Exception as e:  # noqa: BLE001
                    print(f"[!] command failed: {raw[:120]!r}: {e}")
        except websockets.ConnectionClosed:
            pass
        finally:
            self.clients.discard(ws)
            self.obs_viewers.discard(ws)
            self.obs.preview_wanted = bool(self.obs_viewers)
            print(f"[-] phone disconnected: {ws.remote_address[0]}")
            self.update_tray()


class Discovery(asyncio.DatagramProtocol):
    def connection_made(self, transport) -> None:
        self.transport = transport

    def datagram_received(self, data: bytes, addr) -> None:
        if data.strip() == b"PERFLINK_DISCOVER":
            reply = json.dumps({"type": "perflink", "name": socket.gethostname(),
                                "port": WS_PORT, "version": VERSION})
            self.transport.sendto(reply.encode(), addr)


def _cpu_name() -> str | None:
    try:
        import winreg
        k = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0")
        return winreg.QueryValueEx(k, "ProcessorNameString")[0].strip()
    except OSError:
        return None


CPU_NAME = _cpu_name()


async def main() -> None:
    global WS_PORT, DISCOVERY_PORT
    ap = argparse.ArgumentParser(description="PerfLink PC agent")
    ap.add_argument("--interval", type=float, default=0.5, help="seconds between updates")
    ap.add_argument("--ping-host", default="1.1.1.1", help="host used for ping/jitter/loss")
    ap.add_argument("--city", default=None, help="weather city (saved to config.json; default Kolkata)")
    ap.add_argument("--port", type=int, default=WS_PORT, help="WebSocket port (default 47800)")
    ap.add_argument("--discovery-port", type=int, default=DISCOVERY_PORT, help="UDP discovery port (default 47801)")
    ap.add_argument("--tray", action="store_true", help="run in the background with a system-tray icon (no console)")
    args = ap.parse_args()
    WS_PORT, DISCOVERY_PORT = args.port, args.discovery_port

    # Only one agent at a time: if PerfLink is already running, quietly leave it be.
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        if probe.connect_ex(("127.0.0.1", WS_PORT)) == 0:
            print(f"PerfLink is already running (port {WS_PORT} is in use); exiting.")
            return

    agent = Agent(args.interval, args.ping_host, args.city)
    if args.tray:
        from tray import Tray
        def _quit() -> None:
            try:  # also close the FPS helper, or it keeps running after PerfLink exits
                if agent.frames.proc:
                    agent.frames.proc.kill()
            except Exception:  # noqa: BLE001
                pass
            os._exit(0)

        agent.tray = Tray(on_quit=_quit)
        agent.update_tray()
    # USB: re-creates the adb tunnel on replug, and installs/updates the phone app from the latest release
    agent.tunnel = UsbTunnel(WS_PORT, repo=agent.config.get("repo"),
                             notify=lambda title, text: agent.tray.notify(title, text) if agent.tray else None,
                             usb_connected=lambda: any(c.remote_address[0] in ("127.0.0.1", "::1")
                                                       for c in list(agent.clients)))
    loop = asyncio.get_running_loop()
    obs_task = asyncio.create_task(agent.obs.run())  # noqa: F841  (kept alive for the agent's lifetime)
    await loop.create_datagram_endpoint(Discovery, local_addr=("0.0.0.0", DISCOVERY_PORT))
    async with websockets.serve(agent.handler, "0.0.0.0", WS_PORT, ping_interval=10):
        ip = agent.net.local_ip or "?"
        print(f"PerfLink agent {VERSION} running")
        print(f"  WebSocket : ws://{ip}:{WS_PORT}")
        print(f"  Discovery : UDP {DISCOVERY_PORT}")
        print(f"  GPU sensors: {'ok' if agent.sensors.ok else agent.sensors.error}")
        is_admin = bool(ctypes.windll.shell32.IsUserAnAdmin())
        if not is_admin:
            print("  note: run as Administrator to also get CPU temperature/power")
        print("Waiting for the phone... (Ctrl+C to quit)")
        await agent.producer()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
