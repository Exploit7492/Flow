package io.github.aedev.flow.ui.cobalt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.cobalt.CobaltAudio
import io.github.aedev.flow.data.cobalt.CobaltQualities
import io.github.aedev.flow.ui.components.shared.FlowModalSheetDefaults
import io.github.aedev.flow.ui.components.shared.rememberFlowSheetState

private val ContentPadding = 24.dp
private val RowVerticalPadding = 14.dp

/**
 * The quality sheet for a link cobalt resolves. It slides up over the app, offers cobalt's video
 * heights and, when asked for sound alone, its formats, then queues the download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CobaltDownloadSheet(
    state: CobaltDownloadUiState,
    onSelectQuality: (String?) -> Unit,
    onSetAudioOnly: (Boolean) -> Unit,
    onSelectAudioFormat: (String) -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFlowSheetState(),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = FlowModalSheetDefaults.modifier,
        contentWindowInsets = FlowModalSheetDefaults.contentWindowInsets,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ContentPadding),
        ) {
            Text(
                text = stringResource(R.string.cobalt_download_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = state.title.ifBlank { stringResource(R.string.cobalt_download_subtitle) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))

            if (state.isLoading) {
                Text(
                    text = stringResource(R.string.cobalt_download_resolving),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                return@Column
            }

            AudioOnlyRow(audioOnly = state.audioOnly, onSetAudioOnly = onSetAudioOnly)

            if (state.audioOnly) {
                AudioFormats(state, onSelectAudioFormat)
            } else {
                VideoQualities(state.selectedQuality, onSelectQuality)
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onStart,
                enabled = !state.isStarting,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
            ) {
                Text(stringResource(R.string.cobalt_download_start))
            }
        }
    }
}

@Composable
private fun AudioOnlyRow(
    audioOnly: Boolean,
    onSetAudioOnly: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onSetAudioOnly(!audioOnly) }
                .padding(vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.cobalt_download_audio_only), style = MaterialTheme.typography.bodyLarge)
        }
        Switch(checked = audioOnly, onCheckedChange = onSetAudioOnly)
    }
}

@Composable
private fun VideoQualities(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    val choices: List<String?> = listOf(null) + CobaltQualities.HEIGHTS
    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
        items(choices, key = { it ?: "best" }) { quality ->
            ChoiceRow(
                label = quality?.let { "$it" + "p" } ?: stringResource(R.string.download_quality_best),
                selected = quality == selected,
                onClick = { onSelect(quality) },
            )
        }
    }
}

@Composable
private fun AudioFormats(
    state: CobaltDownloadUiState,
    onSelect: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.cobalt_download_audio_format),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
        items(CobaltAudio.FORMATS, key = { it }) { format ->
            ChoiceRow(
                label = if (format == CobaltAudio.BEST) stringResource(R.string.download_quality_best) else format.uppercase(),
                selected = format == state.audioFormat,
                onClick = { onSelect(format) },
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = RowVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
