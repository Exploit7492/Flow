package io.github.aedev.flow.ui.components.shared

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.video.DownloadStreamPolicy
import io.github.aedev.flow.data.video.downloader.request.DownloadSubtitle
import io.github.aedev.flow.innertube.models.response.PlayerResponse
import io.github.aedev.flow.player.state.SubtitleOption
import io.github.aedev.flow.player.stream.VideoCodecUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val HorizontalPadding = 20.dp
private val SectionSpacing = 20.dp
private val ChipSpacing = 8.dp
private val HeaderIconSize = 18.dp
private val MaxSheetBodyHeight = 520.dp
private const val QUALITY_COLUMNS = 2
private const val MIN_THREADS = 1
private const val MAX_THREADS = 8

// The sheet closes as its download starts, so the choices it remembers are saved outside its composition.
private val downloadChoiceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The one download sheet: a slide-up glass panel of labelled quality grids, one grid per kind of
 * stream — the video heights with their codecs, then the sound ladder — plus the subtitle and
 * connection choices above them. Tapping a tile starts that download at once, the way a
 * downloader's own picker does, so the whole ladder reads at a glance and one tap is the whole
 * interaction.
 *
 * It is the native path only: every choice is an InnerTube stream the app fetches and muxes itself,
 * so there is no link to resolve and no server between the tap and the file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaDownloadSheet(
    streamSizes: Map<String, Long>,
    innerTubeVideoFormats: List<PlayerResponse.StreamingData.Format> = emptyList(),
    innerTubeAudioFormats: List<PlayerResponse.StreamingData.Format> = emptyList(),
    video: Video,
    subtitles: List<SubtitleOption> = emptyList(),
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember(context) { PlayerPreferences(context) }
    val preferredLang by prefs.preferredAudioLanguage.collectAsState(initial = "")
    val defaultThreads by prefs.downloadThreads.collectAsState(initial = 3)
    val lastSubtitleLanguage by prefs.lastDownloadSubtitleLanguage.collectAsState(initial = null)

    var threads by remember(defaultThreads) { mutableIntStateOf(defaultThreads.coerceIn(MIN_THREADS, MAX_THREADS)) }
    val subtitleOptions = remember(subtitles) { downloadableSubtitles(subtitles) }
    var subtitleChoice by remember(subtitleOptions, lastSubtitleLanguage) {
        mutableStateOf(defaultDownloadSubtitle(subtitleOptions, lastSubtitleLanguage))
    }

    val videoFormats =
        remember(innerTubeVideoFormats) { DownloadStreamPolicy.buildDownloadVideoFormats(innerTubeVideoFormats) }
    val audioFormats =
        remember(innerTubeAudioFormats) { DownloadStreamPolicy.buildDownloadAudioFormats(innerTubeAudioFormats) }
    val videoByHeight =
        remember(videoFormats) {
            videoFormats
                .groupBy(DownloadStreamPolicy::videoHeight)
                .toSortedMap(compareByDescending { it })
        }
    val hdrLabel = stringResource(R.string.download_quality_hdr)
    val separator = stringResource(R.string.list_separator_dot)

    fun subtitle(): DownloadSubtitle? = subtitleChoice?.toDownloadSubtitle() ?: DownloadSubtitle.OFF.takeIf { subtitleOptions.isNotEmpty() }

    fun rememberChoices() {
        downloadChoiceScope.launch { prefs.setDownloadThreads(threads) }
        if (subtitleOptions.isNotEmpty()) rememberDownloadSubtitleChoice(prefs, subtitleChoice)
    }

    fun downloadVideo(format: PlayerResponse.StreamingData.Format) {
        val audio = DownloadStreamPolicy.pickAacAudio(audioFormats, preferredLang)
        if (audio == null) {
            Toast.makeText(context, context.getString(R.string.download_no_compatible_audio), Toast.LENGTH_LONG).show()
            return
        }
        DownloadLauncher.startVideoDownload(context, video, format, audio, threads, subtitle())
        rememberChoices()
        onDismiss()
    }

    fun downloadAudio(format: PlayerResponse.StreamingData.Format) {
        if (!DownloadLauncher.startAudioOnlyDownload(context, video, format, threads)) return
        rememberChoices()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberFlowSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = FlowModalSheetDefaults.modifier,
        contentWindowInsets = FlowModalSheetDefaults.contentWindowInsets,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.35f),
                                MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        ),
                    ),
        ) {
            SheetHeader(video)

            LazyColumn(
                modifier = Modifier.heightIn(max = MaxSheetBodyHeight),
                verticalArrangement = Arrangement.spacedBy(SectionSpacing),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                if (videoByHeight.isEmpty() && audioFormats.isEmpty()) {
                    item {
                        NoStreams(onFallback = {
                            onDismiss()
                            DownloadLauncher.startDefaultDownload(context, video)
                        })
                    }
                    return@LazyColumn
                }

                if (subtitleOptions.isNotEmpty()) {
                    item(key = "subtitles") {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = HorizontalPadding),
                        ) {
                            DownloadSubtitleRow(
                                options = subtitleOptions,
                                selected = subtitleChoice,
                                onSelect = { subtitleChoice = it },
                            )
                        }
                    }
                }

                item(key = "threads") {
                    ThreadsRow(
                        threads = threads,
                        onDecrease = { threads = (threads - 1).coerceAtLeast(MIN_THREADS) },
                        onIncrease = { threads = (threads + 1).coerceAtMost(MAX_THREADS) },
                    )
                }

                if (videoByHeight.isNotEmpty()) {
                    item(key = "video") {
                        QualitySection(
                            title = stringResource(R.string.video),
                            icon = Icons.Rounded.VideoLibrary,
                            options =
                                videoByHeight.map { (height, formats) ->
                                    val format = formats.first()
                                    videoQualityOption(height, format, streamSizes, hdrLabel, separator) {
                                        downloadVideo(format)
                                    }
                                },
                        )
                    }
                }

                if (audioFormats.isNotEmpty()) {
                    item(key = "audio") {
                        QualitySection(
                            title = stringResource(R.string.download_audio),
                            icon = Icons.Rounded.MusicNote,
                            options = audioFormats.map { format -> audioQualityOption(format, separator) { downloadAudio(format) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(video: Video) {
    Column(modifier = Modifier.padding(horizontal = HorizontalPadding, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.download_sheet_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = video.title.ifBlank { stringResource(R.string.download_sheet_subtitle) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun NoStreams(onFallback: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = HorizontalPadding)) {
        Text(
            text = stringResource(R.string.no_download_streams),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onFallback) { Text(stringResource(R.string.ui_try_sabr_download)) }
    }
}

@Composable
private fun ThreadsRow(
    threads: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.download_threads_label),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(onClick = onDecrease, enabled = threads > MIN_THREADS) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.download_threads_decrease))
        }
        Text(
            text = threads.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        FilledTonalIconButton(onClick = onIncrease, enabled = threads < MAX_THREADS) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.download_threads_increase))
        }
    }
}

/** One cell of a quality grid: the quality on its own line, whatever details the stream carries under it. */
private data class QualityOption(
    val primary: String,
    val secondary: String?,
    val onClick: () -> Unit,
)

@Composable
private fun QualitySection(
    title: String,
    icon: ImageVector,
    options: List<QualityOption>,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = HorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(ChipSpacing),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
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
        QualityGrid(options = options)
    }
}

/** The two-column grid every quality ladder is laid out in; a long ladder simply grows more rows. */
@Composable
private fun QualityGrid(options: List<QualityOption>) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ChipSpacing),
    ) {
        options.chunked(QUALITY_COLUMNS).forEach { rowOptions ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(ChipSpacing),
            ) {
                rowOptions.forEach { option ->
                    QualityTile(
                        option = option,
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                    )
                }
                // Keeps a lone last tile the same width as every other, so the grid never has a full-width orphan.
                repeat(QUALITY_COLUMNS - rowOptions.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun QualityTile(
    option: QualityOption,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = option.onClick,
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = option.primary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (option.secondary != null) {
                Text(
                    text = option.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "1080p60" over "VP9 · HDR · ~120 MB", each line trimmed of whatever the stream does not offer. */
@Composable
private fun videoQualityOption(
    height: Int,
    format: PlayerResponse.StreamingData.Format,
    streamSizes: Map<String, Long>,
    hdrLabel: String,
    separator: String,
    onClick: () -> Unit,
): QualityOption {
    val codec = VideoCodecUtils.codecLabelFromKey(DownloadStreamPolicy.videoCodecKey(format))
    val size = approxDownloadSizeLabel(streamSizes[VideoCodecUtils.streamSizeKey(height, DownloadStreamPolicy.videoCodecKey(format))])
    val hdr = hdrLabel.takeIf { DownloadStreamPolicy.isHdr(format) }
    val detail = listOfNotNull(codec, hdr, size).joinToString(separator)
    return QualityOption(
        primary = VideoCodecUtils.qualityLabelWithFrameRate(height, format.fps ?: 0),
        secondary = detail.ifBlank { null },
        onClick = onClick,
    )
}

/** "M4A · 128 kbps" over the language, trimmed of whatever the stream does not offer. */
@Composable
private fun audioQualityOption(
    format: PlayerResponse.StreamingData.Format,
    separator: String,
    onClick: () -> Unit,
): QualityOption {
    val unknown = stringResource(R.string.audio_format_unknown)
    val kbps = stringResource(R.string.kbps)
    val audioFormat = DownloadStreamPolicy.audioFormatLabel(format, unknown)
    val bitrate = "${DownloadStreamPolicy.audioBitrateKbps(format)}$kbps"
    return QualityOption(
        primary = listOf(audioFormat, bitrate).joinToString(separator),
        secondary = DownloadStreamPolicy.audioLanguageLabel(format),
        onClick = onClick,
    )
}
