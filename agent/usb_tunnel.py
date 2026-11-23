"""
Phone over USB: keeps PerfLink's USB connection alive and keeps the phone app up to date.

1. Tunnel: in USB mode the phone reaches the agent through an adb "reverse" tunnel
   (phone 127.0.0.1:47800 -> PC). Android drops it whenever the cable is unplugged, so this
   re-creates it every few seconds for each connected phone.
2. App install/update: when a phone with USB debugging allowed is plugged in, the installed
   PerfLink app version is compared with the latest GitHub release; if the app is missing or
   older, the release APK is downloaded (cached in agent/cache) and installed with adb.

Does nothing if adb isn't installed. Android only lets this work after "USB debugging" is
allowed once on the phone (Xiaomi/Redmi also need "Install via USB").
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import threading
import time
import urllib.request

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
PHONE_PORT = 47800  # the port the app uses for "127.0.0.1"
PACKAGE = "com.perflink.app"
HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, "cache")
DEFAULT_REPO = "NirabhraKoley/PerfLink"


def _version_tuple(v: str | None) -> tuple:
    return tuple(int(x) for x in re.findall(r"\d+", v or "")) or (0,)


class UsbTunnel:
    def __init__(self, agent_port: int, interval: float = 3.0, repo: str | None = None, notify=None) -> None:
        self.agent_port = agent_port
        self.interval = interval
        self.repo = repo or DEFAULT_REPO
        self.notify = notify or (lambda title, text: None)
        self.adb = shutil.which("adb")
        self.phones: list[str] = []
        self._checked: set[str] = set()      # phones already checked for app updates while plugged in
        self._latest: tuple[float, dict] | None = None  # (fetched at, release info)
        if self.adb:
            threading.Thread(target=self._loop, daemon=True).start()

    def _run(self, *args: str, timeout: int = 10) -> subprocess.CompletedProcess:
        try:
            return subprocess.run([self.adb, *args], capture_output=True, text=True, timeout=timeout,
                                  creationflags=NO_WINDOW)
        except Exception as e:  # noqa: BLE001
            return subprocess.CompletedProcess(args, 1, "", str(e))

    # ------------------------------------------------------------------ main loop
    def _loop(self) -> None:
        want = f"tcp:{PHONE_PORT} tcp:{self.agent_port}"
        while True:
            out = self._run("devices").stdout
            serials = [ln.split("\t")[0] for ln in out.splitlines()[1:] if ln.strip().endswith("\tdevice")]
            for s in serials:
                if want not in self._run("-s", s, "reverse", "--list").stdout:
                    self._run("-s", s, "reverse", f"tcp:{PHONE_PORT}", f"tcp:{self.agent_port}")
                    print(f"[usb] tunnel restored for phone {s}")
                if s not in self._checked:
                    self._checked.add(s)
                    threading.Thread(target=self._update_app, args=(s,), daemon=True).start()
            # forget unplugged phones, so they're checked again next time they're plugged in
            self._checked &= set(serials)
            self.phones = serials
            time.sleep(self.interval)

    # ------------------------------------------------------------------ app install / update
    def _installed_version(self, serial: str) -> str | None:
        out = self._run("-s", serial, "shell", "dumpsys", "package", PACKAGE).stdout
        m = re.search(r"versionName=([\w.\-]+)", out)
        return m.group(1) if m else None

    def _latest_release(self) -> dict | None:
        """Latest GitHub release: {'version': '1.1', 'url': <apk url>}. Cached for 6 hours."""
        if self._latest and time.time() - self._latest[0] < 6 * 3600:
            return self._latest[1]
        try:
            req = urllib.request.Request(f"https://api.github.com/repos/{self.repo}/releases/latest",
                                         headers={"User-Agent": "PerfLink", "Accept": "application/vnd.github+json"})
            with urllib.request.urlopen(req, timeout=15) as r:
                rel = json.load(r)
            asset = next((a for a in rel.get("assets", []) if a.get("name", "").lower().endswith(".apk")), None)
            if not asset:
                return None
            info = {"version": rel.get("tag_name", "").lstrip("vV"), "url": asset["browser_download_url"]}
            self._latest = (time.time(), info)
            return info
        except Exception as e:  # noqa: BLE001
            print(f"[usb] couldn't check for app updates: {e}")
            return None

    def _apk(self, info: dict) -> str | None:
        os.makedirs(CACHE, exist_ok=True)
        path = os.path.join(CACHE, f"PerfLink-{info['version']}.apk")
        if os.path.exists(path) and os.path.getsize(path) > 100_000:
            return path
        try:
            req = urllib.request.Request(info["url"], headers={"User-Agent": "PerfLink"})
            with urllib.request.urlopen(req, timeout=120) as r, open(path + ".part", "wb") as f:
                shutil.copyfileobj(r, f)
            os.replace(path + ".part", path)
            for old in os.listdir(CACHE):  # keep only the newest APK
                if old.endswith(".apk") and old != os.path.basename(path):
                    os.remove(os.path.join(CACHE, old))
            return path
        except Exception as e:  # noqa: BLE001
            print(f"[usb] APK download failed: {e}")
            return None

    def _update_app(self, serial: str) -> None:
        try:
            installed = self._installed_version(serial)
            info = self._latest_release()
            if not info:
                return
            if installed and _version_tuple(installed) >= _version_tuple(info["version"]):
                print(f"[usb] phone {serial}: PerfLink {installed} is up to date")
                return
            apk = self._apk(info)
            if not apk:
                return
            what = f"Updating PerfLink {installed} → {info['version']}" if installed else f"Installing PerfLink {info['version']}"
            print(f"[usb] phone {serial}: {what}")
            r = self._run("-s", serial, "install", "-r", apk, timeout=180)
            text = (r.stdout + r.stderr).strip()
            if "Success" in text:
                verb = "updated" if installed else "installed"
                self.notify("PerfLink", f"Phone app {verb} (version {info['version']})")
                if not installed:  # first install: open it so the phone connects right away
                    self._run("-s", serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity",
                              "-e", "host", "127.0.0.1")
            elif "USER_RESTRICTED" in text or "INSTALL_CANCELED_BY_USER" in text:
                self.notify("PerfLink", "The phone blocked the install. On the phone allow "
                                        "'Install via USB' in Developer options, then replug.")
                self._checked.discard(serial)
            else:
                print(f"[usb] install failed: {text[-200:]}")
        except Exception as e:  # noqa: BLE001
            print(f"[usb] app update error: {e}")
