# PerfLink auto-update for the PC.
#
# Run by the "PerfLink Update" scheduled task, which the installer sets up to run as administrator
# without a prompt: at sign-in, every 2 hours, and whenever PerfLink itself notices a newer version
# on GitHub. It does nothing unless GitHub (main branch) has a newer version than this install; then
# it runs the normal installer quietly (-Auto), which stops PerfLink, replaces its files, keeps the
# settings, updates the phone app if a phone is plugged in, and starts PerfLink again.
#
# Turn it off with "auto_update": false in agent\config.json. Log: %LOCALAPPDATA%\PerfLink-update.log
param(
    [string]$Repo = 'NirabhraKoley/PerfLink',
    [string]$Branch = 'main',
    [switch]$CheckOnly       # say what would happen, change nothing (testing)
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$App = Split-Path (Split-Path $PSCommandPath)          # ...\PerfLink (this file is in agent\)
Set-Location $env:TEMP                                  # never "inside" the folder being replaced
$log = Join-Path (Split-Path $App) 'PerfLink-update.log'
function Log($t) {
    if ($CheckOnly) { Write-Host $t; return }
    try {
        if ((Test-Path $log) -and (Get-Item $log).Length -gt 512KB) { Move-Item $log "$log.old" -Force }
        Add-Content $log "$(Get-Date -Format s)  $t" -Encoding utf8
    } catch { }
}
function Get-Version([string]$text) {
    $m = [regex]::Match($text, '(?m)^VERSION\s*=\s*"([^"]+)"')
    if ($m.Success) { return $m.Groups[1].Value }
}

try {
    # only ever an installed copy: never a source checkout or some other folder
    if (Test-Path (Join-Path $App '.git')) { Log "skipped: $App is a source checkout"; return }
    if (-not (Test-Path (Join-Path $App '.perflink-install'))) { Log "skipped: $App is not a PerfLink install"; return }
    $cfg = Join-Path $App 'agent\config.json'
    if (Test-Path $cfg) {
        try { if ((Get-Content $cfg -Raw | ConvertFrom-Json).auto_update -eq $false) { Log 'skipped: auto_update is off in config.json'; return } } catch { }
    }
    $cur = Get-Version (Get-Content (Join-Path $App 'agent\perflink_agent.py') -Raw -Encoding utf8)
    $url = "https://raw.githubusercontent.com/$Repo/$Branch/agent/perflink_agent.py?t=$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
    $new = Get-Version (Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 30).Content
    if (-not $new) { Log "couldn't read the version on GitHub"; return }
    if ([version]$new -le [version]$cur) {
        if ($CheckOnly) { Log "up to date ($cur; GitHub has $new)" }
        return                                          # up to date: no log line every 6 hours
    }
    Log "updating PerfLink $cur -> $new"
    if ($CheckOnly) { return }
    $inst = Join-Path $env:TEMP 'perflink-autoupdate.ps1'
    Invoke-WebRequest "https://raw.githubusercontent.com/$Repo/$Branch/install.ps1" -OutFile $inst -UseBasicParsing -TimeoutSec 60
    # same process (&), so the installer's "stop PerfLink's helpers" step never stops this script.
    # Its output goes into the log as UTF-8 text (">>" would write UTF-16 into this UTF-8 log).
    & $inst -Elevated -Auto -Repo $Repo -Branch $Branch -InstallDir $App *>&1 | ForEach-Object { "$_" } |
        Out-File -FilePath $log -Append -Encoding utf8
    $now = Get-Version (Get-Content (Join-Path $App 'agent\perflink_agent.py') -Raw -Encoding utf8)
    Log $(if ($now -eq $new) { "updated to $now" } else { "update didn't finish (still $now) - see above" })
    # safety net: PerfLink must be running afterwards, whatever happened above
    Start-Sleep 5
    if (-not (Get-NetTCPConnection -LocalPort 47800 -State Listen -ErrorAction SilentlyContinue)) {
        try { Start-ScheduledTask -TaskName PerfLink -ErrorAction Stop; Log 'PerfLink was not running: started it again' }
        catch { Log "PerfLink is not running and couldn't be started: $($_.Exception.Message)" }
    }
} catch {
    Log "failed: $($_.Exception.Message)"
}
