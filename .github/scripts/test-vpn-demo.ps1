# Only for a disposable administrator runner. Never run on a user's workstation.
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_OS -ne 'Windows') { throw 'Isolated Windows CI is required.' }
$runtime = Join-Path $PSScriptRoot '../../composeApp/build/native/vpn'
$helper = (Resolve-Path (Join-Path $runtime 'NuvioVpn.exe')).Path
$wg = (Resolve-Path (Join-Path $runtime 'wg.exe')).Path
function Request([string]$command) {
    $start = [Diagnostics.ProcessStartInfo]::new((Join-Path $env:ProgramFiles 'NuvioVpn/NuvioVpn.exe'), 'client')
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.UserName = $testUser.Name
    $start.Domain = $env:COMPUTERNAME
    $start.Password = $testPassword
    $start.LoadUserProfile = $true
    $start.WorkingDirectory = Join-Path $env:ProgramFiles 'NuvioVpn'
    $process = [Diagnostics.Process]::Start($start)
    try {
        $process.StandardInput.WriteLine($command)
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(50000)) { $process.Kill(); throw 'Broker request timed out.' }
        $reply = $process.StandardOutput.ReadToEnd().Trim()
        if ($process.ExitCode -ne 0) { throw "Broker returned: $reply" }
        return $reply
    } finally { $process.Dispose() }
}
function Require([bool]$condition, [string]$name) {
    if (-not $condition) { throw "Failed: $name" }
    Write-Output "PASS $name"
}
function Probe([Net.IPAddress]$source = $null) {
    $client = [Net.Sockets.TcpClient]::new([Net.Sockets.AddressFamily]::InterNetwork)
    try {
        if ($null -ne $source) { $client.Client.Bind([Net.IPEndPoint]::new($source, 0)) }
        $task = $client.ConnectAsync($probeAddress, 443)
        return $task.Wait(3000) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}
$probeAddress = [Net.Dns]::GetHostAddresses('github.com') | Where-Object AddressFamily -eq InterNetwork | Select-Object -First 1
$baseline = [Net.Sockets.TcpClient]::new()
try {
    if (-not $baseline.ConnectAsync($probeAddress, 443).Wait(5000)) { throw 'Baseline probe timed out.' }
    $physicalAddress = $baseline.Client.LocalEndPoint.Address.MapToIPv4()
} finally { $baseline.Dispose() }
Require (Probe $physicalAddress) 'physical IPv4 baseline'

# Protocol documented by WireGuard's example/example.c. Only public key is sent.
# The public demonstration service is used for handshake tests, never for user traffic.
$privateKey = (& $wg genkey).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
$publicKey = ($privateKey | & $wg pubkey).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Public key generation failed.' }
$endpoint = [Net.Dns]::GetHostAddresses('demo.wireguard.com') | Where-Object AddressFamily -eq InterNetwork | Select-Object -First 1
$socket = [Net.Sockets.TcpClient]::new()
try {
    $connect = $socket.ConnectAsync($endpoint, 42912)
    if (-not $connect.Wait(10000)) { throw 'Demo server unavailable; real handshake remains unverified.' }
    $stream = $socket.GetStream()
    $stream.ReadTimeout = 10000
    $stream.WriteTimeout = 10000
    $bytes = [Text.Encoding]::ASCII.GetBytes($publicKey + "`n")
    $stream.Write($bytes, 0, $bytes.Length)
    $buffer = [byte[]]::new(256)
    $length = $stream.Read($buffer, 0, $buffer.Length)
    $answer = [Text.Encoding]::ASCII.GetString($buffer, 0, $length).Trim().Split(':')
    if ($answer.Count -ne 4 -or $answer[0] -ne 'OK') { throw 'Demo response invalid.' }
} finally { $socket.Dispose() }
$profile = "[Interface]`nPrivateKey = $privateKey`nAddress = $($answer[3])/32`nDNS = 1.1.1.1`n[Peer]`nPublicKey = $($answer[1])`nAllowedIPs = 0.0.0.0/0`nEndpoint = $($endpoint):$($answer[2])`n"
$testPassword = ConvertTo-SecureString (([Guid]::NewGuid().ToString('N')) + 'Aa!7') -AsPlainText -Force
$testUser = New-LocalUser -Name ('NuvioVpn' + [Guid]::NewGuid().ToString('N').Substring(0,8)) -Password $testPassword
try {
    Add-LocalGroupMember -SID 'S-1-5-32-545' -Member $testUser
    & $helper install $testUser.SID.Value
    Require ($LASTEXITCODE -eq 0) 'broker installation'
    Require ((Request 'status') -eq "STATE`tOff`t0`t0") 'default off without profile'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($profile))
    Require ((Request "import`t$encoded") -eq "STATE`tOff`t1`t0") 'profile imported through owner pipe'
    $encrypted = [IO.File]::ReadAllBytes((Join-Path $env:ProgramFiles 'NuvioVpn/State/NuvioVpn.conf.dpapi'))
    Require (-not [Text.Encoding]::UTF8.GetString($encrypted).Contains($privateKey)) 'SYSTEM-encrypted profile at rest'
    Require ((Request 'arm') -eq "STATE`tBlocked`t1`t0") 'guard armed before tunnel'
    Require (-not (Probe $physicalAddress)) 'physical IPv4 blocked before tunnel'
    $null = Request 'connect'
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        $state = Request 'status'
        if ($state -eq "STATE`tConnected`t1`t1") { break }
        Start-Sleep -Seconds 2
    } while ([DateTime]::UtcNow -lt $deadline)
    Require ($state -eq "STATE`tConnected`t1`t1") 'real WireGuard peer handshake and verified filters'
    Require (-not (Probe $physicalAddress)) 'physical IPv4 bypass blocked with connected tunnel'
    Require ((Request 'hold') -eq "STATE`tBlocked`t1`t0") 'disconnect retains the guard'
    Require (-not (Probe $physicalAddress)) 'physical IPv4 remains blocked after disconnect'
    Restart-Service NuvioVpnControl
    Start-Sleep -Seconds 2
    Require ((Request 'status') -eq "STATE`tBlocked`t1`t0") 'service restart retains guard and encrypted profile'
    Require (-not (Probe $physicalAddress)) 'physical IPv4 remains blocked after service restart'
    Require ((Request 'off') -eq "STATE`tOff`t1`t0") 'explicit disable releases guard'
    Require (Probe $physicalAddress) 'explicit disable restores physical IPv4'
    Require ((Request 'delete') -eq "STATE`tOff`t0`t0") 'profile deletion'
} finally {
    & $helper uninstall
    $privateKey = $null; $profile = $null; $encoded = $null
    $cleanupExit = $LASTEXITCODE
    Remove-LocalUser -SID $testUser.SID
    $testPassword.Dispose()
    if ($cleanupExit -ne 0) { throw 'Runner VPN cleanup failed.' }
}
Write-Output 'Demo integration passed. Provider throughput, external-IP and full DNS/IPv6 leak tests remain release gates.'
