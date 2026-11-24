# Prints the PC's location from Windows' location service as JSON, for PerfLink's weather.
#   {"ok": true, "lat": 22.57, "lon": 88.36, "accuracy_m": 120}
#   {"ok": false, "reason": "..."}   (Location turned off, desktop apps not allowed, no fix in time)
# Windows uses Wi-Fi networks nearby (or GPS / IP) to find the PC. Nothing is sent anywhere by this script.
$ErrorActionPreference = 'Stop'
function Out-Json($o) { $o | ConvertTo-Json -Compress; exit 0 }
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [void][Windows.Devices.Geolocation.Geolocator, Windows.Devices.Geolocation, ContentType = WindowsRuntime]
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
    $access = $asTask.MakeGenericMethod([Windows.Devices.Geolocation.GeolocationAccessStatus]).Invoke(
        $null, @([Windows.Devices.Geolocation.Geolocator]::RequestAccessAsync()))
    if (-not $access.Wait(5000)) { Out-Json @{ ok = $false; reason = 'no answer from the location service' } }
    if ([string]$access.Result -ne 'Allowed') {
        Out-Json @{ ok = $false; reason = "Windows location is $([string]$access.Result) (Settings > Privacy > Location)" }
    }
    $g = New-Object Windows.Devices.Geolocation.Geolocator
    $g.DesiredAccuracy = 'Default'
    $pos = $asTask.MakeGenericMethod([Windows.Devices.Geolocation.Geoposition]).Invoke(
        $null, @($g.GetGeopositionAsync([TimeSpan]::FromMinutes(30), [TimeSpan]::FromSeconds(10))))
    if (-not $pos.Wait(12000)) { Out-Json @{ ok = $false; reason = 'no location fix in time' } }
    $c = $pos.Result.Coordinate
    Out-Json @{ ok = $true; lat = [math]::Round($c.Point.Position.Latitude, 4); lon = [math]::Round($c.Point.Position.Longitude, 4)
                accuracy_m = [int]$c.Accuracy; source = [string]$c.PositionSource }
} catch {
    Out-Json @{ ok = $false; reason = ($_.Exception.Message -split "`n")[0] }
}
