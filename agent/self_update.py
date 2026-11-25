"""
Keeps PerfLink on the PC up to date from GitHub, by itself.

An installed copy (never a source checkout) checks GitHub every 30 minutes. When the version on
GitHub (main branch) is newer, it starts the "PerfLink Update" task, which the installer set up to
run as administrator without a prompt; that task (agent/auto_update.ps1) stops PerfLink, installs the
new version and starts it again. The task also runs by itself at sign-in and every 6 hours, so
updates still arrive if this check can't start it.

Off switch: "auto_update": false in agent/config.json.
"""
from __future__ import annotations

import os
import re
import subprocess
import threading
import time
import urllib.request

TASK = "PerfLink Update"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def version_tuple(v: str | None) -> tuple:
    return tuple(int(x) for x in re.findall(r"\d+", v or "")) or (0,)


def parse_version(text: str) -> str | None:
    m = re.search(r'^VERSION\s*=\s*"([^"]+)"', text or "", re.M)
    return m.group(1) if m else None


def is_newer(remote: str | None, local: str) -> bool:
    return bool(remote) and version_tuple(remote) > version_tuple(local)


class SelfUpdate:
    def __init__(self, version: str, app_dir: str, repo: str | None, notify, enabled: bool = True,
                 interval: float = 1800, first_check: float = 120) -> None:
        self.version = version
        self.app_dir = app_dir
        self.repo = repo or "NirabhraKoley/PerfLink"
        self.notify = notify or (lambda title, text: None)
        self.interval = interval
        self.installed = os.path.exists(os.path.join(app_dir, ".perflink-install")) and \
            not os.path.exists(os.path.join(app_dir, ".git"))
        self.enabled = enabled and self.installed
        self.latest: str | None = None
        self._started_for: tuple[str, float] | None = None   # (version, when) we last started the task for
        self._announce()
        if self.enabled:
            threading.Thread(target=self._loop, args=(first_check,), daemon=True).start()

    # ---------------------------------------------------------------- "updated to X" after an update
    def _announce(self) -> None:
        marker = os.path.join(self.app_dir, "agent", "cache", "last_version.txt")
        try:
            prev = open(marker, encoding="utf-8").read().strip() if os.path.exists(marker) else None
            if prev and prev != self.version:
                self.notify("PerfLink updated", f"Now running PerfLink {self.version}")
                print(f"[update] updated {prev} -> {self.version}")
            os.makedirs(os.path.dirname(marker), exist_ok=True)
            with open(marker, "w", encoding="utf-8") as f:
                f.write(self.version)
        except OSError:
            pass

    # ---------------------------------------------------------------- checking
    def fetch_latest(self) -> str | None:
        url = f"https://raw.githubusercontent.com/{self.repo}/main/agent/perflink_agent.py?t={int(time.time())}"
        req = urllib.request.Request(url, headers={"User-Agent": "PerfLink-agent", "Cache-Control": "no-cache"})
        with urllib.request.urlopen(req, timeout=20) as r:
            return parse_version(r.read(16384).decode("utf-8", "replace"))

    def check_now(self, manual: bool = False) -> str:
        """Check GitHub; start the update task when there's a newer version. Returns what happened."""
        if not self.installed:
            return "Updates are only for installed copies"
        try:
            self.latest = self.fetch_latest()
        except Exception as e:  # noqa: BLE001  (offline, GitHub down...)
            if manual:
                self.notify("PerfLink", "Couldn't check for updates (no internet?)")
            return f"check failed: {e}"
        if not is_newer(self.latest, self.version):
            if manual:
                self.notify("PerfLink", f"PerfLink {self.version} is up to date")
            return "up to date"
        # don't start the task again and again if an update for this version is already under way / failed
        if self._started_for and self._started_for[0] == self.latest and time.time() - self._started_for[1] < 6 * 3600 \
                and not manual:
            return "update already started"
        ok = subprocess.run(["schtasks", "/run", "/tn", TASK], capture_output=True, creationflags=NO_WINDOW).returncode == 0
        self._started_for = (self.latest, time.time())
        if ok:
            print(f"[update] {self.version} -> {self.latest}: started the '{TASK}' task")
            self.notify("PerfLink", f"Updating to PerfLink {self.latest}… it restarts by itself")
            return "updating"
        print(f"[update] {self.latest} is available but the '{TASK}' task couldn't start (run the installer once)")
        if manual:
            self.notify("PerfLink", f"PerfLink {self.latest} is available: run the install command once to turn on auto-update")
        return "task missing"

    def _loop(self, first: float) -> None:
        time.sleep(first)
        while True:
            print(f"[update] check: {self.check_now()}")
            time.sleep(self.interval)
