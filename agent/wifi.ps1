# Wi-Fi details for PerfLink when `netsh wlan` gives nothing (Windows 11 24H2 and later only
# answers it when Location is turned on). Uses the network adapter list and the Windows Runtime
# connection info, which need no Location permission: Wi-Fi adapters, network name, signal,
# link speed, security. Prints JSON.
$ErrorActionPreference = 'SilentlyContinue'
$out = @()
$wifi = @(Get-NetAdapter -Physical | Where-Object { $_.PhysicalMediaType -match '802\.11' -or $_.NdisPhysicalMedium -eq 9 })
if ($wifi) {
    $profiles = @()
    try {
        [void][Windows.Networking.Connectivity.NetworkInformation, Windows.Networking.Connectivity, ContentType = WindowsRuntime]
        $profiles = @([Windows.Networking.Connectivity.NetworkInformation]::GetConnectionProfiles() | Where-Object { $_.IsWlanConnectionProfile })
    } catch { }
    foreach ($a in $wifi) {
        $up = $a.Status -eq 'Up'
        $ssid = $null; $bars = $null; $sec = $null
        if ($up) {
            $ssid = (Get-NetConnectionProfile -InterfaceIndex $a.ifIndex | Select-Object -First 1).Name
            $p = $profiles | Where-Object { $_.NetworkAdapter.NetworkAdapterId -eq [guid]$a.InterfaceGuid } | Select-Object -First 1
            if (-not $p) { $p = $profiles | Where-Object { $_.GetNetworkConnectivityLevel() -ne 'None' } | Select-Object -First 1 }
            if ($p) {
                try { $s = $p.WlanConnectionProfileDetails.GetConnectedSsid(); if ($s) { $ssid = $s } } catch { }
                try { $bars = $p.GetSignalBars() } catch { }
                try {
                    $auth = [string]$p.NetworkSecuritySettings.NetworkAuthenticationType
                    $enc = [string]$p.NetworkSecuritySettings.NetworkEncryptionType
                    $sec = (@($auth, $enc) | Where-Object { $_ -and $_ -ne 'None' -and $_ -ne 'Unknown' }) -join ' / '
                } catch { }
            }
        }
        $out += [pscustomobject]@{
            interface = $a.Name; adapter = $a.InterfaceDescription; connected = $up; state = [string]$a.Status
            ssid = $ssid; bars = $bars; security = $sec
            rx_mbps = if ($up -and $a.ReceiveLinkSpeed) { [math]::Round($a.ReceiveLinkSpeed / 1e6) } else { $null }
            tx_mbps = if ($up -and $a.TransmitLinkSpeed) { [math]::Round($a.TransmitLinkSpeed / 1e6) } else { $null }
        }
    }
}
ConvertTo-Json -InputObject @($out) -Depth 3 -Compress
