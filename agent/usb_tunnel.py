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


def find_adb() -> str | None:
    """adb on PATH, or in the usual install places. Right after the installer adds Platform
    Tools with winget, Windows hasn't refreshed PATH for already-running programs yet."""
    import glob
    found = shutil.which("adb")
    if found:
        return found
    local = os.environ.get("LOCALAPPDATA", "")
    candidates = [os.path.join(local, "Microsoft", "WinGet", "Links", "adb.exe"),
                  os.path.join(local, "Android", "Sdk", "platform-tools", "adb.exe")]
    candidates += glob.glob(os.path.join(local, "Microsoft", "WinGet", "Packages", "Google.PlatformTools*",
                                         "platform-tools", "adb.exe"))
    program_files = os.environ.get("ProgramFiles", "C:\\Program Files")
    candidates += glob.glob(os.path.join(program_files, "*", "platform-tools", "adb.exe"))
    for c in candidates:
        if os.path.isfile(c):
            return c
    return None


def _version_tuple(v: str | None) -> tuple:
    return tuple(int(x) for x in re.findall(r"\d+", v or "")) or (0,)


class UsbTunnel:
    def __init__(self, agent_port: int, interval: float = 3.0, repo: str | None = None, notify=None,
                 usb_connected=None) -> None:
        self.agent_port = agent_port
        self.interval = interval
        self.repo = repo or DEFAULT_REPO
        self.notify = notify or (lambda title, text: None)
        # does any phone use the USB tunnel right now? (the agent sees it as a client from 127.0.0.1)
        self.usb_connected = usb_connected or (lambda: True)
        self._plugged_at: dict[str, float] = {}  # phone -> when it was plugged in (seen by adb)
        self._opened: set[str] = set()           # phones whose app we already opened this plug-in
        self._installing: set[str] = set()
        self.adb = find_adb()
        self.phones: list[str] = []
        self._checked: set[str] = set()      # phones already checked for app updates while plugged in
        self._asked_auth: set[str] = set()   # phones we already told to "Allow USB debugging"
        self._latest: tuple[float, dict] | None = None  # (fetched at, release info)
        print(f"[usb] adb: {self.adb or 'not found yet (USB mode and automatic app install wait for it)'}")
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
            if not self.adb:  # installed later (e.g. by the installer)? look again every 30 s
                time.sleep(30)
                self.adb = find_adb()
                if self.adb:
                    print(f"[usb] adb found: {self.adb}")
                continue
            out = self._run("devices").stdout
            lines = [ln.strip() for ln in out.splitlines()[1:] if "\t" in ln]
            serials = [ln.split("\t")[0] for ln in lines if ln.endswith("\tdevice")]
            # a phone plugged into a new PC must first approve it ("Allow USB debugging?")
            waiting = [ln.split("\t")[0] for ln in lines if ln.endswith("\tunauthorized")]
            for serial in waiting:
                if serial not in self._asked_auth:
                    self._asked_auth.add(serial)
                    print(f"[usb] phone {serial} is waiting for 'Allow USB debugging' on its screen")
                    self.notify("PerfLink", "Phone connected: tap 'Allow' on the phone's "
                                            "'Allow USB debugging?' pop-up (tick 'Always allow').")
            self._asked_auth &= set(waiting)
            for s in serials:
                if want not in self._run("-s", s, "reverse", "--list").stdout:
                    self._run("-s", s, "reverse", f"tcp:{PHONE_PORT}", f"tcp:{self.agent_port}")
                    print(f"[usb] tunnel restored for phone {s}")
                if s not in self._checked:
                    self._checked.add(s)
                    threading.Thread(target=self._update_app, args=(s,), daemon=True).start()
                self._maybe_open_app(s)
            # forget unplugged phones, so they're checked again next time they're plugged in
            self._checked &= set(serials)
            self._opened &= set(serials)
            self._plugged_at = {k: v for k, v in self._plugged_at.items() if k in serials}
            self.phones = serials
            time.sleep(self.interval)

    def _maybe_open_app(self, serial: str) -> None:
        """Phone plugged in but its PerfLink app isn't connected (closed, or waiting on its 'connect to
        a PC' screen, which can't find the PC without Wi-Fi): open it, connected over USB. Once per
        plug-in, so it never keeps popping up if the user closes it on purpose."""
        now = time.time()
        first = self._plugged_at.setdefault(serial, now)
        if serial in self._opened or serial in self._installing or now - first < 10:
            return
        try:
            if self.usb_connected():
                self._opened.add(serial)  # already connected: nothing to do this plug-in
                return
        except Exception:  # noqa: BLE001
            return
        if not self._installed_version(serial):
            return  # app not installed (yet): _update_app installs and opens it
        self._opened.add(serial)
        r = self._run("-s", serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity", "-e", "host", "127.0.0.1")
        print(f"[usb] phone {serial}: opened the PerfLink app over USB" if r.returncode == 0
              else f"[usb] phone {serial}: couldn't open the app: {(r.stdout + r.stderr).strip()[-120:]}")

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
            self._installing.add(serial)
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
        finally:
            self._installing.discard(serial)
