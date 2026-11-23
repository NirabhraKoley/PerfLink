# PerfLink

**Your Windows PC's live stats and controls on an Android phone.** FPS, frame time, GPU/CPU load, temperature and power, network, processes, USB and Bluetooth devices, Wi-Fi, media, OBS and quick actions, on a full-screen landscape dashboard you can keep beside your monitor.

```
 PC (Windows)                                    Phone (Android)
 ┌────────────────────────────────┐  Wi-Fi/LAN   ┌──────────────────────────┐
 │ agent/perflink_agent.py        │ ───────────▶ │ PerfLink app             │
 │  PresentMon  → FPS, frame time │  WebSocket   │  9 swipeable pages       │
 │  LibreHWMon  → GPU/CPU sensors │  :47800      │  auto-discovers the PC   │
 │  psutil      → CPU, RAM, net   │  UDP :47801  │  or connect over USB     │
 │  WinRT / OBS → media, OBS      │  (discovery) │                          │
 └────────────────────────────────┘              └──────────────────────────┘
```

## Pages

Swipe left/right; the pages loop. A **Home** button sits top-right on every other page. The number in brackets is the page index (see [Open a specific page](#open-a-specific-page)).

| # | Page | What it shows |
|---|---|---|
| `[0]` | **Home** | 12-hour clock, date, phone battery %, weather, PC media player (cover art, seek, play/pause/skip, volume, mute) and CPU / GPU / RAM / network tiles with sparklines. Tap the PC name for settings (weather city, disconnect). |
| `[1]` | **Performance** | FPS, frame time, 1% / 0.1% lows, stutter graph; GPU load, power, temps, clocks, VRAM; CPU per-thread load, RAM; network speed, ping, jitter, packet loss. |
| `[2]` | **Overlay** | A compact list in the same order as AMD's in-game overlay (FPS, frame time, 99th% FPS, GPU util/power/temp, GPU mem util/clock, CPU util, system memory…). |
| `[3]` | **Processes** | Task Manager-style list grouped by app, with real Windows icons, CPU / RAM bars and a sort switch. Tap a row for details. |
| `[4]` | **USB** | How many USB devices are plugged in (hubs counted separately), with type, port and plug-in time, plus USB drives and a plug/unplug feed. |
| `[5]` | **Bluetooth & Wi-Fi** | Two panels. Left: how many Bluetooth devices are connected, with name, type (headphones, mouse, controller…), battery level and the latest connect/disconnect. Right: the PC's Wi-Fi network (name, signal, band/channel, Wi-Fi standard, speed, security, IP) or wired connection. |
| `[6]` | **Sessions** | Running Claude Code sessions, desktop AI apps (Claude, ChatGPT, Copilot, Ollama, LM Studio, Cursor…), and GitHub review requests / PRs (via the `gh` CLI). |
| `[7]` | **OBS** | Live preview, record / pause / go live / virtual cam / replay, scene switching, audio mixer. |
| `[8]` | **Launcher** | 12 tiles: Terminal, Files, VS Code, Browser, Spotify, Calculator, Settings, Snip, Task Manager, Mute, Screen Off, Lock. |

A pop-up appears on every page when a USB device is plugged in or removed, or a Bluetooth device connects or disconnects.

On the PC, PerfLink runs quietly in the background with an **icon next to the clock** (right-click it for status, *Start with Windows*, log and *Quit*), and **starts automatically when you sign in to Windows**.

## Requirements

- **PC:** Windows 10 or 11, [Python 3.10+](https://www.python.org/downloads/) (tick *Add python.exe to PATH*).
- **Phone:** Android 8.0 or newer, on the same network as the PC (or connected by USB).
- Optional: [OBS Studio](https://obsproject.com/) 28+ for the OBS page, the [GitHub CLI](https://cli.github.com/) for the GitHub panel.

GPU sensors are read through LibreHardwareMonitor and work with AMD, NVIDIA and Intel GPUs. It was developed and tested on an AMD Radeon RX 6700 XT.

## Install (one command)

> **New here?** The step-by-step guide, with everything you need, phone setup, USB mode and troubleshooting, is in **[INSTALL.md](INSTALL.md)**.

**On the PC:** open PowerShell and paste:

```powershell
irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/install.ps1 | iex
```

That's it. Windows asks for administrator permission once (click **Yes**); then the installer:
1. installs Python 3.12 and Android Platform Tools (adb) with winget if they're missing
2. downloads PerfLink into `%LOCALAPPDATA%\PerfLink`
3. installs the Python packages, [PresentMon](https://github.com/GameTechDev/PresentMon) and [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor)
4. opens the Windows Firewall for PerfLink's two ports
5. makes PerfLink **start with Windows**, in the background with a **tray icon** (no window, no prompts)
6. adds **PerfLink** to the Start menu and desktop, and starts it

**Phone app, automatically:** plug the phone into the PC with **USB debugging** allowed (once, in the phone's Developer options; on Xiaomi/Redmi also allow *Install via USB*). PerfLink then installs the app, and installs future updates by itself whenever the phone is plugged in.

Run the same command again later to **update**; your settings are kept.

**Update fails?** If the installer says the PerfLink folder *"doesn't look like a PerfLink install"* or that its files are *in use*, start fresh. First right-click the PerfLink tray icon and choose **Quit PerfLink**. Then delete the folder and run the install command again:

```powershell
Remove-Item "$env:LOCALAPPDATA\PerfLink" -Recurse -Force
```

This removes only PerfLink's program folder. The phone app and everything else stay, but your weather city must be set again.

**Or on the phone:** download **[`PerfLink.apk`](../../releases/latest/download/PerfLink.apk)** and open it (Android will ask you to allow installing apps from your browser). Connect the phone to the same Wi-Fi as the PC, open PerfLink and tap your PC.

**Next time:** nothing to do. PerfLink starts with Windows. (The Start menu / desktop shortcut starts it again if you quit it.)

**Uninstall:** like any app: **Settings → Apps → PerfLink → Uninstall** (also in Control Panel and in the tray icon's menu). Or:

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/install.ps1))) -Uninstall
```

<details>
<summary>Manual install (without the installer)</summary>

```powershell
git clone https://github.com/NirabhraKoley/PerfLink.git
cd PerfLink
powershell -ExecutionPolicy Bypass -File agent\setup.ps1
```

Then double-click **`start_agent.bat`**, or **`start_agent_admin.bat`** to also get CPU temperature/power and FPS on PCs where PresentMon needs admin. Allow the phone through the firewall once, in an **administrator** PowerShell:

```powershell
New-NetFirewallRule -DisplayName "PerfLink TCP" -Direction Inbound -Protocol TCP -LocalPort 47800 -Action Allow
New-NetFirewallRule -DisplayName "PerfLink UDP" -Direction Inbound -Protocol UDP -LocalPort 47801 -Action Allow
```

If the phone doesn't list your PC, type the PC's IP address, which the agent window shows (e.g. `192.168.1.x`).
</details>

## USB mode (no Wi-Fi or firewall needed)

With USB debugging on and [adb](https://developer.android.com/tools/releases/platform-tools) installed:

```powershell
adb reverse tcp:47800 tcp:47800
adb shell am start -n com.perflink.app/.MainActivity -e host 127.0.0.1
```

### Open a specific page

Add `--ei page N` (N = page index from the table above), e.g. `--ei page 3` opens Processes.

## Optional setup

- **Weather city:** tap the PC name on the Home page, or start the agent with `--city "London"`. Weather data comes from [Open-Meteo](https://open-meteo.com/).
- **OBS page:** in OBS open *Tools → WebSocket Server Settings* and tick *Enable WebSocket server*. The agent reads the password from OBS's own config, so there's nothing to type.
- **GitHub panel:** install the GitHub CLI (`winget install GitHub.cli`) and run `gh auth login`.
- **Ping target:** `--ping-host <ip>` measures latency to a specific server (default `1.1.1.1`).

## Notes

- **FRAME GEN LAG** and **UPSCALE UPGRADE** (from AMD's overlay) are only available inside AMD's driver, so they always show N/A.
- FPS follows whichever window is in the foreground. PresentMon works without admin rights if your account is in the *Performance Log Users* group.
- **Security:** anyone on your network who has the app can see your stats and use the launcher, media and OBS controls. Only run the agent on a network you trust. The agent only accepts a fixed list of actions, never arbitrary commands.

## Build from source

**Android app**: open `android/` in [Android Studio](https://developer.android.com/studio), or from the command line with JDK 17 and Android SDK 34:

```powershell
cd android
.\gradlew.bat assembleRelease
```

The APK is written to `android/app/build/outputs/apk/release/app-release.apk`. Release builds are signed with the debug key so they can be sideloaded directly.

## Project structure

```
agent/                     PC side (Python)
  perflink_agent.py        WebSocket server, sensors, FPS, network
  extras.py                media, volume, weather
  processes.py             process list + icons
  usb_devices.py           USB devices + plug/unplug feed
  usb_tunnel.py            USB link to the phone + automatic app install/updates
  bluetooth.py / .ps1      Bluetooth devices, connection state, battery
  wifi.py                  Wi-Fi / wired connection details
  tray.py, autostart.py    tray icon + start with Windows
  ai_status.py             Claude Code sessions, AI apps, GitHub
  obs_control.py           OBS WebSocket client
  launcher.py              the 12 launcher actions
  setup.ps1                installs dependencies
android/                   Android app (Kotlin, Jetpack Compose)
PerfLink.vbs               start PerfLink in the background (used by the shortcuts)
uninstall.ps1              removes PerfLink (used by Settings > Apps and the tray menu)
diagnose.ps1               read-only troubleshooting report
start_agent.bat            start the agent in a console window
start_agent_admin.bat      start the agent as administrator
```

## Credits

- [PresentMon](https://github.com/GameTechDev/PresentMon) (MIT) for frame timing
- [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor) (MPL-2.0) for hardware sensors
- [Open-Meteo](https://open-meteo.com/) (CC BY 4.0) for weather data
- [psutil](https://github.com/giampaolo/psutil), [websockets](https://github.com/python-websockets/websockets), [pythonnet](https://github.com/pythonnet/pythonnet), [pycaw](https://github.com/AndreMiras/pycaw), [PyWinRT](https://github.com/pywinrt/pywinrt), [OkHttp](https://github.com/square/okhttp)

## License

[MIT](LICENSE) © 2026 Nirabhra Koley
