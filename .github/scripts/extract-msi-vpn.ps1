param([Parameter(Mandatory)][string]$Msi, [Parameter(Mandatory)][string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$msiPath = (Resolve-Path -LiteralPath $Msi).Path
$output = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $output -Force | Out-Null
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($msiPath, 0)
$view = $database.OpenView('SELECT `File`, `FileName` FROM `File`'); $view.Execute()
$jarId = $null
while ($record = $view.Fetch()) { if ($record.StringData(2) -match 'composeApp-desktop-.*\.jar$') { $jarId = $record.StringData(1) } }
$view.Close()
[Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
[Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null
if (-not $jarId -or $jarId -notmatch '^[A-Za-z0-9_]+$') { throw 'Application archive missing or invalid.' }
& (Join-Path $env:ProgramFiles '7-Zip/7z.exe') e $msiPath $jarId ('-o' + $output) -y | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'MSI archive extraction failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead((Join-Path $output $jarId))
try {
    foreach ($name in @('NuvioVpn.exe', 'wireguard.exe', 'wg.exe', 'runtime.sha256', 'WireGuard-Windows-LICENSE.txt', 'WireGuard-Tools-LICENSE.txt', 'WireGuard-NT-LICENSE.txt', 'WireGuard-SOURCES.txt')) {
        $entry = $zip.GetEntry('vpn/windows-x64/' + $name)
        if (-not $entry) { throw "Missing runtime file: $name" }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $output $name), $true)
    }
} finally { $zip.Dispose() }
foreach ($line in Get-Content (Join-Path $output 'runtime.sha256')) {
    if ($line -notmatch '^(NuvioVpn\.exe|wireguard\.exe|wg\.exe)=([a-fA-F0-9]{64})$') { throw 'Invalid runtime manifest.' }
    if ((Get-FileHash (Join-Path $output $Matches[1]) -Algorithm SHA256).Hash -ne $Matches[2]) { throw 'Packaged runtime hash mismatch.' }
}
Write-Output 'Packaged runtime extracted and verified without installation.'
