# PerfLink agent setup (Windows)
#   Installs the Python packages and downloads the two native helpers into agent\bin:
#     * PresentMon (Intel, MIT license)               -> FPS / frame time
#     * LibreHardwareMonitorLib (MPL-2.0) + its deps  -> GPU/CPU temperature, power, clocks
#   Run from the repo root:   powershell -ExecutionPolicy Bypass -File agent\setup.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$bin  = Join-Path $here 'bin'
$tmp  = Join-Path $env:TEMP 'perflink-setup'
New-Item -ItemType Directory -Force $bin, $tmp | Out-Null

Write-Host '== Python packages ==' -ForegroundColor Cyan
python -m pip install --upgrade -r (Join-Path $here 'requirements.txt')

Write-Host '== PresentMon 2.6.0 ==' -ForegroundColor Cyan
$pm = Join-Path $bin 'PresentMon.exe'
if (-not (Test-Path $pm)) {
    Invoke-WebRequest 'https://github.com/GameTechDev/PresentMon/releases/download/v2.6.0/PresentMon-2.6.0-x64.exe' -OutFile $pm -UseBasicParsing
}

Write-Host '== LibreHardwareMonitorLib 0.9.6 and dependencies (NuGet) ==' -ForegroundColor Cyan
# package id -> version (the .NET Framework builds are loaded through pythonnet)
$pkgs = [ordered]@{
    'librehardwaremonitorlib'                = '0.9.6'
    'system.memory'                          = '4.6.3'
    'system.buffers'                         = '4.6.1'
    'system.numerics.vectors'                = '4.6.1'
    'system.runtime.compilerservices.unsafe' = '6.1.2'
    'system.threading.accesscontrol'         = '10.0.3'
    'hidsharp'                               = '2.6.4'
    'diskinfotoolkit'                        = '1.1.2'
    'ramspdtoolkit-ndd'                      = '1.4.2'
}
foreach ($id in $pkgs.Keys) {
    $v = $pkgs[$id]
    $zip = Join-Path $tmp "$id.$v.zip"
    $dir = Join-Path $tmp "$id.$v"
    Invoke-WebRequest "https://api.nuget.org/v3-flatcontainer/$id/$v/$id.$v.nupkg" -OutFile $zip -UseBasicParsing
    Expand-Archive $zip $dir -Force
    if ($id -eq 'librehardwaremonitorlib') {
        $lib = Join-Path $dir 'runtimes\win-x64\lib\net472'
    } else {
        $lib = Get-ChildItem (Join-Path $dir 'lib') -Directory | Where-Object { $_.Name -match '^net4[6-8]' } |
               Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
        if (-not $lib) { $lib = Join-Path $dir 'lib\netstandard2.0' }
    }
    Get-ChildItem $lib -Filter *.dll | Copy-Item -Destination $bin -Force
    Write-Host "  $id $v"
}

Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
Write-Host ''
Write-Host 'Done. Start the agent with start_agent.bat (or start_agent_admin.bat for CPU temperature).' -ForegroundColor Green
