# Real WireGuardNT/WFP test, exclusively on a disposable GitHub-hosted runner.
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_OS -ne 'Windows' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted') { throw 'Disposable Windows CI is required.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$runtime = Join-Path $root 'composeApp/build/native/vpn'
$helper = Join-Path $runtime 'NuvioVpn.exe'
$wg = Join-Path $runtime 'wg.exe'
$qa = Join-Path $root 'build/vpn-peer-qa'
New-Item -ItemType Directory -Force $qa | Out-Null
function Require([bool]$condition, [string]$name) { if (-not $condition) { throw "Failed: $name" }; Write-Output "PASS $name" }
function Request([string]$command) {
    $start = [Diagnostics.ProcessStartInfo]::new((Join-Path $env:ProgramFiles 'NuvioVpn/NuvioVpn.exe'), 'client')
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $start.UserName = $testUser.Name; $start.Domain = $env:COMPUTERNAME; $start.Password = $testPassword
    $start.LoadUserProfile = $true; $start.WorkingDirectory = Join-Path $env:ProgramFiles 'NuvioVpn'
    $process = [Diagnostics.Process]::Start($start)
    try {
        $process.StandardInput.WriteLine($command); $process.StandardInput.Close()
        if (-not $process.WaitForExit(50000)) { $process.Kill(); throw 'Broker request timed out.' }
        $reply = $process.StandardOutput.ReadToEnd().Trim()
        if ($process.ExitCode -ne 0) { throw "Broker returned: $reply" }
        return $reply
    } finally { $process.Dispose() }
}
function ProbePhysical {
    $client = [Net.Sockets.TcpClient]::new([Net.Sockets.AddressFamily]::InterNetwork)
    try {
        $client.Client.Bind([Net.IPEndPoint]::new($physicalAddress, 0))
        return $client.ConnectAsync($probeAddress, 443).Wait(3000) -and $client.Connected
    } catch { return $false } finally { $client.Dispose() }
}
function Http([string]$url) {
    $handler = [Net.Http.HttpClientHandler]::new(); $handler.UseProxy = $false
    $client = [Net.Http.HttpClient]::new($handler); $client.Timeout = [TimeSpan]::FromSeconds(8)
    try { return $client.GetStringAsync($url).GetAwaiter().GetResult() } finally { $client.Dispose(); $handler.Dispose() }
}
function TestNativeTorrentFixture($fixture) {
    $binary = Join-Path $root 'composeApp/src/desktopMain/resources/torrserver/windows-amd64/TorrServer.exe'
    $pointer = (& git -C $root show 'HEAD:composeApp/src/desktopMain/resources/torrserver/windows-amd64/TorrServer.exe') -join "`n"
    if ($pointer -notmatch 'oid sha256:([a-f0-9]{64})' -or (Get-FileHash -LiteralPath $binary -Algorithm SHA256).Hash -ne $Matches[1]) {
        throw 'Native torrent binary does not match its committed LFS object.'
    }
    $config = Join-Path $qa 'torrserver'
    New-Item -ItemType Directory -Force $config | Out-Null
    $motor = Start-Process -FilePath $binary -ArgumentList @('--port', '18091', '--ip', '127.0.0.1', '--path', ('"' + $config + '"')) -WorkingDirectory $config -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $qa 'native-torrent.log') -RedirectStandardError (Join-Path $qa 'native-torrent-error.log')
    try {
        $started = [DateTime]::UtcNow
        $deadline = $started.AddSeconds(60)
        do {
            try { $null = Http 'http://127.0.0.1:18091/echo'; $ready = $true } catch { $ready = $false }
            if ($ready) { break }; Start-Sleep -Milliseconds 200
        } while ([DateTime]::UtcNow -lt $deadline)
        Require $ready 'native torrent engine loopback control starts under VPN'
        Write-Output ('Native cold process startup: {0:N2} seconds (protocol test; UI startup acceptance is separate).' -f ([DateTime]::UtcNow - $started).TotalSeconds)
        $added = Invoke-RestMethod 'http://127.0.0.1:18091/torrents' -Method Post -ContentType 'application/json' -Body (@{ action='add'; link=$fixture.magnet; save_to_db=$false } | ConvertTo-Json -Compress)
        Require ($added.hash -eq $fixture.info_hash) 'native torrent metadata identity'
        $handler = [Net.Http.HttpClientHandler]::new(); $handler.UseProxy = $false
        $client = [Net.Http.HttpClient]::new($handler); $client.Timeout = [TimeSpan]::FromSeconds(120)
        try {
            $url = "http://127.0.0.1:18091/stream?link=$($added.hash)&index=1&play"
            # Desktop playback and HTTP download consume the same native motor.
            $tasks = @($client.GetByteArrayAsync($url), $client.GetByteArrayAsync($url))
            foreach ($task in $tasks) {
                $content = $task.GetAwaiter().GetResult()
                $sha = [Security.Cryptography.SHA256]::Create()
                try { $digest = [BitConverter]::ToString($sha.ComputeHash($content)).Replace('-', '').ToLowerInvariant() } finally { $sha.Dispose() }
                Require ($content.Length -eq $fixture.bytes -and $digest -eq $fixture.sha256) 'concurrent native stream/download fixture SHA-256'
            }
        } finally { $client.Dispose(); $handler.Dispose() }
        $metrics = (Http 'http://127.0.0.1:8765/metrics') | ConvertFrom-Json
        Require ($metrics.transfers -ge 2) 'native tracker and peer traverse the encrypted netstack'
    } finally {
        if (-not $motor.HasExited) { $motor.Kill(); $motor.WaitForExit() }
        $motor.Dispose()
    }
}
function AwaitConnected {
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do { $state = Request 'status'; if ($state -eq "STATE`tConnected`t1`t1") { return }; Start-Sleep -Milliseconds 500 } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Controlled peer handshake/filter proof timed out.'
}
function AwaitHeldBroker {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        try {
            $state = Request 'status'
            if ($state -ne "STATE`tBlocked`t1`t0") { throw 'Restart lost the held policy.' }
            return
        } catch {
            if ($_.Exception.Message -notmatch 'SETUP_REQUIRED') { throw }
            if (ProbePhysical) { throw 'Physical network opened while waiting for broker IPC.' }
        }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Restarted broker IPC readiness timed out.'
}
$probeAddress = [Net.Dns]::GetHostAddresses('github.com') | Where-Object AddressFamily -eq InterNetwork | Select-Object -First 1
$baseline = [Net.Sockets.TcpClient]::new()
try {
    if (-not $baseline.ConnectAsync($probeAddress, 443).Wait(5000)) { throw 'Baseline timed out.' }
    $physicalAddress = $baseline.Client.LocalEndPoint.Address.MapToIPv4()
} finally { $baseline.Dispose() }
Require (ProbePhysical) 'physical network baseline'
& go -C (Join-Path $root 'tools/vpn-peer') test -v .
if ($LASTEXITCODE -ne 0) { throw 'Peer self-test failed.' }
& go -C (Join-Path $root 'tools/vpn-peer') build -o (Join-Path $qa 'vpn-peer.exe') .
if ($LASTEXITCODE -ne 0) { throw 'Peer build failed.' }
$testPassword = ConvertTo-SecureString (([Guid]::NewGuid().ToString('N')) + 'Aa!7') -AsPlainText -Force
$testUser = $null
$peerProcess = $null; $seedProcess = $null
try {
    $fixturePath = Join-Path $qa 'fixture.json'
    $oldAdvertise = $env:NUVIO_QA_ADVERTISE_IP; $oldOutput = $env:NUVIO_QA_FIXTURE_OUTPUT
    try {
        $env:NUVIO_QA_ADVERTISE_IP = '10.90.0.1'; $env:NUVIO_QA_FIXTURE_OUTPUT = $fixturePath
        $seedProcess = Start-Process -FilePath (Get-Command python).Source -ArgumentList @('tools/qa_torrent_seed.py') -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $qa 'seed.log') -RedirectStandardError (Join-Path $qa 'seed-error.log')
    } finally { $env:NUVIO_QA_ADVERTISE_IP = $oldAdvertise; $env:NUVIO_QA_FIXTURE_OUTPUT = $oldOutput }
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while (-not (Test-Path -LiteralPath $fixturePath) -and [DateTime]::UtcNow -lt $deadline) { Start-Sleep -Milliseconds 200 }
    if (-not (Test-Path -LiteralPath $fixturePath)) { throw 'Fixture seed failed to start.' }
    $peerProcess = Start-Process -FilePath (Join-Path $qa 'vpn-peer.exe') -ArgumentList @('-fixture', ('"' + $fixturePath + '"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $qa 'peer.log') -RedirectStandardError (Join-Path $qa 'peer-error.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do { try { $ready = (Http 'http://127.0.0.1:8765/ready') -eq 'ready' } catch { $ready = $false }; if ($ready) { break }; Start-Sleep -Milliseconds 200 } while ([DateTime]::UtcNow -lt $deadline)
    Require $ready 'ephemeral peer ready'
    $privateKey = (& $wg genkey).Trim(); if ($LASTEXITCODE -ne 0) { throw 'Key generation failed.' }
    $publicKey = ($privateKey | & $wg pubkey).Trim(); if ($LASTEXITCODE -ne 0) { throw 'Public key generation failed.' }
    $enrolled = Invoke-RestMethod 'http://127.0.0.1:8765/enroll' -Method Post -Body $publicKey -ContentType 'text/plain'
    $profile = "[Interface]`nPrivateKey = $privateKey`nAddress = 10.90.0.2/32, fd90::2/128`nDNS = 10.90.0.1`nMTU = 1420`n[Peer]`nPublicKey = $($enrolled.publicKey)`nAllowedIPs = 0.0.0.0/0, ::/0`nEndpoint = $($physicalAddress):$($enrolled.port)`nPersistentKeepalive = 25`n"
    $testUser = New-LocalUser -Name ('NuvioPeer' + [Guid]::NewGuid().ToString('N').Substring(0,8)) -Password $testPassword
    Add-LocalGroupMember -SID 'S-1-5-32-545' -Member $testUser
    & $helper install $testUser.SID.Value
    Require ($LASTEXITCODE -eq 0) 'non-administrator broker owner'
    Require ((Request 'status') -eq "STATE`tOff`t0`t0") 'default off'
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($profile))
    Require ((Request "import`t$encoded") -eq "STATE`tOff`t1`t0") 'ephemeral encrypted profile imported'
    Require ((Request 'arm') -eq "STATE`tBlocked`t1`t0") 'guard before tunnel'
    Require (-not (ProbePhysical)) 'physical bypass blocked before tunnel'
    $null = Request 'connect'; AwaitConnected
    Require (((Http 'http://10.90.0.1:8765/probe') | ConvertFrom-Json).source -eq '10.90.0.2') 'IPv4 data crosses encrypted tunnel'
    Require (((Http 'http://[fd90::1]:8765/probe') | ConvertFrom-Json).source -eq 'fd90::2') 'IPv6 data crosses encrypted tunnel'
    Require (([Net.Dns]::GetHostAddresses('vpn-fixture.test') | Where-Object { $_.ToString() -eq '10.90.0.1' }).Count -gt 0) 'DNS resolved only by encrypted fixture peer'
    Require (((Http 'http://vpn-fixture.test:8765/probe') | ConvertFrom-Json).source -in @('10.90.0.2','fd90::2')) 'DNS destination stays inside tunnel'
    Require (-not (ProbePhysical)) 'physical source binding cannot bypass connected tunnel'
    TestNativeTorrentFixture $enrolled.fixture
    1..10 | ForEach-Object {
        Require ((Request 'hold') -eq "STATE`tBlocked`t1`t0") "cycle $_ holds guard"
        Require (-not (ProbePhysical)) "cycle $_ blocks physical network"
        $null = Request 'connect'; AwaitConnected
        Require (((Http 'http://10.90.0.1:8765/probe') | ConvertFrom-Json).source -eq '10.90.0.2') "cycle $_ reconnects encrypted data"
    }
    1..10 | ForEach-Object {
        Restart-Service NuvioVpnControl
        AwaitHeldBroker
        Require ((Request 'status') -eq "STATE`tBlocked`t1`t0") "idle pipe restart $_ retains guard"
        Require (-not (ProbePhysical)) "idle pipe restart $_ blocks physical network"
    }
    $null = Request 'connect'; AwaitConnected
    $service = Get-CimInstance Win32_Service -Filter "Name='NuvioVpnControl'"
    Stop-Process -Id $service.ProcessId -Force
    Require (-not (ProbePhysical)) 'abrupt broker death retains persistent guard'
    Start-Service NuvioVpnControl
    AwaitHeldBroker
    Require ((Request 'status') -like "STATE`tBlocked`t1`t0") 'broker restart returns held policy'
    $null = Request 'connect'; AwaitConnected
    Require (((Http 'http://10.90.0.1:8765/probe') | ConvertFrom-Json).source -eq '10.90.0.2') 'encrypted data recovers after broker restart'
    Require ((Request 'off') -eq "STATE`tOff`t1`t0") 'explicit disable releases guard'
    Require (ProbePhysical) 'explicit disable restores physical network'
    Require ((Request 'delete') -eq "STATE`tOff`t0`t0") 'profile removal'
    Http 'http://127.0.0.1:8765/metrics' | Set-Content -LiteralPath (Join-Path $qa 'metrics.json')
} finally {
    if ($null -ne $peerProcess) {
        try { Http 'http://127.0.0.1:8765/metrics' | Set-Content -LiteralPath (Join-Path $qa 'metrics.json') } catch { }
    }
    & $helper uninstall
    $cleanupExit = $LASTEXITCODE
    $privateKey = $null; $profile = $null; $encoded = $null
    if ($null -ne $testUser) { Remove-LocalUser -SID $testUser.SID }
    $testPassword.Dispose()
    if ($null -ne $peerProcess) { Stop-Process -Id $peerProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($null -ne $seedProcess) { Stop-Process -Id $seedProcess.Id -Force -ErrorAction SilentlyContinue }
    if ($cleanupExit -ne 0) { throw 'Disposable runner guard cleanup failed.' }
}
Write-Output 'Controlled peer checks passed. Physical device, sleep/roaming and provider throughput acceptance remain separate gates.'
