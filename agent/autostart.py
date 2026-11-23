"""
"Start with Windows" for PerfLink.

Uses a Task Scheduler task (not the Startup folder) so the agent starts at sign-in *with
administrator rights and no UAC prompt*. Admin is needed for FPS (PresentMon) on most PCs
and for CPU temperature. The task:
  * runs  pythonw.exe perflink_agent.py --tray  (no console window, icon in the tray)
  * triggers at sign-in of the current user, only while that user is signed in (so the
    tray icon is visible), with highest privileges
  * never times out and keeps running on battery (Windows' defaults would stop it on laptops)

Creating/removing the task needs admin: if the agent isn't elevated, a UAC prompt is shown.
"""
from __future__ import annotations

import ctypes
import os
import subprocess
import sys
import tempfile

TASK = "PerfLink"
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
HERE = os.path.dirname(os.path.abspath(__file__))


def pythonw() -> str:
    exe = sys.executable
    w = os.path.join(os.path.dirname(exe), "pythonw.exe")
    return w if os.path.exists(w) else exe


def is_admin() -> bool:
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:  # noqa: BLE001
        return False


def is_enabled() -> bool:
    r = subprocess.run(["schtasks", "/query", "/tn", TASK], capture_output=True, creationflags=NO_WINDOW)
    return r.returncode == 0


def _ps_register(user: str) -> str:
    script = os.path.join(HERE, "perflink_agent.py")
    q = lambda s: s.replace("'", "''")  # noqa: E731  (PowerShell single-quote escaping)
    return (
        f"$a = New-ScheduledTaskAction -Execute '{q(pythonw())}' -Argument '\"{q(script)}\" --tray' "
        f"-WorkingDirectory '{q(HERE)}'; "
        f"$t = New-ScheduledTaskTrigger -AtLogOn -User '{q(user)}'; "
        f"$p = New-ScheduledTaskPrincipal -UserId '{q(user)}' -LogonType Interactive -RunLevel Highest; "
        "$s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries "
        "-ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -StartWhenAvailable; "
        f"Register-ScheduledTask -TaskName '{TASK}' -Action $a -Trigger $t -Principal $p -Settings $s "
        "-Description 'PerfLink: PC stats on your phone (tray icon)' -Force | Out-Null"
    )


def _run_ps(command: str) -> bool:
    """Run PowerShell, elevated via UAC if we aren't admin. Returns True on success."""
    if is_admin():
        r = subprocess.run(["powershell", "-NoProfile", "-Command", command], capture_output=True,
                           creationflags=NO_WINDOW)
        return r.returncode == 0
    # write to a file so quoting survives the trip through the elevated process
    fd, path = tempfile.mkstemp(suffix=".ps1")
    with os.fdopen(fd, "w", encoding="utf-8-sig") as f:
        f.write(command)
    try:
        ps = (f"Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden "
              f"-ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','{path}'")
        r = subprocess.run(["powershell", "-NoProfile", "-Command", ps], capture_output=True,
                           creationflags=NO_WINDOW)
        return r.returncode == 0
    finally:
        try:
            os.remove(path)
        except OSError:
            pass


def enable() -> bool:
    user = f"{os.environ.get('USERDOMAIN', '')}\\{os.environ.get('USERNAME', '')}".lstrip("\\")
    return _run_ps(_ps_register(user)) and is_enabled()


def disable() -> bool:
    _run_ps(f"Unregister-ScheduledTask -TaskName '{TASK}' -Confirm:$false")
    return not is_enabled()


if __name__ == "__main__":  # python autostart.py on|off|status
    cmd = (sys.argv[1:] or ["status"])[0]
    if cmd == "on":
        print("enabled" if enable() else "failed")
    elif cmd == "off":
        print("disabled" if disable() else "failed")
    else:
        print("enabled" if is_enabled() else "disabled")
