"""
Task-manager style process list for the PerfLink "Processes" page.

Processes are grouped by executable (Firefox ×13), with total CPU % (normalised
to all cores, like Task Manager) and RAM. Friendly names come from the exe's
FileDescription and icons are extracted once per exe as PNG (via .NET System.Drawing).
Processes we cannot open (system / elevated) are marked "locked".
"""
from __future__ import annotations

import base64
import hashlib
import os
import threading
import time

import psutil

MAX_APPS = 60


class Processes:
    def __init__(self) -> None:
        self._procs: dict[int, psutil.Process] = {}
        self._meta: dict[str, dict] = {}  # exe path (lower) -> {name, icon_id}
        self.icons: dict[str, str] = {}  # icon_id -> base64 png
        self.new_icons: dict[str, str] = {}  # not yet broadcast
        self.state: dict = {"apps": [], "total_processes": 0, "total_apps": 0}
        self._lock = threading.Lock()
        self._net = None
        try:
            import clr  # type: ignore
            clr.AddReference("System.Drawing")
            import System.Drawing  # type: ignore  # noqa: F401
            import System.Diagnostics  # type: ignore  # noqa: F401
            import System.IO  # type: ignore  # noqa: F401
            self._net = True
        except Exception:  # noqa: BLE001
            self._net = None
        threading.Thread(target=self._loop, daemon=True).start()

    # ------------------------------------------------------------------ exe metadata
    def _describe(self, path: str) -> dict:
        key = path.lower()
        meta = self._meta.get(key)
        if meta is not None:
            return meta
        name = os.path.splitext(os.path.basename(path))[0]
        icon_id = None
        if self._net:
            try:
                from System.Diagnostics import FileVersionInfo  # type: ignore
                desc = (FileVersionInfo.GetVersionInfo(path).FileDescription or "").strip()
                if desc and len(desc) < 60:
                    name = desc
            except Exception:  # noqa: BLE001
                pass
            try:
                from System.Drawing import Icon  # type: ignore
                from System.Drawing.Imaging import ImageFormat  # type: ignore
                from System.IO import MemoryStream  # type: ignore
                ico = Icon.ExtractAssociatedIcon(path)
                if ico is not None:
                    bmp = ico.ToBitmap()
                    ms = MemoryStream()
                    bmp.Save(ms, ImageFormat.Png)
                    data = bytes(ms.ToArray())
                    bmp.Dispose(); ico.Dispose(); ms.Dispose()
                    icon_id = hashlib.sha1(data).hexdigest()[:12]
                    if icon_id not in self.icons:
                        b64 = base64.b64encode(data).decode()
                        with self._lock:
                            self.icons[icon_id] = b64
                            self.new_icons[icon_id] = b64
            except Exception:  # noqa: BLE001
                pass
        meta = {"name": name, "icon_id": icon_id}
        self._meta[key] = meta
        return meta

    # ------------------------------------------------------------------ scan
    def _loop(self) -> None:
        while True:
            try:
                self._scan()
            except Exception:  # noqa: BLE001
                pass
            time.sleep(2)

    def _scan(self) -> None:
        ncpu = psutil.cpu_count() or 1
        groups: dict[str, dict] = {}
        alive: set[int] = set()
        for p in psutil.process_iter(["name"]):
            pid = p.pid
            if pid == 0:
                continue  # System Idle Process
            alive.add(pid)
            proc = self._procs.get(pid)
            first = proc is None
            if first:
                proc = self._procs[pid] = p
            try:
                cpu = proc.cpu_percent(None)
                if first:
                    cpu = 0.0
            except psutil.Error:
                cpu = 0.0
            try:
                rss = proc.memory_info().rss
            except psutil.Error:
                rss = 0
            pname = p.info["name"] or f"pid {pid}"
            locked = False
            try:
                exe = proc.exe()
            except psutil.Error:
                exe, locked = "", True
            if not exe:
                locked = True
            gkey = pname.lower()
            g = groups.get(gkey)
            if g is None:
                meta = self._describe(exe) if exe else {"name": os.path.splitext(pname)[0], "icon_id": None}
                g = groups[gkey] = {"key": gkey, "name": meta["name"], "exe": pname, "path": exe or None,
                                    "icon_id": meta["icon_id"], "count": 0, "cpu": 0.0, "ram": 0,
                                    "locked": locked, "pids": [], "threads": 0, "started": None}
            elif g["icon_id"] is None and exe:
                meta = self._describe(exe)
                g.update(name=meta["name"], icon_id=meta["icon_id"], path=exe)
            g["count"] += 1
            g["cpu"] += cpu
            g["ram"] += rss
            g["locked"] = g["locked"] or locked  # lock shown if any instance is protected
            if len(g["pids"]) < 12:
                g["pids"].append(pid)
            try:
                g["threads"] += proc.num_threads()
                ct = proc.create_time()
                g["started"] = ct if g["started"] is None else min(g["started"], ct)
            except psutil.Error:
                pass
        for pid in list(self._procs):
            if pid not in alive:
                del self._procs[pid]

        apps = []
        for g in groups.values():
            g["cpu"] = round(g["cpu"] / ncpu, 1)
            g["ram_mb"] = round(g["ram"] / 2**20)
            del g["ram"]
            apps.append(g)
        by_cpu = sorted(apps, key=lambda a: (-a["cpu"], -a["ram_mb"]))[:MAX_APPS]
        by_ram = sorted(apps, key=lambda a: -a["ram_mb"])[:MAX_APPS]
        keep = {a["key"]: a for a in by_cpu + by_ram}
        with self._lock:
            self.state = {"apps": list(keep.values()), "total_processes": len(alive),
                          "total_apps": len(groups)}

    # ------------------------------------------------------------------ public
    def snapshot(self) -> dict:
        with self._lock:
            return dict(self.state)

    def take_new_icons(self) -> dict[str, str]:
        with self._lock:
            new, self.new_icons = self.new_icons, {}
        return new

    def all_icons(self) -> dict[str, str]:
        with self._lock:
            return dict(self.icons)
