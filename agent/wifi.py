"""
Wi-Fi (and wired) connection details for the PerfLink "Bluetooth & Wi-Fi" page.

Wi-Fi comes from `netsh wlan show interfaces`: network name (SSID), signal, Wi-Fi standard,
band, channel, link speeds, security. When the PC has no Wi-Fi or isn't on it, the active
wired (Ethernet) connections are listed instead, with link speed and IP address.
"""
from __future__ import annotations

import re
import socket
import subprocess
import threading
import time

import psutil

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
_VIRTUAL = re.compile(r"loopback|vethernet|virtual|vmware|vbox|hyper-v|bluetooth|tap|tun|wsl|npcap|teredo|isatap", re.I)


def _ipv4(iface: str) -> str | None:
    for a in psutil.net_if_addrs().get(iface, []):
        if a.family == socket.AF_INET and not a.address.startswith("169.254."):
            return a.address
    return None


def _wifi_interfaces() -> tuple[list[dict], bool]:
    """Parse `netsh wlan show interfaces`. Returns (interfaces, wifi service available)."""
    try:
        r = subprocess.run(["netsh", "wlan", "show", "interfaces"], capture_output=True, timeout=15,
                           creationflags=NO_WINDOW)
        out = r.stdout.decode("utf-8", errors="replace")
    except Exception:  # noqa: BLE001
        return [], False
    if "wlansvc" in out.lower() or "is not running" in out.lower():
        return [], False
    blocks, cur = [], None
    for line in out.splitlines():
        m = re.match(r"^\s{2,}([^:]+?)\s*:\s(.*)$", line)
        if not m:
            continue
        key, val = m.group(1).strip().lower(), m.group(2).strip()
        if key == "name":
            cur = {}
            blocks.append(cur)
        if cur is not None:
            cur[key] = val
    result = []
    for b in blocks:
        state = b.get("state", "").lower()
        connected = state == "connected"
        sig = re.search(r"\d+", b.get("signal", ""))
        name = b.get("name")
        result.append({
            "interface": name,
            "adapter": b.get("description"),
            "connected": connected,
            "state": b.get("state"),
            "ssid": b.get("ssid") if connected else None,
            "bssid": b.get("ap bssid") or b.get("bssid"),
            "signal": int(sig.group()) if sig and connected else None,
            "standard": b.get("radio type"),
            "band": b.get("band"),
            "channel": b.get("channel"),
            "security": " / ".join(x for x in (b.get("authentication"), b.get("cipher")) if x) or None,
            "rx_mbps": _num(b.get("receive rate (mbps)")),
            "tx_mbps": _num(b.get("transmit rate (mbps)")),
            "ip": _ipv4(name) if name and connected else None,
        })
    return result, True


def _num(v: str | None) -> float | None:
    try:
        return float(v) if v else None
    except ValueError:
        return None


def _wired(wifi_names: set[str]) -> list[dict]:
    out = []
    stats = psutil.net_if_stats()
    for name, st in stats.items():
        if not st.isup or name in wifi_names or _VIRTUAL.search(name):
            continue
        ip = _ipv4(name)
        if not ip:
            continue
        out.append({"interface": name, "speed_mbps": st.speed or None, "ip": ip})
    return out


class WifiStatus:
    def __init__(self, interval: float = 5.0) -> None:
        self.interval = interval
        self.state: dict = {"available": False, "wifi": [], "wired": []}
        self._lock = threading.Lock()
        threading.Thread(target=self._loop, daemon=True).start()

    def snapshot(self) -> dict:
        with self._lock:
            return dict(self.state)

    def _loop(self) -> None:
        while True:
            try:
                wifi, available = _wifi_interfaces()
                wired = _wired({w["interface"] for w in wifi if w.get("interface")})
                with self._lock:
                    self.state = {"available": available, "wifi": wifi, "wired": wired}
            except Exception as e:  # noqa: BLE001
                with self._lock:
                    self.state = {**self.state, "error": f"Wi-Fi: {e}"}
            time.sleep(self.interval)


if __name__ == "__main__":
    w = WifiStatus()
    time.sleep(3)
    print(w.snapshot())
