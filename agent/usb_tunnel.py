"""
Keeps PerfLink's USB connection alive.

In USB mode the phone reaches the agent through an adb "reverse" tunnel (phone 127.0.0.1:47800 ->
PC :47800). Android drops that tunnel whenever the cable is unplugged, so the app would sit on
"Reconnecting…" after a replug. This checks every few seconds and re-creates the tunnel for every
connected phone that has USB debugging allowed. Does nothing if adb isn't installed.
"""
from __future__ import annotations

import shutil
import subprocess
import threading
import time

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
PHONE_PORT = 47800  # the port the app uses for "127.0.0.1"


class UsbTunnel:
    def __init__(self, agent_port: int, interval: float = 3.0) -> None:
        self.agent_port = agent_port
        self.interval = interval
        self.adb = shutil.which("adb")
        self.phones: list[str] = []
        if self.adb:
            threading.Thread(target=self._loop, daemon=True).start()

    def _run(self, *args: str) -> str:
        try:
            return subprocess.run([self.adb, *args], capture_output=True, text=True, timeout=10,
                                  creationflags=NO_WINDOW).stdout
        except Exception:  # noqa: BLE001
            return ""

    def _loop(self) -> None:
        want = f"tcp:{PHONE_PORT} tcp:{self.agent_port}"
        while True:
            serials = [line.split("\t")[0] for line in self._run("devices").splitlines()[1:]
                       if line.strip().endswith("\tdevice")]
            for s in serials:
                if want not in self._run("-s", s, "reverse", "--list"):
                    self._run("-s", s, "reverse", f"tcp:{PHONE_PORT}", f"tcp:{self.agent_port}")
                    print(f"[usb] tunnel restored for phone {s}")
            self.phones = serials
            time.sleep(self.interval)
