param([Parameter(Mandatory)][string]$Msi, [string]$Helper = 'composeApp/build/native/vpn/NuvioVpn.exe')
$ErrorActionPreference = 'Stop'
$msiPath = (Resolve-Path -LiteralPath $Msi).Path
$helperPath = (Resolve-Path -LiteralPath $Helper).Path
function Call-Com($Object, [string]$Method, [object[]]$Arguments = @()) {
    $Object.GetType().InvokeMember($Method, [Reflection.BindingFlags]::InvokeMethod, $null, $Object, $Arguments)
}
function Set-Com($Object, [string]$Property, [object[]]$Arguments) {
    $Object.GetType().InvokeMember($Property, [Reflection.BindingFlags]::SetProperty, $null, $Object, $Arguments) | Out-Null
}
function Query($Database, [string]$Sql, $Record = $null) {
    $view = Call-Com $Database 'OpenView' @($Sql)
    try { Call-Com $view 'Execute' @($Record) | Out-Null } finally { Call-Com $view 'Close' | Out-Null }
}
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = Call-Com $installer 'OpenDatabase' @($msiPath, 1)
try {
    # Binary-backed actions run from the installer cache even after app files are removed.
    $record = Call-Com $installer 'CreateRecord' @(2)
    Set-Com $record 'StringData' @(1, 'NuvioVpnMaintenance')
    Call-Com $record 'SetStream' @(2, $helperPath) | Out-Null
    Query $database 'INSERT INTO `Binary` (`Name`, `Data`) VALUES (?, ?)' $record
    $actions = @(
        @('NuvioVpnRollback', 3330, 'installer-rollback', 1501, 'Installed OR JP_UPGRADABLE_FOUND'),
        @('NuvioVpnPrepare', 3074, 'installer-prepare', 1502, 'Installed OR JP_UPGRADABLE_FOUND'),
        @('NuvioVpnResume', 3074, 'installer-resume', 6501, 'NOT (REMOVE="ALL")'),
        @('NuvioVpnRemove', 3074, 'installer-remove', 6502, 'REMOVE="ALL" AND NOT UPGRADINGPRODUCTCODE'),
        @('NuvioVpnCommit', 3586, 'installer-commit', 6503, '1')
    )
    foreach ($action in $actions) {
        Query $database ("INSERT INTO ``CustomAction`` (``Action``, ``Type``, ``Source``, ``Target``) VALUES ('{0}', {1}, 'NuvioVpnMaintenance', '{2}')" -f $action[0], $action[1], $action[2])
        Query $database ("INSERT INTO ``InstallExecuteSequence`` (``Action``, ``Condition``, ``Sequence``) VALUES ('{0}', '{1}', {2})" -f $action[0], $action[4], $action[3])
    }
    Call-Com $database 'Commit' | Out-Null
    Write-Output 'MSI VPN rollback, upgrade/repair and uninstall actions embedded. Installation has not been tested by this patch step.'
} finally {
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($database) | Out-Null
    [Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer) | Out-Null
}
