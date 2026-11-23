# Prints the PC's Bluetooth state as JSON for the PerfLink "Bluetooth" page.
# Uses the Windows Runtime Bluetooth APIs from Windows PowerShell 5.1 (no extra packages):
#   radio on/off, and every paired device with its name, type, address, connected flag
#   and battery level (when the device reports one).
$ErrorActionPreference = 'SilentlyContinue'
Add-Type -AssemblyName System.Runtime.WindowsRuntime
$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
    $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
function Await($op, [Type]$type) {
    $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
    [void]$task.Wait(8000)
    $task.Result
}
[void][Windows.Devices.Enumeration.DeviceInformation, Windows.Devices.Enumeration, ContentType = WindowsRuntime]
[void][Windows.Devices.Enumeration.DeviceInformationCollection, Windows.Devices.Enumeration, ContentType = WindowsRuntime]
[void][Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime]

# --- radio (adapter) state
$radio = $null
$radios = Await ([Windows.Devices.Radios.Radio]::GetRadiosAsync()) ([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]])
foreach ($r in $radios) { if ($r.Kind -eq 'Bluetooth') { $radio = [string]$r.State; break } }

# --- paired devices (classic + Low Energy), with their connection state
$props = [string[]]@('System.Devices.Aep.IsConnected', 'System.Devices.Aep.DeviceAddress',
                     'System.Devices.Aep.Category', 'System.Devices.Aep.Bluetooth.Cod.Major')
$protocols = @{ 'classic' = '{e0cbf06c-cd8b-4647-bb8a-263b43f0f974}'; 'le' = '{bb7bb05e-5972-42b5-94fc-76eaa7084d49}' }
$devices = @()
foreach ($kind in $protocols.Keys) {
    $aqs = "System.Devices.Aep.ProtocolId:=`"$($protocols[$kind])`" AND System.Devices.Aep.IsPaired:=System.StructuredQueryType.Boolean#True"
    $found = Await ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync($aqs, $props,
                   [Windows.Devices.Enumeration.DeviceInformationKind]::AssociationEndpoint)) ([Windows.Devices.Enumeration.DeviceInformationCollection])
    foreach ($d in $found) {
        $addr = [string]$d.Properties['System.Devices.Aep.DeviceAddress']
        $cat = $d.Properties['System.Devices.Aep.Category']
        $devices += [pscustomobject]@{
            name      = $d.Name
            address   = $addr
            kind      = $kind
            connected = [bool]$d.Properties['System.Devices.Aep.IsConnected']
            category  = if ($cat) { ($cat | Select-Object -First 1) } else { $null }
            major     = $d.Properties['System.Devices.Aep.Bluetooth.Cod.Major']
            battery   = $null
        }
    }
}

# --- battery level, read from the matching Plug and Play device (only some devices report it)
if ($devices) {
    $pnp = Get-PnpDevice -Class Bluetooth -PresentOnly
    foreach ($dev in $devices) {
        $hex = ($dev.address -replace '[^0-9A-Fa-f]', '').ToUpper()
        if (-not $hex) { continue }
        foreach ($p in ($pnp | Where-Object { $_.InstanceId -like "*$hex*" })) {
            $b = (Get-PnpDeviceProperty -InstanceId $p.InstanceId -KeyName '{104EA319-6EE2-4701-BD47-8DDBF425BBE5} 2').Data
            if ($b -ne $null) { $dev.battery = [int]$b; break }
        }
    }
}

# merge classic + LE entries of the same device (dual-mode devices appear twice)
$merged = $devices | Group-Object { ($_.address -replace '[^0-9A-Fa-f]', '').ToUpper() + '|' + $_.name } | ForEach-Object {
    $g = $_.Group
    $first = $g[0]
    $first.connected = [bool]($g | Where-Object connected)
    $bat = ($g | Where-Object { $_.battery -ne $null } | Select-Object -First 1).battery
    if ($bat -ne $null) { $first.battery = $bat }
    $first
}
@{ radio = $radio; devices = @($merged) } | ConvertTo-Json -Depth 4 -Compress
