# Prints the PC's Bluetooth state as JSON for the PerfLink "Bluetooth" page.
# Windows PowerShell 5.1, no extra packages. Paired devices come from Plug and Play, which is fast
# and works on every Windows 10/11 PC: name, address, connected flag, device class, battery.
# The radio on/off state comes from the Windows Runtime with a short time limit, so a
# slow or blocked call can never stall the page.
$ErrorActionPreference = 'SilentlyContinue'

$KEY_CONNECTED = '{83DA6326-97A6-4088-9453-A1923F573B29} 15'   # DEVPKEY_Device_IsConnected
$KEY_COD       = '{2BD67D8B-8BEB-48D5-87E0-6CDA3428040A} 10'   # DEVPKEY_Bluetooth_ClassOfDevice
$KEY_BATTERY   = '{104EA319-6EE2-4701-BD47-8DDBF425BBE5} 2'    # battery level (hands-free devices)

# --- paired devices: one PnP node per device (BTHENUM\DEV_<address> classic, BTHLE\DEV_<address> LE)
$all = @(Get-PnpDevice -Class Bluetooth)
$byAddr = [ordered]@{}
foreach ($d in $all) {
    if ($d.InstanceId -notmatch '^(BTHENUM|BTHLE)\\DEV_([0-9A-F]{12})') { continue }
    $addr = $Matches[2]
    $kind = if ($Matches[1] -eq 'BTHLE') { 'le' } else { 'classic' }
    $conn = [bool](Get-PnpDeviceProperty -InstanceId $d.InstanceId -KeyName $KEY_CONNECTED).Data
    $cod = (Get-PnpDeviceProperty -InstanceId $d.InstanceId -KeyName $KEY_COD).Data
    $major = if ($cod -ne $null) { ([int]$cod -shr 8) -band 0x1F } else { $null }
    if ($byAddr.Contains($addr)) {            # dual-mode devices appear as classic and LE
        $e = $byAddr[$addr]
        $e.connected = $e.connected -or $conn
        if ($e.major -eq $null) { $e.major = $major }
        continue
    }
    $byAddr[$addr] = [pscustomobject]@{
        name = $d.FriendlyName; address = $addr; kind = $kind; connected = $conn
        category = $null; major = $major; battery = $null
    }
}

# --- battery: reported on the device's hands-free / service nodes, whose ids contain the address
foreach ($dev in $byAddr.Values) {
    foreach ($p in ($all | Where-Object { $_.Present -and $_.InstanceId -like "*$($dev.address)*" })) {
        $b = (Get-PnpDeviceProperty -InstanceId $p.InstanceId -KeyName $KEY_BATTERY).Data
        if ($b -ne $null) { $dev.battery = [int]$b; break }
    }
}

# --- radio state (Windows Runtime, limited to 4 seconds)
$radio = $null
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [void][Windows.Devices.Radios.Radio, Windows.System.Devices, ContentType = WindowsRuntime]
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
    $task = $asTask.MakeGenericMethod([System.Collections.Generic.IReadOnlyList[Windows.Devices.Radios.Radio]]).Invoke(
        $null, @([Windows.Devices.Radios.Radio]::GetRadiosAsync()))
    if ($task.Wait(4000)) {
        foreach ($r in $task.Result) { if ([string]$r.Kind -eq 'Bluetooth') { $radio = [string]$r.State; break } }
    }
} catch { }
# no adapter at all -> off; a connected device proves it's on, whatever the radio API said
$adapter = $all | Where-Object { $_.Present -and $_.InstanceId -notmatch '^(BTHENUM|BTHLE|BTH|SWD)\\' -and $_.Status -eq 'OK' }
if (-not $adapter) { $radio = 'Off' }
if ($byAddr.Values | Where-Object connected) { $radio = 'On' }

@{ radio = $radio; devices = @($byAddr.Values) } | ConvertTo-Json -Depth 4 -Compress
