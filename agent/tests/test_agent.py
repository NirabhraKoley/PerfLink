"""Unit tests for the PC agent's pure logic. Run from the agent folder:  python -m unittest discover tests"""
import json
import os
import subprocess
import sys
import types
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import bluetooth  # noqa: E402
import mirror  # noqa: E402
import usb_tunnel  # noqa: E402
import weather  # noqa: E402
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


if __name__ == "__main__":
    unittest.main()
