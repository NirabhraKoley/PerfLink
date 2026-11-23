# PerfLink uninstaller (works offline). Used by Windows "Apps / Programs and Features", the tray
# menu, and `install.ps1 -Uninstall`. Removes everything the installer added:
#   the background agent, the "start with Windows" task, the firewall rules, the shortcuts,
#   the Programs and Features entry and the PerfLink folder.
# Python, adb and the phone app are left alone (uninstall the phone app on the phone).
param([switch]$Quiet, [switch]$Elevated, [string]$AppDir)

$ErrorActionPreference = 'SilentlyContinue'
$App = if ($AppDir) { $AppDir } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$Key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\PerfLink'

function Is-Admin { ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator) }

# The script lives inside the folder it deletes: run a copy from %TEMP%, as administrator.
if (-not $Elevated) {
    if (-not $Quiet) {
        Add-Type -AssemblyName System.Windows.Forms
        $r = [System.Windows.Forms.MessageBox]::Show('Remove PerfLink from this PC?' + "`n`n" +
            'This stops PerfLink and removes it, its auto-start, firewall rules and shortcuts.',
            'Uninstall PerfLink', 'YesNo', 'Question')
        if ($r -ne 'Yes') { return }
    }
    $copy = Join-Path $env:TEMP 'perflink-uninstall.ps1'
    Copy-Item $MyInvocation.MyCommand.Path $copy -Force
    $argv = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-WindowStyle', 'Hidden', '-File', "`"$copy`"",
              '-Elevated', '-AppDir', "`"$App`"")
    if ($Quiet) { $argv += '-Quiet' }
    if (Is-Admin) { Start-Process powershell -ArgumentList $argv -Wait -WindowStyle Hidden }
    else { try { Start-Process powershell -Verb RunAs -ArgumentList $argv -Wait -WindowStyle Hidden } catch { return } }
    return
}

# ------------------------------------------------------------------ elevated copy does the work
# Only remove what belongs to THIS install: a PC can have another PerfLink (e.g. a second copy or
# a source checkout), whose start-up task, shortcuts and Apps entry must be left alone.
$paths = @($App, (Resolve-Path $App -ErrorAction SilentlyContinue).Path,
           (New-Object -ComObject Scripting.FileSystemObject).GetFolder($App).Path) | Where-Object { $_ } | Select-Object -Unique
function Mine($text) { foreach ($p in $paths) { if ("$text" -like "*$p\*") { return $true } }; $false }
$task = Get-ScheduledTask -TaskName PerfLink
$ownTask = $task -and (Mine $task.Actions[0].Arguments)
$reg = Get-ItemProperty $Key
$ownReg = $reg -and (Mine "$($reg.InstallLocation)\")

# 1. stop PerfLink running from this folder (and its FPS helper)
if ($ownTask) { Stop-ScheduledTask -TaskName PerfLink }
Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'python*.exe' -and (Mine $_.CommandLine) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Get-Process PresentMon | Where-Object { Mine $_.Path } | Stop-Process -Force
Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'powershell.exe' -and $_.ProcessId -ne $PID -and
    $_.CommandLine -like '*.ps1*' -and $_.CommandLine -notlike '*uninstall.ps1*' -and (Mine $_.CommandLine) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep -Milliseconds 800
# 2. start with Windows; firewall rules (shared) only when removing the registered install
if ($ownTask) { Unregister-ScheduledTask -TaskName PerfLink -Confirm:$false }
if ($ownReg -or -not $reg) { Remove-NetFirewallRule -DisplayName 'PerfLink TCP', 'PerfLink UDP' }
# 3. shortcuts that start this install, and its Programs and Features entry
$shell = New-Object -ComObject WScript.Shell
foreach ($lnk in (Join-Path ([Environment]::GetFolderPath('Programs')) 'PerfLink.lnk'), (Join-Path ([Environment]::GetFolderPath('Desktop')) 'PerfLink.lnk')) {
    if ((Test-Path $lnk) -and (Mine $shell.CreateShortcut($lnk).Arguments)) { Remove-Item $lnk -Force }
}
if ($ownReg) { Remove-Item $Key -Recurse -Force }
# 4. the program folder: only a folder the installer created (it carries the .perflink-install marker,
#    or is the default install folder of an older version), and never a source checkout (.git)
$default = Join-Path $env:LOCALAPPDATA 'PerfLink'
$isInstall = (Test-Path (Join-Path $App '.perflink-install')) -or
             ((Resolve-Path $App -ErrorAction SilentlyContinue).Path -eq $default -and (Test-Path (Join-Path $App 'agent\perflink_agent.py')))
if ($App -and $isInstall -and -not (Test-Path (Join-Path $App '.git'))) {
    for ($i = 0; $i -lt 10 -and (Test-Path $App); $i++) {   # files can stay locked for a moment
        Remove-Item $App -Recurse -Force
        if (Test-Path $App) { Start-Sleep 1 }
    }
}

#    plus old versions an update couldn't delete at the time (PerfLink.old-<date>)
if ($App -and $isInstall) {
    Get-ChildItem (Split-Path $App) -Directory -Filter "$(Split-Path $App -Leaf).old-*" |
        Where-Object { -not (Test-Path (Join-Path $_.FullName '.git')) } | ForEach-Object { Remove-Item $_.FullName -Recurse -Force }
}

if (-not $Quiet) {
    Add-Type -AssemblyName System.Windows.Forms
    [void][System.Windows.Forms.MessageBox]::Show('PerfLink has been removed from this PC.' + "`n`n" +
        'To remove the phone app, uninstall PerfLink on the phone.', 'PerfLink', 'OK', 'Information')
}
