# PerfLink diagnostics: prints what's installed and running, to troubleshoot problems.
#   irm https://raw.githubusercontent.com/NirabhraKoley/PerfLink/main/diagnose.ps1 | iex
# Only reads information; changes nothing. Nothing is sent anywhere.
$ErrorActionPreference = 'SilentlyContinue'
function H($t) { Write-Host "`n== $t" -ForegroundColor Cyan }
function Row($k, $v, [bool]$ok = $true) { Write-Host ("  {0,-26} " -f $k) -NoNewline; Write-Host $v -ForegroundColor $(if ($ok) { 'Green' } else { 'Yellow' }) }

# Windows "block" rules for these programs (inbound, enabled), e.g. left by a cancelled "Allow access?"
# pop-up for Python. A block rule beats PerfLink's allow rules on that network type, so phones can't
# connect over Wi-Fi (USB still works). Read with netsh, which works with or without admin rights.
function Get-BlockRules([string[]]$programs) {
    $want = @($programs | Where-Object { $_ } | ForEach-Object { $_.ToLower() })
    $rules = @(); $cur = @{}
    foreach ($l in (netsh advfirewall firewall show rule name=all dir=in verbose)) {
        if ($l -match '^Rule Name:\s+(.*)') { $rules += , $cur; $cur = @{ Name = $matches[1].Trim() } }
        elseif ($l -match '^(Enabled|Action|Profiles|Program):\s+(.*)') { $cur[$matches[1]] = $matches[2].Trim() }
    }
    $rules += , $cur
    $rules | Where-Object { $_.Action -eq 'Block' -and $_.Enabled -eq 'Yes' -and $want -contains "$($_.Program)".ToLower() }
}
# the networks this PC is on whose type (Public / Private / Domain) those block rules cover
function Get-BlockedNetworks($rules) {
    $profiles = (@($rules) | ForEach-Object { $_.Profiles }) -join ','
    if (-not $profiles) { return }
    @(Get-NetConnectionProfile -ErrorAction SilentlyContinue) | Where-Object {
        $cat = if ("$($_.NetworkCategory)" -like 'Domain*') { 'Domain' } else { "$($_.NetworkCategory)" }
        $profiles -match $cat -or $profiles -match 'Any'
    }
}

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
$procs = Get-CimInstance Win32_Process | Where-Object { $_.Name -like 'python*.exe' -and $_.CommandLine -like '*perflink_agent.py*' }
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

H 'Bluetooth'
$bt = @(Get-PnpDevice -Class Bluetooth)
foreach ($d in ($bt | Where-Object { $_.InstanceId -notmatch '^(BTHENUM|BTHLE)\\' -and $_.InstanceId -notmatch '^(BTH|SWD)\\' })) {
    Row 'Adapter' "$($d.FriendlyName) [$($d.Status), present=$($d.Present)]" ($d.Status -eq 'OK')
}
foreach ($d in ($bt | Where-Object { $_.InstanceId -match '^(BTHENUM|BTHLE)\\DEV_' })) {
    $c = (Get-PnpDeviceProperty -InstanceId $d.InstanceId -KeyName '{83DA6326-97A6-4088-9453-A1923F573B29} 15').Data
    Row 'Paired device' "$($d.FriendlyName) [$(($d.InstanceId -split '\\')[0])] connected=$c status=$($d.Status) present=$($d.Present)" ([bool]$c)
}
$svc = $bt | Where-Object { $_.InstanceId -match '^BTHENUM\\\{' -and $_.Present }
Row 'Active Bluetooth services' (($svc | ForEach-Object { $_.FriendlyName } | Select-Object -Unique) -join ', ')
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [void][Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime]
    $m = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
    $t = $m.MakeGenericMethod([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]]).Invoke($null, @([Windows.Devices.Radios.Radio]::GetRadiosAsync()))
    if ($t.Wait(4000)) { Row 'Radios' (($t.Result | ForEach-Object { "$($_.Kind)=$($_.State)" }) -join ', ') } else { Row 'Radios' 'no answer in 4 s' $false }
} catch { Row 'Radios' "error: $_" $false }

H 'Network'
$fwOk = [bool]((netsh advfirewall firewall show rule name="PerfLink TCP") -match 'Enabled:\s+Yes')  # netsh: works with or without admin
Row 'Firewall rules' $(if ($fwOk) { 'present' } else { 'missing' }) $fwOk
$ip = (Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.PrefixOrigin -in 'Dhcp', 'Manual' -and $_.IPAddress -notlike '169.*' } | Select-Object -First 1).IPAddress
Row 'PC IP' $ip
foreach ($n in @(Get-NetConnectionProfile)) { Row "Network type ($($n.InterfaceAlias))" "$($n.NetworkCategory)" }
# the Python PerfLink runs with (start-with-Windows task), and its console twin
$pyRun = if ($task) { $task.Actions[0].Execute } else { Get-Content (Join-Path $App 'agent\pythonw.txt') -ErrorAction SilentlyContinue }
$pyProgs = @($pyRun, $(if ($pyRun) { Join-Path (Split-Path $pyRun) 'python.exe' })) | Where-Object { $_ }
$blockedRules = @(Get-BlockRules $pyProgs)
$blockedNets = @(Get-BlockedNetworks $blockedRules)
if ($blockedRules.Count -and $blockedNets.Count) {
    Row 'Wi-Fi phones' "BLOCKED: Windows blocks Python on $($blockedNets[0].NetworkCategory) networks and this PC is on one" $false
    Row '  fix (home network)' "Settings > Network & internet > $($blockedNets[0].InterfaceAlias) > Network profile type > Private" $false
} elseif ($blockedRules.Count) {
    Row 'Wi-Fi phones' "ok (Python is only blocked on $((@($blockedRules | ForEach-Object { $_.Profiles }) | Select-Object -Unique) -join '/') networks)"
} else {
    Row 'Wi-Fi phones' 'ok (nothing blocks PerfLink)'
}

H 'Python'
foreach ($c in 'py', 'python') { $cmd = Get-Command $c; if ($cmd -and $cmd.Source -notlike '*WindowsApps*') { $py = $c; break } }
Row 'Python' $(if ($py) { (& $py --version) } else { 'not found' }) ([bool]$py)
if ($py) {
    $t = & $py -c "import clr, System; clr.AddReference('System.Windows.Forms'); print('ok')" 2>&1
    Row 'pythonnet (tray icon)' $(if ($t -match 'ok') { 'ok' } else { "FAILED: $t" }) ($t -match 'ok')
}
Write-Host "`nTake a screenshot of this window and share it." -ForegroundColor White
