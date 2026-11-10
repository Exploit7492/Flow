package io.github.aedev.flow.ui.cobalt

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.cobalt.CobaltAudio
import io.github.aedev.flow.data.cobalt.CobaltQualities
import io.github.aedev.flow.ui.components.shared.FlowModalSheetDefaults
import io.github.aedev.flow.ui.components.shared.rememberFlowSheetState

private val HorizontalPadding = 20.dp
private val RowHeight = 56.dp
private val IconSize = 24.dp
private val IconLabelGap = 16.dp

/** The video heights the sheet shows before "More": the common ladder, tallest first. */
private val CommonHeights = CobaltQualities.HEIGHTS.drop(2)

/** The two tallest heights, behind "More". */
private val MoreHeights = CobaltQualities.HEIGHTS.take(2)

/**
 * The quality sheet for a link cobalt resolves. It slides up over the app as one flat ladder — the
 * sound ladder, then cobalt's video heights, then its own best — and starts the download on the row
 * that is tapped, the way a downloader's own picker does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CobaltDownloadSheet(
    state: CobaltDownloadUiState,
    onChoose: (CobaltDownloadChoice) -> Unit,
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
        Column(modifier = Modifier.fillMaxWidth()) {
            Header(state)

            if (state.isLoading) {
                Text(
                    text = stringResource(R.string.cobalt_download_resolving),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = HorizontalPadding, vertical = 24.dp),
                )
                return@Column
            }

            QualityLadder(state, onChoose)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header(state: CobaltDownloadUiState) {
    Column(modifier = Modifier.padding(horizontal = HorizontalPadding, vertical = 4.dp)) {
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
    }
}

@Composable
private fun QualityLadder(
    state: CobaltDownloadUiState,
    onChoose: (CobaltDownloadChoice) -> Unit,
) {
    var showMore by remember { mutableStateOf(false) }
    val selected = state.selection
    LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
        items(CobaltAudio.LADDER, key = { "audio-${it.format}-${it.bitrate}" }) { choice ->
            QualityRow(
                icon = Icons.Rounded.MusicNote,
                label = audioLabel(choice),
                selected = selected == CobaltDownloadChoice.Audio(choice.format, choice.bitrate),
                enabled = !state.isStarting,
                onClick = { onChoose(CobaltDownloadChoice.Audio(choice.format, choice.bitrate)) },
            )
        }
        item(key = "divider") {
            HorizontalDivider(
                modifier = Modifier.padding(start = HorizontalPadding + IconSize + IconLabelGap),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
        items(videoHeights(showMore), key = { "video-$it" }) { quality ->
            QualityRow(
                icon = Icons.Rounded.VideoLibrary,
                label = "$quality" + "p",
                selected = selected == CobaltDownloadChoice.Video(quality),
                enabled = !state.isStarting,
                onClick = { onChoose(CobaltDownloadChoice.Video(quality)) },
            )
        }
        if (!showMore) {
            item(key = "more") {
                QualityRow(
                    icon = Icons.Rounded.ExpandMore,
                    label = stringResource(R.string.download_quality_more),
                    selected = false,
                    enabled = !state.isStarting,
                    onClick = { showMore = true },
                )
            }
        }
        item(key = "best") {
            QualityRow(
                icon = Icons.Rounded.VideoLibrary,
                label = stringResource(R.string.download_quality_best),
                selected = selected == CobaltDownloadChoice.Video(null),
                enabled = !state.isStarting,
                onClick = { onChoose(CobaltDownloadChoice.Video(null)) },
            )
        }
    }
}

private fun videoHeights(showMore: Boolean): List<String> = if (showMore) CommonHeights + MoreHeights else CommonHeights

@Composable
private fun audioLabel(choice: CobaltAudio.Choice): String =
    when {
        choice.format == CobaltAudio.BEST -> stringResource(R.string.download_quality_best)
        choice.bitrate == null -> choice.format.uppercase()
        else -> "${choice.format.uppercase()} ${choice.bitrate}K"
    }

@Composable
private fun QualityRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(RowHeight)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = HorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize),
        )
        Spacer(Modifier.width(IconLabelGap))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier.size(IconSize),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
