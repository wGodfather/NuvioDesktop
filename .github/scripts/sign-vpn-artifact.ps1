param([Parameter(Mandatory)][string]$Path)
$ErrorActionPreference = 'Stop'
$artifact = (Resolve-Path -LiteralPath $Path).Path
$versionFile = Join-Path $PSScriptRoot '../../composeApp/Configuration/DesktopVersion.properties'
$version = (Select-String -LiteralPath $versionFile -Pattern '^VERSION_NAME=').Line.Split('=', 2)[1].Trim()
if (-not $env:NUVIO_SIGN_CERT_FILE) {
    if (-not $version.Contains('-')) { throw 'Stable Windows packaging requires a trusted code-signing certificate.' }
    Write-Output 'Unsigned experimental artifact; the stable signing gate remains closed.'
    exit 0
}
if (-not $env:NUVIO_SIGN_CERT_PASSWORD -or -not $env:NUVIO_SIGN_CERT_THUMBPRINT) { throw 'Signing certificate password and pinned thumbprint are required.' }
$cert = New-Object Security.Cryptography.X509Certificates.X509Certificate2(
    $env:NUVIO_SIGN_CERT_FILE, $env:NUVIO_SIGN_CERT_PASSWORD,
    [Security.Cryptography.X509Certificates.X509KeyStorageFlags]::EphemeralKeySet)
try {
    if (-not $cert.HasPrivateKey -or $cert.Thumbprint -ne $env:NUVIO_SIGN_CERT_THUMBPRINT -or $cert.NotAfter -le (Get-Date)) {
        throw 'Signing certificate identity, key or validity is incorrect.'
    }
    $password = ConvertTo-SecureString $env:NUVIO_SIGN_CERT_PASSWORD -AsPlainText -Force
    # Set-AuthenticodeSignature avoids exposing a PFX password in child-process arguments.
    $result = Set-AuthenticodeSignature -LiteralPath $artifact -Certificate $cert -HashAlgorithm SHA256 -TimestampServer 'http://timestamp.digicert.com'
    if ($result.Status -ne 'Valid') { throw 'Artifact signature is not trusted on the build host.' }
    $verified = Get-AuthenticodeSignature -LiteralPath $artifact
    if ($verified.Status -ne 'Valid' -or $verified.SignerCertificate.Thumbprint -ne $env:NUVIO_SIGN_CERT_THUMBPRINT -or -not $verified.TimeStamperCertificate) {
        throw 'Trusted SHA-256 signing and timestamp verification failed.'
    }
    Write-Output 'Artifact publisher and timestamp verified.'
} finally { $cert.Dispose() }
