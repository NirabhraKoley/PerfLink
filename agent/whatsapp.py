"""
WhatsApp notifications for the PerfLink Home page, mirrored from WhatsApp Desktop on the PC.

WhatsApp Desktop shows each new message as a Windows notification, and a call as one while it rings. Windows keeps the ones
still in the notification center (Action Center) in its notification database,
%LOCALAPPDATA%\\Microsoft\\Windows\\Notifications\\wpndatabase.db. This reads it (read-only)
every few seconds, so the phone shows the same messages as the PC: a message appears when
WhatsApp notifies, and goes away when it's dismissed on the PC or WhatsApp clears it
(for example, when the chat is opened).

Calls: WhatsApp removes a call's notification as soon as the call ends, so the notification list
alone never shows missed calls. PerfLink checks every 2 seconds, remembers each call it sees
ringing, and keeps counting it until WhatsApp is next opened on the PC (like WhatsApp's own Calls
badge, which clears once you look at it). Remembered calls survive a PerfLink restart.

Nothing is sent anywhere except to the phones connected to PerfLink.
"""
from __future__ import annotations

import ctypes
import json
import os
import re
import sqlite3
import threading
import time
import xml.etree.ElementTree as ET

import psutil

DB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Microsoft", "Windows", "Notifications", "wpndatabase.db")
MAX_ITEMS = 20
CALLS_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cache", "whatsapp_calls.json")
_FILETIME_EPOCH = 11644473600  # seconds between 1601-01-01 and 1970-01-01


def _filetime_to_unix(ft) -> float | None:
    try:
        ft = int(ft)
    except (TypeError, ValueError):
        return None
    return ft / 10_000_000 - _FILETIME_EPOCH if ft > 0 else None


def parse_toast(payload) -> dict | None:
    """Toast XML -> {'title', 'text'}. WhatsApp puts the chat name in the first <text>, the
    message in the second (in groups: 'Sender: message')."""
    if isinstance(payload, bytes):
        payload = payload.decode("utf-8", errors="replace")
    if not payload or "<toast" not in payload:
        return None
    try:
        root = ET.fromstring(payload)
    except ET.ParseError:
        return None
    texts = [(t.text or "").strip() for t in root.iter("text")]
    texts = [t for t in texts if t]
    if not texts:
        return None
    title = texts[0]
    text = " ".join(texts[1:]) if len(texts) > 1 else ""
    actions = [(a.get("content") or "").strip() for a in root.iter("action")]
    return {"title": title, "text": re.sub(r"\s+", " ", text), "scenario": root.get("scenario") or "",
            "actions": [a for a in actions if a]}


_CALL = re.compile(r"\b(voice|video|missed|incoming|outgoing)\s+(group\s+)?call\b|\bcalling\b|\bmissed call", re.I)
_CALL_BUTTONS = re.compile(r"^(accept|answer|decline|reject|ignore)\b", re.I)
_MANY = re.compile(r"\b(\d+)\s+(new\s+)?messages\b", re.I)


def classify(title: str, text: str, scenario: str = "", actions: list[str] | None = None) -> tuple[str, int]:
    """('call' | 'message', how many). A ringing call is an 'incomingCall' notification with
    Accept/Decline buttons. WhatsApp sums several messages up as 'N new messages'."""
    both = f"{title} {text}"
    if scenario.lower() == "incomingcall" or any(_CALL_BUTTONS.match(a) for a in actions or []) \
            or _CALL.search(both):
        return "call", 1
    m = _MANY.search(both)
    return "message", int(m.group(1)) if m else 1


def read_notifications(db_path: str = DB) -> tuple[bool, list[dict]]:
    """(WhatsApp is registered for notifications, its notifications newest first)."""
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=2)
    try:
        handlers = [r[0] for r in con.execute(
            "SELECT RecordId FROM NotificationHandler WHERE PrimaryId LIKE '%WhatsApp%'")]
        if not handlers:
            return False, []
        marks = ",".join("?" * len(handlers))
        rows = con.execute(
            f"SELECT Id, Payload, ArrivalTime FROM Notification WHERE HandlerId IN ({marks}) "
            f"AND Type = 'toast' ORDER BY ArrivalTime DESC LIMIT {MAX_ITEMS * 2}", handlers).fetchall()
    finally:
        con.close()
    items = []
    for nid, payload, arrival in rows:
        t = parse_toast(payload)
        if t:
            kind, count = classify(t["title"], t["text"], t["scenario"], t["actions"])
            items.append({"id": str(nid), "title": t["title"], "text": t["text"], "t": _filetime_to_unix(arrival),
                          "kind": kind, "count": count})
        if len(items) >= MAX_ITEMS:
            break
    return True, items


def _whatsapp_running() -> bool:
    for p in psutil.process_iter(["name"]):
        if "whatsapp" in (p.info.get("name") or "").lower():
            return True
    return False


def _whatsapp_in_front() -> bool:
    """Is a WhatsApp window the one in front (the user is looking at WhatsApp)?"""
    try:
        user32 = ctypes.windll.user32
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return False
        pid = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        return "whatsapp" in psutil.Process(pid.value).name().lower()
    except Exception:  # noqa: BLE001
        return False


class WhatsAppNotifications:
    def __init__(self, interval: float = 2.0) -> None:
        self.interval = interval
        self.state: dict = {"installed": False, "running": False, "notifications": [], "messages": 0, "calls": 0,
                            "error": None}
        self._lock = threading.Lock()
        self._calls: dict[str, dict] = self._load_calls()  # calls seen ringing, until WhatsApp is opened
        threading.Thread(target=self._loop, daemon=True).start()

    @staticmethod
    def _load_calls() -> dict:
        try:
            with open(CALLS_FILE, encoding="utf-8") as f:
                data = json.load(f)
            return {k: v for k, v in data.items() if isinstance(v, dict)}
        except (OSError, ValueError):
            return {}

    def _save_calls(self) -> None:
        try:
            os.makedirs(os.path.dirname(CALLS_FILE), exist_ok=True)
            with open(CALLS_FILE + ".tmp", "w", encoding="utf-8") as f:
                json.dump(self._calls, f)
            os.replace(CALLS_FILE + ".tmp", CALLS_FILE)
        except OSError:
            pass

    def _track_calls(self, items: list[dict], in_front: bool) -> None:
        changed = False
        if in_front and self._calls:   # the user opened WhatsApp on the PC: calls have been seen
            self._calls.clear()
            changed = True
        for i in items:
            if i["kind"] == "call" and i["id"] not in self._calls and not in_front:
                self._calls[i["id"]] = {"id": "call-" + i["id"], "title": i["title"],
                                        "text": i["text"] or "Call", "t": i["t"] or time.time()}
                changed = True
        if changed:
            self._save_calls()

    def snapshot(self) -> dict:
        with self._lock:
            return dict(self.state)

    def _loop(self) -> None:
        n = 0
        running = False
        while True:
            try:
                if n % 5 == 0:  # the process list is slower: check it every ~15 s
                    running = _whatsapp_running()
                installed, items = read_notifications()
                self._track_calls(items, _whatsapp_in_front())
                messages = [i for i in items if i["kind"] == "message"]
                calls = sorted(self._calls.values(), key=lambda c: c.get("t") or 0, reverse=True)
                shown = sorted(messages + [{**c, "kind": "call", "count": 1} for c in calls],
                               key=lambda i: i.get("t") or 0, reverse=True)[:MAX_ITEMS]
                with self._lock:
                    self.state = {"installed": installed, "running": running, "notifications": shown,
                                  "messages": sum(i["count"] for i in messages),
                                  "calls": len(calls),
                                  "error": None}
            except Exception as e:  # noqa: BLE001  (database busy for a moment, etc.)
                with self._lock:
                    self.state = {**self.state, "error": f"WhatsApp: {e}"}
            n += 1
            time.sleep(self.interval)


if __name__ == "__main__":
    print(read_notifications())
