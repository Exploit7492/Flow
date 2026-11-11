package io.github.aedev.flow.ui.components.downloadcomplete

import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.components.shared.MediaThumbnail
import kotlinx.coroutines.delay

private val CardHorizontalPadding = 16.dp
private val CardThumbnailWidth = 96.dp
private const val AUTO_DISMISS_MS = 5_000L

/**
 * The heads-up card a finished download drops in from the top, in the style of YouTube's new-video
 * card: the thumbnail, "Download complete" and the title, sliding down and auto-dismissing. Tapping
 * it opens the downloads list.
 *
 * [enabled] keeps it away from onboarding and picture-in-picture. The card is only ever shown for a
 * download that finishes while the app is running — see [DownloadCompleteViewModel].
 */
@Composable
fun DownloadCompleteHost(
    enabled: Boolean,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val viewModel: DownloadCompleteViewModel = hiltViewModel(activity)

    var current by remember { mutableStateOf<DownloadComplete?>(null) }
    var visible by remember { mutableStateOf(false) }
    val canShow by rememberUpdatedState(enabled)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (canShow) {
                current = event
                visible = true
            }
        }
    }

    val shown = current
    LaunchedEffect(shown, visible) {
        if (visible && shown != null) {
            delay(AUTO_DISMISS_MS)
            visible = false
        }
    }

    AnimatedVisibility(
        visible = visible && shown != null && enabled,
        enter =
            slideInVertically(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                initialOffsetY = { -it },
            ) + fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
        exit =
            slideOutVertically(
                animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                targetOffsetY = { -it },
            ) + fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        modifier = modifier,
    ) {
        shown?.let { event ->
            DownloadCompleteCard(
                event = event,
                onClick = {
                    visible = false
                    onOpenDownloads()
                },
                onDismiss = { visible = false },
            )
        }
    }
}

@Composable
private fun DownloadCompleteCard(
    event: DownloadComplete,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = CardHorizontalPadding, vertical = 8.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MediaThumbnail(
                videoId = event.videoId,
                thumbnailUrl = event.thumbnailUrl,
                width = CardThumbnailWidth,
                shape = MaterialTheme.shapes.small,
                placeholder = Icons.Rounded.VideoLibrary,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.notification_download_complete),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (event.uploader.isNotBlank()) {
                    Text(
                        text = event.uploader,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.close))
            }
        }
    }
}
