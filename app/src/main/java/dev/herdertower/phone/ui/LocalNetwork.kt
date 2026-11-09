package dev.herdertower.phone.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Android 17 (API 37) guards the local network: an app that targets it
 * needs ACCESS_LOCAL_NETWORK to open a connection to a device on the LAN,
 * and the tower (the Mac) is always one. Without it every request times out.
 */
object LocalNetwork {
    /** The permission's name (a string: the constant only exists in the API 37 SDK). */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** Whether the permission is still missing on a phone that asks for it. */
    fun needed(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 37 && ContextCompat.checkSelfPermission(c, PERMISSION) != PackageManager.PERMISSION_GRANTED
}

/** What the app knows about the permission now, and how to ask for it. */
class LocalNetworkAccess(val needed: Boolean, val ask: () -> Unit)

/**
 * Follows the permission (checked again on every resume, so a grant in the
 * settings counts) and asks for it: once by itself when [askNow], then from
 * the card. After a denial the system shows no dialog any more: the card then
 * opens the app's settings. [onGranted] runs after a yes.
 */
@Composable
fun rememberLocalNetwork(askNow: Boolean = false, onGranted: () -> Unit = {}): LocalNetworkAccess {
    val context = LocalContext.current
    var needed by remember { mutableStateOf(LocalNetwork.needed(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var denied by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        needed = LocalNetwork.needed(context)
        onPauseOrDispose {}
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        needed = LocalNetwork.needed(context)
        if (ok) onGranted() else denied = true
    }
    LaunchedEffect(needed, askNow) {
        if (needed && askNow && !asked) {
            asked = true
            launcher.launch(LocalNetwork.PERMISSION)
        }
    }
    return LocalNetworkAccess(needed) {
        val activity = context.findActivity()
        val dialogGone = denied && activity != null && !activity.shouldShowRequestPermissionRationale(LocalNetwork.PERMISSION)
        if (dialogGone) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData("package:${context.packageName}".toUri()))
            }
        } else {
            launcher.launch(LocalNetwork.PERMISSION)
        }
    }
}

/** The card that says the tower can't be reached until the permission is given, with the way to give it. */
@Composable
fun LocalNetworkNotice(access: LocalNetworkAccess, modifier: Modifier = Modifier) {
    if (!access.needed) return
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(tc.blocked)
            Spacer(Modifier.size(10.dp))
            Text(
                "The tower is a device on your local network: allow the app to reach it, or it can't connect.",
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = tc.text,
            )
            TextButton(onClick = access.ask) { Text("Allow", color = tc.text, fontWeight = FontWeight.Medium) }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
