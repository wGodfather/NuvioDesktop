param(
    [Parameter(Mandatory)][string]$PackageSourceCommit,
    [Parameter(Mandatory)][long]$WindowsRun,
    [Parameter(Mandatory)][long]$AndroidRun,
    [Parameter(Mandatory)][long]$PeerRun,
    [Parameter(Mandatory)][long]$MatrixRun
)
$ErrorActionPreference = 'Stop'
if ($env:GITHUB_ACTIONS -ne 'true' -or $env:RUNNER_ENVIRONMENT -ne 'github-hosted' -or $env:RUNNER_OS -ne 'Windows') {
    throw 'Package preparation requires a disposable Windows CI runner.'
}
if ($PackageSourceCommit -notmatch '^[a-f0-9]{40}$') { throw 'Exact source SHA is required.' }
$repo = 'wGodfather/NuvioDesktop'
if ($env:GITHUB_REPOSITORY -ne $repo) { throw 'This draft belongs only to the owner fork.' }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$props = @{}
Get-Content -LiteralPath (Join-Path $root 'composeApp/Configuration/DesktopVersion.properties') | ForEach-Object {
    if ($_ -match '^([A-Z_]+)=(.+)$') { $props[$Matches[1]] = $Matches[2].Trim() }
}
$version = $props.VERSION_NAME
if ($version -ne '0.1.30-alpha' -or $props.VERSION_CODE -ne '31' -or $props.WINDOWS_MSI_VERSION -ne '1.1.31') {
    throw 'This workflow prepares only the experimental review candidate, never stable.'
}
$env:NUVIO_REVIEW_SOURCE = $PackageSourceCommit
$env:NUVIO_REVIEW_OUTPUT = Join-Path $env:RUNNER_TEMP 'vpn-release-review'
$qa = Join-Path $env:RUNNER_TEMP 'vpn-review-qa'
New-Item -ItemType Directory -Force $env:NUVIO_REVIEW_OUTPUT, $qa | Out-Null
# Validate all acceptance evidence before downloading or modifying a release.
python (Join-Path $PSScriptRoot 'prepare-vpn-review.py') --check $WindowsRun $AndroidRun $PeerRun $MatrixRun
if ($LASTEXITCODE -ne 0) { throw 'Source/evidence validation failed.' }
gh run download $WindowsRun -R $repo --name "Nuvio-Windows-$version" --dir (Join-Path $env:NUVIO_REVIEW_OUTPUT 'windows')
if ($LASTEXITCODE -ne 0) { throw 'Windows artifact download failed.' }
gh run download $AndroidRun -R $repo --name "Nuvio-Android-$version" --dir (Join-Path $env:NUVIO_REVIEW_OUTPUT 'android')
if ($LASTEXITCODE -ne 0) { throw 'Android artifact download failed.' }
gh run download $WindowsRun -R $repo --pattern 'Nuvio-Windows-MSI-QA-*' --dir (Join-Path $qa 'msi')
if ($LASTEXITCODE -ne 0) { throw 'MSI QA download failed.' }
gh run download $AndroidRun -R $repo --name "Nuvio-Android-QA-$version" --dir (Join-Path $qa 'android-package')
if ($LASTEXITCODE -ne 0) { throw 'Android package QA download failed.' }
gh run download $MatrixRun -R $repo --pattern 'vpn-*' --dir (Join-Path $qa 'android-matrix')
if ($LASTEXITCODE -ne 0) { throw 'Device matrix QA download failed.' }
gh run download $PeerRun -R $repo --pattern 'vpn-windows-peer-*' --dir (Join-Path $qa 'windows-peer')
if ($LASTEXITCODE -ne 0) { throw 'Windows peer QA download failed.' }

$msi = Join-Path $env:NUVIO_REVIEW_OUTPUT "windows/Nuvio-Windows-x64-$version.msi"
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($msi, 0)
$view = $database.OpenView('SELECT `Value` FROM `Property` WHERE `Property`=''ProductVersion''')
$view.Execute(); $record = $view.Fetch(); $msiVersion = $record.StringData(1); $view.Close()
[Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
[Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null
$signature = Get-AuthenticodeSignature -LiteralPath $msi
@{ version=$msiVersion; signatureStatus=$signature.Status.ToString(); installed=$false } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $env:NUVIO_REVIEW_OUTPUT 'windows-package-info.json') -Encoding utf8
$runtime = Join-Path $qa 'windows-runtime'
& (Join-Path $PSScriptRoot 'extract-msi-vpn.ps1') -Msi $msi -OutputDirectory $runtime
if ($LASTEXITCODE -ne 0) { throw 'Packaged VPN runtime verification failed.' }
& (Join-Path $runtime 'NuvioVpn.exe') self-test
if ($LASTEXITCODE -ne 0) { throw 'Packaged helper tests failed.' }
Copy-Item -LiteralPath (Join-Path $runtime 'runtime.sha256') -Destination (Join-Path $env:NUVIO_REVIEW_OUTPUT 'Windows-VPN-runtime.sha256')
python (Join-Path $PSScriptRoot 'prepare-vpn-review.py') $WindowsRun $AndroidRun $PeerRun $MatrixRun
if ($LASTEXITCODE -ne 0) { throw 'Package integrity preparation failed.' }

# No publish command is present. Existing public releases are immutable here.
$existing = gh release view $version -R $repo --json isDraft,targetCommitish 2>$null
if ($LASTEXITCODE -eq 0) {
    $release = $existing | ConvertFrom-Json
    if (-not $release.isDraft -or $release.targetCommitish -ne $PackageSourceCommit) {
        throw 'Refusing to modify a public release or a draft belonging to another source.'
    }
} else {
    gh release create $version -R $repo --target $PackageSourceCommit --draft --prerelease --title 'Nuvio Türkiye 0.1.30-alpha — deneysel VPN inceleme taslağı' --notes-file (Join-Path $root 'RELEASE_NOTES_TR.md')
    if ($LASTEXITCODE -ne 0) { throw 'Draft creation failed.' }
}
$files = @((Get-ChildItem -LiteralPath (Join-Path $env:NUVIO_REVIEW_OUTPUT 'android') -File).FullName) +
    @((Get-ChildItem -LiteralPath (Join-Path $env:NUVIO_REVIEW_OUTPUT 'windows') -File).FullName) +
    @((Get-ChildItem -LiteralPath $env:NUVIO_REVIEW_OUTPUT -File).FullName)
gh release upload $version @files -R $repo --clobber
if ($LASTEXITCODE -ne 0) { throw 'Draft asset upload failed.' }
$verified = gh release view $version -R $repo --json isDraft,isPrerelease,targetCommitish,assets | ConvertFrom-Json
if ($LASTEXITCODE -ne 0 -or -not $verified.isDraft -or -not $verified.isPrerelease -or $verified.targetCommitish -ne $PackageSourceCommit) {
    throw 'Draft identity verification failed.'
}
foreach ($file in $files) {
    $name = [IO.Path]::GetFileName($file)
    $asset = @($verified.assets | Where-Object name -eq $name)
    if ($asset.Count -ne 1 -or $asset[0].size -ne (Get-Item -LiteralPath $file).Length) {
        throw "Draft asset visibility/size mismatch: $name"
    }
}
$roundtrip = Join-Path $env:RUNNER_TEMP 'vpn-review-roundtrip'
gh release download $version -R $repo --dir $roundtrip
if ($LASTEXITCODE -ne 0) { throw 'Authenticated draft asset download failed.' }
foreach ($file in $files) {
    $downloaded = Join-Path $roundtrip ([IO.Path]::GetFileName($file))
    if ((Get-FileHash -LiteralPath $downloaded -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash) {
        throw 'Downloaded draft asset checksum mismatch.'
    }
}
Write-Output 'PASS verified experimental draft and SHA-256 roundtrip of every uploaded asset; stable publication remains blocked.'
