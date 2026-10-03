package com.nuvio.app.features.vpn

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.lang.ref.WeakReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Launchers register before STARTED; private profiles never go in intent extras. */
internal object AndroidVpnUi {
    private var activity = WeakReference<ComponentActivity>(null)
    private var permissionLauncher: ActivityResultLauncher<Intent>? = null
    private var profileLauncher: ActivityResultLauncher<Array<String>>? = null
    private var permissionResult: CompletableDeferred<Boolean>? = null
    private var profileResult: CompletableDeferred<Uri?>? = null
    fun bind(owner: ComponentActivity) {
        activity = WeakReference(owner)
        permissionLauncher = owner.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            permissionResult?.complete(it.resultCode == Activity.RESULT_OK)
        }
        profileLauncher = owner.registerForActivityResult(ActivityResultContracts.OpenDocument()) { profileResult?.complete(it) }
    }
    fun unbind(owner: ComponentActivity) {
        if (activity.get() !== owner) return
        permissionResult?.cancel(); profileResult?.cancel()
        activity.clear(); permissionLauncher = null; profileLauncher = null
    }
    suspend fun preparePermission() = withContext(Dispatchers.Main) {
        val owner = activity.get() ?: throw VpnOperationException("OPEN_APP_REQUIRED")
        val intent = VpnService.prepare(owner) ?: return@withContext
        val result = CompletableDeferred<Boolean>().also { permissionResult = it }
        try {
            checkNotNull(permissionLauncher).launch(intent)
            if (!withTimeout(120000) { result.await() }) throw VpnOperationException("VPN_PERMISSION_REQUIRED")
        } finally { permissionResult = null }
    }
    suspend fun selectProfile(): Uri? = withContext(Dispatchers.Main) {
        if (activity.get() == null) throw VpnOperationException("OPEN_APP_REQUIRED")
        val result = CompletableDeferred<Uri?>().also { profileResult = it }
        try {
            try { checkNotNull(profileLauncher).launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }
            catch (_: android.content.ActivityNotFoundException) { throw VpnOperationException("PROFILE_PICKER_UNAVAILABLE") }
            withTimeout(120000) { result.await() }
        } finally { profileResult = null }
    }
    suspend fun openSettings() = withContext(Dispatchers.Main) {
        val owner = activity.get() ?: throw VpnOperationException("OPEN_APP_REQUIRED")
        try { owner.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
        catch (_: android.content.ActivityNotFoundException) { throw VpnOperationException("VPN_SETTINGS_UNAVAILABLE") }
    }
}
