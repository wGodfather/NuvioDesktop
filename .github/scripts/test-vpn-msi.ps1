param([Parameter(Mandatory)][string]$Msi)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_OS -ne 'Windows' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted') {
    throw 'MSI/network tests require a disposable GitHub-hosted Windows runner.'
}
$current = (Resolve-Path -LiteralPath $Msi).Path
$work = Join-Path $env:RUNNER_TEMP 'vpn-msi-lifecycle'
New-Item -ItemType Directory -Path $work -Force | Out-Null
& gh run download 37086247592 --repo wGodfather/NuvioDesktop --name Nuvio-Windows-0.1.30-alpha --dir (Join-Path $work 'previous')
if ($LASTEXITCODE -ne 0) { throw 'Previous experimental MSI unavailable; upgrade gate cannot run.' }
$previous = (Get-ChildItem (Join-Path $work 'previous') -Filter '*.msi' -Recurse | Select-Object -First 1).FullName
if (-not $previous -or (Get-FileHash $previous -Algorithm SHA256).Hash -ne '24B1D43143945C12286C24C7497D9D9C74339AAEDEB9D52AAC4CA58FB268D819') { throw 'Previous MSI checksum mismatch.' }
$oldRuntime = Join-Path $work 'previous-runtime'
& (Join-Path $PSScriptRoot 'extract-msi-vpn.ps1') -Msi $previous -OutputDirectory $oldRuntime
$helper = Join-Path $oldRuntime 'NuvioVpn.exe'
$cleanupHelper = (Resolve-Path 'composeApp/build/native/vpn/NuvioVpn.exe').Path
function Require([bool]$Result, [string]$Name) { if (-not $Result) { throw "Failed: $Name" }; Write-Output "PASS $Name" }
function Install([string]$Package, [string]$Mode, [string]$Name, [bool]$ExpectFailure = $false) {
    $log = Join-Path $work ($Name + '.log')
    $arguments = @($Mode, ('"' + $Package + '"'), '/qn', '/norestart', '/l*v', ('"' + $log + '"'))
    # Repair normally uses the cached database, which has no injected action.
    if ($ExpectFailure) { $arguments += @('REINSTALL=ALL', 'REINSTALLMODE=vomus') }
    $child = Start-Process msiexec.exe -ArgumentList $arguments -WindowStyle Hidden -Wait -PassThru
    if ($ExpectFailure) {
        Write-Output "Injected MSI exit code: $($child.ExitCode)" | Out-Host
        Require ($child.ExitCode -eq 1603) 'injected MSI failure' | Out-Host
        return $log
    }
    if ($child.ExitCode -eq 3010) { throw 'Reboot required; lifecycle gate is incomplete.' }
    Require ($child.ExitCode -eq 0) $Name
}
function Request([string]$Command) {
    $reply = $Command | & (Join-Path $env:ProgramFiles 'NuvioVpn/NuvioVpn.exe') client
    if ($LASTEXITCODE -ne 0) { throw 'Broker request failed.' }; return ($reply -join "`n").Trim()
}
$address = [Net.Dns]::GetHostAddresses('github.com') | Where-Object AddressFamily -eq InterNetwork | Select-Object -First 1
function Probe {
    $socket = [Net.Sockets.TcpClient]::new()
    try { return $socket.ConnectAsync($address, 443).Wait(3000) -and $socket.Connected }
    catch { return $false } finally { $socket.Dispose() }
}
$sentinel = Join-Path $env:APPDATA 'Nuvio/vpn-msi-user-data-sentinel.txt'
New-Item -ItemType Directory -Path (Split-Path $sentinel) -Force | Out-Null
[IO.File]::WriteAllText($sentinel, 'existing-library-download-preferences')
$userHash = (Get-FileHash $sentinel -Algorithm SHA256).Hash
try {
    Require (Probe) 'network baseline'
    Install $previous '/i' 'install-previous'
    & $helper install ([Security.Principal.WindowsIdentity]::GetCurrent().User.Value)
    Require ($LASTEXITCODE -eq 0) 'install previous packaged broker'
    $key = [Convert]::ToBase64String([byte[]](1..32))
    $profile = "[Interface]`nPrivateKey = $key`nAddress = 10.90.0.2/32`nDNS = 1.1.1.1`n[Peer]`nPublicKey = $key`nAllowedIPs = 0.0.0.0/0, ::/0`nEndpoint = 192.0.2.1:51820`n"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($profile))
    $null = Request "import`t$encoded"
    $encryptedFile = Join-Path $env:ProgramFiles 'NuvioVpn/State/NuvioVpn.conf.dpapi'
    $encryptedHash = (Get-FileHash $encryptedFile -Algorithm SHA256).Hash
    $null = Request 'arm'; Require (-not (Probe)) 'guard before upgrade'
    Install $current '/i' 'upgrade-to-current'
    Require ((Request 'status') -eq "STATE`tBlocked`t1`t0") 'upgrade resumes guarded broker'
    Require (-not (Probe)) 'upgrade keeps network protection'
    Require ((Get-FileHash $encryptedFile -Algorithm SHA256).Hash -eq $encryptedHash) 'upgrade preserves encrypted profile'
    Require ((Get-FileHash $sentinel -Algorithm SHA256).Hash -eq $userHash) 'upgrade preserves app data'
    Install $current '/fa' 'repair-current'
    Require ((Request 'status') -eq "STATE`tBlocked`t1`t0") 'repair resumes guarded broker'
    Require (-not (Probe)) 'repair keeps protection'
    $failedMsi = Join-Path $work 'forced-rollback.msi'; Copy-Item -LiteralPath $current -Destination $failedMsi
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase($failedMsi, 1)
    foreach ($sql in @(
        "INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES ('NuvioVpnForcedFailure', 3074, 'NuvioVpnMaintenance', 'installer-test-failure')",
        "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Condition``, ``Sequence``) VALUES ('NuvioVpnForcedFailure', '1', 6504)"
    )) { $view = $database.OpenView($sql); $view.Execute(); $view.Close() }
    $database.Commit(); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null
    $failureLog = Install $failedMsi '/i' 'forced-rollback' $true
    $failureText = Get-Content -LiteralPath $failureLog -Raw
    Require ($failureText -match 'NuvioVpnForcedFailure returned actual error code' -and $failureText -match 'Rollback: NuvioVpnRollback') 'deferred failure invokes rollback'
    Require ((Request 'status') -eq "STATE`tBlocked`t1`t0") 'rollback restores broker and guard'
    Require ((Get-FileHash $encryptedFile -Algorithm SHA256).Hash -eq $encryptedHash) 'rollback preserves encrypted profile'
    Require (-not (Probe)) 'rollback keeps protection'
    Install $current '/x' 'uninstall-current'
    Require (Probe) 'uninstall restores networking'
    Require (-not (Get-Service NuvioVpnControl -ErrorAction SilentlyContinue)) 'uninstall removes owned broker'
    Require (-not (Get-Service 'WireGuardTunnel$NuvioVpn' -ErrorAction SilentlyContinue)) 'uninstall removes owned tunnel'
    Require (-not (Test-Path -LiteralPath $encryptedFile)) 'uninstall removes saved VPN profile'
    Require ((Get-FileHash $sentinel -Algorithm SHA256).Hash -eq $userHash) 'uninstall preserves app data'
} finally {
    & $cleanupHelper uninstall
    if ($LASTEXITCODE -ne 0) { throw 'Runner recovery failed.' }
    Clear-DnsClientCache
    $profile = $null; $encoded = $null; $key = $null
}
Write-Output 'MSI lifecycle passed. Signing, reboot/sleep and device gates remain separate.'
