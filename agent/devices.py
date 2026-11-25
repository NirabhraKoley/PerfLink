"""
Which phones may connect to this PC over Wi-Fi: an allow list and a block list.

A phone connecting over Wi-Fi says hello with an ID its app made up once (so the decision sticks
when its IP changes) and its name. A new phone needs the user's OK on the PC first (pop-up: Allow /
Block); blocked phones are refused straight away. Phones on USB (127.0.0.1) are physically plugged
into this PC and are always trusted. Older apps that don't say hello are known by their IP.

Stored in config.json under "devices" (the installer keeps config.json across updates).
"""
from __future__ import annotations

import time

ALLOWED, BLOCKED, UNKNOWN = "allowed", "blocked", "unknown"


def device_id(hello: dict | None, ip: str) -> str:
    did = str((hello or {}).get("id") or "").strip()
    return did[:64] if did else f"ip:{ip}"


def device_name(hello: dict | None, ip: str) -> str:
    name = str((hello or {}).get("name") or "").strip()
    return name[:60] if name else f"Phone at {ip}"


class Devices:
    def __init__(self, config: dict, save) -> None:
        self.config = config
        self.save = save   # writes config.json
        d = config.setdefault("devices", {})
        self.allowed: dict = d.setdefault("allowed", {})
        self.blocked: dict = d.setdefault("blocked", {})

    def status(self, did: str) -> str:
        if did in self.blocked:
            return BLOCKED
        if did in self.allowed:
            return ALLOWED
        return UNKNOWN

    def _entry(self, name: str, ip: str, old: dict | None) -> dict:
        return {"name": name, "ip": ip, "since": (old or {}).get("since") or time.strftime("%Y-%m-%d %H:%M"),
                "last": time.strftime("%Y-%m-%d %H:%M")}

    def allow(self, did: str, name: str, ip: str) -> None:
        self.blocked.pop(did, None)
        self.allowed[did] = self._entry(name, ip, self.allowed.get(did))
        self.save(self.config)

    def block(self, did: str, name: str, ip: str) -> None:
        self.allowed.pop(did, None)
        self.blocked[did] = self._entry(name, ip, self.blocked.get(did))
        self.save(self.config)

    def seen(self, did: str, ip: str) -> None:
        """An allowed phone connected again: remember its latest IP (saved only when it changed)."""
        e = self.allowed.get(did)
        if e is not None and e.get("ip") != ip:
            e["ip"] = ip
            e["last"] = time.strftime("%Y-%m-%d %H:%M")
            self.save(self.config)

    def unblock(self, did: str) -> None:
        """Off the block list: the phone is asked about again next time."""
        if self.blocked.pop(did, None) is not None:
            self.save(self.config)

    def forget(self, did: str) -> None:
        """Off the allow list: the phone is asked about again next time."""
        if self.allowed.pop(did, None) is not None:
            self.save(self.config)

    def listing(self) -> dict:
        return {"allowed": dict(self.allowed), "blocked": dict(self.blocked)}
