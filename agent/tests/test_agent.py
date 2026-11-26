"""Unit tests for the PC agent's pure logic. Run from the agent folder:  python -m unittest discover tests"""
import json
import os
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import bluetooth  # noqa: E402
import mirror  # noqa: E402
import usb_tunnel  # noqa: E402
import weather  # noqa: E402
import webapp  # noqa: E402
import whatsapp  # noqa: E402


class WhatsAppTests(unittest.TestCase):
    def test_parse_toast_chat_and_message(self):
        xml = ('<toast launch="x"><visual><binding template="ToastGeneric"><text>Mom</text>'
               '<text>Dinner   is\nready</text><text placement="attribution">whatsapp</text></binding></visual></toast>')
        t = whatsapp.parse_toast(xml.encode())
        self.assertEqual(t["title"], "Mom")
        self.assertEqual(t["text"], "Dinner is ready")
        self.assertEqual(t["attribution"], "whatsapp")

    def test_parse_toast_rejects_junk(self):
        self.assertIsNone(whatsapp.parse_toast(""))
        self.assertIsNone(whatsapp.parse_toast("<tile/>"))
        self.assertIsNone(whatsapp.parse_toast("<toast><broken"))
        self.assertIsNone(whatsapp.parse_toast("<toast><visual><binding><text> </text></binding></visual></toast>"))

    def test_classify(self):
        self.assertEqual(whatsapp.classify("Mom", "Hi"), ("message", 1))
        self.assertEqual(whatsapp.classify("Mom", "5 new messages"), ("message", 5))
        self.assertEqual(whatsapp.classify("Mom", "Incoming voice call"), ("call", 1))
        self.assertEqual(whatsapp.classify("Mom", "", scenario="incomingCall"), ("call", 1))
        self.assertEqual(whatsapp.classify("Mom", "", actions=["Accept", "Decline"]), ("call", 1))
        self.assertEqual(whatsapp.classify("Team", "Missed video call"), ("call", 1))

    def test_filetime(self):
        self.assertIsNone(whatsapp._filetime_to_unix(None))
        self.assertIsNone(whatsapp._filetime_to_unix(0))
        self.assertAlmostEqual(whatsapp._filetime_to_unix(116444736000000000), 0.0)


class WeatherTests(unittest.TestCase):
    def test_aqi_label(self):
        self.assertIsNone(weather.aqi_label(None))
        self.assertEqual(weather.aqi_label(10), "Good")
        self.assertEqual(weather.aqi_label(100), "Moderate")
        self.assertEqual(weather.aqi_label(180), "Unhealthy")
        self.assertEqual(weather.aqi_label(999), "Hazardous")

    def test_compass(self):
        self.assertIsNone(weather.compass(None))
        self.assertEqual(weather.compass(0), "N")
        self.assertEqual(weather.compass(359), "N")
        self.assertEqual(weather.compass(90), "E")
        self.assertEqual(weather.compass(225), "SW")
        self.assertEqual(weather.compass(-90), "W")


class UsbTests(unittest.TestCase):
    def test_version_tuple(self):
        v = usb_tunnel._version_tuple
        self.assertGreater(v("1.17"), v("1.9"))
        self.assertGreater(v("1.10"), v("1.9"))
        self.assertEqual(v(None), (0,))
        self.assertEqual(v("versionName=1.16"), (1, 16))


class BluetoothTests(unittest.TestCase):
    def test_type(self):
        t = bluetooth._type
        self.assertEqual(t("Galaxy Buds2", None, None), "Audio")
        self.assertEqual(t("MX Master Mouse", None, None), "Mouse")
        self.assertEqual(t("Xbox Wireless Controller", None, None), "Gamepad")
        self.assertEqual(t("Redmi Note", None, None), "Phone")
        self.assertEqual(t("Thing", None, 5), "Input")
        self.assertEqual(t("Thing", None, "junk"), "Device")


class MirrorTests(unittest.TestCase):
    def _mirror(self, monitors):
        m = mirror.Mirror.__new__(mirror.Mirror)
        m.monitors = monitors
        return m

    def test_to_desktop_maps_and_clamps(self):
        m = self._mirror([{"rect": {"x": -1920, "y": 0, "w": 1920, "h": 1080}, "off": False}])
        self.assertEqual(m.to_desktop(0, 0), (-1920, 0))
        self.assertEqual(m.to_desktop(0.5, 0.5), (-960, 540))
        self.assertEqual(m.to_desktop(5, -3), (-1, 0))   # clamped inside the monitor

    def test_to_desktop_skips_switched_off_monitor(self):
        m = self._mirror([{"rect": {"x": 0, "y": 0, "w": 100, "h": 100}, "off": True},
                          {"rect": {"x": 100, "y": 0, "w": 200, "h": 100}, "off": False}])
        self.assertEqual(m.to_desktop(0.5, 0.5), (200, 50))

    def test_monitor_rects_has_primary(self):
        rects = mirror._monitor_rects()
        self.assertTrue(rects, "no monitors found")
        self.assertEqual(sum(1 for r in rects if r["primary"]), 1)


class AgentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import perflink_agent
        cls.a = perflink_agent

    def test_identity(self):
        self.assertTrue(self.a.USER_NAME)
        self.assertIn(self.a.PC_KIND, ("PC", "Laptop"))
        self.assertRegex(self.a.VERSION, r"^\d+\.\d+$")

    def test_ram_modules_parsing(self):
        cim = [{"DeviceLocator": "Controller0-ChannelA-DIMM0", "Capacity": 8 * 2**30, "ConfiguredClockSpeed": 3200,
                "Speed": 3600, "SMBIOSMemoryType": 26, "Manufacturer": "Kingston "},
               {"DeviceLocator": "DIMM1", "Capacity": 16 * 2**30, "ConfiguredClockSpeed": None, "Speed": 4800,
                "SMBIOSMemoryType": 34, "Manufacturer": ""},
               {"DeviceLocator": "empty", "Capacity": 0}]
        fake = types.SimpleNamespace(stdout=json.dumps(cim))
        with mock.patch.object(subprocess, "run", return_value=fake):
            self.a._read_ram_modules()
        self.assertEqual(self.a.RAM_MODULES, [
            {"gb": 8, "mts": 3200, "type": "DDR4", "slot": "Controller0-ChannelA-DIMM0", "maker": "Kingston"},
            {"gb": 16, "mts": 4800, "type": "DDR5", "slot": "DIMM1", "maker": None},
        ])

    def test_ram_single_module_json_object(self):
        fake = types.SimpleNamespace(stdout=json.dumps({"DeviceLocator": "A", "Capacity": 2**34, "SMBIOSMemoryType": 99}))
        with mock.patch.object(subprocess, "run", return_value=fake):
            self.a._read_ram_modules()
        self.assertEqual(self.a.RAM_MODULES, [{"gb": 16, "mts": None, "type": None, "slot": "A", "maker": None}])


class SelfUpdateTests(unittest.TestCase):
    def test_versions(self):
        import self_update as su
        self.assertEqual(su.parse_version(chr(10).join(['x = 1', 'VERSION = "1.20"'])), "1.20")
        self.assertIsNone(su.parse_version("nothing here"))
        self.assertTrue(su.is_newer("1.20", "1.19"))
        self.assertTrue(su.is_newer("1.10", "1.9"))
        self.assertFalse(su.is_newer("1.19", "1.19"))
        self.assertFalse(su.is_newer(None, "1.19"))

    def _app(self, installed=True, git=False):
        import tempfile
        d = tempfile.mkdtemp()
        os.makedirs(os.path.join(d, "agent"))
        if installed:
            open(os.path.join(d, ".perflink-install"), "w").close()
        if git:
            os.makedirs(os.path.join(d, ".git"))
        return d

    def test_checkout_never_updates(self):
        import self_update as su
        u = su.SelfUpdate("1.19", self._app(git=True), None, notify=None, first_check=999)
        self.assertFalse(u.enabled)
        self.assertEqual(u.check_now(), "Updates are only for installed copies")

    def test_newer_version_starts_task_once(self):
        import self_update as su
        notes = []
        u = su.SelfUpdate("1.19", self._app(), None, notify=lambda t, x: notes.append(x), enabled=False)
        ok = types.SimpleNamespace(returncode=0)
        with mock.patch.object(u, "fetch_latest", return_value="1.20"),                 mock.patch.object(su.subprocess, "run", return_value=ok) as run:
            self.assertEqual(u.check_now(), "updating")
            self.assertEqual(u.check_now(), "update already started")   # not again right away
        self.assertEqual(run.call_count, 1)
        self.assertIn("PerfLink Update", run.call_args[0][0])
        self.assertTrue(any("1.20" in n for n in notes))

    def test_up_to_date_and_offline(self):
        import self_update as su
        u = su.SelfUpdate("1.20", self._app(), None, notify=None, enabled=False)
        with mock.patch.object(u, "fetch_latest", return_value="1.20"):
            self.assertEqual(u.check_now(), "up to date")
        with mock.patch.object(u, "fetch_latest", side_effect=OSError("offline")):
            self.assertTrue(u.check_now().startswith("check failed"))

    def test_announces_after_update(self):
        import self_update as su
        d = self._app()
        su.SelfUpdate("1.19", d, None, notify=None, enabled=False)
        notes = []
        su.SelfUpdate("1.20", d, None, notify=lambda t, x: notes.append(x), enabled=False)
        self.assertEqual(notes, ["Now running PerfLink 1.20"])


class DevicesTests(unittest.TestCase):
    def _devices(self):
        import devices as dv
        saves = []
        cfg = {}
        return dv, dv.Devices(cfg, lambda c: saves.append(dict(c))), saves, cfg

    def test_ids_and_names(self):
        import devices as dv
        self.assertEqual(dv.device_id({"id": "abc"}, "1.2.3.4"), "abc")
        self.assertEqual(dv.device_id(None, "1.2.3.4"), "ip:1.2.3.4")        # older app: by IP
        self.assertEqual(dv.device_name({"name": "Redmi Note 5"}, "x"), "Redmi Note 5")
        self.assertEqual(dv.device_name({}, "1.2.3.4"), "Phone at 1.2.3.4")

    def test_allow_block_unblock_forget(self):
        dv, d, saves, cfg = self._devices()
        self.assertEqual(d.status("p1"), dv.UNKNOWN)
        d.allow("p1", "Redmi", "192.168.1.4")
        self.assertEqual(d.status("p1"), dv.ALLOWED)
        d.block("p1", "Redmi", "192.168.1.4")                    # block wins and leaves the allow list
        self.assertEqual(d.status("p1"), dv.BLOCKED)
        self.assertNotIn("p1", d.allowed)
        d.unblock("p1")
        self.assertEqual(d.status("p1"), dv.UNKNOWN)             # asked again next time
        d.allow("p1", "Redmi", "192.168.1.4"); d.forget("p1")
        self.assertEqual(d.status("p1"), dv.UNKNOWN)
        self.assertIn("devices", cfg)                            # stored in config.json
        self.assertTrue(saves)

    def test_seen_updates_ip_only_when_changed(self):
        dv, d, saves, cfg = self._devices()
        d.allow("p1", "Redmi", "192.168.1.4")
        n = len(saves)
        d.seen("p1", "192.168.1.4")
        self.assertEqual(len(saves), n)                           # same IP: nothing written
        d.seen("p1", "192.168.1.9")
        self.assertEqual(d.allowed["p1"]["ip"], "192.168.1.9")
        self.assertEqual(len(saves), n + 1)


class UpdateNowTests(unittest.TestCase):
    def _u(self):
        import self_update as su, tempfile
        d = tempfile.mkdtemp(); os.makedirs(os.path.join(d, "agent"))
        open(os.path.join(d, ".perflink-install"), "w").close()
        return su, su.SelfUpdate("1.26", d, "NirabhraKoley/PerfLink", notify=None, enabled=False)

    def test_opens_powershell_via_explorer(self):
        su, u = self._u()
        with mock.patch.object(su.subprocess, "Popen") as popen:
            self.assertTrue(u.open_update_window())
        args, kw = popen.call_args
        self.assertEqual(args[0][0], "explorer.exe")       # Windows' shell starts it: not part of PerfLink
        launcher = args[0][1]
        text = open(launcher, encoding="ascii").read()
        self.assertIn("powershell.exe -NoExit", text)       # the window stays open to read
        self.assertIn("irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/install.ps1 | iex", text)
        self.assertIn('cd /d "%USERPROFILE%"', text)        # not inside the install folder
        self.assertNotIn("PerfLink", kw["cwd"].split(os.sep)[-1])

    def test_falls_back_to_starting_powershell_directly(self):
        su, u = self._u()
        calls = []
        def popen(args, creationflags=0, cwd=None):
            calls.append((args[0], creationflags))
            if args[0] == "explorer.exe" or creationflags & su.BREAKAWAY:
                raise OSError("refused")
        with mock.patch.object(su.subprocess, "Popen", side_effect=popen):
            self.assertTrue(u.open_update_window())
        self.assertEqual([c[0] for c in calls], ["explorer.exe", "powershell.exe", "powershell.exe"])
        self.assertEqual(calls[-1][1], su.NEW_CONSOLE)

    def test_schedule_script_targets_two_hours(self):
        import self_update as su
        self.assertIn("PT2H", su.SCHEDULE_PS)
        self.assertIn("-Hours 2", su.SCHEDULE_PS)
        self.assertIn("'PerfLink Update'", su.SCHEDULE_PS)


if __name__ == "__main__":
    unittest.main()


class WebAppTests(unittest.TestCase):
    """The iPhone / browser web app served on the agent's port."""

    def test_pages_resolve(self):
        for path in ("/", "/index.html", "/iphone", "/ios/", "/?x=1", "/#home"):
            status, ctype, body = webapp.page(path)
            self.assertEqual(status, 200, path)
            self.assertTrue(ctype.startswith("text/html"), path)
            self.assertIn(b"/app.js", body)
        self.assertEqual(webapp.page("/app.js")[1], "text/javascript; charset=utf-8")
        self.assertEqual(webapp.page("/app.css")[1], "text/css; charset=utf-8")
        self.assertEqual(webapp.page("/manifest.webmanifest")[1], "application/manifest+json")
        self.assertEqual(webapp.page("/icon-180.png")[1], "image/png")

    def test_nothing_outside_the_web_folder(self):
        for path in ("/../perflink_agent.py", "/..%2fconfig.json", "/../config.json", "/web/../../agent/config.json",
                     "/C:/Windows/win.ini", "/..\perflink_agent.py", "/missing.html", "/nothing"):
            self.assertEqual(webapp.page(path)[0], 404, path)

    def test_only_known_file_types(self):
        with tempfile.TemporaryDirectory() as d:
            for name in ("ok.html", "secret.py", "notes.txt"):
                with open(os.path.join(d, name), "w") as f:
                    f.write("x")
            self.assertEqual(webapp.page("/ok.html", d)[0], 200)
            self.assertEqual(webapp.page("/secret.py", d)[0], 404)
            self.assertEqual(webapp.page("/notes.txt", d)[0], 404)

    def test_websocket_handshakes_pass_through(self):
        ws_req = types.SimpleNamespace(path="/", headers={"Upgrade": "websocket", "Connection": "Upgrade"})
        self.assertIsNone(webapp.process_request(None, ws_req))
        page_req = types.SimpleNamespace(path="/", headers={})
        r = webapp.process_request(None, page_req)
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.headers["Cache-Control"], "no-cache")
        self.assertEqual(int(r.headers["Content-Length"]), len(r.body))
        self.assertEqual(webapp.process_request(None, types.SimpleNamespace(path="/x.py", headers={})).status_code, 404)

    def test_page_says_hello_like_the_android_app(self):
        js = webapp.page("/app.js")[2].decode("utf-8")
        self.assertIn('cmd: "hello", id: DEVICE_ID, name: DEVICE_NAME', js)
        for status in ('"pending"', '"blocked"', '"not_allowed"', '"allowed"'):
            self.assertIn(status, js)

    def test_served_over_the_real_port(self):
        """Same port: a browser gets the page and a WebSocket client still gets stats."""
        import asyncio
        import urllib.request
        import websockets

        async def handler(ws):
            await ws.send(json.dumps({"type": "stats"}))

        async def run():
            async with websockets.serve(handler, "127.0.0.1", 0, process_request=webapp.process_request) as srv:
                port = srv.sockets[0].getsockname()[1]
                html = await asyncio.to_thread(lambda: urllib.request.urlopen(f"http://127.0.0.1:{port}/").read())
                async with websockets.connect(f"ws://127.0.0.1:{port}") as c:
                    msg = json.loads(await c.recv())
                return html, msg

        html, msg = asyncio.run(run())
        self.assertIn(b"PerfLink", html)
        self.assertEqual(msg["type"], "stats")
