package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** No saved-state persistence, QR upload server, or intent extras containing private keys. */
@Composable
internal fun VpnProfileDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var privateKey by remember { mutableStateOf("") }
    var publicKey by remember { mutableStateOf("") }
    var presharedKey by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var dns by remember { mutableStateOf("") }
    fun clear() { privateKey = ""; presharedKey = "" }
    val fields = listOf(privateKey, publicKey, address, endpoint, dns)
    val valid = fields.all { it.isNotBlank() && it.length <= 256 && '\n' !in it && '\r' !in it } &&
        presharedKey.length <= 44 && '\n' !in presharedKey && '\r' !in presharedKey
    AlertDialog(onDismissRequest = { clear(); onDismiss() },
        title = { Text(stringResource(Res.string.vpn_manual)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(Res.string.vpn_manual_description))
            OutlinedTextField(privateKey, { privateKey = it.take(44) }, singleLine = true,
                label = { Text("PrivateKey") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(publicKey, { publicKey = it.take(44) }, singleLine = true, label = { Text("PublicKey") })
            OutlinedTextField(presharedKey, { presharedKey = it.take(44) }, singleLine = true,
                label = { Text("PresharedKey (optional)") }, visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            OutlinedTextField(address, { address = it.take(256) }, singleLine = true, label = { Text("Address (IP/CIDR)") })
            OutlinedTextField(endpoint, { endpoint = it.take(256) }, singleLine = true, label = { Text("Endpoint (IP:port)") })
            OutlinedTextField(dns, { dns = it.take(256) }, singleLine = true, label = { Text("DNS (IP)") })
        } },
        confirmButton = { TextButton(enabled = valid, onClick = {
            val config = "[Interface]\nPrivateKey = ${privateKey.trim()}\nAddress = ${address.trim()}\nDNS = ${dns.trim()}\n" +
                "[Peer]\nPublicKey = ${publicKey.trim()}\n" +
                (if (presharedKey.isNotBlank()) "PresharedKey = ${presharedKey.trim()}\n" else "") +
                "AllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = ${endpoint.trim()}\nPersistentKeepalive = 25\n"
            clear(); onImport(config)
        }) { Text(stringResource(Res.string.vpn_import)) } },
        dismissButton = { TextButton(onClick = { clear(); onDismiss() }) { Text(stringResource(Res.string.vpn_cancel)) } })
}
