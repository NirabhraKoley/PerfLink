"""
OBS Studio remote control for the PerfLink "OBS" page.

Talks to OBS's built-in obs-websocket v5 server (OBS 28+) on localhost. The
password is read from OBS's own config, so nothing needs to be typed. Exposes:
scenes (switch), record/stream/virtual-cam/replay-buffer state + toggles,
audio inputs (mute + volume), OBS stats and a small preview of the program scene.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import itertools
import json
import os
import time

import psutil
import websockets

OBS_CONFIG = os.path.join(os.environ.get("APPDATA", ""), "obs-studio", "plugin_config", "obs-websocket", "config.json")
OBS_EXE = r"C:\Program Files\obs-studio\bin\64bit\obs64.exe"


def _obs_running() -> bool:
    for p in psutil.process_iter(["name"]):
        if (p.info["name"] or "").lower() == "obs64.exe":
            return True
    return False


def _read_config() -> dict:
    try:
        with open(OBS_CONFIG, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


class ObsError(Exception):
    pass


class Obs:
    def __init__(self) -> None:
        self.status = "starting"  # not_installed | not_running | disabled | auth_failed | connecting | connected
        self.message: str | None = None
        self.version: str | None = None
        self.scenes: list[str] = []
        self.current_scene: str | None = None
        self.record = {"active": False, "paused": False, "timecode": None}
        self.stream = {"active": False, "timecode": None, "kbps": None, "dropped": 0, "total": 0, "congestion": None}
        self.virtualcam = False
        self.replay = {"available": False, "active": False}
        self.inputs: dict[str, dict] = {}  # name -> {muted, mul, db}
        self.stats: dict = {}
        self.preview_b64: str | None = None
        self.preview_wanted = False
        self._ws = None
        self._ids = itertools.count(1)
        self._pending: dict[str, asyncio.Future] = {}
        self._last_bytes: tuple[float, int] | None = None
        self.on_change = None  # async callback when something changes (push to phones)

    # ------------------------------------------------------------------ public
    def snapshot(self) -> dict:
        return {
            "status": self.status, "message": self.message, "version": self.version,
            "scenes": self.scenes, "current_scene": self.current_scene,
            "record": dict(self.record), "stream": dict(self.stream),
            "virtualcam": self.virtualcam, "replay": dict(self.replay),
            "inputs": [{"name": n, **v} for n, v in self.inputs.items()],
            "stats": dict(self.stats),
        }

    async def run(self) -> None:
        """Connect/reconnect forever."""
        while True:
            if not os.path.exists(OBS_EXE) and not _read_config():
                self._set("not_installed", "OBS Studio is not installed on the PC.")
            elif not _obs_running():
                self._set("not_running", "OBS is not running. Tap Open OBS to start it.")
            else:
                cfg = _read_config()
                if not cfg.get("server_enabled", False):
                    self._set("disabled", "Turn on OBS's WebSocket server: in OBS open Tools → "
                                          "WebSocket Server Settings → tick 'Enable WebSocket server' → OK.")
                else:
                    try:
                        await self._session(cfg)
                    except ObsError as e:
                        pass  # status already set
                    except Exception as e:  # noqa: BLE001
                        self._set("connecting", f"Connecting to OBS… ({e.__class__.__name__})")
            self._reset_state()
            await asyncio.sleep(3)

    async def command(self, action: str, msg: dict) -> None:
        if self.status != "connected":
            raise ObsError("OBS is not connected")
        if action == "scene":
            await self.req("SetCurrentProgramScene", {"sceneName": str(msg["value"])})
        elif action == "toggle_record":
            await self.req("ToggleRecord")
        elif action == "toggle_pause":
            await self.req("ToggleRecordPause")
        elif action == "toggle_stream":
            await self.req("ToggleStream")
        elif action == "toggle_virtualcam":
            await self.req("ToggleVirtualCam")
        elif action == "toggle_replay":
            await self.req("ToggleReplayBuffer")
        elif action == "save_replay":
            await self.req("SaveReplayBuffer")
        elif action == "mute":
            await self.req("ToggleInputMute", {"inputName": str(msg["input"])})
        elif action == "volume":
            mul = max(0.0, min(1.0, float(msg["value"])))
            await self.req("SetInputVolume", {"inputName": str(msg["input"]), "inputVolumeMul": mul})
        else:
            raise ObsError(f"unknown OBS action '{action}'")
        await asyncio.sleep(0.1)
        await self._poll_status()

    # ------------------------------------------------------------------ protocol
    def _set(self, status: str, message: str | None) -> None:
        self.status, self.message = status, message

    def _reset_state(self) -> None:
        self.scenes, self.current_scene, self.inputs, self.stats = [], None, {}, {}
        self.record = {"active": False, "paused": False, "timecode": None}
        self.stream = {"active": False, "timecode": None, "kbps": None, "dropped": 0, "total": 0, "congestion": None}
        self.virtualcam, self.replay, self.preview_b64 = False, {"available": False, "active": False}, None

    async def req(self, rtype: str, data: dict | None = None, timeout: float = 5.0):
        if self._ws is None:
            raise ObsError("not connected")
        rid = str(next(self._ids))
        fut = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        await self._ws.send(json.dumps({"op": 6, "d": {"requestType": rtype, "requestId": rid, "requestData": data or {}}}))
        try:
            d = await asyncio.wait_for(fut, timeout)
        finally:
            self._pending.pop(rid, None)
        st = d.get("requestStatus", {})
        if not st.get("result"):
            raise ObsError(st.get("comment") or f"{rtype} failed ({st.get('code')})")
        return d.get("responseData") or {}

    async def _session(self, cfg: dict) -> None:
        port = int(cfg.get("server_port", 4455))
        self._set("connecting", "Connecting to OBS…")
        async with websockets.connect(f"ws://127.0.0.1:{port}", subprotocols=["obswebsocket.json"],
                                      max_size=None, open_timeout=5) as ws:
            hello = json.loads(await ws.recv())["d"]
            ident = {"rpcVersion": 1}
            auth = hello.get("authentication")
            if auth:
                pw = cfg.get("server_password", "") if cfg.get("auth_required", True) else ""
                secret = base64.b64encode(hashlib.sha256((pw + auth["salt"]).encode()).digest()).decode()
                ident["authentication"] = base64.b64encode(
                    hashlib.sha256((secret + auth["challenge"]).encode()).digest()).decode()
            await ws.send(json.dumps({"op": 1, "d": ident}))
            try:
                first = json.loads(await ws.recv())
            except websockets.ConnectionClosed as e:
                if e.rcvd and e.rcvd.code == 4009:
                    self._set("auth_failed", "OBS rejected the password. Re-open the agent after changing it in OBS.")
                    raise ObsError("auth") from e
                raise
            if first.get("op") != 2:
                raise ObsError("unexpected handshake")
            self._ws = ws
            self.version = hello.get("obsWebSocketVersion")
            reader = asyncio.create_task(self._reader(ws))
            try:
                await self._load_all()
                self._set("connected", None)
                await self._changed()
                n = 0
                while not reader.done():
                    await self._poll_status()
                    if self.preview_wanted and self.current_scene:
                        await self._grab_preview()
                    n += 1
                    if n % 10 == 0:
                        await self._load_inputs()
                    await self._changed()
                    await asyncio.sleep(0.7)
            finally:
                reader.cancel()
                self._ws = None
                for f in self._pending.values():
                    if not f.done():
                        f.set_exception(ObsError("disconnected"))
                self._pending.clear()

    async def _reader(self, ws) -> None:
        async for raw in ws:
            m = json.loads(raw)
            op, d = m.get("op"), m.get("d", {})
            if op == 7:
                fut = self._pending.get(d.get("requestId"))
                if fut and not fut.done():
                    fut.set_result(d)
            elif op == 5:
                await self._event(d.get("eventType"), d.get("eventData") or {})

    async def _event(self, etype: str, data: dict) -> None:
        if etype == "CurrentProgramSceneChanged":
            self.current_scene = data.get("sceneName")
        elif etype in ("SceneListChanged", "SceneCreated", "SceneRemoved", "SceneNameChanged"):
            asyncio.create_task(self._load_scenes())
        elif etype == "InputMuteStateChanged" and data.get("inputName") in self.inputs:
            self.inputs[data["inputName"]]["muted"] = data.get("inputMuted")
        elif etype == "InputVolumeChanged" and data.get("inputName") in self.inputs:
            self.inputs[data["inputName"]].update(mul=data.get("inputVolumeMul"), db=_db(data.get("inputVolumeDb")))
        elif etype in ("InputCreated", "InputRemoved", "InputNameChanged"):
            asyncio.create_task(self._load_inputs())
        elif etype == "RecordStateChanged":
            self.record["active"] = bool(data.get("outputActive"))
        elif etype == "StreamStateChanged":
            self.stream["active"] = bool(data.get("outputActive"))
        elif etype == "VirtualcamStateChanged":
            self.virtualcam = bool(data.get("outputActive"))
        elif etype == "ReplayBufferStateChanged":
            self.replay["active"] = bool(data.get("outputActive"))
        elif etype == "ExitStarted":
            self._set("not_running", "OBS is closing.")
        else:
            return
        await self._changed()

    async def _changed(self) -> None:
        if self.on_change:
            await self.on_change()

    # ------------------------------------------------------------------ loaders
    async def _load_all(self) -> None:
        await self._load_scenes()
        await self._load_inputs()
        await self._poll_status()

    async def _load_scenes(self) -> None:
        r = await self.req("GetSceneList")
        scenes = sorted(r.get("scenes", []), key=lambda s: -s.get("sceneIndex", 0))  # OBS UI order
        self.scenes = [s["sceneName"] for s in scenes]
        self.current_scene = r.get("currentProgramSceneName")

    async def _load_inputs(self) -> None:
        r = await self.req("GetInputList")
        inputs: dict[str, dict] = {}
        for i in r.get("inputs", []):
            name = i["inputName"]
            try:
                vol = await self.req("GetInputVolume", {"inputName": name})
                mute = await self.req("GetInputMute", {"inputName": name})
            except ObsError:
                continue  # not an audio source
            inputs[name] = {"muted": mute.get("inputMuted", False), "mul": vol.get("inputVolumeMul"),
                            "db": _db(vol.get("inputVolumeDb"))}
        self.inputs = inputs

    async def _poll_status(self) -> None:
        rec = await self.req("GetRecordStatus")
        self.record = {"active": rec.get("outputActive", False), "paused": rec.get("outputPaused", False),
                       "timecode": _tc(rec.get("outputTimecode")) if rec.get("outputActive") else None}
        st = await self.req("GetStreamStatus")
        kbps = None
        if st.get("outputActive"):
            now, b = time.monotonic(), int(st.get("outputBytes", 0))
            if self._last_bytes and now > self._last_bytes[0] and b >= self._last_bytes[1]:
                kbps = round((b - self._last_bytes[1]) * 8 / 1000 / (now - self._last_bytes[0]))
            self._last_bytes = (now, b)
        else:
            self._last_bytes = None
        self.stream = {"active": st.get("outputActive", False),
                       "timecode": _tc(st.get("outputTimecode")) if st.get("outputActive") else None,
                       "kbps": kbps if kbps is not None else (self.stream.get("kbps") if st.get("outputActive") else None),
                       "dropped": st.get("outputSkippedFrames", 0), "total": st.get("outputTotalFrames", 0),
                       "congestion": st.get("outputCongestion")}
        try:
            self.virtualcam = (await self.req("GetVirtualCamStatus")).get("outputActive", False)
        except ObsError:
            self.virtualcam = False
        try:
            self.replay = {"available": True, "active": (await self.req("GetReplayBufferStatus")).get("outputActive", False)}
        except ObsError:
            self.replay = {"available": False, "active": False}
        s = await self.req("GetStats")
        self.stats = {"fps": round(s.get("activeFps", 0), 1), "cpu": round(s.get("cpuUsage", 0), 1),
                      "memory_mb": round(s.get("memoryUsage", 0)),
                      "render_skipped": s.get("renderSkippedFrames", 0), "render_total": s.get("renderTotalFrames", 0),
                      "output_skipped": s.get("outputSkippedFrames", 0),
                      "frame_ms": round(s.get("averageFrameRenderTime", 0), 2),
                      "disk_free_mb": round(s.get("availableDiskSpace", 0))}

    async def _grab_preview(self) -> None:
        try:
            r = await self.req("GetSourceScreenshot", {"sourceName": self.current_scene, "imageFormat": "jpg",
                                                       "imageWidth": 480, "imageCompressionQuality": 55})
            data = r.get("imageData", "")
            self.preview_b64 = data.split(",", 1)[1] if "," in data else None
        except ObsError:
            self.preview_b64 = None


def _db(v) -> float | None:
    if v is None:
        return None
    return round(max(float(v), -100.0), 1)


def _tc(t: str | None) -> str | None:
    return t.split(".")[0] if t else None  # "00:01:23.456" -> "00:01:23"


def open_obs() -> str:
    if not os.path.exists(OBS_EXE):
        raise RuntimeError("OBS is not installed")
    if _obs_running():
        return "OBS is already running"
    import subprocess
    # OBS must be started from its own folder or it can't find its data files
    subprocess.Popen([OBS_EXE], cwd=os.path.dirname(OBS_EXE), close_fds=True,
                     creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
    return "Starting OBS…"
