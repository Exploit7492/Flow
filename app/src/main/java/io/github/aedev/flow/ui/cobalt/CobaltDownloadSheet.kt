package io.github.aedev.flow.ui.cobalt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import io.github.aedev.flow.data.video.downloader.request.DownloadEngine
import io.github.aedev.flow.ui.components.shared.FlowFilterChip
import io.github.aedev.flow.ui.components.shared.FlowModalSheetDefaults
import io.github.aedev.flow.ui.components.shared.rememberFlowSheetState

private val HorizontalPadding = 20.dp
private val SectionSpacing = 20.dp
private val ChipSpacing = 8.dp
private val HeaderSpacing = 8.dp
private val HeaderIconSize = 18.dp
private val MaxGridHeight = 460.dp

/** The video heights the grid shows before "More": the common ladder, tallest first. */
private val CommonHeights = CobaltQualities.HEIGHTS.drop(2)

/** The two tallest heights, behind "More". */
private val MoreHeights = CobaltQualities.HEIGHTS.take(2)

/**
 * The quality sheet for a link cobalt resolves. The choices are two labelled grids of chips — the
 * video heights with cobalt's own "Best", then the sound ladder — that wrap into rows, so the whole
 * ladder reads at a glance instead of as one long stacked list. Tapping a chip starts the download
 * with that choice, the way a downloader's own picker does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CobaltDownloadSheet(
    state: CobaltDownloadUiState,
    onChoose: (CobaltDownloadChoice) -> Unit,
    onEngine: (DownloadEngine) -> Unit,
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

            QualityGrid(state, onChoose, onEngine)
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
private fun QualityGrid(
    state: CobaltDownloadUiState,
    onChoose: (CobaltDownloadChoice) -> Unit,
    onEngine: (DownloadEngine) -> Unit,
) {
    var showMore by remember { mutableStateOf(false) }
    val selected = state.selection
    val enabled = !state.isStarting
    val heights = if (showMore) CommonHeights + MoreHeights else CommonHeights
    LazyColumn(
        modifier = Modifier.heightIn(max = MaxGridHeight),
        verticalArrangement = Arrangement.spacedBy(SectionSpacing),
        contentPadding = PaddingValues(bottom = 8.dp),
    ) {
        item(key = "video") {
            QualitySection(
                title = stringResource(R.string.video),
                icon = Icons.Rounded.VideoLibrary,
            ) {
                heights.forEach { quality ->
                    QualityChip(
                        label = quality + "p",
                        selected = selected == CobaltDownloadChoice.Video(quality),
                        enabled = enabled,
                        onClick = { onChoose(CobaltDownloadChoice.Video(quality)) },
                    )
                }
                if (!showMore) {
                    QualityChip(
                        label = stringResource(R.string.download_quality_more),
                        selected = false,
                        enabled = enabled,
                        onClick = { showMore = true },
                    )
                }
                QualityChip(
                    label = stringResource(R.string.download_quality_best),
                    selected = selected == CobaltDownloadChoice.Video(null),
                    enabled = enabled,
                    onClick = { onChoose(CobaltDownloadChoice.Video(null)) },
                )
            }
        }
        item(key = "audio") {
            QualitySection(
                title = stringResource(R.string.download_audio),
                icon = Icons.Rounded.MusicNote,
            ) {
                CobaltAudio.LADDER.forEach { choice ->
                    QualityChip(
                        label = audioLabel(choice),
                        selected = selected == CobaltDownloadChoice.Audio(choice.format, choice.bitrate),
                        enabled = enabled,
                        onClick = { onChoose(CobaltDownloadChoice.Audio(choice.format, choice.bitrate)) },
                    )
                }
            }
        }
        item(key = "engine") {
            QualitySection(
                title = stringResource(R.string.download_engine),
                icon = Icons.Rounded.Memory,
            ) {
                ENGINE_CHOICES.forEach { engine ->
                    QualityChip(
                        label = engineLabel(engine),
                        selected = state.engine == engine,
                        enabled = enabled,
                        onClick = { onEngine(engine) },
                    )
                }
            }
        }
    }
}

/**
 * The muxers the sheet offers: cobalt's own output, the app's Kotlin remuxer, and FFmpeg. All three
 * are always offered; the runner uses the FFmpeg binary when one is bundled and the Kotlin remuxer
 * otherwise, so a choice always finishes the file.
 */
private val ENGINE_CHOICES = listOf(DownloadEngine.COBALT, DownloadEngine.NATIVE, DownloadEngine.FFMPEG)

@Composable
private fun engineLabel(engine: DownloadEngine): String =
    stringResource(
        when (engine) {
            DownloadEngine.COBALT -> R.string.download_engine_cobalt
            DownloadEngine.NATIVE -> R.string.download_engine_native
            DownloadEngine.FFMPEG -> R.string.download_engine_ffmpeg
        },
    )

@Composable
private fun QualitySection(
    title: String,
    icon: ImageVector,
    content: @Composable FlowRowScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(HeaderSpacing),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HeaderSpacing),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(HeaderIconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        QualityChips(content = content)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QualityChips(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
        verticalArrangement = Arrangement.spacedBy(ChipSpacing),
        content = content,
    )
}

@Composable
private fun QualityChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FlowFilterChip(
        label = label,
        selected = selected,
        onClick = { if (enabled) onClick() },
    )
}

@Composable
private fun audioLabel(choice: CobaltAudio.Choice): String =
    when {
        choice.format == CobaltAudio.BEST -> stringResource(R.string.download_quality_best)
        choice.bitrate == null -> choice.format.uppercase()
        else -> "${choice.format.uppercase()} ${choice.bitrate}K"
    }
