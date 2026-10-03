param([Parameter(Mandatory)][string]$Msi)
$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase((Resolve-Path -LiteralPath $Msi).Path, 1)
try {
    $view = $database.OpenView('SELECT `Value` FROM `Property` WHERE `Property`=''ProductVersion''')
    try {
        $view.Execute(); $record = $view.Fetch(); $version = [Version]$record.StringData(1)
        [Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) | Out-Null
    } finally { $view.Close(); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) | Out-Null }
    $nextVersion = '{0}.{1}.{2}' -f $version.Major, $version.Minor, ($version.Build + 1)
    $product = '{' + [Guid]::NewGuid().ToString().ToUpperInvariant() + '}'
    # VersionMin/Max are Upgrade primary keys; MSI SQL cannot UPDATE them.
    # Preserve nullable fields and flags by deleting/reinserting the fetched row.
    foreach ($property in @('JP_UPGRADABLE_FOUND', 'JP_DOWNGRADABLE_FOUND')) {
        $view = $database.OpenView("SELECT ``UpgradeCode``, ``VersionMin``, ``VersionMax``, ``Language``, ``Attributes``, ``Remove``, ``ActionProperty`` FROM ``Upgrade`` WHERE ``ActionProperty``='$property'")
        $record = $null
        try {
            $view.Execute(); $record = $view.Fetch()
            if ($null -eq $record) { throw "Missing upgrade rule: $property" }
            $view.Modify(6, $record) # msimodifyDelete
            $field = if ($property -eq 'JP_UPGRADABLE_FOUND') { 3 } else { 2 }
            $record.GetType().InvokeMember('StringData', [Reflection.BindingFlags]::SetProperty, $null, $record, @([int]$field, $nextVersion)) | Out-Null
            $view.Modify(1, $record) # msimodifyInsert
        } finally {
            if ($null -ne $record) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) | Out-Null }
            $view.Close(); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) | Out-Null
        }
    }
    foreach ($sql in @(
        "UPDATE ``Property`` SET ``Value``='$product' WHERE ``Property``='ProductCode'",
        "UPDATE ``Property`` SET ``Value``='$nextVersion' WHERE ``Property``='ProductVersion'",
        "INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES ('NuvioVpnForcedFailure', 3074, 'NuvioVpnMaintenance', 'installer-test-failure')",
        "INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Condition``, ``Sequence``) VALUES ('NuvioVpnForcedFailure', 'NOT (REMOVE=`"ALL`")', 6580)"
    )) {
        $view = $database.OpenView($sql)
        try { $view.Execute() } finally { $view.Close(); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) | Out-Null }
    }
    $database.Commit()
    # A modified installer is a distinct package. Reusing PackageCode triggers
    # Windows Installer SecureRepair hash rejection before the fault action.
    $summary = $database.SummaryInformation(1)
    $package = '{' + [Guid]::NewGuid().ToString().ToUpperInvariant() + '}'
    try {
        $summary.GetType().InvokeMember('Property', [Reflection.BindingFlags]::SetProperty, $null, $summary,
            @([int]9, $package)) | Out-Null
        $summary.Persist()
    } finally { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($summary) | Out-Null }
    # Persist writes SummaryInformation into this transacted database; commit it
    # too, otherwise the old PackageCode makes MSI pick the installed cache.
    $database.Commit()
} finally {
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null
}
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase((Resolve-Path -LiteralPath $Msi).Path, 0)
try {
    foreach ($property in @('ProductCode', 'ProductVersion')) {
        $view = $database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property``='$property'")
        try {
            $view.Execute(); $record = $view.Fetch()
            $expected = if ($property -eq 'ProductCode') { $product } else { $nextVersion }
            if ($record.StringData(1) -ne $expected) { throw "Fault database did not persist $property." }
            [Runtime.InteropServices.Marshal]::FinalReleaseComObject($record) | Out-Null
        } finally { $view.Close(); [Runtime.InteropServices.Marshal]::FinalReleaseComObject($view) | Out-Null }
    }
    $summary = $database.SummaryInformation(0)
    try {
        $actual = $summary.GetType().InvokeMember('Property', [Reflection.BindingFlags]::GetProperty, $null, $summary, @([int]9))
        if ($actual -ne $package) { throw 'Fault PackageCode was not persisted; cached MSI would hide the fault.' }
    } finally { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($summary) | Out-Null }
    Write-Output "PASS persisted distinct fault ProductCode, PackageCode and version $nextVersion"
} finally {
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null
}
