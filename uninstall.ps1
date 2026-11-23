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
# 1. stop PerfLink
if (Get-ScheduledTask -TaskName PerfLink) { Stop-ScheduledTask -TaskName PerfLink }
Get-CimInstance Win32_Process | Where-Object { $_.Name -in 'python.exe', 'pythonw.exe' -and $_.CommandLine -like '*perflink_agent.py*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
Start-Sleep -Milliseconds 800
# 2. auto-start, firewall
Unregister-ScheduledTask -TaskName PerfLink -Confirm:$false
Remove-NetFirewallRule -DisplayName 'PerfLink TCP', 'PerfLink UDP'
# 3. shortcuts + Programs and Features entry
Remove-Item (Join-Path ([Environment]::GetFolderPath('Programs')) 'PerfLink.lnk'), (Join-Path ([Environment]::GetFolderPath('Desktop')) 'PerfLink.lnk') -Force
Remove-Item $Key -Recurse -Force
# 4. the program folder: only a folder the installer created (it carries the .perflink-install marker,
#    or is the default install folder of an older version), and never a source checkout (.git)
$default = Join-Path $env:LOCALAPPDATA 'PerfLink'
$isInstall = (Test-Path (Join-Path $App '.perflink-install')) -or
             ((Resolve-Path $App -ErrorAction SilentlyContinue).Path -eq $default -and (Test-Path (Join-Path $App 'agent\perflink_agent.py')))
if ($App -and $isInstall -and -not (Test-Path (Join-Path $App '.git'))) { Remove-Item $App -Recurse -Force }

if (-not $Quiet) {
    Add-Type -AssemblyName System.Windows.Forms
    [void][System.Windows.Forms.MessageBox]::Show('PerfLink has been removed from this PC.' + "`n`n" +
        'To remove the phone app, uninstall PerfLink on the phone.', 'PerfLink', 'OK', 'Information')
}
