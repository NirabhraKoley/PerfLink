"""
WhatsApp notifications for the PerfLink Home page, mirrored from WhatsApp Desktop on the PC.

WhatsApp Desktop shows each new message as a Windows notification. Windows keeps the ones
still in the notification center (Action Center) in its notification database,
%LOCALAPPDATA%\\Microsoft\\Windows\\Notifications\\wpndatabase.db. This reads it (read-only)
every few seconds, so the phone shows the same messages as the PC: a message appears when
WhatsApp notifies, and goes away when it's dismissed on the PC or WhatsApp clears it
(for example, when the chat is opened).

Nothing is sent anywhere except to the phones connected to PerfLink.
"""
from __future__ import annotations

import os
import re
import sqlite3
import threading
import time
import xml.etree.ElementTree as ET

import psutil

DB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Microsoft", "Windows", "Notifications", "wpndatabase.db")
MAX_ITEMS = 20
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
    return {"title": title, "text": re.sub(r"\s+", " ", text)}


_CALL = re.compile(r"\b(voice|video|missed|incoming|outgoing)\s+(group\s+)?call\b|\bcalling\b|\bmissed call", re.I)
_MANY = re.compile(r"\b(\d+)\s+(new\s+)?messages\b", re.I)


def classify(title: str, text: str) -> tuple[str, int]:
    """('call' | 'message', how many). WhatsApp sums several messages up as 'N new messages'."""
    both = f"{title} {text}"
    if _CALL.search(both):
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
            kind, count = classify(t["title"], t["text"])
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


class WhatsAppNotifications:
    def __init__(self, interval: float = 3.0) -> None:
        self.interval = interval
        self.state: dict = {"installed": False, "running": False, "notifications": [], "messages": 0, "calls": 0,
                            "error": None}
        self._lock = threading.Lock()
        threading.Thread(target=self._loop, daemon=True).start()

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
                with self._lock:
                    self.state = {"installed": installed, "running": running, "notifications": items,
                                  "messages": sum(i["count"] for i in items if i["kind"] == "message"),
                                  "calls": sum(i["count"] for i in items if i["kind"] == "call"),
                                  "error": None}
            except Exception as e:  # noqa: BLE001  (database busy for a moment, etc.)
                with self._lock:
                    self.state = {**self.state, "error": f"WhatsApp: {e}"}
            n += 1
            time.sleep(self.interval)


if __name__ == "__main__":
    print(read_notifications())
