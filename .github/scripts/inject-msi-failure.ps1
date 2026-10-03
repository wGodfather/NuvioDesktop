param([Parameter(Mandatory)][string]$Msi)
$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase((Resolve-Path -LiteralPath $Msi).Path, 1)
try {
    foreach ($sql in @(
        "INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES ('NuvioVpnForcedFailure', 3074, 'NuvioVpnMaintenance', 'installer-test-failure')",
        "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Condition``, ``Sequence``) VALUES ('NuvioVpnForcedFailure', 'NOT (REMOVE=`"ALL`")', 6504)"
    )) {
        $view = $database.OpenView($sql)
        try { $view.Execute() } finally { $view.Close(); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) | Out-Null }
    }
    $database.Commit()
    # A modified installer is a distinct package. Reusing PackageCode triggers
    # Windows Installer SecureRepair hash rejection before the fault action.
    $summary = $database.SummaryInformation(1)
    try {
        $summary.GetType().InvokeMember('Property', [Reflection.BindingFlags]::SetProperty, $null, $summary,
            @([int]9, ('{' + [Guid]::NewGuid().ToString().ToUpperInvariant() + '}'))) | Out-Null
        $summary.Persist()
    } finally { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($summary) | Out-Null }
} finally {
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null
}
