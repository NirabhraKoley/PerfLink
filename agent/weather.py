"""
Weather for PerfLink: a short summary for the Home page and a detailed report for the Weather page.

Location comes from the PC, not the phone:
  1. Windows' location service (pc_location.ps1), when Location is on for desktop apps. Precise,
     especially on a laptop with Wi-Fi.
  2. Otherwise the approximate location of the PC's internet connection (ipwho.is): city level.
  3. Or a city typed in the app's settings ("manual"), until "Use PC location" is chosen again.

Forecast and air quality: Open-Meteo (free, no key). Place names for Windows coordinates:
BigDataCloud's free reverse geocoding.
"""
from __future__ import annotations

import json
import os
import subprocess
import threading
import time
import urllib.parse
import urllib.request

NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
HERE = os.path.dirname(os.path.abspath(__file__))
LOCATION_SCRIPT = os.path.join(HERE, "pc_location.ps1")

WMO = {
    0: "Clear sky", 1: "Mainly clear", 2: "Partly cloudy", 3: "Overcast",
    45: "Fog", 48: "Rime fog", 51: "Light drizzle", 53: "Drizzle", 55: "Heavy drizzle",
    56: "Freezing drizzle", 57: "Freezing drizzle", 61: "Light rain", 63: "Rain",
    65: "Heavy rain", 66: "Freezing rain", 67: "Freezing rain", 71: "Light snow", 73: "Snow",
    75: "Heavy snow", 77: "Snow grains", 80: "Light showers", 81: "Showers", 82: "Heavy showers",
    85: "Snow showers", 86: "Heavy snow showers", 95: "Thunderstorm", 96: "Thunderstorm, hail",
    99: "Thunderstorm, heavy hail",
}


def _get(url: str, timeout: int = 12) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "PerfLink weather"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def aqi_label(aqi) -> str | None:
    if aqi is None:
        return None
    for limit, name in ((50, "Good"), (100, "Moderate"), (150, "Unhealthy for sensitive groups"),
                        (200, "Unhealthy"), (300, "Very unhealthy")):
        if aqi <= limit:
            return name
    return "Hazardous"


def compass(deg) -> str | None:
    if deg is None:
        return None
    return ["N", "NE", "E", "SE", "S", "SW", "W", "NW"][int((float(deg) % 360) / 45 + 0.5) % 8]


class Weather:
    REFRESH = 600          # forecast every 10 minutes
    LOCATE_EVERY = 1800    # look for the PC's location again every 30 minutes

    def __init__(self, city: str | None = None, mode: str = "auto") -> None:
        self.mode = mode if mode in ("auto", "manual") else "auto"
        self.city = (city or "").strip()
        self.state: dict = {"city": None, "temp_c": None, "location_source": None}
        self.detail: dict | None = None
        self.error: str | None = None
        self._loc: dict | None = None
        self._loc_at = 0.0
        self._wake = threading.Event()
        threading.Thread(target=self._loop, daemon=True).start()

    # ------------------------------------------------------------------ settings from the phone
    def set_city(self, city: str) -> None:
        self.mode, self.city = "manual", city.strip()
        self._loc = None
        self._wake.set()

    def use_pc_location(self) -> None:
        self.mode = "auto"
        self._loc = None
        self._wake.set()

    # ------------------------------------------------------------------ location
    def _windows_location(self) -> dict | None:
        try:
            r = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", LOCATION_SCRIPT],
                               capture_output=True, text=True, timeout=30, creationflags=NO_WINDOW)
            lines = r.stdout.strip().splitlines()
            j = json.loads(lines[-1]) if lines else {}
        except Exception:  # noqa: BLE001
            return None
        if not j.get("ok"):
            return None
        lat, lon = j["lat"], j["lon"]
        name, region, country = None, None, None
        try:
            g = _get("https://api.bigdatacloud.net/data/reverse-geocode-client?" + urllib.parse.urlencode(
                {"latitude": lat, "longitude": lon, "localityLanguage": "en"}))
            name = g.get("city") or g.get("locality")
            region, country = g.get("principalSubdivision"), g.get("countryName")
        except Exception:  # noqa: BLE001
            pass
        return {"lat": lat, "lon": lon, "name": name or f"{lat:.2f}, {lon:.2f}", "region": region,
                "country": country, "source": "windows", "accuracy_m": j.get("accuracy_m")}

    def _network_location(self) -> dict | None:
        try:
            j = _get("https://ipwho.is/?fields=success,city,region,country,latitude,longitude")
        except Exception:  # noqa: BLE001
            return None
        if not j.get("success") or j.get("latitude") is None:
            return None
        return {"lat": j["latitude"], "lon": j["longitude"], "name": j.get("city") or "Unknown",
                "region": j.get("region"), "country": j.get("country"), "source": "network"}

    def _city_location(self, city: str) -> dict:
        g = _get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + urllib.parse.quote(city))
        if not g.get("results"):
            raise ValueError(f"city '{city}' not found")
        r = g["results"][0]
        return {"lat": r["latitude"], "lon": r["longitude"], "name": r.get("name", city),
                "region": r.get("admin1"), "country": r.get("country"), "source": "manual"}

    def _locate(self) -> dict:
        if self.mode == "manual" and self.city:
            return self._city_location(self.city)
        loc = self._windows_location() or self._network_location()
        if loc:
            return loc
        if self.city:  # offline lookups failed: fall back to the last city we knew
            return self._city_location(self.city)
        raise ValueError("couldn't find the PC's location")

    # ------------------------------------------------------------------ forecast
    def _fetch(self, loc: dict) -> None:
        q = urllib.parse.urlencode({
            "latitude": loc["lat"], "longitude": loc["lon"], "timezone": "auto", "forecast_days": 7,
            "current": "temperature_2m,apparent_temperature,relative_humidity_2m,dew_point_2m,precipitation,"
                       "weather_code,is_day,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,"
                       "wind_gusts_10m,visibility,uv_index",
            "hourly": "temperature_2m,weather_code,precipitation_probability,is_day",
            "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,"
                     "precipitation_sum,sunrise,sunset,uv_index_max,wind_speed_10m_max",
        })
        w = _get("https://api.open-meteo.com/v1/forecast?" + q)
        aq = {}
        try:
            aq = _get("https://air-quality-api.open-meteo.com/v1/air-quality?" + urllib.parse.urlencode({
                "latitude": loc["lat"], "longitude": loc["lon"], "current": "us_aqi,pm2_5,pm10", "timezone": "auto"})
            ).get("current", {})
        except Exception:  # noqa: BLE001  (air quality is optional)
            pass
        cur, day, hr = w["current"], w["daily"], w["hourly"]
        code = int(cur["weather_code"])
        # next 24 hours, starting with the current hour
        now = cur["time"][:13]
        start = next((i for i, t in enumerate(hr["time"]) if t[:13] >= now), 0)
        hourly = [{"time": hr["time"][i][11:16], "temp_c": round(hr["temperature_2m"][i]),
                   "code": hr["weather_code"][i], "rain_pct": hr["precipitation_probability"][i],
                   "is_day": bool(hr["is_day"][i])}
                  for i in range(start, min(start + 24, len(hr["time"])))]
        daily = [{"date": day["time"][i], "code": day["weather_code"][i],
                  "high_c": round(day["temperature_2m_max"][i]), "low_c": round(day["temperature_2m_min"][i]),
                  "rain_pct": day["precipitation_probability_max"][i], "rain_mm": day["precipitation_sum"][i],
                  "sunrise": (day["sunrise"][i] or "")[11:16], "sunset": (day["sunset"][i] or "")[11:16],
                  "uv_max": day["uv_index_max"][i], "wind_max_kmh": day["wind_speed_10m_max"][i]}
                 for i in range(len(day["time"]))]
        place = ", ".join(x for x in (loc.get("name"), loc.get("region")) if x)
        today = daily[0] if daily else {}
        self.state = {
            "city": loc.get("name"),
            "place": place,
            "location_source": loc.get("source"),
            "temp_c": round(cur["temperature_2m"]),
            "feels_c": round(cur["apparent_temperature"]),
            "high_c": today.get("high_c"),
            "low_c": today.get("low_c"),
            "code": code, "is_day": bool(cur.get("is_day", 1)),
            "condition": WMO.get(code, "—"),
            "humidity": cur.get("relative_humidity_2m"),
            "wind_kmh": cur.get("wind_speed_10m"),
            "rain_pct": hourly[0]["rain_pct"] if hourly else None,
        }
        self.detail = {
            **self.state,
            "country": loc.get("country"),
            "accuracy_m": loc.get("accuracy_m"),
            "updated": time.time(),
            "dew_point_c": cur.get("dew_point_2m"),
            "precip_mm": cur.get("precipitation"),
            "cloud_pct": cur.get("cloud_cover"),
            "pressure_hpa": cur.get("pressure_msl"),
            "wind_dir": compass(cur.get("wind_direction_10m")),
            "gust_kmh": cur.get("wind_gusts_10m"),
            "visibility_km": round(cur["visibility"] / 1000, 1) if cur.get("visibility") is not None else None,
            "uv": cur.get("uv_index"),
            "aqi": aq.get("us_aqi"), "aqi_label": aqi_label(aq.get("us_aqi")),
            "pm25": aq.get("pm2_5"), "pm10": aq.get("pm10"),
            "sunrise": today.get("sunrise"), "sunset": today.get("sunset"),
            "hourly": hourly, "daily": daily,
        }

    def _loop(self) -> None:
        while True:
            try:
                if self._loc is None or time.time() - self._loc_at > self.LOCATE_EVERY:
                    self._loc = self._locate()
                    self._loc_at = time.time()
                    if self._loc.get("source") != "manual":
                        self.city = self._loc.get("name") or self.city  # remembered as a fallback
                self._fetch(self._loc)
                self.error = None
            except Exception as e:  # noqa: BLE001
                self.error = f"weather: {e}"
            self._wake.wait(self.REFRESH if self.error is None else 60)
            self._wake.clear()


if __name__ == "__main__":
    w = Weather()
    for _ in range(40):
        time.sleep(1)
        if w.detail or w.error:
            break
    print(json.dumps({"state": w.state, "error": w.error,
                      "detail_keys": sorted((w.detail or {}).keys()),
                      "hours": len((w.detail or {}).get("hourly", [])),
                      "days": len((w.detail or {}).get("daily", []))}, indent=1))
