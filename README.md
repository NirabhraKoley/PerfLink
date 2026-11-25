# PerfLink

**Your Windows PC's live stats and controls on an Android phone.** FPS, frame time, GPU/CPU load, temperature and power, network, processes, USB and Bluetooth devices, Wi-Fi, media, OBS and quick actions, on a full-screen landscape dashboard you can keep beside your monitor.

```
 PC (Windows)                                    Phone (Android)
 ┌────────────────────────────────┐  Wi-Fi/LAN   ┌──────────────────────────┐
 │ agent/perflink_agent.py        │ ───────────▶ │ PerfLink app             │
 │  PresentMon  → FPS, frame time │  WebSocket   │  12 swipeable pages      │
 │  LibreHWMon  → GPU/CPU sensors │  :47800      │  auto-discovers the PC   │
 │  psutil      → CPU, RAM, net   │  UDP :47801  │  or connect over USB     │
 │  WinRT / OBS → media, OBS      │  (discovery) │                          │
 └────────────────────────────────┘              └──────────────────────────┘
```

## Pages

Swipe left/right; the pages loop. A bar at the top of every page shows its name, a **Disconnect** button (left) and a **Home** button (right). The number in brackets is the page index (see [Open a specific page](#open-a-specific-page)).

| # | Page | What it shows |
|---|---|---|
| `[0]` | **Home** | Clock with a 12h / 24h switch, date and year, phone battery % and USB / Wi-Fi, WhatsApp messages and calls waiting on the PC (mirrored from WhatsApp Desktop's notifications; tap to see them), weather for the PC's location (tap it for the Weather page), PC media player (cover art, seek, play/pause/skip, volume, mute) and CPU / GPU / RAM / network tiles with sparklines. Tap the PC name for settings (weather city, disconnect). |
| `[1]` | **Mirror** | One swipe right of Home. The PC's main monitor live on the phone, with the mouse pointer, at up to about 30 frames a second, over Wi-Fi or USB. The PC's sound plays on the phone's speaker (switch at the top right). **Touch control** (off by default, so you can just watch and swipe pages): turn it on with the *Touch* switch at the top right. Then tap to click there, press and hold to right-click, drag to drag, and use two fingers to scroll. For example: play a video on the PC, switch the monitor off, and keep watching and listening on the phone. It streams only while this page is open. |
| `[2]` | **Clock** | Works on the phone alone (no PC needed), on a black background. Three tabs. **Clock & Calendar**: a big analog clock (hands for hours, minutes and seconds, with the day and date) or a big digital one (12h / 24h, the same setting as Home); the Analog / Digital choice is remembered. The *Style* button cycles five designs for each: analog **Classic**, **Minimal**, **Neon**, **Roman**, **Vintage** (old-school ivory dial, brass bezel, Breguet hands); digital **Thin**, **LCD** (seven-segment), **Neon**, **Flip** (flip-clock cards), **Nixie** (glowing orange digits in glass tubes). The calendar takes the design's colour, and each mode remembers its design. Next to it, this month's calendar with today circled; ‹ › for other months, tap the month name to come back. **Timers**: set hours, minutes and seconds (or 1 / 5 / 10 / 30 min), then Start, Pause, Resume, Reset, with a ring that empties as time runs out; at zero the phone rings and vibrates (stop it there, or from the banner on any page). **Stopwatch**: Start, Stop, Lap and Reset, to hundredths of a second, with each lap's time and the total (fastest lap in green, slowest in red). The timer and stopwatch keep running while you swipe to other pages. |
| `[3]` | **Remote** | Control the PC's mouse and keyboard, over Wi-Fi or USB. Left half: a touchpad. Move with one finger, tap to click, tap with two fingers to right-click, drag with two fingers to scroll. It also has Left click, Hold (to drag) and Right click buttons. Right half: a keyboard with letters, numbers and symbols, arrows, Esc, Tab and Del. Ctrl, Alt, Win and Shift apply to the next key, for shortcuts like Ctrl+C or Alt+Tab. Swipe on the page title to change page. |
| `[4]` | **Launcher** | 12 tiles: Terminal, Files, VS Code, Browser, Spotify, Calculator, Settings, Snip, Task Manager, Mute, Screen Off, Lock. |
| `[5]` | **Performance** | FPS, frame time, 1% / 0.1% lows, stutter graph; GPU load, power, temps, clocks, VRAM; CPU per-thread load, RAM; network speed, ping, jitter, packet loss. |
| `[6]` | **Overlay** | A compact list in the same order as AMD's in-game overlay (FPS, frame time, 99th% FPS, GPU util/power/temp, GPU mem util/clock, CPU util, system memory…). |
| `[7]` | **Processes** | Task Manager-style list grouped by app, with real Windows icons, CPU / RAM bars and a sort switch. Tap a row for details. |
| `[8]` | **OBS** | Live preview, record / pause / go live / virtual cam / replay, scene switching, audio mixer. |
| `[9]` | **Sessions** | Running Claude Code sessions, desktop AI apps (Claude, ChatGPT, Copilot, Ollama, LM Studio, Cursor…), and GitHub review requests / PRs (via the `gh` CLI). |
| `[10]` | **Bluetooth & Wi-Fi** | Two panels. Left: how many Bluetooth devices are connected, with name, type (headphones, mouse, controller…), battery level and the latest connect/disconnect. Right: the PC's Wi-Fi network (name, signal, band/channel, Wi-Fi standard, speed, security, IP) or wired connection. |
| `[11]` | **USB** | How many USB devices are plugged in (hubs counted separately), with type, port and plug-in time, plus USB drives and a plug/unplug feed. |
| `[12]` | **Weather** | One swipe left of Home (the last page), or tap the weather on Home. Detailed weather for where the **PC** is: now (feels like, humidity, wind and gusts, UV, air quality, pressure, visibility, sunrise and sunset), the next 24 hours and 7 days. |

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

Run the same command again later to **update**; your settings are kept. A phone plugged in by USB is updated at the same time. If PerfLink's files on the PC are in use, the installer still updates the phone, keeps the current PC version running and asks you to restart the PC and run the command again.

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

Add `--ei page N` (N = page index from the table above), e.g. `--ei page 7` opens Processes.

## Optional setup

- **WhatsApp:** open WhatsApp Desktop on the PC with its Windows notifications turned on. The Home page then counts the messages and calls still in the PC's notification center. They clear when you dismiss them on the PC or open the chat.
- **Weather location:** follows the PC. PerfLink uses Windows' location when it's on (*Settings > Privacy > Location*, with desktop apps allowed). Otherwise it uses the approximate location of the PC's internet connection ([ipwho.is](https://ipwho.is/)). To use a fixed city, tap the PC name on Home and type one. *Use PC location* there switches back. Weather and air quality come from [Open-Meteo](https://open-meteo.com/).
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

## Version history

All releases are on the [Releases page](https://github.com/NirabhraKoley/PerfLink/releases). To update, run the install command again on the PC; the phone app updates itself over USB, or install the newest `PerfLink.apk`.

| Version | Date | What changed |
|---|---|---|
| **1.19** | 25 Nov 2026 | **Clock page** (one swipe right of Mirror, works without the PC): analog or digital clock in five designs each (including old-school Vintage and Nixie), with a matching month calendar, a timer that rings and vibrates, and a stopwatch with laps. |
| **1.18** | 25 Nov 2026 | Bigger Home clock that fills its space, with a slim 12h / 24h switch to its left. The date shows the year. RAM modules on Performance as a tidy table (`RAM 1 │ 8 GB │ DDR4 │ 2666 MT/s`). |
| **1.17** | 25 Nov 2026 | A top bar on every page with the page name, Disconnect (left) and Home (right). The first page dot is a home icon. Each RAM stick on its own row with size, DDR type and speed. 12h / 24h clock switch. Layouts checked on 16:9, 20:9 and 4:3 screens and kept clear of camera cut-outs. Unit tests for the PC agent and the app. |
| **1.16** | 25 Nov 2026 | Disconnect button (with confirm) on every page. RAM split (`8 GB + 8 GB`) on Home, Overlay and Performance. The Home button now always slides all the way to Home. *(1.15 was folded into this release.)* |
| **1.14** | 25 Nov 2026 | The PC list and Home show who uses each PC (Windows name), whether it's a laptop or a desktop, and its IP address. |
| **1.13** | 25 Nov 2026 | Home shows how the phone is connected (USB or Wi-Fi) next to the battery. The installer warns when Windows Firewall blocks Python on the current network. |
| **1.12** | 24 Nov 2026 | Crash-proof screen mirroring (capture runs in separate helper processes that restart on their own, with a GDI fallback). The app scales to fit every screen size. |
| **1.11** | 24 Nov 2026 | New page order (Weather is one swipe left of Home, Mirror one swipe right). Mirror touch control is off by default. |
| **1.10** | 24 Nov 2026 | Touch control on Mirror (tap, hold for right-click, drag, two-finger scroll). Mirror shows the main monitor only. Social page removed. |
| **1.9** | 24 Nov 2026 | Mirror keeps working when a monitor is switched off or the monitor layout changes. |
| **1.8** | 24 Nov 2026 | **Mirror page**: the PC screen and sound live on the phone. |
| **1.7** | 24 Nov 2026 | Social page with notifications from social apps open on the PC *(removed in 1.10)*. |
| **1.6** | 24 Nov 2026 | **Weather page** with detailed weather for the PC's location (now, 24 hours, 7 days). |
| **1.5** | 24 Nov 2026 | **Remote page**: use the phone as the PC's touchpad and keyboard. WhatsApp calls counted while they ring. The PC opens the phone app when a phone is plugged in over USB. |
| **1.4** | 24 Nov 2026 | WhatsApp messages and calls waiting on the PC, shown on Home. More reliable updates. |
| **1.3** | 23 Nov 2026 | Bluetooth & Network page as two panels (Bluetooth devices, Wi-Fi / Ethernet). Better Bluetooth and Wi-Fi detection. |
| **1.2** | 23 Nov 2026 | Phone battery % on Home. Fixes for updating and for PCs with several Pythons. |
| **1.1** | 23 Nov 2026 | Tray icon, start with Windows, automatic phone-app install over USB, Bluetooth & Wi-Fi page, proper uninstall from Settings > Apps. |
| **1.0** | 23 Nov 2026 | First release: live PC stats (FPS, GPU, CPU, RAM, network) on the phone, with Home, Performance, Overlay, Processes, Launcher, OBS and Sessions pages. |

## Credits

- [PresentMon](https://github.com/GameTechDev/PresentMon) (MIT) for frame timing
- [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor) (MPL-2.0) for hardware sensors
- [Open-Meteo](https://open-meteo.com/) (CC BY 4.0) for weather data
- [psutil](https://github.com/giampaolo/psutil), [websockets](https://github.com/python-websockets/websockets), [pythonnet](https://github.com/pythonnet/pythonnet), [pycaw](https://github.com/AndreMiras/pycaw), [PyWinRT](https://github.com/pywinrt/pywinrt), [OkHttp](https://github.com/square/okhttp)

## License

[MIT](LICENSE) © 2026 Nirabhra Koley
