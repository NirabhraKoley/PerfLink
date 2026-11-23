"""
Bluetooth devices for the PerfLink "Bluetooth" page: whether Bluetooth is on, how many devices
are connected, and each paired device's name, type, connection state and battery level.
Reads the Windows Bluetooth APIs through bluetooth.ps1 every few seconds and keeps a
connect/disconnect feed.
"""
from __future__ import annotations

import collections
import json
import os
import subprocess
import threading
import time

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
SCRIPT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "bluetooth.ps1")

# Bluetooth "Class of Device" major classes
_MAJOR = {1: "Computer", 2: "Phone", 3: "Network", 4: "Audio", 5: "Peripheral", 6: "Imaging", 7: "Wearable",
          8: "Toy", 9: "Health"}


def _type(name: str, category, major) -> str:
    n = (name or "").lower()
    c = str(category or "").lower()
    if any(k in n for k in ("bud", "pod", "headphone", "headset", "earphone", "speaker", "sound", "audio", "wh-", "wf-")) \
            or "audio" in c:
        return "Audio"
    if "mouse" in n or "mouse" in c:
        return "Mouse"
    if "keyboard" in n or "keyboard" in c:
        return "Keyboard"
    if any(k in n for k in ("controller", "gamepad", "xbox", "dualsense", "dualshock", "joy-con")) or "gaming" in c:
        return "Gamepad"
    if any(k in n for k in ("watch", "band", "fit")) or "watch" in c:
        return "Watch"
    if "phone" in c or any(k in n for k in ("phone", "galaxy", "redmi", "pixel", "oneplus", "iphone")):
        return "Phone"
    try:
        major = int(major) if major is not None else None
    except (TypeError, ValueError):
        major = None
    m = _MAJOR.get(major)
    if m == "Peripheral":
        return "Input"
    return m or "Device"


class BluetoothDevices:
    def __init__(self, interval: float = 5.0) -> None:
        self.interval = interval
        self.state: dict = {"radio": None, "devices": [], "connected_count": 0, "paired_count": 0, "error": None}
        self.events: collections.deque = collections.deque(maxlen=30)
        self._known: dict[str, tuple[str, bool]] | None = None
        self._lock = threading.Lock()
        threading.Thread(target=self._loop, daemon=True).start()

    def snapshot(self) -> dict:
        with self._lock:
            s = dict(self.state)
            s["events"] = list(self.events)
            return s

    def _loop(self) -> None:
        while True:
            try:
                self._scan()
            except Exception as e:  # noqa: BLE001
                with self._lock:
                    self.state["error"] = f"Bluetooth: {e}"
            time.sleep(self.interval)

    def _scan(self) -> None:
        out = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", SCRIPT],
                             capture_output=True, text=True, timeout=30, creationflags=NO_WINDOW).stdout.strip()
        data = json.loads(out.splitlines()[-1]) if out else {}
        raw = data.get("devices") or []
        if isinstance(raw, dict):
            raw = [raw]
        devices = []
        for d in raw:
            name = d.get("name") or "Unknown device"
            devices.append({
                "id": (d.get("address") or name),
                "name": name,
                "type": _type(name, d.get("category"), d.get("major")),
                "connected": bool(d.get("connected")),
                "battery": d.get("battery"),
                "le": d.get("kind") == "le",
            })
        devices.sort(key=lambda x: (not x["connected"], x["name"].lower()))

        current = {d["id"]: (d["name"], d["connected"]) for d in devices}
        if self._known is not None:
            for did, (nm, conn) in current.items():
                was = self._known.get(did)
                if conn and not (was and was[1]):
                    self._event("connected", f"{nm} connected")
                elif not conn and was and was[1]:
                    self._event("disconnected", f"{nm} disconnected")
            for did, (nm, conn) in self._known.items():
                if did not in current and conn:
                    self._event("disconnected", f"{nm} disconnected")
        self._known = current

        with self._lock:
            self.state = {"radio": data.get("radio"), "devices": devices,
                          "connected_count": sum(1 for d in devices if d["connected"]),
                          "paired_count": len(devices), "error": None}

    def _event(self, kind: str, text: str) -> None:
        with self._lock:
            self.events.appendleft({"t": time.time(), "kind": kind, "text": text})
