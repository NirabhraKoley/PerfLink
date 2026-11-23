"""
USB devices plugged into the PC, for the PerfLink "USB" page.

* Counts real devices (mouse, keyboard, headset, phone, webcam, drive…) separately from hubs,
  and ignores the per-function sub-devices Windows creates for composite devices.
* Names come from the device itself ("Razer DeathAdder Essential"), with the time it was plugged in.
* Keeps a plug/unplug feed.

A fast WMI query (in-process, via pythonnet) checks for changes every 2 s; the slower property
lookup (PowerShell Get-PnpDeviceProperty) only runs for newly seen devices.
"""
from __future__ import annotations

import collections
import json
import re
import subprocess
import threading
import time
from datetime import datetime

import psutil

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
PHYS_RE = re.compile(r"^USB\\VID_([0-9A-F]{4})&PID_([0-9A-F]{4})\\(?!.*&MI_)", re.I)


class UsbDevices:
    def __init__(self) -> None:
        self.state: dict = {"devices": [], "device_count": 0, "hub_count": 0, "drives": [], "error": None}
        self.events: collections.deque = collections.deque(maxlen=30)
        self._details: dict[str, dict] = {}  # instance id -> {desc, arrived, location}
        self._known: dict[str, str] | None = None  # instance id -> display name (for unplug events)
        self._lock = threading.Lock()
        self._searcher = None
        threading.Thread(target=self._loop, daemon=True).start()

    def snapshot(self) -> dict:
        with self._lock:
            s = dict(self.state)
            s["events"] = list(self.events)
            return s

    # ------------------------------------------------------------------ polling
    def _loop(self) -> None:
        while True:
            try:
                self._scan()
            except Exception as e:  # noqa: BLE001
                with self._lock:
                    self.state["error"] = f"USB: {e}"
            time.sleep(2)

    def _entities(self) -> list[tuple[str, str, str]]:
        """(DeviceID, Name, PNPClass) for USB/HID/USBSTOR devices, via WMI."""
        if self._searcher is None:
            import clr  # type: ignore
            clr.AddReference("System.Management")
            from System.Management import ManagementObjectSearcher  # type: ignore
            self._searcher = ManagementObjectSearcher(
                "SELECT DeviceID, Name, PNPClass FROM Win32_PnPEntity "
                "WHERE DeviceID LIKE 'USB%' OR DeviceID LIKE 'HID%' OR DeviceID LIKE 'USBSTOR%'")
        out = []
        for o in self._searcher.Get():
            out.append((str(o["DeviceID"] or ""), str(o["Name"] or ""), str(o["PNPClass"] or "")))
        return out

    def _fetch_details(self, ids: list[str]) -> None:
        """Product name reported by the device, plug-in time and port, for new devices only."""
        if not ids:
            return
        quoted = ",".join("'" + i.replace("'", "''") + "'" for i in ids)
        ps = (
            "$ErrorActionPreference='SilentlyContinue'; $r=@{}; foreach($id in @(" + quoted + ")){ "
            "$p = Get-PnpDeviceProperty -InstanceId $id -KeyName 'DEVPKEY_Device_BusReportedDeviceDesc',"
            "'DEVPKEY_Device_LastArrivalDate','DEVPKEY_Device_LocationInfo'; $h=@{}; "
            "foreach($x in $p){ $v=$x.Data; if($v -is [datetime]){ $v=$v.ToString('o') }; $h[$x.KeyName]=$v }; "
            "$r[$id]=$h }; $r | ConvertTo-Json -Compress -Depth 3")
        out = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True, text=True,
                             timeout=60, creationflags=NO_WINDOW).stdout.strip()
        data = json.loads(out) if out else {}
        for i in ids:
            h = data.get(i) or {}
            arrived = h.get("DEVPKEY_Device_LastArrivalDate")
            try:
                ts = datetime.fromisoformat(arrived).timestamp() if arrived else None
            except ValueError:
                ts = None
            self._details[i] = {"desc": (h.get("DEVPKEY_Device_BusReportedDeviceDesc") or "").strip() or None,
                                "arrived": ts, "location": h.get("DEVPKEY_Device_LocationInfo")}

    def _scan(self) -> None:
        ents = self._entities()
        physical = [(d, n, c) for d, n, c in ents if PHYS_RE.match(d)]
        new = [d for d, _, _ in physical if d not in self._details]
        self._fetch_details(new)

        devices = []
        for did, name, cls in physical:
            m = PHYS_RE.match(did)
            vid, pid = m.group(1).upper(), m.group(2).upper()
            key = f"VID_{vid}&PID_{pid}"
            # classes + names of this device's functions (interfaces, HID mouse/keyboard, etc.)
            related = [(n, c) for d, n, c in ents if key in d.upper() and d != did]
            det = self._details.get(did, {})
            display = det.get("desc") or name
            if display in ("USB Composite Device", "USB Input Device") and related:
                display = related[0][0]
            category = _category(display, cls, related)
            devices.append({
                "id": did, "name": display, "category": category, "hub": category == "Hub",
                "vid": vid, "pid": pid, "arrived": det.get("arrived"),
                "port": _port(det.get("location")),
                "functions": sorted({n for n, c in related if c not in ("USB",)})[:6],
            })
        order = {"Hub": 1}
        devices.sort(key=lambda x: (order.get(x["category"], 0), x["name"].lower()))

        # plug / unplug feed
        current = {d["id"]: d["name"] for d in devices}
        if self._known is not None:
            for did, nm in current.items():
                if did not in self._known:
                    self._event("connected", f"{nm} connected")
            for did, nm in self._known.items():
                if did not in current:
                    self._event("disconnected", f"{nm} disconnected")
                    self._details.pop(did, None)  # fresh arrival time next time it's plugged in
        self._known = current

        drives = []
        for p in psutil.disk_partitions(all=False):
            if "removable" in (p.opts or "").lower():
                try:
                    u = psutil.disk_usage(p.mountpoint)
                    drives.append({"letter": p.mountpoint.rstrip("\\"), "fs": p.fstype,
                                   "total_gb": round(u.total / 1e9, 1), "used_gb": round(u.used / 1e9, 1)})
                except OSError:
                    drives.append({"letter": p.mountpoint.rstrip("\\"), "fs": p.fstype, "total_gb": None, "used_gb": None})

        with self._lock:
            self.state = {"devices": devices, "device_count": sum(1 for d in devices if not d["hub"]),
                          "hub_count": sum(1 for d in devices if d["hub"]), "drives": drives, "error": None}

    def _event(self, kind: str, text: str) -> None:
        with self._lock:
            self.events.appendleft({"t": time.time(), "kind": kind, "text": text})


def _port(location: str | None) -> str | None:
    m = re.search(r"Port_#0*(\d+)\.Hub_#0*(\d+)", location or "")
    return f"Port {m.group(1)} · Hub {m.group(2)}" if m else None


def _category(name: str, cls: str, related: list[tuple[str, str]]) -> str:
    n = name.lower()
    classes = {c for _, c in related} | {cls}
    names = " ".join(x.lower() for x, _ in related)
    if "hub" in n and cls == "USB":
        return "Hub"
    if cls == "Bluetooth" or "bluetooth" in n:
        return "Bluetooth"
    if "WPD" in classes or "android" in n or "adb" in names or "iphone" in n or "apple mobile" in names:
        return "Phone"
    if "DiskDrive" in classes or "USBSTOR" in names or "mass storage" in names:
        return "Storage"
    if "Camera" in classes or "Image" in classes or "cam" in n:
        return "Camera"
    # the device's own name wins: many keyboards also expose a "mouse" function and vice versa
    if "keyboard" in n:
        return "Keyboard"
    if "mouse" in n or "deathadder" in n:
        return "Mouse"
    if "mouse" in names:
        return "Mouse"
    if "keyboard" in names:
        return "Keyboard"
    if "gamepad" in n or "controller" in n or "game controller" in names or "xinput" in names:
        return "Gamepad"
    if "AudioEndpoint" in classes or "MEDIA" in classes or "audio" in n or "headset" in n:
        return "Audio"
    if "Net" in classes or "wireless" in n or "wi-fi" in n or "wlan" in n:
        return "Network"
    if "Printer" in classes:
        return "Printer"
    return "Device"
