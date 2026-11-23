"""
Launcher actions for the PerfLink "Launcher" page.

Only this fixed list of actions can be triggered from the phone. The agent never
runs arbitrary commands sent over the network.
"""
from __future__ import annotations

import ctypes
import os
import shlex
import shutil
import subprocess
import winreg

LOCAL = os.environ.get("LOCALAPPDATA", "")
APPDATA = os.environ.get("APPDATA", "")


def _spawn(args: list[str], console: bool = False) -> None:
    flags = subprocess.CREATE_NEW_CONSOLE if console else 0
    subprocess.Popen(args, creationflags=flags | subprocess.CREATE_NEW_PROCESS_GROUP,
                     close_fds=True, cwd=os.path.expanduser("~"))


def _default_browser() -> str | None:
    try:
        k = winreg.OpenKey(winreg.HKEY_CURRENT_USER,
                           r"Software\Microsoft\Windows\Shell\Associations\UrlAssociations\https\UserChoice")
        prog = winreg.QueryValueEx(k, "ProgId")[0]
        cmd = winreg.QueryValue(winreg.HKEY_CLASSES_ROOT, rf"{prog}\shell\open\command")
        exe = shlex.split(cmd, posix=False)[0].strip('"')
        return exe if os.path.exists(exe) else None
    except OSError:
        return None


def _vscode() -> str | None:
    for p in (os.path.join(LOCAL, "Programs", "Microsoft VS Code", "Code.exe"),
              r"C:\Program Files\Microsoft VS Code\Code.exe"):
        if os.path.exists(p):
            return p
    return None


def _spotify() -> str | None:
    p = os.path.join(APPDATA, "Spotify", "Spotify.exe")
    return p if os.path.exists(p) else None


class Launcher:
    def __init__(self, volume) -> None:
        self.volume = volume
        self.browser = _default_browser()
        self.code = _vscode()
        self.spotify = _spotify()
        self.wt = shutil.which("wt.exe")

    def info(self) -> dict:
        """Per-action hints shown under the tile labels."""
        return {
            "terminal": "Windows Terminal" if self.wt else "PowerShell",
            "browser": os.path.splitext(os.path.basename(self.browser))[0].title() if self.browser else "Default browser",
            "spotify": "App" if self.spotify else "Web player",
            "vscode": None if self.code else "Not installed",
        }

    def run(self, action: str) -> str:
        """Run one action; returns a short message for the phone. Raises on failure."""
        if action == "terminal":
            if self.wt:
                _spawn([self.wt])
            else:
                _spawn(["powershell.exe", "-NoExit", "-NoLogo"], console=True)
            return "Opened terminal"
        if action == "files":
            _spawn(["explorer.exe"])
            return "Opened File Explorer"
        if action == "vscode":
            if not self.code:
                raise RuntimeError("VS Code is not installed")
            _spawn([self.code])
            return "Opened VS Code"
        if action == "browser":
            if self.browser:
                _spawn([self.browser])
            else:
                os.startfile("https://www.google.com")
            return "Opened browser"
        if action == "spotify":
            if self.spotify:
                _spawn([self.spotify])
                return "Opened Spotify"
            os.startfile("https://open.spotify.com")
            return "Opened Spotify web player"
        if action == "calculator":
            _spawn(["calc.exe"])
            return "Opened Calculator"
        if action == "settings":
            os.startfile("ms-settings:")
            return "Opened Settings"
        if action == "snip":
            os.startfile("ms-screenclip:")
            return "Snipping: select an area on the PC"
        if action == "taskmgr":
            os.startfile("taskmgr.exe")  # ShellExecute handles its auto-elevation
            return "Opened Task Manager"
        if action == "mute":
            self.volume.toggle_mute()
            return "Muted" if self.volume.read().get("muted") else "Unmuted"
        if action == "screen_off":
            # SC_MONITORPOWER = 2 (off). Posted, not sent, so a hung window can't block us.
            ctypes.windll.user32.PostMessageW(0xFFFF, 0x0112, 0xF170, 2)
            return "Screen off: move the mouse to wake"
        if action == "lock":
            if not ctypes.windll.user32.LockWorkStation():
                raise RuntimeError("Could not lock the PC")
            return "PC locked"
        raise ValueError(f"unknown action '{action}'")
