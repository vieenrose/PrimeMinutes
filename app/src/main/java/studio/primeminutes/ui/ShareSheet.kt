package studio.primeminutes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.primeminutes.R
import studio.primeminutes.core.models.SizeFormat
import studio.primeminutes.core.share.ShareKit
import studio.primeminutes.core.share.ShareKit.What
import studio.primeminutes.core.share.ShareKit.Where
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette
import studio.primeminutes.ui.theme.Spacing

/**
 * The one "Share" sheet (spec v2): WHAT (whole session `.pmin` / PDF ± the recording / audio only),
 * then WHERE (Nearby = Quick Share, e-mail with the files attached, or any other app). Sizes on every "what"; a privacy hint on a recording's
 * voices, a copyright hint on an online source's audio.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    title: String,
    hasSession: Boolean,
    online: Boolean,
    sessionBytes: Long,
    audioBytes: Long,
    /** The PDF's size once built (null: being built). */
    pdfBytes: Long?,
    /** The .docx's size once built (null: being built). */
    docxBytes: Long?,
    /** The remembered document format, and where a new choice is saved. */
    format: ShareKit.DocFormat,
    onFormat: (ShareKit.DocFormat) -> Unit,
    onGo: (What, attachAudio: Boolean, ShareKit.DocFormat, Where) -> Unit,
    onDismiss: () -> Unit,
) {
    val pal = LocalPrimeMinutesPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var what by remember { mutableStateOf(ShareKit.defaultWhat(online, hasSession)) }
    var fmt by remember { mutableStateOf(format) }
    val docBytes = if (fmt == ShareKit.DocFormat.PDF) pdfBytes else docxBytes
    var attach by remember { mutableStateOf(ShareKit.attachAudioByDefault(online, audioBytes)) }
    val total = ShareKit.size(what, attach, sessionBytes, audioBytes, docBytes)
    fun go(where: Where) { onGo(what, what == What.PDF && attach, fmt, where) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = pal.Slate800) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xxl).testTag("share-sheet")) {
            Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleLarge, color = pal.Slate200,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = Spacing.lg).semantics { heading() })
            Text(title, style = MaterialTheme.typography.bodyMedium, color = pal.Slate400, maxLines = 1,
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm))

            Section(stringResource(R.string.share_what))
            Column(Modifier.selectableGroup()) {
                WhatRow(Icons.Filled.FolderZip, stringResource(R.string.share_what_session), stringResource(R.string.share_what_session_desc),
                    SizeFormat.bytes(sessionBytes), selected = what == What.SESSION, enabled = hasSession,
                    hint = if (online) stringResource(R.string.share_hint_copyright) else stringResource(R.string.share_hint_voices),
                    tag = "share-what-session") { what = What.SESSION }
                WhatRow(Icons.Filled.PictureAsPdf, stringResource(R.string.share_what_pdf), stringResource(R.string.share_what_pdf_desc),
                    docBytes?.let { SizeFormat.bytes(it) } ?: "…", selected = what == What.PDF, enabled = hasSession, hint = null,
                    tag = "share-what-pdf") { what = What.PDF }
                if (what == What.PDF) SingleChoiceSegmentedButtonRow(Modifier.padding(start = 72.dp, end = Spacing.lg, bottom = Spacing.xs).testTag("share-format")) {
                    ShareKit.DocFormat.entries.forEachIndexed { i, f ->
                        SegmentedButton(
                            selected = fmt == f,
                            onClick = { fmt = f; onFormat(f) },
                            shape = SegmentedButtonDefaults.itemShape(i, ShareKit.DocFormat.entries.size),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = pal.Sky.copy(alpha = 0.18f), activeContentColor = pal.Slate200, inactiveContentColor = pal.Slate400),
                            modifier = Modifier.testTag("share-format-${f.name.lowercase()}"),
                        ) { Text(stringResource(if (f == ShareKit.DocFormat.PDF) R.string.share_format_pdf else R.string.share_format_word)) }
                    }
                }
                if (what == What.PDF) Row(
                    Modifier.fillMaxWidth().padding(start = 72.dp, end = Spacing.lg).heightIn(min = 48.dp)
                        .clickable(role = Role.Switch) { attach = !attach },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_attach_audio, SizeFormat.bytes(audioBytes)), color = pal.Slate200, style = MaterialTheme.typography.bodyMedium)
                        if (online) Text(stringResource(R.string.share_hint_copyright), color = pal.WarningText, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = attach, onCheckedChange = { attach = it }, modifier = Modifier.testTag("share-attach-audio"),
                        colors = SwitchDefaults.colors(checkedTrackColor = pal.Sky, checkedThumbColor = pal.OnSky))
                }
                WhatRow(Icons.Filled.MusicNote, stringResource(R.string.share_what_audio), stringResource(R.string.share_what_audio_desc),
                    SizeFormat.bytes(audioBytes), selected = what == What.AUDIO, enabled = true,
                    hint = if (online) stringResource(R.string.share_hint_copyright) else stringResource(R.string.share_hint_voices),
                    tag = "share-what-audio") { what = What.AUDIO }
            }

            HorizontalDivider(color = pal.Hairline, modifier = Modifier.padding(vertical = Spacing.sm))
            Section(stringResource(R.string.share_where))
            WhereRow(Icons.Filled.NearMe, null, stringResource(R.string.share_where_nearby), stringResource(R.string.share_where_nearby_desc), true, "share-where-nearby") { go(Where.NEARBY) }
            WhereRow(Icons.Filled.Email, null, stringResource(R.string.share_where_mail),
                if ((total ?: 0L) > ShareKit.MAIL_LIMIT) stringResource(R.string.share_mail_too_big) else stringResource(R.string.share_where_mail_desc),
                true, "share-where-mail", warn = (total ?: 0L) > ShareKit.MAIL_LIMIT) { go(Where.MAIL) }
            HorizontalDivider(color = pal.Hairline, modifier = Modifier.padding(vertical = Spacing.xs))
            ListItem(
                headlineContent = { Text(stringResource(R.string.share_other_apps), color = pal.Slate200, style = MaterialTheme.typography.bodyMedium) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = pal.Slate400, modifier = Modifier.size(20.dp)) },
                trailingContent = { total?.let { Text(SizeFormat.bytes(it), color = pal.Slate400, style = MaterialTheme.typography.bodySmall) } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { go(Where.OTHER) }.testTag("share-where-other"),
            )
        }
    }
}

@Composable
private fun Section(text: String) {
    val pal = LocalPrimeMinutesPalette.current
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = pal.Sky, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xs).semantics { heading() })
}

@Composable
private fun WhatRow(icon: ImageVector, title: String, desc: String, size: String, selected: Boolean, enabled: Boolean, hint: String?, tag: String, onClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val alpha = if (enabled) 1f else 0.4f
    ListItem(
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(title, color = pal.Slate200.copy(alpha = alpha), modifier = Modifier.weight(1f, fill = false))
                Text(size, color = pal.Slate400.copy(alpha = alpha), style = MaterialTheme.typography.bodySmall)
            }
        },
        supportingContent = {
            Column {
                Text(desc, color = pal.Slate400.copy(alpha = alpha))
                if (hint != null && selected) Text(hint, color = pal.WarningText, style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = null, enabled = enabled, colors = RadioButtonDefaults.colors(selectedColor = pal.Sky))
                Spacer(Modifier.size(Spacing.xs))
                Icon(icon, null, tint = pal.Sky.copy(alpha = alpha), modifier = Modifier.size(20.dp))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).testTag(tag),
    )
}

@Composable
private fun WhereRow(icon: ImageVector, image: ImageBitmap?, title: String, desc: String, enabled: Boolean, tag: String, warn: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    val alpha = if (enabled) 1f else 0.45f
    ListItem(
        headlineContent = { Text(title, color = pal.Slate200.copy(alpha = alpha)) },
        supportingContent = { Text(desc, color = if (warn) pal.WarningText else pal.Slate400.copy(alpha = alpha)) },
        leadingContent = {
            if (image != null) Image(image, null, Modifier.size(28.dp))
            else Icon(icon, null, tint = pal.Sky.copy(alpha = alpha), modifier = Modifier.size(28.dp))
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag),
    )
}
