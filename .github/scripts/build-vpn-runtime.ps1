param([string]$OutputDirectory = 'composeApp/build/native/vpn')
$ErrorActionPreference = 'Stop'
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    # Gradle may inherit PowerShell 7 module paths from the desktop runtime.
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1')
    Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1')
}
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = [IO.Path]::GetFullPath((Join-Path $repo $OutputDirectory))
if (-not $output.StartsWith($repo + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'VPN build output must be inside this checkout.'
}
New-Item -ItemType Directory -Path $output -Force | Out-Null
$compiler = Join-Path $env:WINDIR 'Microsoft.NET/Framework64/v4.0.30319/csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw '.NET Framework 4.8 C# compiler is required.' }
$sources = (Get-ChildItem -LiteralPath (Join-Path $repo 'composeApp/src/desktopMain/native/vpn') -Filter '*.cs').FullName
& $compiler /nologo /target:exe /platform:x64 /optimize+ ('/out:' + (Join-Path $output 'NuvioVpn.exe')) /reference:System.ServiceProcess.dll /reference:System.Security.dll $sources
if ($LASTEXITCODE -ne 0) { throw 'VPN helper compilation failed.' }
& (Join-Path $output 'NuvioVpn.exe') self-test
if ($LASTEXITCODE -ne 0) { throw 'VPN helper self-tests failed.' }
$msi = Join-Path $output 'wireguard-amd64-1.1.1.msi'
$expectedMsi = '7BFED60AD61B785C914B38B61555A975488E1D3EC472DBFB2FCDF498FCA75242'
if (-not (Test-Path -LiteralPath $msi)) {
    Invoke-WebRequest -UseBasicParsing -Uri 'https://download.wireguard.com/windows-client/wireguard-amd64-1.1.1.msi' -OutFile $msi
}
if ((Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash -ne $expectedMsi) { throw 'WireGuard MSI checksum mismatch.' }
$signature = Get-AuthenticodeSignature -LiteralPath $msi
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.GetNameInfo('SimpleName', $false) -ne 'WireGuard LLC') {
    throw 'WireGuard MSI publisher signature verification failed.'
}
$sevenZip = Join-Path ${env:ProgramFiles} '7-Zip/7z.exe'
if (-not (Test-Path -LiteralPath $sevenZip)) { throw '7-Zip is required to extract the signed MSI without installing it.' }
& $sevenZip x $msi ('-o' + $output) -y | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'WireGuard extraction failed.' }
$expectedFiles = @{
    'wireguard.exe' = '04C71FB1A555A6ACE40D3AE9113C90C4829D7CA1D6C903FD20A8D41A0E2BEF94'
    'wg.exe' = 'E1F8CB5C9E30A878FA7EB0B2251BB4EB4946E669063E6FD5E4383CA4BD0DCAF2'
}
foreach ($name in $expectedFiles.Keys) {
    $path = Join-Path $output $name
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $expectedFiles[$name]) { throw "Runtime checksum mismatch: $name" }
    $signature = Get-AuthenticodeSignature -LiteralPath $path
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.GetNameInfo('SimpleName', $false) -ne 'WireGuard LLC') {
        throw "Runtime signature mismatch: $name"
    }
}
$manifest = foreach ($name in @('NuvioVpn.exe', 'wireguard.exe', 'wg.exe')) {
    $hash = (Get-FileHash -LiteralPath (Join-Path $output $name) -Algorithm SHA256).Hash.ToLowerInvariant()
    "$name=$hash"
}
Set-Content -LiteralPath (Join-Path $output 'runtime.sha256') -Value $manifest -Encoding ascii
Copy-Item -LiteralPath (Join-Path $repo 'composeApp/src/desktopMain/native/vpn/WireGuard-Windows-LICENSE.txt') -Destination $output
Copy-Item -LiteralPath (Join-Path $repo 'composeApp/src/desktopMain/native/vpn/WireGuard-Tools-LICENSE.txt') -Destination $output
Copy-Item -LiteralPath (Join-Path $repo 'composeApp/src/desktopMain/native/vpn/WireGuard-NT-LICENSE.txt') -Destination $output
Copy-Item -LiteralPath (Join-Path $repo 'composeApp/src/desktopMain/native/vpn/WireGuard-SOURCES.txt') -Destination $output
Write-Output 'VPN runtime built and verified; no driver, service, or firewall was installed.'
