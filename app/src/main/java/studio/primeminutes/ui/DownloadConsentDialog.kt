package studio.primeminutes.ui

import androidx.compose.material.icons.outlined.Download
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.primeminutes.R
import studio.primeminutes.core.models.DownloadPlan
import studio.primeminutes.core.models.SizeFormat
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing

/**
 * Before the first-use model downloads: what will be fetched and how big it is (from the model
 * registry), a warning when the phone is not on Wi-Fi, and the choice — now, only on Wi-Fi, or later.
 */
@Composable
fun DownloadConsentDialog(
    pending: DownloadPlan.Pending,
    unmetered: Boolean,
    onNow: () -> Unit,
    onWifiOnly: () -> Unit,
    onLater: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    AlertDialog(
        onDismissRequest = onLater,
        icon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Download, contentDescription = null) },
        title = { Text(stringResource(R.string.dl_consent_title)) },
        text = {
            Column {
                Text(stringResource(R.string.dl_consent_total, SizeFormat.bytes(pending.total)), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Spacing.sm))
                if (pending.speechBytes > 0) Text(stringResource(R.string.dl_consent_speech, SizeFormat.bytes(pending.speechBytes)))
                if (pending.readerBytes > 0) Text(stringResource(R.string.dl_consent_reader, SizeFormat.bytes(pending.readerBytes)))
                Spacer(Modifier.height(Spacing.sm))
                Text(stringResource(R.string.dl_consent_body), style = MaterialTheme.typography.bodySmall, color = pal.Slate400)
                if (!unmetered) {
                    Spacer(Modifier.height(Spacing.sm))
                    Text(stringResource(R.string.dl_consent_metered), color = pal.WarningText, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                TextButton(onClick = onNow) { Text(stringResource(R.string.dl_consent_now)) }
                TextButton(onClick = onWifiOnly) { Text(stringResource(R.string.dl_consent_wifi_only)) }
                TextButton(onClick = onLater) { Text(stringResource(R.string.dl_consent_later)) }
            }
        },
    )
}
