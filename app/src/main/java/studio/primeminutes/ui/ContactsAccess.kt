package studio.primeminutes.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import studio.primeminutes.R
import studio.primeminutes.core.people.ContactsProvider
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing

/** Contacts permission state for a screen: [granted] re-checked on every resume (back from Settings). */
class ContactsAccess(val granted: Boolean, val request: () -> Unit)

private fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
fun rememberContactsAccess(onGranted: () -> Unit = {}): ContactsAccess {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContactsProvider.granted(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = ContactsProvider.granted(context) }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val prefs = remember { context.getSharedPreferences("people_contacts", Context.MODE_PRIVATE) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = ContactsProvider.granted(context)
        if (granted) onGranted()
    }
    return ContactsAccess(granted) {
        val act = context.activity()
        val asked = prefs.getBoolean("perm_asked", false)
        val rationale = act != null && ContactsProvider.PERMISSIONS.any { ActivityCompat.shouldShowRequestPermissionRationale(act, it) }
        if (asked && !rationale) {
            // Denied for good ("don't ask again"): only the app's settings page can grant it now.
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            prefs.edit().putBoolean("perm_asked", true).apply()
            launcher.launch(ContactsProvider.PERMISSIONS)
        }
    }
}

/** Why people need Contacts, and the button that asks (or opens the app's settings). */
@Composable
fun ContactsBlockedCard(access: ContactsAccess, modifier: Modifier = Modifier) {
    val pal = LocalPrimeMinutesPalette.current
    Column(modifier.fillMaxWidth().padding(vertical = Spacing.sm).background(pal.PanelSurface, MaterialTheme.shapes.medium).padding(Spacing.md).testTag("contacts-blocked")) {
        Text(stringResource(R.string.contacts_needed_title), color = pal.Slate200, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.contacts_needed_text), color = pal.Slate400, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        TextButton(onClick = access.request, modifier = Modifier.align(Alignment.End).testTag("contacts-grant")) {
            Text(stringResource(R.string.contacts_grant))
        }
    }
}

