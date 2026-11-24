"""
Social apps for PerfLink's "Social" page: the notifications of every social / chat app that is
running on the PC, in detail (who, what, when).

How it works (same as the WhatsApp row on Home): each app's messages arrive as Windows
notifications, and Windows keeps the unread ones in its notification center. PerfLink mirrors that
list (read-only) every few seconds. When a message is read or a call is seen on the PC, the app clears
its notification and it disappears from the phone as well.

Only apps that are running are shown. Windows doesn't say whether an app is signed in, but an
app that isn't signed in doesn't get messages.

Web apps: some "apps" (Instagram from the Microsoft Store, Messenger, X, LinkedIn…) are websites in an
Edge or Chrome app window. They're recognised by their window title, and their notifications, which
Windows files under the browser, by the website they come from.
"""
from __future__ import annotations

import ctypes
import os
import re
import sqlite3
import threading
import time

import psutil

from whatsapp import DB, classify, parse_toast, _filetime_to_unix

MAX_PER_APP = 30

# key, display name, matches the app's notification id (lower case), process names, Store package name
APPS = [
    ("whatsapp", "WhatsApp", ("whatsapp",), ("whatsapp.exe", "whatsapp.root.exe"), "5319275a.whatsappdesktop"),
    ("telegram", "Telegram", ("telegram",), ("telegram.exe",), "telegrammessengerllp.telegramdesktop"),
    ("discord", "Discord", ("discord",), ("discord.exe", "discordptb.exe", "discordcanary.exe"), None),
    ("instagram", "Instagram", ("instagram",), ("instagram.exe",), "facebook.instagrambeta"),
    ("messenger", "Messenger", ("messenger", "facebook.317180b0bb486"), ("messenger.exe",), "facebook.317180b0bb486"),
    ("facebook", "Facebook", ("facebook.facebook",), ("facebook.exe",), "facebook.facebook"),
    ("signal", "Signal", ("signal",), ("signal.exe",), None),
    ("slack", "Slack", ("slack",), ("slack.exe",), None),
    ("teams", "Microsoft Teams", ("msteams", "microsoft.teams", "teams"), ("ms-teams.exe", "teams.exe"), "msteams"),
    ("skype", "Skype", ("skype",), ("skype.exe",), "microsoft.skypeapp"),
    ("viber", "Viber", ("viber",), ("viber.exe",), None),
    ("x", "X (Twitter)", ("twitter",), ("twitter.exe",), "9e2f88e3.twitter"),
    ("linkedin", "LinkedIn", ("linkedin",), ("linkedin.exe",), "7eadb8ac.linkedinforwindows"),
    ("wechat", "WeChat", ("wechat", "tencent"), ("wechat.exe", "weixin.exe"), None),
    ("zoom", "Zoom", ("zoom",), ("zoom.exe",), None),
]


# window titles of each app (also when it's a web app in a browser window or tab)
TITLES = {
    "whatsapp": r"\bWhatsApp\b", "telegram": r"\bTelegram\b", "discord": r"\bDiscord\b",
    "instagram": r"\bInstagram\b", "messenger": r"\bMessenger\b", "facebook": r"\bFacebook\b",
    "signal": r"^Signal\b", "slack": r"\bSlack\b", "teams": r"Microsoft Teams", "skype": r"\bSkype\b",
    "viber": r"\bViber\b", "x": r"(/ X$|\bTwitter\b)", "linkedin": r"\bLinkedIn\b", "wechat": r"\bWeChat\b",
}
# websites, for notifications that Windows files under a browser (web apps)
SITES = {
    "instagram.com": "instagram", "messenger.com": "messenger", "facebook.com": "facebook",
    "x.com": "x", "twitter.com": "x", "linkedin.com": "linkedin", "web.whatsapp.com": "whatsapp",
    "discord.com": "discord", "web.telegram.org": "telegram", "teams.microsoft.com": "teams",
    "teams.live.com": "teams", "app.slack.com": "slack",
}
_BROWSERS = re.compile(r"msedge|microsoftedge|chrome|brave|opera|vivaldi|firefox|_crx_", re.I)


def _window_titles() -> list[str]:
    """Titles of the visible top-level windows."""
    user32 = ctypes.windll.user32
    titles: list[str] = []
    proto = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p)

    def each(hwnd, _):
        if user32.IsWindowVisible(hwnd):
            n = user32.GetWindowTextLengthW(hwnd)
            if n:
                buf = ctypes.create_unicode_buffer(n + 1)
                user32.GetWindowTextW(hwnd, buf, n + 1)
                titles.append(buf.value)
        return True

    try:
        user32.EnumWindows(proto(each), 0)
    except Exception:  # noqa: BLE001
        pass
    return titles


def _attribute_values(payload) -> str:
    """Every attribute value in a notification's XML (links, image addresses, action arguments)."""
    if isinstance(payload, bytes):
        payload = payload.decode("utf-8", errors="replace")
    try:
        import xml.etree.ElementTree as ET
        return " ".join(v for el in ET.fromstring(payload).iter() for v in el.attrib.values())
    except Exception:  # noqa: BLE001
        return ""


def _site_app(*texts: str) -> str | None:
    blob = " ".join(texts).lower()
    for site, key in SITES.items():
        if site in blob:
            return key
    return None


def _running_apps() -> set[str]:
    """Keys of the apps above that have a process running (by name, or Store apps by their folder)."""
    names, paths = set(), []
    for p in psutil.process_iter(["name", "exe"]):
        n = (p.info.get("name") or "").lower()
        names.add(n)
        exe = (p.info.get("exe") or "").lower()
        if "windowsapps" in exe:
            paths.append(exe)
    out = set()
    for key, _, _, procs, pkg in APPS:
        if any(n in names for n in procs) or (pkg and any(pkg in x for x in paths)):
            out.add(key)
    for title in _window_titles():  # web apps (Edge / Chrome app windows and tabs)
        for key, pat in TITLES.items():
            if re.search(pat, title):
                out.add(key)
    return out


def _app_for(primary_id: str) -> str | None:
    pid = primary_id.lower()
    for key, _, ids, _, _ in APPS:
        if any(i in pid for i in ids):
            return key
    return None


def read_all(db_path: str = DB) -> tuple[set[str], dict[str, list[dict]]]:
    """(apps registered for notifications, {app: notifications newest first})."""
    con = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=2)
    try:
        handlers = {}
        for rid, primary in con.execute("SELECT RecordId, PrimaryId FROM NotificationHandler"):
            key = _app_for(primary or "") or ("@browser" if _BROWSERS.search(primary or "") else None)
            if key:
                handlers[rid] = key
        if not handlers:
            return set(), {}
        marks = ",".join("?" * len(handlers))
        rows = con.execute(
            f"SELECT Id, HandlerId, Payload, ArrivalTime FROM Notification WHERE HandlerId IN ({marks}) "
            f"AND Type = 'toast' ORDER BY ArrivalTime DESC", list(handlers)).fetchall()
    finally:
        con.close()
    out: dict[str, list[dict]] = {}
    for nid, hid, payload, arrival in rows:
        key = handlers[hid]
        t = parse_toast(payload)
        if not t:
            continue
        if key == "@browser":  # a web app's notification: which website sent it?
            # Edge says so in the attribution line; Firefox and Chrome in the notification's links
            # and images (launch / action / image addresses). Message text isn't used, so a mail
            # that mentions instagram.com isn't mistaken for Instagram.
            key = _site_app(t.get("attribution", ""), t.get("launch", ""), _attribute_values(payload))
            if not key:
                continue
        if len(out.get(key, [])) >= MAX_PER_APP:
            continue
        kind, count = classify(t["title"], t["text"], t["scenario"], t["actions"])
        out.setdefault(key, []).append({"id": f"{key}-{nid}", "title": t["title"], "text": t["text"],
                                        "t": _filetime_to_unix(arrival), "kind": kind, "count": count})
    return {k for k in handlers.values() if k != "@browser"} | set(out), out


class SocialApps:
    def __init__(self, whatsapp=None, interval: float = 3.0) -> None:
        self.interval = interval
        self.whatsapp = whatsapp  # WhatsApp's remembered calls (see whatsapp.py) are added to its list
        self.state: dict = {"apps": [], "error": None}
        self._lock = threading.Lock()
        threading.Thread(target=self._loop, daemon=True).start()

    def snapshot(self) -> dict:
        with self._lock:
            return dict(self.state)

    def _loop(self) -> None:
        n, running = 0, set()
        names = {key: name for key, name, *_ in APPS}
        while True:
            try:
                if n % 4 == 0:  # the process list is slower: every ~12 s
                    running = _running_apps()
                registered, notes = read_all()
                apps = []
                for key, *_ in APPS:
                    if key not in running:
                        continue  # only apps open on the PC (and so able to receive messages)
                    items = list(notes.get(key, []))
                    if key == "whatsapp" and self.whatsapp:
                        wa = self.whatsapp.snapshot()  # includes calls seen ringing, until WhatsApp is opened
                        items = [i for i in items if i["kind"] != "call"] + \
                                [i for i in wa.get("notifications", []) if i.get("kind") == "call"]
                        items.sort(key=lambda i: i.get("t") or 0, reverse=True)
                    apps.append({
                        "key": key, "name": names[key], "registered": key in registered,
                        "messages": sum(i.get("count", 1) for i in items if i.get("kind") != "call"),
                        "calls": sum(1 for i in items if i.get("kind") == "call"),
                        "notifications": items[:MAX_PER_APP],
                    })
                apps.sort(key=lambda a: -(a["messages"] + a["calls"]))
                with self._lock:
                    self.state = {"apps": apps, "error": None}
            except Exception as e:  # noqa: BLE001
                with self._lock:
                    self.state = {**self.state, "error": f"Social: {e}"}
            n += 1
            time.sleep(self.interval)


if __name__ == "__main__":
    import json
    s = SocialApps()
    time.sleep(4)
    print(json.dumps([{k: (len(v) if k == "notifications" else v) for k, v in a.items()} for a in s.snapshot()["apps"]], indent=1))
