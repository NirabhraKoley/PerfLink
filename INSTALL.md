# Installing PerfLink

PerfLink has two parts:

| Part | Runs on | What it does |
|---|---|---|
| **PerfLink agent** | your Windows PC | reads FPS, GPU/CPU sensors, network, processes, USB devices, media and OBS, and sends them to the phone |
| **PerfLink app** | your Android phone | shows everything on 8 swipeable pages and sends controls back to the PC |

You install both once. Setup takes about 5 minutes.

---

## 1. What you need

### Required

| Where | What | Notes |
|---|---|---|
| PC | **Windows 10 or 11** (64-bit) | |
| PC | **Python 3.10 or newer** | The one-command installer installs Python 3.12 for you if it's missing. Manual installs: get it from [python.org](https://www.python.org/downloads/) and tick **"Add python.exe to PATH"**. |
| PC | **Internet connection** | Only during installation, to download PerfLink and its helpers. |
| PC | **Administrator permission** | One prompt to open the Windows Firewall for the phone. FPS and CPU temperature also need it while running. |
| Phone | **Android 8.0 or newer** | |
| Both | **Same Wi-Fi / router**, or a **USB cable** | The PC can be on Ethernet as long as it's the same router. |

### Installed automatically (nothing to do)

The installer (or `agent\setup.ps1`) downloads these into PerfLink's own folder:

| Component | Used for | License |
|---|---|---|
| [PresentMon](https://github.com/GameTechDev/PresentMon) 2.6.0 (Intel) | FPS, frame time, 1% lows | MIT |
| [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor) 0.9.6 | GPU/CPU temperature, power, clocks, VRAM | MPL-2.0 |
| Python packages: psutil, websockets, pythonnet, pycaw, PyWinRT | CPU/RAM/network, phone connection, volume, media controls | open source |

### Optional (only for some pages)

| Application | Needed for | How to get it |
|---|---|---|
| **OBS Studio 28+** | OBS page (preview, record, stream, scenes, audio) | [obsproject.com](https://obsproject.com/). Then in OBS: *Tools → WebSocket Server Settings → tick Enable WebSocket server → OK*. |
| **GitHub CLI** | GitHub panel on the Sessions page | `winget install GitHub.cli`, then `gh auth login` |
| **Android Platform Tools (adb)** | USB mode (connect by cable, no Wi-Fi) and installing the app from the PC | `winget install Google.PlatformTools` |
| **Claude desktop app / Claude Code** | Claude sessions on the Sessions page | [claude.ai/download](https://claude.ai/download) |

---

## 2. Install on the PC

### Option A: one command (recommended)

1. Press **Start**, type **PowerShell**, and open **Windows PowerShell**. It doesn't need to be an admin window.
2. Paste this line and press **Enter**:

   ```powershell
   irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/install.ps1 | iex
   ```

3. Wait about a minute. When Windows asks *"Do you want to allow this app to make changes?"*, click **Yes**. That's the firewall step, and the agent's start-up.
4. When you see **"All set!"**, the agent is running. A **PerfLink** shortcut is now on your desktop and in the Start menu.

The installer puts everything in `%LOCALAPPDATA%\PerfLink` and changes nothing else on your PC.

### Option B: manual

1. Install **Python 3.10+** from [python.org](https://www.python.org/downloads/) and tick **"Add python.exe to PATH"**.
2. Download PerfLink: on the GitHub page click **Code → Download ZIP**, then extract it (e.g. to `C:\PerfLink`).
3. Open PowerShell **in that folder** and run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File agent\setup.ps1
   ```

4. Open PowerShell **as administrator** (right-click → *Run as administrator*) and allow the phone through the firewall:

   ```powershell
   New-NetFirewallRule -DisplayName "PerfLink TCP" -Direction Inbound -Protocol TCP -LocalPort 47800 -Action Allow
   ```
   ```powershell
   New-NetFirewallRule -DisplayName "PerfLink UDP" -Direction Inbound -Protocol UDP -LocalPort 47801 -Action Allow
   ```

5. Start the agent by double-clicking **`start_agent_admin.bat`**. Use `start_agent.bat` if you don't want admin; FPS may then show N/A on some PCs, and CPU temperature always will.

---

## 3. Install on the phone

1. On the phone, open the GitHub page → **Releases** → download **`PerfLink.apk`**:
   `https://github.com/NirabhraKoley/PerfLink/releases/latest/download/PerfLink.apk`
2. Open the downloaded file. Android will say installing apps from this source isn't allowed: tap **Settings**, switch on **Allow from this source**, go back and tap **Install**.
3. Open **PerfLink**. It runs full screen in landscape.

> **Tip:** if the phone is plugged into the PC with USB debugging on (see step 5) and adb is installed, the one-command installer installs the app on the phone for you.

---

## 4. Connect (Wi-Fi)

1. Make sure the PerfLink agent is running on the PC (desktop shortcut **PerfLink**).
2. Connect the phone to the **same Wi-Fi** as the PC.
3. Open PerfLink. Your PC appears under **"PCs on this network"**: tap **Connect**.
4. Not listed? Type the PC's IP address (the agent window shows it, e.g. `192.168.1.x`) and tap **Connect**.

The app remembers the PC and reconnects by itself after Wi-Fi drops or a PC restart.

---

## 5. Connect by USB cable (optional, no Wi-Fi needed)

USB mode is the most stable connection and doesn't need the firewall rules.

**One-time phone setup (USB debugging):**
1. *Settings → About phone* → tap **Build number** (on Xiaomi/Redmi: **MIUI version**) **7 times** until it says you're a developer.
2. *Settings → Additional settings / System → Developer options* → turn on **USB debugging**.
3. Plug the phone into the PC and tap **Allow** on the *"Allow USB debugging?"* pop-up (tick *Always allow from this computer*).

**PC setup:** install adb once:
```powershell
winget install Google.PlatformTools
```

**Connect:**
```powershell
adb reverse tcp:47800 tcp:47800
```
```powershell
adb shell am start -n com.perflink.app/.MainActivity -e host 127.0.0.1
```
(Or type `127.0.0.1` in the app's *Connect manually* box.) After that, the agent re-creates the USB link by itself whenever you unplug and replug the cable.

---

## 6. Everyday use

- **Start:** open **PerfLink** from the desktop or Start menu on the PC, then open the app on the phone.
- **Pages:** swipe left/right; they loop. The **🏠 button** (top-right) jumps back to Home.
- **Weather city:** tap the PC name at the top-left of the Home page.
- **Update:** run the same one-command install again. Your settings are kept.

## 7. Uninstall

PC (removes the program, shortcuts and firewall rules; Python stays installed):

```powershell
& ([scriptblock]::Create((irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/install.ps1))) -Uninstall
```

Phone: long-press the PerfLink icon → **Uninstall**.

---

## 8. Troubleshooting

| Problem | Fix |
|---|---|
| Phone doesn't find the PC | Same Wi-Fi? (Not mobile data or a guest network.) Firewall rules added? Type the PC's IP manually. |
| Stuck on "Connecting/Reconnecting to the PC…" | Is the agent running on the PC? Over USB: is the cable in and USB debugging allowed? The app retries every few seconds by itself. |
| FPS shows **N/A** | Start the agent as administrator (`start_agent_admin.bat`, or the PerfLink shortcut). FPS follows the window in front, so click into the game. |
| CPU TEMP shows **N/A** | Needs administrator. Start with `start_agent_admin.bat`. |
| FRAME GEN LAG / UPSCALE UPGRADE show **N/A** | Normal. Only AMD's own driver can read those. |
| `python` is not recognized | Python isn't on PATH. Reinstall it and tick **"Add python.exe to PATH"**, or use the one-command installer. |
| OBS page says WebSocket is off | In OBS: *Tools → WebSocket Server Settings → Enable WebSocket server → Apply → OK*. |
| GitHub panel says "not signed in" | Run `gh auth login` on the PC. |
| Media card shows "Nothing playing" | Play something in an app that supports Windows media controls (Spotify, YouTube in a browser, etc.). |
| `irm … \| iex` is blocked | Your organisation may block scripts. Use the manual install (Option B). |

## 9. Build the app yourself (developers)

Needs **JDK 17** and **Android SDK 34**, e.g. by installing [Android Studio](https://developer.android.com/studio). Then:

```powershell
cd android
.\gradlew.bat assembleRelease
```

The APK is written to `android\app\build\outputs\apk\release\app-release.apk`.

---

**Security note:** anyone on your network who has the app can see your PC stats and use the launcher, media and OBS controls. Use PerfLink on a network you trust (your home Wi-Fi), or over USB.
