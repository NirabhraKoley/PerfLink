"""
Keeps PerfLink on the PC up to date from GitHub, by itself.

An installed copy (never a source checkout) checks GitHub every 30 minutes. When the version on
GitHub (main branch) is newer, it starts the "PerfLink Update" task, which the installer set up to
run as administrator without a prompt; that task (agent/auto_update.ps1) stops PerfLink, installs the
new version and starts it again. The task also runs by itself at sign-in and every 2 hours (a PC
that was off, or an update that failed, catches up within 2 hours), so updates still arrive if this
check can't start it. PCs set up with the older 6-hour schedule are moved to 2 hours at start-up.

The tray's "Update now (PowerShell)" opens a PowerShell window that runs the install command, to
update by hand and watch it.

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
NEW_CONSOLE = 0x00000010          # CREATE_NEW_CONSOLE: its own PowerShell window
BREAKAWAY = 0x01000000            # CREATE_BREAKAWAY_FROM_JOB: keeps running when the installer stops PerfLink

# Moves the update task to "at sign-in + every 2 hours" (it was every 6 hours before 1.26).
SCHEDULE_PS = r"""
$t = Get-ScheduledTask -TaskName 'PerfLink Update' -ErrorAction SilentlyContinue
if (-not $t) { 'no task'; return }
if ($t.Triggers | Where-Object { $_.Repetition -and $_.Repetition.Interval -eq 'PT2H' }) { 'ok'; return }
$l = New-ScheduledTaskTrigger -AtLogOn -User $t.Principal.UserId
$l.Delay = 'PT3M'
$r = New-ScheduledTaskTrigger -Once -At ((Get-Date).Date.AddHours(1)) -RepetitionInterval (New-TimeSpan -Hours 2) `
        -RepetitionDuration (New-TimeSpan -Days 3650)
Set-ScheduledTask -TaskName 'PerfLink Update' -Trigger @($l, $r) | Out-Null
'changed to every 2 hours'
"""


def install_command(repo: str) -> str:
    return f"irm https://raw.githubusercontent.com/{repo}/main/install.ps1 | iex"


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
            threading.Thread(target=self.ensure_schedule, daemon=True).start()

    # ---------------------------------------------------------------- the task's schedule
    def ensure_schedule(self) -> str:
        """Every 2 hours (older installs: every 6). Needs the administrator rights PerfLink starts with."""
        try:
            out = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", SCHEDULE_PS],
                                 capture_output=True, text=True, timeout=60, creationflags=NO_WINDOW).stdout.strip()
        except Exception as e:  # noqa: BLE001
            out = f"failed: {e}"
        if out and out != "ok":
            print(f"[update] task schedule: {out}")
        return out

    # ---------------------------------------------------------------- tray: "Update now (PowerShell)"
    def open_update_window(self) -> bool:
        """Opens PowerShell and runs the install command in it, so the update can be watched.

        The window is started by Windows' own shell (Explorer), not by PerfLink: the installer stops
        PerfLink, and a window started by PerfLink could be closed along with it, halfway through the
        update. So it runs like the command typed by hand: Windows asks "allow?" once (click Yes)."""
        script = (f"$host.UI.RawUI.WindowTitle = 'PerfLink update'; "
                  f"Write-Host 'Updating PerfLink from GitHub...' -ForegroundColor Cyan; {install_command(self.repo)}")
        home = os.path.expanduser("~")   # never "inside" the folder the installer replaces
        launcher = os.path.join(os.environ.get("TEMP", home), "perflink-update-now.cmd")
        try:
            lines = ["@echo off", 'cd /d "%USERPROFILE%"',
                     f'powershell.exe -NoExit -NoProfile -ExecutionPolicy Bypass -Command "{script}"']
            with open(launcher, "w", encoding="ascii", errors="replace", newline="\r\n") as f:
                f.write("\n".join(lines) + "\n")
            subprocess.Popen(["explorer.exe", launcher], cwd=home, creationflags=NO_WINDOW)
            print("[update] opened PowerShell (via Explorer) to update by hand")
            return True
        except OSError as e:
            print(f"[update] couldn't open the update window via Explorer: {e}")
        args = ["powershell.exe", "-NoExit", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script]
        for flags in (NEW_CONSOLE | BREAKAWAY, NEW_CONSOLE):   # fallback: start it directly
            try:
                subprocess.Popen(args, creationflags=flags, cwd=home)
                return True
            except OSError:
                continue
        self.notify("PerfLink", "Couldn't open PowerShell. Run the install command from the PerfLink page on GitHub.")
        return False

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
