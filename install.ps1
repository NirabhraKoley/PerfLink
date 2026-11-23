# PerfLink one-command installer for Windows
#
#   Install:    irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/install.ps1 | iex
#   Uninstall:  & ([scriptblock]::Create((irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/install.ps1))) -Uninstall
#
# What it does (nothing else):
#   1. Installs Python 3.12 with winget if Python isn't found
#   2. Downloads PerfLink into %LOCALAPPDATA%\PerfLink
#   3. Installs the Python packages, PresentMon and LibreHardwareMonitor (agent\setup.ps1)
#   4. Opens the Windows Firewall for PerfLink's two ports (one admin prompt)
#   5. Adds "PerfLink" to the Start menu and desktop, and starts it
#   6. If a phone is connected with USB debugging and adb is installed, installs the app on it
param(
    [switch]$Uninstall,
    [string]$Repo = 'NirabhraKoley/PerfLink',   # <-- your GitHub "user/repo"
    [string]$Branch = 'main',
    # advanced / testing
    [string]$From,                               # install from a local folder instead of downloading
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'PerfLink'),
    [switch]$SkipFirewall, [switch]$SkipShortcuts, [switch]$NoStart
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # much faster Invoke-WebRequest
$App      = $InstallDir
$StartLnk = Join-Path ([Environment]::GetFolderPath('Programs')) 'PerfLink.lnk'
$DeskLnk  = Join-Path ([Environment]::GetFolderPath('Desktop')) 'PerfLink.lnk'
$FwRules  = 'New-NetFirewallRule -DisplayName ''PerfLink TCP'' -Direction Inbound -Protocol TCP -LocalPort 47800 -Action Allow | Out-Null; ' +
            'New-NetFirewallRule -DisplayName ''PerfLink UDP'' -Direction Inbound -Protocol UDP -LocalPort 47801 -Action Allow | Out-Null'

function Step($t) { Write-Host "`n==> $t" -ForegroundColor Cyan }
function Done($t) { Write-Host "    $t" -ForegroundColor Green }

# ------------------------------------------------------------------ uninstall
if ($Uninstall) {
    Step 'Removing PerfLink'
    Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*perflink_agent.py*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Remove-Item $StartLnk, $DeskLnk -Force -ErrorAction SilentlyContinue
    Remove-Item $App -Recurse -Force -ErrorAction SilentlyContinue
    if (Get-NetFirewallRule -DisplayName 'PerfLink*' -ErrorAction SilentlyContinue) {
        Start-Process powershell -Verb RunAs -Wait -ArgumentList '-NoProfile', '-Command',
            "Remove-NetFirewallRule -DisplayName 'PerfLink TCP','PerfLink UDP' -ErrorAction SilentlyContinue"
    }
    Done 'PerfLink removed (Python and its packages were left installed).'
    return
}

if ($Repo -like 'NirabhraKoley/*' -and -not $From) { throw 'install.ps1: set $Repo to your GitHub "user/repo" before publishing.' }

Write-Host 'PerfLink installer' -ForegroundColor White
Write-Host "Source: https://github.com/$Repo" -ForegroundColor DarkGray

# ------------------------------------------------------------------ 1. Python
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
$py = Find-Python
if (-not $py) {
    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        throw 'Python 3.10+ is needed and winget is not available. Install Python from https://www.python.org/downloads/ (tick "Add python.exe to PATH") and run this again.'
    }
    Write-Host '    Python not found - installing Python 3.12 with winget...'
    winget install --id Python.Python.3.12 -e --scope user --silent --accept-package-agreements --accept-source-agreements | Out-Null
    $env:Path = [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $py = Find-Python
    if (-not $py) { throw 'Python was installed but is not on PATH yet. Close this window, open a new PowerShell and run the install command again.' }
}
Done "Using $(& $py --version)"

# ------------------------------------------------------------------ 2. download PerfLink
Step 'Downloading PerfLink'
$tmp = Join-Path $env:TEMP ("perflink-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $tmp | Out-Null
if ($From) {
    # local copy (testing): only the files that would be in the repo
    $stage = Join-Path $tmp 'PerfLink-local'
    robocopy $From $stage /E /NFL /NDL /NJH /NJS /NP /XD _downloads _toolchain bin __pycache__ build .gradle .git /XF *.apk local.properties config.json | Out-Null
    $src = Get-Item $stage
} else {
    $zip = Join-Path $tmp 'perflink.zip'
    Invoke-WebRequest "https://github.com/$Repo/archive/refs/heads/$Branch.zip" -OutFile $zip -UseBasicParsing
    Expand-Archive $zip $tmp -Force
    $src = Get-ChildItem $tmp -Directory | Select-Object -First 1
}
# keep the user's settings (weather city) and already-downloaded helpers when updating
$keep = @{}
foreach ($f in 'agent\config.json') { $p = Join-Path $App $f; if (Test-Path $p) { $keep[$f] = Get-Content $p -Raw } }
$hadBin = Test-Path (Join-Path $App 'agent\bin\PresentMon.exe')
if ($hadBin) { Move-Item (Join-Path $App 'agent\bin') (Join-Path $tmp 'bin-keep') -Force }
if (-not $From) {   # updating: stop the running agent so its files can be replaced
    Get-CimInstance Win32_Process -Filter "Name='python.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -like '*perflink_agent.py*' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
if (Test-Path $App) { Remove-Item $App -Recurse -Force }
Move-Item $src.FullName $App
if ($hadBin) { Move-Item (Join-Path $tmp 'bin-keep') (Join-Path $App 'agent\bin') -Force }
foreach ($f in $keep.Keys) { Set-Content (Join-Path $App $f) $keep[$f] -Encoding utf8 }
Done "Installed to $App"

# ------------------------------------------------------------------ 3. dependencies
Step 'Installing dependencies (Python packages, PresentMon, LibreHardwareMonitor)'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $App 'agent\setup.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Dependency setup failed (see the messages above).' }
Done 'Dependencies ready'

# ------------------------------------------------------------------ 4. firewall
Step 'Allowing the phone through the Windows Firewall'
if ($SkipFirewall) {
    Done 'Skipped (-SkipFirewall)'
} elseif (Get-NetFirewallRule -DisplayName 'PerfLink TCP' -ErrorAction SilentlyContinue) {
    Done 'Firewall rules already present'
} else {
    Write-Host '    Windows will ask for administrator permission once - click Yes.'
    try {
        Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile', '-Command', $FwRules
        Done 'Firewall rules added (TCP 47800, UDP 47801)'
    } catch {
        Write-Host '    Skipped (permission declined). Wi-Fi connections may be blocked; USB mode still works.' -ForegroundColor Yellow
    }
}

# ------------------------------------------------------------------ 5. shortcuts + start
Step 'Creating shortcuts'
$launcher = Join-Path $App 'start_agent_admin.bat'   # admin: FPS works on every PC + CPU temperature
if ($SkipShortcuts) {
    Done 'Skipped (-SkipShortcuts)'
} else {
    $shell = New-Object -ComObject WScript.Shell
    foreach ($lnk in $StartLnk, $DeskLnk) {
        $s = $shell.CreateShortcut($lnk)
        $s.TargetPath = $launcher
        $s.WorkingDirectory = $App
        $s.Description = 'PerfLink - PC stats on your phone'
        $s.IconLocation = "$env:SystemRoot\System32\imageres.dll,144"
        $s.Save()
    }
    Done 'Start menu + desktop shortcut "PerfLink"'
}

# ------------------------------------------------------------------ 6. phone app via USB (optional)
$apkUrl = "https://github.com/$Repo/releases/latest/download/PerfLink.apk"
$adb = Get-Command adb -ErrorAction SilentlyContinue
if ($adb -and -not $From) {
    $devices = (& adb devices) | Select-String '\tdevice$'
    if ($devices) {
        Step 'Installing the phone app over USB'
        $apk = Join-Path $tmp 'PerfLink.apk'
        try {
            Invoke-WebRequest $apkUrl -OutFile $apk -UseBasicParsing
            & adb install -r $apk | Out-Null
            Done 'PerfLink installed on the connected phone'
        } catch { Write-Host '    Could not install over USB - use the download link below.' -ForegroundColor Yellow }
    }
}
Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue

if (-not $NoStart) {
    Step 'Starting PerfLink'
    Start-Process $launcher -WorkingDirectory $App
    Done 'Agent starting (approve the admin prompt)'
}

$ip = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
       Where-Object { $_.PrefixOrigin -in 'Dhcp', 'Manual' -and $_.IPAddress -notlike '169.*' } |
       Select-Object -First 1).IPAddress
Write-Host ''
Write-Host 'All set!' -ForegroundColor Green
Write-Host "  1. On your phone, install the app:  $apkUrl"
Write-Host '  2. Connect the phone to the same Wi-Fi as this PC and open PerfLink.'
Write-Host "  3. Tap your PC in the list (or type its IP: $ip)."
Write-Host '  Next time, just open "PerfLink" from the Start menu or desktop.'
