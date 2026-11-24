"""
Extras for the PerfLink home screen: now-playing media + transport control,
master volume, and weather (Open-Meteo, no API key).

All of this runs on the asyncio main thread: WinRT async calls are awaited
directly and pycaw (COM) stays on the thread that initialised it.
"""
from __future__ import annotations

import asyncio
import base64
import datetime as dt
import hashlib
import json
import os
import sys

# WinRT initialises the main thread as a multithreaded apartment; comtypes (pycaw)
# must use the same mode or it fails with "Cannot change thread mode".
sys.coinit_flags = 0  # COINIT_MULTITHREADED
import time

HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, "config.json")


# ----------------------------------------------------------------------------- config
def load_config() -> dict:
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_config(cfg: dict) -> None:
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)


# ----------------------------------------------------------------------------- media
class Media:
    def __init__(self) -> None:
        self.ok = False
        self.error: str | None = None
        self.state: dict = {"playing": False, "title": None, "artist": None, "album": None,
                            "app": None, "position_s": None, "duration_s": None, "art_id": None}
        self.art_id: str | None = None
        self.art_b64: str | None = None
        self.on_art = None  # callback(art_id, b64) when the cover changes
        self._mgr = None
        self._last_key = None
        self._pos = (0.0, 0.0, False)  # (position at sample, monotonic sample time, playing)
        try:
            from winrt.windows.media.control import (  # type: ignore
                GlobalSystemMediaTransportControlsSessionManager as Manager,
                GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
            )
            self._Manager, self._Status = Manager, Status
            self.ok = True
        except Exception as e:  # noqa: BLE001
            self.error = f"media: {e}"

    async def _session(self):
        if self._mgr is None:
            self._mgr = await self._Manager.request_async()
        return self._mgr.get_current_session()

    async def poll(self) -> None:
        if not self.ok:
            return
        try:
            s = await self._session()
        except Exception as e:  # noqa: BLE001
            self.error = f"media: {e}"
            return
        if s is None:
            self.state.update(playing=False, title=None, artist=None, album=None, app=None,
                              position_s=None, duration_s=None, art_id=None)
            self._last_key = None
            return
        info = s.get_playback_info()
        playing = info.playback_status == self._Status.PLAYING
        props = await s.try_get_media_properties_async()
        key = (props.title, props.artist, props.album_title)
        if key != self._last_key:
            self._last_key = key
            await self._load_art(props)
        tl = s.get_timeline_properties()
        dur = tl.end_time.total_seconds() if tl.end_time else 0
        pos = tl.position.total_seconds() if tl.position else 0
        # Many apps only update the timeline occasionally: extrapolate while playing
        try:
            age = (dt.datetime.now(dt.timezone.utc) - tl.last_updated_time).total_seconds()
        except Exception:  # noqa: BLE001
            age = 0
        if playing and 0 < age < 3600:
            pos += age
        if dur > 0:
            pos = min(pos, dur)
        app = s.source_app_user_model_id or ""
        self.state.update(
            playing=playing, title=props.title or None, artist=props.artist or None,
            album=props.album_title or None, app=_pretty_app(app),
            position_s=round(pos, 1) if dur > 0 else None,
            duration_s=round(dur, 1) if dur > 0 else None, art_id=self.art_id,
        )

    async def _load_art(self, props) -> None:
        self.art_id, self.art_b64 = None, None
        if props.thumbnail is None:
            return
        try:
            from winrt.windows.storage.streams import Buffer, InputStreamOptions  # type: ignore
            stream = await props.thumbnail.open_read_async()
            size = int(stream.size)
            if not 0 < size < 4_000_000:
                return
            buf = Buffer(size)
            await stream.read_async(buf, size, InputStreamOptions.READ_AHEAD)
            data = bytes(memoryview(buf))[: buf.length]
            self.art_id = hashlib.sha1(data).hexdigest()[:12]
            self.art_b64 = base64.b64encode(data).decode()
            if self.on_art:
                self.on_art(self.art_id, self.art_b64)
        except Exception as e:  # noqa: BLE001
            self.error = f"media art: {e}"

    async def command(self, action: str, value=None) -> None:
        if not self.ok:
            return
        s = await self._session()
        if s is None:
            return
        if action == "play_pause":
            await s.try_toggle_play_pause_async()
        elif action == "next":
            await s.try_skip_next_async()
        elif action == "prev":
            await s.try_skip_previous_async()
        elif action == "seek" and value is not None:
            await s.try_change_playback_position_async(int(float(value) * 10_000_000))
        await asyncio.sleep(0.15)
        await self.poll()


def _pretty_app(aumid: str) -> str | None:
    if not aumid:
        return None
    low = aumid.lower()
    for k, v in (("spotify", "Spotify"), ("chrome", "Chrome"), ("msedge", "Edge"),
                 ("firefox", "Firefox"), ("zunemusic", "Media Player"), ("vlc", "VLC"),
                 ("brave", "Brave"), ("opera", "Opera"), ("youtube", "YouTube Music")):
        if k in low:
            return v
    name = aumid.split("!")[0].split("\\")[-1]
    return name.removesuffix(".exe")


# ----------------------------------------------------------------------------- volume
class Volume:
    def __init__(self) -> None:
        self.ok = False
        self.error: str | None = None
        self._ep = None
        self._ep_time = 0.0

    def _endpoint(self):
        # Re-acquire periodically so switching the default output device is picked up
        if self._ep is None or time.monotonic() - self._ep_time > 10:
            from pycaw.pycaw import AudioUtilities  # type: ignore
            self._ep = AudioUtilities.GetSpeakers().EndpointVolume
            self._ep_time = time.monotonic()
        return self._ep

    def read(self) -> dict:
        try:
            ep = self._endpoint()
            self.ok = True
            return {"level": round(ep.GetMasterVolumeLevelScalar() * 100), "muted": bool(ep.GetMute())}
        except Exception as e:  # noqa: BLE001
            self.error = f"volume: {e}"
            self._ep = None
            return {"level": None, "muted": None}

    def set_level(self, pct: float) -> None:
        ep = self._endpoint()
        ep.SetMasterVolumeLevelScalar(max(0.0, min(1.0, float(pct) / 100)), None)
        if ep.GetMute() and pct > 0:
            ep.SetMute(0, None)

    def toggle_mute(self) -> None:
        ep = self._endpoint()
        ep.SetMute(0 if ep.GetMute() else 1, None)


# ----------------------------------------------------------------------------- weather
