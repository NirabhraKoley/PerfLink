# PerfLink diagnostics: prints what's installed and running, to troubleshoot problems.
#   irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/diagnose.ps1 | iex
# Only reads information; changes nothing. Nothing is sent anywhere.
$ErrorActionPreference = 'SilentlyContinue'
function H($t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Row($k, $v, [bool]$ok = $true) { Write-Host ("  {0,-26} " -f $k) -NoNewline; Write-Host $v -ForegroundColor $(if ($ok) { 'Green' } else { 'Yellow' }) }

$App = Join-Path $env:LOCALAPPDATA 'PerfLink'
H 'Install'
$agentPy = Join-Path $App 'agent\perflink_agent.py'
$ver = (Select-String -Path $agentPy -Pattern '^VERSION\s*=\s*"([^"]+)"').Matches.Groups[1].Value
Row 'Folder' $(if (Test-Path $agentPy) { "$App (version $ver)" } else { 'NOT installed here' }) (Test-Path $agentPy)
Row 'Helpers (agent\bin)' ("{0} files" -f (Get-ChildItem (Join-Path $App 'agent\bin') -File).Count) ((Get-ChildItem (Join-Path $App 'agent\bin') -File).Count -ge 9)
$reg = Get-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\PerfLink'
Row 'Listed in Settings > Apps' $(if ($reg) { "yes ($($reg.DisplayVersion))" } else { 'no' }) ([bool]$reg)

H 'Start with Windows (scheduled task)'
$task = Get-ScheduledTask -TaskName PerfLink
if ($task) {
    $info = $task | Get-ScheduledTaskInfo
    Row 'Task' "$($task.State), last run $($info.LastRunTime), last result 0x$('{0:X}' -f $info.LastTaskResult)" ($info.LastTaskResult -in 0, 267009)
    Row 'Runs' "$($task.Actions[0].Execute) $($task.Actions[0].Arguments)"
    Row 'Runs as' "$($task.Principal.UserId) ($($task.Principal.RunLevel))"
} else { Row 'Task' 'missing (PerfLink will not start with Windows)' $false }

H 'Running now'
$procs = Get-CimInstance Win32_Process | Where-Object { $_.Name -in 'python.exe', 'pythonw.exe' -and $_.CommandLine -like '*perflink_agent.py*' }
Row 'PerfLink process' $(if ($procs) { ($procs | ForEach-Object { "$($_.Name) pid $($_.ProcessId)$(if ($_.CommandLine -like '*--tray*') { ' (tray mode)' })" }) -join ', ' } else { 'not running' }) ([bool]$procs)
$listen = Get-NetTCPConnection -LocalPort 47800 -State Listen
Row 'Port 47800' $(if ($listen) { 'listening' } else { 'not listening' }) ([bool]$listen)
$phones = (Get-NetTCPConnection -LocalPort 47800 -State Established | Measure-Object).Count
Row 'Phones connected' $phones ($phones -gt 0)

H 'Log (last 25 lines)'
$log = Join-Path $App 'agent\perflink.log'
if (Test-Path $log) { Get-Content $log -Tail 25 | ForEach-Object { "  $_" } } else { Row 'Log' 'no log file yet' $false }

H 'Phone over USB'
$adb = (Get-Command adb).Source
if (-not $adb) { $adb = Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\Google.PlatformTools*\platform-tools\adb.exe" | Select-Object -First 1 -ExpandProperty FullName }
Row 'adb' $(if ($adb) { $adb } else { 'not found' }) ([bool]$adb)
if ($adb) {
    $dev = (& $adb devices) | Select-Object -Skip 1 | Where-Object { $_.Trim() }
    if ($dev) { $dev | ForEach-Object {
        $state = ($_ -split "`t")[1]
        Row 'Phone' $(switch ($state) { 'device' { 'connected and allowed' } 'unauthorized' { "waiting: tap 'Allow' on the phone's USB debugging pop-up" } default { $state } }) ($state -eq 'device') }
    } else { Row 'Phone' 'none (plug it in with USB debugging on)' $false }
}

H 'Network'
Row 'Firewall rules' $(if (Get-NetFirewallRule -DisplayName 'PerfLink*') { 'present' } else { 'missing' }) ([bool](Get-NetFirewallRule -DisplayName 'PerfLink*'))
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.PrefixOrigin -in 'Dhcp', 'Manual' -and $_.IPAddress -notlike '169.*' } | Select-Object -First 1).IPAddress
Row 'PC IP' $ip

H 'Python'
foreach ($c in 'py', 'python') { $cmd = Get-Command $c; if ($cmd -and $cmd.Source -notlike '*WindowsApps*') { $py = $c; break } }
Row 'Python' $(if ($py) { (& $py --version) } else { 'not found' }) ([bool]$py)
if ($py) {
    $t = & $py -c "import clr, System; clr.AddReference('System.Windows.Forms'); print('ok')" 2>&1
    Row 'pythonnet (tray icon)' $(if ($t -match 'ok') { 'ok' } else { "FAILED: $t" }) ($t -match 'ok')
}
Write-Host "`nTake a screenshot of this window and share it." -ForegroundColor White
