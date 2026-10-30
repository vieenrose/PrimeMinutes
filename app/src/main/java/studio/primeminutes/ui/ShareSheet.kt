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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Share
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

            // Redesign: one format choice, the content toggles, one 分享… button; destinations (Quick Share,
            // mail, any app) are the system share sheet's job.
            Section(stringResource(R.string.share_format))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                FormatChip(stringResource(R.string.share_format_pdf), what == What.PDF && fmt == ShareKit.DocFormat.PDF, hasSession, "share-format-pdf") {
                    what = What.PDF; fmt = ShareKit.DocFormat.PDF; onFormat(fmt)
                }
                FormatChip(stringResource(R.string.share_format_word), what == What.PDF && fmt == ShareKit.DocFormat.WORD, hasSession, "share-format-word") {
                    what = What.PDF; fmt = ShareKit.DocFormat.WORD; onFormat(fmt)
                }
                FormatChip(stringResource(R.string.share_what_session), what == What.SESSION, hasSession, "share-what-session") { what = What.SESSION }
                FormatChip(stringResource(R.string.share_what_audio), what == What.AUDIO, true, "share-what-audio") { what = What.AUDIO }
            }
            // What the choice is, its size, and its caution.
            val (desc, size) = when (what) {
                What.SESSION -> stringResource(R.string.share_what_session_desc) to SizeFormat.bytes(sessionBytes)
                What.PDF -> stringResource(R.string.share_what_pdf_desc) to (docBytes?.let { SizeFormat.bytes(it) } ?: "…")
                else -> stringResource(R.string.share_what_audio_desc) to SizeFormat.bytes(audioBytes)
            }
            Text("$desc · $size", style = MaterialTheme.typography.bodySmall, color = pal.Slate400,
                modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm))
            if (what != What.PDF) Text(
                if (online) stringResource(R.string.share_hint_copyright) else stringResource(R.string.share_hint_voices),
                color = pal.WarningText, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 2.dp))

            if (what == What.PDF) {
                Section(stringResource(R.string.share_content))
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).heightIn(min = 48.dp)
                        .clickable(role = Role.Switch) { attach = !attach },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.share_attach_audio, SizeFormat.bytes(audioBytes)), color = pal.Slate200, style = MaterialTheme.typography.bodyMedium)
                        if (online && attach) Text(stringResource(R.string.share_hint_copyright), color = pal.WarningText, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = attach, onCheckedChange = { attach = it }, modifier = Modifier.testTag("share-attach-audio"),
                        colors = SwitchDefaults.colors(checkedTrackColor = pal.Sky, checkedThumbColor = pal.OnSky))
                }
            }

            if ((total ?: 0L) > ShareKit.MAIL_LIMIT) Text(stringResource(R.string.share_mail_too_big), color = pal.WarningText,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs))
            Spacer(Modifier.height(Spacing.lg))
            studio.primeminutes.ui.components.GradientButton(
                text = stringResource(R.string.share_go) + (total?.let { "  ·  " + SizeFormat.bytes(it) } ?: ""),
                icon = Icons.Filled.Share,
                enabled = what != What.PDF || docBytes != null,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).heightIn(min = 52.dp).testTag("share-where-other"),
            ) { go(Where.OTHER) }
        }
    }
}

@Composable
private fun FormatChip(label: String, on: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val pal = LocalPrimeMinutesPalette.current
    Box(
        Modifier.heightIn(min = 48.dp).selectable(selected = on, enabled = enabled, role = Role.RadioButton, onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = MaterialTheme.typography.labelLarge, maxLines = 1,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = when { on -> pal.Slate900; enabled -> pal.Slate200; else -> pal.Slate400.copy(alpha = 0.5f) },
            modifier = Modifier.heightIn(min = 34.dp).clip(CircleShape)
                .background(if (on) pal.Slate200 else pal.Fill)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Section(text: String) {
    val pal = LocalPrimeMinutesPalette.current
    Text(text, style = studio.primeminutes.ui.theme.TypeRole.groupHeader(), color = pal.Ink3,
        modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.xs).semantics { heading() })
}
