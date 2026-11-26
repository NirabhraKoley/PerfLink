"""
The iPhone / web version of PerfLink, served by the agent on its own port.

An iPhone (or any phone / tablet / computer with a browser) opens http://<PC-IP>:47800 in Safari and
gets the PerfLink web app from agent/web. Add to Home Screen makes it open full screen like an app.
It speaks the same WebSocket messages as the Android app on the same port, so it says hello and
waits for the user's Allow on the PC just like a new Android phone.

The port's WebSocket connections are left alone: only plain page requests (no "Upgrade: websocket")
are answered here, through websockets' process_request hook.
"""
from __future__ import annotations

import os

from websockets.datastructures import Headers
from websockets.http11 import Response

WEB_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "web")

TYPES = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".webmanifest": "application/manifest+json",
    ".json": "application/json",
    ".png": "image/png",
    ".svg": "image/svg+xml",
    ".ico": "image/x-icon",
}

# short names the page can be opened at (http://pc:47800/iphone is easy to type)
ALIASES = {"/": "index.html", "/index.html": "index.html", "/iphone": "index.html", "/ios": "index.html",
           "/app": "index.html"}


def resolve(path: str, web_dir: str = WEB_DIR) -> str | None:
    """The file under web_dir for a request path, or None (unknown, or trying to leave the folder)."""
    path = path.split("?", 1)[0].split("#", 1)[0]
    name = ALIASES.get(path.rstrip("/") or "/") or ALIASES.get(path)
    if name is None:
        name = path.lstrip("/")
        if not name or "\\" in name or ".." in name.split("/") or ":" in name:
            return None
    full = os.path.normpath(os.path.join(web_dir, name))
    root = os.path.normpath(web_dir)
    if os.path.commonpath([full, root]) != root or not os.path.isfile(full):
        return None
    if os.path.splitext(full)[1].lower() not in TYPES:
        return None
    return full


def page(path: str, web_dir: str = WEB_DIR) -> tuple[int, str, bytes]:
    """(status, content type, body) for a plain page request."""
    full = resolve(path, web_dir)
    if full is None:
        return 404, "text/plain; charset=utf-8", b"Not found. Open http://<this PC's IP>:47800 for PerfLink."
    with open(full, "rb") as f:
        return 200, TYPES[os.path.splitext(full)[1].lower()], f.read()


def is_websocket(headers) -> bool:
    return "websocket" in str(headers.get("Upgrade", "")).lower()


def process_request(connection, request):
    """websockets hook: WebSocket handshakes go through; anything else gets a page of the web app."""
    if is_websocket(request.headers):
        return None
    status, ctype, body = page(request.path)
    reason = "OK" if status == 200 else "Not Found"
    headers = Headers([
        ("Content-Type", ctype),
        ("Content-Length", str(len(body))),
        # always fresh: after a PerfLink update the phone gets the new page straight away
        ("Cache-Control", "no-cache"),
        ("X-Content-Type-Options", "nosniff"),
        ("Connection", "close"),
    ])
    return Response(status, reason, headers, body)
