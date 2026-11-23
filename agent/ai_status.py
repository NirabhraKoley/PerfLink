"""
Desktop AI apps + Claude Code sessions + GitHub, for the PerfLink "Sessions" page.

* AI apps: detects which known AI desktop apps are installed, whether they are
  running, their RAM/CPU and uptime. Ollama and LM Studio also report loaded models.
* Claude Code sessions: ~/.claude/sessions/<pid>.json (name, busy/idle, since when).
* GitHub: review requests, your open PRs and unread notifications via the `gh` CLI.

Everything is refreshed on background threads; `snapshot()` is cheap.
"""
from __future__ import annotations

import glob
import json
import os
import shutil
import subprocess
import threading
import time
import urllib.request

import psutil

HOME = os.path.expanduser("~")
LOCAL = os.environ.get("LOCALAPPDATA", "")
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)

# name, process names (lowercase), Appx package name fragments, install paths, local API
APPS = [
    {"id": "claude", "name": "Claude", "procs": {"claude.exe"}, "appx": ["Claude", "AnthropicClaude"],
     "paths": [os.path.join(LOCAL, "AnthropicClaude")], "exclude_cmd": "claude-code"},
    {"id": "chatgpt", "name": "ChatGPT", "procs": {"chatgpt.exe"}, "appx": ["OpenAI.ChatGPT"],
     "paths": [os.path.join(LOCAL, "Programs", "ChatGPT")]},
    {"id": "copilot", "name": "Microsoft Copilot", "procs": {"copilot.exe", "microsoft.copilot.exe"},
     "appx": ["Microsoft.Copilot"], "paths": []},
    {"id": "perplexity", "name": "Perplexity", "procs": {"perplexity.exe"}, "appx": ["Perplexity"],
     "paths": [os.path.join(LOCAL, "Programs", "Perplexity")]},
    {"id": "gemini", "name": "Gemini", "procs": {"gemini.exe"}, "appx": ["Google.Gemini"], "paths": []},
    {"id": "ollama", "name": "Ollama", "procs": {"ollama.exe", "ollama app.exe", "ollama_llama_server.exe"},
     "appx": [], "paths": [os.path.join(LOCAL, "Programs", "Ollama")], "api": "ollama"},
    {"id": "lmstudio", "name": "LM Studio", "procs": {"lm studio.exe", "lms.exe"}, "appx": [],
     "paths": [os.path.join(LOCAL, "Programs", "LM Studio"), os.path.join(LOCAL, "Programs", "lm-studio")],
     "api": "lmstudio"},
    {"id": "jan", "name": "Jan", "procs": {"jan.exe"}, "appx": [], "paths": [os.path.join(LOCAL, "Programs", "jan")]},
    {"id": "cursor", "name": "Cursor", "procs": {"cursor.exe"}, "appx": [],
     "paths": [os.path.join(LOCAL, "Programs", "cursor")]},
    {"id": "windsurf", "name": "Windsurf", "procs": {"windsurf.exe"}, "appx": [],
     "paths": [os.path.join(LOCAL, "Programs", "Windsurf")]},
]


def _http_json(url: str, timeout: float = 1.0):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return json.load(r)


class AiStatus:
    def __init__(self) -> None:
        self.apps: list[dict] = []
        self.sessions: list[dict] = []
        self.github: dict = {"available": False, "message": "Checking GitHub…"}
        self._appx: set[str] = set()
        self._procs: dict[int, psutil.Process] = {}
        self._lock = threading.Lock()
        threading.Thread(target=self._appx_loop, daemon=True).start()
        threading.Thread(target=self._apps_loop, daemon=True).start()
        threading.Thread(target=self._github_loop, daemon=True).start()

    # ------------------------------------------------------------------ installed Store apps
    def _appx_loop(self) -> None:
        while True:
            try:
                out = subprocess.run(
                    ["powershell", "-NoProfile", "-Command", "Get-AppxPackage | ForEach-Object Name"],
                    capture_output=True, text=True, timeout=60, creationflags=NO_WINDOW).stdout
                self._appx = {line.strip().lower() for line in out.splitlines() if line.strip()}
            except Exception:  # noqa: BLE001
                pass
            time.sleep(600)

    def _installed(self, app: dict) -> bool:
        if any(any(frag.lower() in n for n in self._appx) for frag in app["appx"]):
            return True
        return any(p and os.path.isdir(p) for p in app["paths"])

    # ------------------------------------------------------------------ running apps
    def _apps_loop(self) -> None:
        while True:
            try:
                self._scan_apps()
                self._scan_sessions()
            except Exception:  # noqa: BLE001
                pass
            time.sleep(2)

    def _scan_apps(self) -> None:
        by_app: dict[str, list[psutil.Process]] = {a["id"]: [] for a in APPS}
        seen: set[int] = set()
        for p in psutil.process_iter(["name", "exe"]):
            name = (p.info["name"] or "").lower()
            for a in APPS:
                if name in a["procs"]:
                    excl = a.get("exclude_cmd")
                    if excl and excl in (p.info["exe"] or "").lower():
                        continue  # Claude Code CLI binaries are shown as sessions instead
                    proc = self._procs.get(p.pid)
                    if proc is None:
                        proc = self._procs[p.pid] = p
                        try:
                            proc.cpu_percent(None)
                        except psutil.Error:
                            pass
                    by_app[a["id"]].append(proc)
                    seen.add(p.pid)
        for pid in list(self._procs):
            if pid not in seen:
                del self._procs[pid]

        ncpu = psutil.cpu_count() or 1
        apps = []
        for a in APPS:
            procs = by_app[a["id"]]
            running = bool(procs)
            if not running and not self._installed(a):
                continue
            ram = cpu = 0.0
            started = None
            for p in procs:
                try:
                    ram += p.memory_info().rss
                    cpu += p.cpu_percent(None)
                    ct = p.create_time()
                    started = ct if started is None else min(started, ct)
                except psutil.Error:
                    pass
            entry = {
                "id": a["id"], "name": a["name"], "running": running,
                "ram_mb": round(ram / 2**20) if running else None,
                "cpu": round(cpu / ncpu, 1) if running else None,
                "uptime_s": round(time.time() - started) if started else None,
                "processes": len(procs), "detail": None, "models": [],
            }
            if running and a.get("api") == "ollama":
                try:
                    ps = _http_json("http://127.0.0.1:11434/api/ps")
                    entry["models"] = [m.get("name") for m in ps.get("models", [])]
                    entry["detail"] = f"{len(entry['models'])} model(s) loaded" if entry["models"] else "No model loaded"
                except Exception:  # noqa: BLE001
                    entry["detail"] = "API not responding"
            elif running and a.get("api") == "lmstudio":
                try:
                    ms = _http_json("http://127.0.0.1:1234/v1/models")
                    entry["models"] = [m.get("id") for m in ms.get("data", [])]
                    entry["detail"] = f"Server on · {len(entry['models'])} model(s)"
                except Exception:  # noqa: BLE001
                    entry["detail"] = "Local server off"
            apps.append(entry)
        apps.sort(key=lambda e: (not e["running"], e["name"]))
        with self._lock:
            self.apps = apps

    # ------------------------------------------------------------------ Claude Code sessions
    def _scan_sessions(self) -> None:
        now_ms = time.time() * 1000
        out = []
        for f in glob.glob(os.path.join(HOME, ".claude", "sessions", "*.json")):
            try:
                with open(f, encoding="utf-8") as fh:
                    s = json.load(fh)
                pid = int(s.get("pid", 0))
                if not pid or not psutil.pid_exists(pid):
                    continue
                status = str(s.get("status") or "unknown").lower()
                since = s.get("statusUpdatedAt") or s.get("updatedAt") or s.get("startedAt") or now_ms
                cwd = s.get("cwd") or ""
                entry = s.get("entrypoint") or ""
                out.append({
                    "name": s.get("name") or os.path.basename(cwd.rstrip("\\/")) or "claude",
                    "status": {"busy": "Working", "idle": "Idle", "waiting": "Needs input"}.get(status, status.title()),
                    "busy": status == "busy",
                    "since_s": max(0, round((now_ms - since) / 1000)),
                    "folder": os.path.basename(cwd.rstrip("\\/")) or cwd,
                    "source": "Desktop" if "desktop" in entry else ("CLI" if "cli" in entry else entry or "CLI"),
                })
            except Exception:  # noqa: BLE001
                continue
        out.sort(key=lambda e: (not e["busy"], e["since_s"]))
        with self._lock:
            self.sessions = out

    # ------------------------------------------------------------------ GitHub
    def _gh(self, *args: str):
        r = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=30,
                           creationflags=NO_WINDOW, cwd=HOME)
        if r.returncode != 0:
            raise RuntimeError((r.stderr or r.stdout).strip().splitlines()[0] if (r.stderr or r.stdout) else "gh failed")
        return json.loads(r.stdout or "null")

    def _github_loop(self) -> None:
        fields = "number,title,repository,url,updatedAt"
        while True:
            if not shutil.which("gh"):
                self.github = {"available": False,
                               "message": "GitHub CLI not installed. Install it (winget install GitHub.cli) "
                                          "and run 'gh auth login' on the PC."}
            else:
                try:
                    reviews = self._gh("search", "prs", "--review-requested=@me", "--state=open",
                                       "--limit", "10", "--json", fields)
                    mine = self._gh("search", "prs", "--author=@me", "--state=open",
                                    "--limit", "10", "--json", fields)
                    notes = self._gh("api", "notifications")

                    def slim(prs):
                        return [{"number": p["number"], "title": p["title"],
                                 "repo": (p.get("repository") or {}).get("nameWithOwner", ""),
                                 "updated": p.get("updatedAt")} for p in prs or []]

                    self.github = {"available": True, "message": None,
                                   "notifications": len(notes or []),
                                   "review_requests": slim(reviews), "my_prs": slim(mine)}
                except Exception as e:  # noqa: BLE001
                    msg = str(e)
                    if "auth login" in msg or "not logged" in msg.lower():
                        msg = "Not signed in. Run 'gh auth login' on the PC."
                    self.github = {"available": False, "message": f"GitHub: {msg}"}
            time.sleep(60)

    # ------------------------------------------------------------------ public
    def snapshot(self) -> dict:
        with self._lock:
            return {"apps": list(self.apps), "sessions": list(self.sessions), "github": dict(self.github)}
