# PerfLink one-command installer for Windows
#
#   Install / update:  irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/install.ps1 | iex
#   Uninstall:         & ([scriptblock]::Create((irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/install.ps1))) -Uninstall
#
# What it does (nothing else). Windows asks for administrator permission once, at the start.
#   1. Installs Python 3.12 (and Android Platform Tools / adb) with winget if they're missing
#   2. Downloads PerfLink into %LOCALAPPDATA%\PerfLink
#   3. Installs the Python packages, PresentMon and LibreHardwareMonitor (agent\setup.ps1)
#   4. Opens the Windows Firewall for PerfLink's two ports
#   5. Makes PerfLink start with Windows (scheduled task, admin rights, no prompt) and runs it
#      in the background with an icon in the system tray
#   6. Adds "PerfLink" to the Start menu and desktop
#   7. Installs/updates the phone app on any phone plugged in with USB debugging allowed
param(
    [switch]$Uninstall,
    [string]$Repo = 'NirabhraKoley/PerfLink',   # <-- your GitHub "user/repo"
    [string]$Branch = 'main',
    # advanced / testing
    [string]$From,                               # install from a local folder instead of downloading
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'PerfLink'),
    [string]$ForUser = "$env:USERDOMAIN\$env:USERNAME",
    [switch]$SkipFirewall, [switch]$SkipShortcuts, [switch]$SkipAutostart, [switch]$NoStart, [switch]$NoAdb,
    [switch]$Elevated                            # internal: set when re-launched as administrator
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # much faster Invoke-WebRequest
$App      = $InstallDir
$StartLnk = Join-Path ([Environment]::GetFolderPath('Programs')) 'PerfLink.lnk'
$DeskLnk  = Join-Path ([Environment]::GetFolderPath('Desktop')) 'PerfLink.lnk'

function Step($t) { Write-Host "`n==> $t" -ForegroundColor Cyan }
function Done($t) { Write-Host "    $t" -ForegroundColor Green }
function Warn($t) { Write-Host "    $t" -ForegroundColor Yellow }
function Is-Admin { ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator) }
# Never delete a source checkout or anything that isn't a PerfLink install folder.
function Assert-SafeToReplace($dir) {
    if (-not (Test-Path $dir)) { return }
    if (Test-Path (Join-Path $dir '.git')) { throw "Refusing to delete '$dir': it is a source checkout (.git found). Use -InstallDir for a separate install folder." }
    # A PerfLink folder, even one an interrupted update left half-deleted, still has one of these;
    # an empty folder is fine too. Anything else is someone else's folder: leave it alone.
    $signs = '.perflink-install', 'PerfLink.vbs', 'agent\perflink_agent.py', 'agent\usb_tunnel.py', 'agent\bluetooth.ps1',
             'agent\wifi.ps1', 'agent\perflink.log', 'agent\pythonw.txt', 'agent\perflink.ico', 'agent\bin\PresentMon.exe',
             'agent\__pycache__\perflink_agent.*.pyc', 'agent\__pycache__\usb_tunnel.*.pyc', 'agent\cache\phone_app.json'
    $isPerfLink = $signs | Where-Object { Test-Path (Join-Path $dir $_) } | Select-Object -First 1
    # no files at all (only empty folders left) counts as empty
    $isEmpty = -not (Get-ChildItem $dir -Recurse -File -Force -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $isPerfLink -and -not $isEmpty) { throw "Refusing to delete '$dir': it doesn't look like a PerfLink install." }
}
function Find-Adb {
    $c = Get-Command adb -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    $list = @("$env:LOCALAPPDATA\Microsoft\WinGet\Links\adb.exe", "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe") +
            @(Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools*\platform-tools\adb.exe" -ErrorAction SilentlyContinue | ForEach-Object FullName)
    foreach ($p in $list) { if ($p -and (Test-Path $p)) { return $p } }
}
function Stop-Agent {
    # (not schtasks.exe: under ErrorAction Stop its "task not found" message aborted the install)
    if (Get-ScheduledTask -TaskName PerfLink -ErrorAction SilentlyContinue) {
        Stop-ScheduledTask -TaskName PerfLink -ErrorAction SilentlyContinue
    }
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like 'python*.exe' -and $_.CommandLine -like '*perflink_agent.py*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    # PerfLink's FPS helper keeps running (and locks agent\bin) if only the agent is stopped
    Get-Process PresentMon -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$App\*" } |
        Stop-Process -Force -ErrorAction SilentlyContinue
    # ...and so do its Bluetooth / Wi-Fi checks (powershell.exe running agent\*.ps1 inside the folder)
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -eq 'powershell.exe' -and $_.ProcessId -ne $PID -and $_.CommandLine -like "*$App\agent\*.ps1*" } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    # ...and so does adb's background server, when an older PerfLink started it from inside the
    # folder. Stopping it is harmless: it starts again by itself the next time it's needed.
    $adbExe = Find-Adb
    if ($adbExe) { try { cmd /c "`"$adbExe`" kill-server >nul 2>nul" } catch { } }
    Start-Sleep -Milliseconds 1500
}

if ($Repo -notmatch '^[\w.-]+/[\w.-]+$' -and -not $From) { throw "install.ps1: -Repo must look like 'user/repo' (got '$Repo')." }

# ------------------------------------------------------------------ run as administrator (one prompt)
if (-not (Is-Admin)) {
    Write-Host 'PerfLink installer: Windows will ask for administrator permission - click Yes.' -ForegroundColor White
    $self = Join-Path $env:TEMP 'perflink-install.ps1'
    if ($PSCommandPath) { Copy-Item $PSCommandPath $self -Force }
    else { Invoke-WebRequest "https://raw.githubusercontent.com/$Repo/$Branch/install.ps1" -OutFile $self -UseBasicParsing }
    $argv = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$self`"", '-Elevated',
              '-Repo', $Repo, '-Branch', $Branch, '-InstallDir', "`"$InstallDir`"", '-ForUser', "`"$ForUser`"")
    if ($From)          { $argv += @('-From', "`"$From`"") }
    foreach ($sw in 'Uninstall', 'SkipFirewall', 'SkipShortcuts', 'SkipAutostart', 'NoStart', 'NoAdb') {
        if ((Get-Variable $sw -ValueOnly).IsPresent) { $argv += "-$sw" }
    }
    try { Start-Process powershell -Verb RunAs -Wait -ArgumentList $argv }
    catch { Write-Host 'Administrator permission was declined - nothing was changed.' -ForegroundColor Yellow }
    return
}

# keep the window open at the end when we were re-launched in a new elevated window
function Finish { if ($Elevated) { Write-Host ''; Read-Host 'Press Enter to close' | Out-Null } }

# ------------------------------------------------------------------ uninstall
if ($Uninstall) {
    Step 'Removing PerfLink'
    $un = Join-Path $App 'uninstall.ps1'
    if (Test-Path $un) {   # same uninstaller Windows "Apps" uses
        & powershell -NoProfile -ExecutionPolicy Bypass -File $un -Quiet -Elevated -AppDir $App
        Done 'PerfLink removed (Python, adb and their packages were left installed).'
        Finish
        return
    }
    Stop-Agent
    Unregister-ScheduledTask -TaskName PerfLink -Confirm:$false -ErrorAction SilentlyContinue
    Remove-NetFirewallRule -DisplayName 'PerfLink TCP', 'PerfLink UDP' -ErrorAction SilentlyContinue
    Remove-Item $StartLnk, $DeskLnk -Force -ErrorAction SilentlyContinue
    Remove-Item 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\PerfLink' -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\PerfLink' -Recurse -Force -ErrorAction SilentlyContinue
    Assert-SafeToReplace $App
    Remove-Item $App -Recurse -Force -ErrorAction SilentlyContinue
    Done 'PerfLink removed (Python, adb and their packages were left installed).'
    Finish
    return
}

try {
Write-Host 'PerfLink installer' -ForegroundColor White
Write-Host "Source: https://github.com/$Repo" -ForegroundColor DarkGray

# ------------------------------------------------------------------ 1. Python (+ adb)
Step 'Checking Python'
function Find-Python {
    foreach ($c in 'py', 'python') {
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd -and $cmd.Source -notlike '*WindowsApps*') {   # skip the Microsoft Store stub
            try { $v = & $c -c 'import sys; print(sys.version_info >= (3, 10))' 2>$null } catch { $v = $null }
            if ($v -eq 'True') { return $c }
        }
    }
    return $null
}
function Refresh-Path { $env:Path = [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [Environment]::GetEnvironmentVariable('Path', 'Machine') }
$py = Find-Python
if (-not $py) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw 'Python 3.10+ is needed and winget is not available. Install Python from https://www.python.org/downloads/ (tick "Add python.exe to PATH") and run this again.'
    }
    Write-Host '    Python not found - installing Python 3.12 with winget...'
    winget install --id Python.Python.3.12 -e --scope user --silent --accept-package-agreements --accept-source-agreements | Out-Null
    Refresh-Path
    $py = Find-Python
    if (-not $py) { throw 'Python was installed but is not on PATH yet. Close this window, open a new PowerShell and run the install command again.' }
}
# Pin ONE interpreter (full path) for everything: packages are installed into it and the
# start-with-Windows task runs its pythonw.exe. ('py' and 'python' can be different Pythons.)
$pyExe = (& $py -c "import sys; print(sys.executable)").Trim()
$pyw = Join-Path (Split-Path $pyExe) 'pythonw.exe'
if (-not (Test-Path $pyw)) { $pyw = $pyExe }
Done "Using $(& $pyExe --version) at $pyExe"

if (-not $NoAdb -and -not (Get-Command adb -ErrorAction SilentlyContinue) -and (Get-Command winget -ErrorAction SilentlyContinue)) {
    Step 'Installing Android Platform Tools (adb) - lets PerfLink install the phone app over USB'
    winget install --id Google.PlatformTools -e --silent --accept-package-agreements --accept-source-agreements | Out-Null
    Refresh-Path
    if (Get-Command adb -ErrorAction SilentlyContinue) { Done 'adb installed' } else { Warn 'adb not installed (optional - you can install the APK by hand)' }
}

# ------------------------------------------------------------------ 2. download PerfLink
Step 'Downloading PerfLink'
$tmp = Join-Path $env:TEMP ("perflink-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $tmp | Out-Null
if ($From) {
    # local copy (testing): only the files that would be in the repo
    $stage = Join-Path $tmp 'PerfLink-local'
    robocopy $From $stage /E /NFL /NDL /NJH /NJS /NP /XD _downloads _toolchain bin __pycache__ build .gradle .git /XF *.apk local.properties config.json perflink.log* | Out-Null
    $src = Get-Item $stage
} else {
    $zip = Join-Path $tmp 'perflink.zip'
    Invoke-WebRequest "https://github.com/$Repo/archive/refs/heads/$Branch.zip" -OutFile $zip -UseBasicParsing
    Expand-Archive $zip $tmp -Force
    $src = Get-ChildItem $tmp -Directory | Select-Object -First 1
}
Stop-Agent   # updating: the running agent locks files in agent\bin
# keep the user's settings (weather city) and already-downloaded helpers when updating
$keep = @{}
foreach ($f in 'agent\config.json') { $p = Join-Path $App $f; if (Test-Path $p) { $keep[$f] = Get-Content $p -Raw } }
Assert-SafeToReplace $App
# leftovers of earlier updates whose old folder couldn't be deleted at the time
Get-ChildItem (Split-Path $App) -Directory -Filter "$(Split-Path $App -Leaf).old-*" -ErrorAction SilentlyContinue |
    Where-Object { -not (Test-Path (Join-Path $_.FullName '.git')) } |
    ForEach-Object { Remove-Item $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
# Replace the old version. First RENAME the old folder: that either fully works or changes nothing,
# so a locked file can never leave a half-deleted install behind. Files can stay locked for a moment
# after PerfLink stops, so retry; if something still holds them, say what.
$old = $null
$hadBin = $false
$pcKept = $false      # true: the PC part couldn't be updated now; the current version keeps running
$pcWho = $null
for ($try = 1; ; $try++) {
    try {
        if (Test-Path $App) {
            $old = "$App.old-$(Get-Date -Format yyyyMMddHHmmss)"
            Rename-Item $App (Split-Path $old -Leaf) -ErrorAction Stop
        }
        break
    } catch {
        if ($try -ge 10) {
            $who = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
                Where-Object { $_.ProcessId -ne $PID -and ($_.ExecutablePath -like "$App\*" -or $_.CommandLine -like "*$App*") } |
                ForEach-Object { "$($_.Name) (pid $($_.ProcessId))" }
            $pcWho = $who -join ', '
            if (-not (Test-Path (Join-Path $App 'agent\perflink_agent.py'))) {   # nothing usable to keep
                throw ("Couldn't replace $App because its files are in use" + $(if ($who) { " by: $pcWho" } else { '' }) +
                       ". Restart the PC, then run the command again.")
            }
            $pcKept = $true
            break
        }
        Stop-Agent
        if ($try -ge 3) {   # anything else of PerfLink's still running from the folder (old helpers)
            Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -like 'powershell*.exe' -and $_.ProcessId -ne $PID -and $_.CommandLine -like "*$App\*" } |
                ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        }
        Start-Sleep 1
    }
}
if ($pcKept) {
    Warn "Couldn't update PerfLink on this PC right now: its files are in use$(if ($pcWho) { " by $pcWho" })."
    Warn 'The current version keeps working. Close any File Explorer or terminal window open in that folder,'
    Warn 'or restart the PC, then run the command again to update it.'
} else {
    Move-Item $src.FullName $App
    # marker: the uninstaller only ever deletes folders that carry it
    Set-Content (Join-Path $App '.perflink-install') "PerfLink install folder (created by install.ps1 on $(Get-Date -Format s)). The uninstaller deletes this folder." -Encoding utf8
    if ($old) {
        # keep the already-downloaded helpers (PresentMon, sensor DLLs), then delete the old version
        $oldBin = Join-Path $old 'agent\bin'
        if (Test-Path (Join-Path $oldBin 'PresentMon.exe')) {
            $hadBin = $true
            Move-Item $oldBin (Join-Path $App 'agent\bin') -Force -ErrorAction SilentlyContinue
            if (-not (Test-Path (Join-Path $App 'agent\bin\PresentMon.exe'))) {   # moved? else copy
                Copy-Item $oldBin (Join-Path $App 'agent') -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
        Remove-Item $old -Recurse -Force -ErrorAction SilentlyContinue   # anything still locked goes next time
    }
    foreach ($f in $keep.Keys) { Set-Content (Join-Path $App $f) $keep[$f] -Encoding utf8 }
    Done "Installed to $App"

    # ------------------------------------------------------------------ 3. dependencies
    Step 'Installing dependencies (Python packages, PresentMon, LibreHardwareMonitor)'
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $App 'agent\setup.ps1') -Python $pyExe
    if ($LASTEXITCODE -ne 0) { throw 'Dependency setup failed (see the messages above).' }
    Set-Content (Join-Path $App 'agent\pythonw.txt') $pyw -Encoding ascii   # used by PerfLink.vbs
    # quick self-test: the chosen Python can load everything PerfLink needs
    $imp = & $pyExe -c "import psutil, websockets, clr, pycaw; print('ok')" 2>&1
    if ("$imp" -notmatch 'ok') { throw "Python packages are missing in $pyExe : $imp" }
    Done 'Dependencies ready'

    # ------------------------------------------------------------------ 4. firewall
    Step 'Allowing the phone through the Windows Firewall'
    if ($SkipFirewall) { Done 'Skipped (-SkipFirewall)' }
    else {
        Remove-NetFirewallRule -DisplayName 'PerfLink TCP', 'PerfLink UDP' -ErrorAction SilentlyContinue
        New-NetFirewallRule -DisplayName 'PerfLink TCP' -Direction Inbound -Protocol TCP -LocalPort 47800 -Action Allow | Out-Null
        New-NetFirewallRule -DisplayName 'PerfLink UDP' -Direction Inbound -Protocol UDP -LocalPort 47801 -Action Allow | Out-Null
        Done 'Firewall rules added (TCP 47800, UDP 47801)'
    }

    # ------------------------------------------------------------------ 5. start with Windows (tray)
    Step 'Making PerfLink start with Windows (tray icon, no window)'
    $agentPy = Join-Path $App 'agent\perflink_agent.py'
    if ($SkipAutostart) { Done 'Skipped (-SkipAutostart)' }
    else {
        $a = New-ScheduledTaskAction -Execute $pyw -Argument "`"$agentPy`" --tray" -WorkingDirectory (Join-Path $App 'agent')
        $t = New-ScheduledTaskTrigger -AtLogOn -User $ForUser
        $p = New-ScheduledTaskPrincipal -UserId $ForUser -LogonType Interactive -RunLevel Highest
        $s = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -StartWhenAvailable
        Register-ScheduledTask -TaskName PerfLink -Action $a -Trigger $t -Principal $p -Settings $s `
            -Description 'PerfLink: PC stats on your phone (tray icon)' -Force | Out-Null
        Done "Starts automatically when $ForUser signs in (as administrator, no prompt)"
    }

    # ------------------------------------------------------------------ 6. shortcuts
    Step 'Creating shortcuts'
    if ($SkipShortcuts) { Done 'Skipped (-SkipShortcuts)' }
    else {
        $ico = Join-Path $App 'agent\perflink.ico'
        try { & $pyExe -c "import sys; sys.path.insert(0, r'$App\agent'); import tray; tray.save_icon(r'$ico')" 2>$null } catch {}
        $shell = New-Object -ComObject WScript.Shell
        foreach ($lnk in $StartLnk, $DeskLnk) {
            $sc = $shell.CreateShortcut($lnk)
            $sc.TargetPath = "$env:SystemRoot\System32\wscript.exe"
            $sc.Arguments = "`"$(Join-Path $App 'PerfLink.vbs')`""
            $sc.WorkingDirectory = $App
            $sc.Description = 'PerfLink - PC stats on your phone'
            $sc.IconLocation = if (Test-Path $ico) { $ico } else { "$env:SystemRoot\System32\imageres.dll,144" }
            $sc.Save()
        }
        Done 'Start menu + desktop shortcut "PerfLink"'
    }

    # ------------------------------------------------------------------ Windows "Apps / Programs and Features" entry
    Step 'Registering PerfLink with Windows (Apps & features / Programs and Features)'
    $ver = (Select-String -Path $agentPy -Pattern '^VERSION\s*=\s*"([^"]+)"' | Select-Object -First 1).Matches.Groups[1].Value
    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\PerfLink'
    New-Item $key -Force | Out-Null
    $sizeKb = [int]((Get-ChildItem $App -Recurse -File | Measure-Object Length -Sum).Sum / 1KB)
    $un = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$(Join-Path $App 'uninstall.ps1')`""
    $vals = @{
        DisplayName = 'PerfLink'; DisplayVersion = $ver; Publisher = 'Nirabhra Koley'
        DisplayIcon = $(if (Test-Path (Join-Path $App 'agent\perflink.ico')) { Join-Path $App 'agent\perflink.ico' } else { $pyw })
        InstallLocation = $App; URLInfoAbout = "https://github.com/$Repo"; HelpLink = "https://github.com/$Repo/blob/main/INSTALL.md"
        UninstallString = $un; QuietUninstallString = "$un -Quiet"
        InstallDate = (Get-Date -Format 'yyyyMMdd'); Comments = 'PC stats and controls on your Android phone'
    }
    foreach ($k in $vals.Keys) { Set-ItemProperty $key -Name $k -Value $vals[$k] }
    foreach ($k in 'NoModify', 'NoRepair') { Set-ItemProperty $key -Name $k -Value 1 -Type DWord }
    Set-ItemProperty $key -Name EstimatedSize -Value $sizeKb -Type DWord
    Done "Listed as 'PerfLink $ver' - uninstall it from Settings > Apps or Control Panel like any app"
}

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

$agentPy = Join-Path $App 'agent\perflink_agent.py'
$apkUrl = "https://github.com/$Repo/releases/latest/download/PerfLink.apk"

# ------------------------------------------------------------------ 7. phone app (USB)
# Done here, while PerfLink is stopped, so the installer and PerfLink never install at the same time,
# and so the phone is updated even when the PC part couldn't be.
# adb writes notes like "* daemon not running; starting now" to stderr, which Windows PowerShell 5.1
# turns into errors under ErrorAction Stop. Run it through cmd so they never reach PowerShell.
function Invoke-Adb([string]$argLine) { try { @(cmd /c "`"$script:adb`" $argLine 2>nul") } catch { @() } }
function Get-Phones {
    $all = Invoke-Adb 'devices' | Select-Object -Skip 1 | Where-Object { $_ -match "`t" }
    [pscustomobject]@{
        Ready   = @($all | Where-Object { $_ -match "`tdevice$" } | ForEach-Object { ($_ -split "`t")[0] })
        Waiting = @($all | Where-Object { $_ -match "`tunauthorized$" } | ForEach-Object { ($_ -split "`t")[0] })
    }
}
function ConvertTo-Version([string]$v) {
    $n = @([regex]::Matches("$v", '\d+') | ForEach-Object { [int]$_.Value })
    while ($n.Count -lt 2) { $n += 0 }
    [version]($n[0..([Math]::Min(3, $n.Count - 1))] -join '.')
}
function Update-PhoneApp {
    Step 'Phone app (USB)'
    $script:adb = Find-Adb
    if ($NoAdb -or -not $script:adb) { Done "adb isn't available - install the app on the phone from $apkUrl"; return }
    $ph = Get-Phones
    if ($ph.Waiting.Count -and -not $ph.Ready.Count) {
        Warn "On the phone, tap 'Allow' on the 'Allow USB debugging?' pop-up (tick 'Always allow')..."
        for ($i = 0; $i -lt 30 -and -not $ph.Ready.Count; $i++) { Start-Sleep 2; $ph = Get-Phones }
    }
    if (-not $ph.Ready.Count) {
        if ($ph.Waiting.Count) { Warn "USB debugging wasn't allowed on the phone. Allow it and replug the phone: PerfLink then updates the app by itself." }
        else { Done 'No phone plugged in. Plug it in any time (USB debugging on) and PerfLink installs or updates the app by itself.' }
        return
    }
    $asset = $null
    try {
        $rel = Invoke-RestMethod "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ 'User-Agent' = 'PerfLink-installer' }
        $asset = $rel.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
        $latest = $rel.tag_name -replace '^[vV]', ''
    } catch { }
    if (-not $asset) { Warn "Couldn't get the phone app from GitHub - install it on the phone from $apkUrl"; return }
    $apk = $null
    foreach ($serial in $ph.Ready) {
        $model = "$(Invoke-Adb "-s $serial shell getprop ro.product.model" | Select-Object -First 1)".Trim()
        $name = if ($model) { $model } else { $serial }
        $m = [regex]::Match(((Invoke-Adb "-s $serial shell dumpsys package com.perflink.app") -join "`n"), 'versionName=([\w.\-]+)')
        $have = if ($m.Success) { $m.Groups[1].Value } else { $null }
        if ($have -and (ConvertTo-Version $have) -ge (ConvertTo-Version $latest)) {
            Done "$($name): PerfLink app is up to date (version $have)"
            continue
        }
        if (-not $apk) {
            $apk = Join-Path $env:TEMP "PerfLink-$latest.apk"
            try { Invoke-WebRequest $asset.browser_download_url -OutFile $apk -UseBasicParsing }
            catch { Warn "Couldn't download the phone app - install it on the phone from $apkUrl"; return }
        }
        Write-Host "    Wait, application is installing in your mobile ($name)" -ForegroundColor Yellow -NoNewline
        $log = Join-Path $env:TEMP 'perflink-adb-install.txt'
        $proc = Start-Process cmd -ArgumentList "/c `"`"$script:adb`" -s $serial install -r `"$apk`" > `"$log`" 2>&1`"" -WindowStyle Hidden -PassThru
        $begin = Get-Date
        while (-not $proc.HasExited -and ((Get-Date) - $begin).TotalMinutes -lt 5) { Start-Sleep 2; Write-Host '.' -ForegroundColor Yellow -NoNewline }
        Write-Host ''
        $out = "$(Get-Content $log -Raw -ErrorAction SilentlyContinue)"
        if ($out -match 'Success') {
            Done "$($name): PerfLink app $(if ($have) { "updated $have -> $latest" } else { "installed (version $latest)" })"
            if (-not $have) { Invoke-Adb "-s $serial shell am start -n com.perflink.app/.MainActivity -e host 127.0.0.1" | Out-Null }
        } elseif (-not $proc.HasExited) {
            Warn "$($name): still installing - keep the phone plugged in; it finishes by itself."
        } elseif ($out -match 'USER_RESTRICTED|CANCELED_BY_USER') {
            Warn "$($name): the phone blocked the install. Turn on 'Install via USB' in Developer options, then replug the phone."
        } else {
            Warn "$($name): the app didn't install ($($out.Trim() -replace '\s+', ' ')). Install it on the phone from $apkUrl"
        }
    }
    if ($apk) { Remove-Item $apk -Force -ErrorAction SilentlyContinue }
}
Update-PhoneApp

# ------------------------------------------------------------------ 8. start
if (-not $NoStart) {
    Step 'Starting PerfLink'
    if (-not $SkipAutostart) { Start-ScheduledTask -TaskName PerfLink }
    else { Start-Process $pyw -ArgumentList "`"$agentPy`" --tray" -WorkingDirectory (Join-Path $App 'agent') }
    # Make sure it really started (it runs without a window, so a crash would otherwise go unnoticed)
    $up = $false
    for ($i = 0; $i -lt 30 -and -not $up; $i++) {
        Start-Sleep 1
        $up = [bool](Get-NetTCPConnection -LocalPort 47800 -State Listen -ErrorAction SilentlyContinue)
    }
    if ($up) { Done 'Running in the background - look for the PerfLink icon next to the clock (it may be under the ^ arrow)' }
    else {
        Warn "PerfLink didn't start. Last lines of its log ($(Join-Path $App 'agent\perflink.log')):"
        Get-Content (Join-Path $App 'agent\perflink.log') -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" -ForegroundColor Yellow }
        throw 'PerfLink could not start - please share a screenshot of this window.'
    }
}

$ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
       Where-Object { $_.PrefixOrigin -in 'Dhcp', 'Manual' -and $_.IPAddress -notlike '169.*' } |
       Select-Object -First 1).IPAddress
Write-Host ''
if ($pcKept) {
    Write-Host 'Phone part done - the PC part still needs an update.' -ForegroundColor Yellow
    Write-Host '  PerfLink on this PC is still the previous version (its files were in use).'
    Write-Host '  Restart the PC, then run the install command again to update it.'
} else {
    Write-Host 'All set!' -ForegroundColor Green
}
Write-Host '  Phone app: plug your phone into this PC with USB debugging allowed and PerfLink installs'
Write-Host '             the app (and future updates) by itself. Or download it on the phone:'
Write-Host "             $apkUrl"
Write-Host '  Then connect the phone to the same Wi-Fi as this PC, open PerfLink and tap your PC'
Write-Host "  (or type its IP: $ip)."
Write-Host '  PerfLink now starts with Windows. Right-click its tray icon for options.'

}
catch {
    Write-Host ''
    Write-Host "Installation failed: $($_.Exception.Message)" -ForegroundColor Red
}
Finish
