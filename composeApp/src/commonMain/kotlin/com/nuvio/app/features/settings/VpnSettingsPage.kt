package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.vpn.VpnPlatform
import com.nuvio.app.features.vpn.VpnStatus
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.vpnSettingsContent(isTablet: Boolean) {
    item {
        val controller = remember { VpnPlatform.controller() }
        val state by controller.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var confirmDisable by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        var editProfile by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { controller.refresh() }
        val statusLabel = when (state.status) {
            VpnStatus.Off -> Res.string.vpn_status_off
            VpnStatus.Ready -> Res.string.vpn_status_ready
            VpnStatus.Connecting -> Res.string.vpn_status_connecting
            VpnStatus.Connected -> Res.string.vpn_status_connected
            VpnStatus.Blocked -> Res.string.vpn_status_blocked
            VpnStatus.SetupRequired -> Res.string.vpn_status_setup
            VpnStatus.Unsupported -> Res.string.vpn_unsupported
            VpnStatus.Error -> Res.string.vpn_status_error
        }
        SettingsSection(title = stringResource(Res.string.vpn_page_title), isTablet = isTablet) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(title = stringResource(Res.string.vpn_enable),
                    description = stringResource(Res.string.vpn_optional), checked = state.enabled,
                    enabled = state.supported && !state.busy && (state.enabled || state.profilePresent), isTablet = isTablet,
                    onCheckedChange = { value ->
                        if (value) scope.launch { controller.setEnabled(true) } else confirmDisable = true
                    })
                SettingsGroupDivider(isTablet)
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(statusLabel), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(if (state.profilePresent) Res.string.vpn_profile_present else Res.string.vpn_profile_missing),
                        style = MaterialTheme.typography.bodyMedium)
                    if (state.errorCode != null) {
                        val error = when (state.errorCode) {
                            "LITERAL_IP_REQUIRED" -> Res.string.vpn_error_literal
                            "PROFILE_REQUIRED" -> Res.string.vpn_profile_missing
                            "SETUP_REQUIRED", "RUNTIME_MISSING" -> Res.string.vpn_status_setup
                            "TORRENT_STILL_RUNNING" -> Res.string.vpn_error_engine
                            "FULL_TUNNEL_REQUIRED" -> Res.string.vpn_error_full_tunnel
                            "RECOVERY_REQUIRED" -> Res.string.vpn_error_recovery
                            "OTHER_USER_VPN" -> Res.string.vpn_error_other_user
                            "LOCKDOWN_REQUIRED", "SYSTEM_VPN_CONTROLS", "VPN_SETTINGS_UNAVAILABLE" -> Res.string.vpn_lockdown_description
                            "VPN_PERMISSION_REQUIRED" -> Res.string.vpn_status_setup
                            "PROFILE_PICKER_UNAVAILABLE" -> Res.string.vpn_manual_description
                            else -> Res.string.vpn_error_generic
                        }
                        Text(stringResource(error), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (state.supported) {
                    SettingsNavigationRow(title = stringResource(Res.string.vpn_setup),
                        description = stringResource(Res.string.vpn_setup_description), isTablet = isTablet,
                        enabled = !state.busy && state.status !in setOf(VpnStatus.Connected, VpnStatus.Connecting),
                        onClick = { scope.launch { controller.setup() } })
                    if (controller.supportsTextImport) {
                        SettingsNavigationRow(title = stringResource(Res.string.vpn_system_settings),
                            description = stringResource(Res.string.vpn_lockdown_description), isTablet = isTablet,
                            enabled = !state.busy, onClick = { scope.launch { controller.openSystemSettings() } })
                    }
                    SettingsNavigationRow(title = stringResource(Res.string.vpn_import),
                        description = stringResource(Res.string.vpn_import_description), isTablet = isTablet,
                        enabled = !state.busy && state.status != VpnStatus.SetupRequired,
                        onClick = { scope.launch { controller.importProfile() } })
                    if (controller.supportsTextImport) {
                        SettingsNavigationRow(title = stringResource(Res.string.vpn_manual),
                            description = stringResource(Res.string.vpn_manual_description), isTablet = isTablet,
                            enabled = !state.busy, onClick = { editProfile = true })
                    }
                    SettingsNavigationRow(title = stringResource(Res.string.vpn_connect), description = null,
                        isTablet = isTablet, enabled = !state.busy && state.enabled && state.profilePresent,
                        onClick = { scope.launch { controller.connect() } })
                    SettingsNavigationRow(title = stringResource(Res.string.vpn_disconnect),
                        description = stringResource(Res.string.vpn_disconnect_description), isTablet = isTablet,
                        enabled = !state.busy && state.enabled,
                        onClick = { scope.launch { controller.disconnect() } })
                    SettingsSwitchRow(title = stringResource(Res.string.vpn_auto_connect), checked = state.autoConnect,
                        enabled = !state.busy, isTablet = isTablet,
                        onCheckedChange = { value -> scope.launch { controller.setAutoConnect(value) } })
                    SettingsNavigationRow(title = stringResource(Res.string.vpn_delete), description = null,
                        isTablet = isTablet, enabled = !state.busy && state.profilePresent,
                        onClick = { confirmDelete = true })
                }
            }
            Text(stringResource(if (state.supported) Res.string.vpn_scope else Res.string.vpn_unsupported),
                modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.vpn_security_note), modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(Res.string.vpn_preview_note), modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium)
        }
        if (editProfile) VpnProfileDialog(onDismiss = { editProfile = false }, onImport = { profile ->
            editProfile = false
            scope.launch { controller.importProfileText(profile) }
        })
        if (confirmDisable || confirmDelete) {
            val delete = confirmDelete
            AlertDialog(onDismissRequest = { confirmDisable = false; confirmDelete = false },
                title = { Text(stringResource(if (delete) Res.string.vpn_delete else Res.string.vpn_disable_title)) },
                text = { Text(stringResource(if (delete) Res.string.vpn_delete_note else Res.string.vpn_disable_note)) },
                confirmButton = { TextButton(onClick = {
                    confirmDisable = false; confirmDelete = false
                    scope.launch { if (delete) controller.deleteProfile() else controller.setEnabled(false) }
                }) { Text(stringResource(Res.string.vpn_confirm)) } },
                dismissButton = { TextButton(onClick = { confirmDisable = false; confirmDelete = false }) {
                    Text(stringResource(Res.string.vpn_cancel))
                } })
        }
    }
}
