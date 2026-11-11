package io.github.aedev.flow.ui.components.shared.quickactions

import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.cobalt.CobaltDownloadSheet
import io.github.aedev.flow.ui.cobalt.CobaltDownloadViewModel
import io.github.aedev.flow.ui.components.shared.MediaDownloadSheet
import kotlinx.coroutines.flow.collectLatest

/**
 * What the video menus hand off to the shell: the native download sheet, the cobalt sheet for a
 * link Flow has no page for, and each action's confirmation on the app's snackbar with its undo.
 * Call once, in the shell.
 */
@Composable
fun QuickActionsHost(
    snackbarHostState: SnackbarHostState,
    viewModel: QuickActionsViewModel = sharedQuickActionsViewModel(),
) {
    val context = LocalContext.current
    val pending by viewModel.pendingDownload.collectAsStateWithLifecycle()
    val pendingCobaltUrl by viewModel.pendingCobaltUrl.collectAsStateWithLifecycle()

    pendingCobaltUrl?.let { url ->
        val cobaltViewModel: CobaltDownloadViewModel = hiltViewModel()
        LaunchedEffect(url) { cobaltViewModel.open(url) }
        val cobaltState by cobaltViewModel.state.collectAsStateWithLifecycle()
        CobaltDownloadSheet(
            state = cobaltState,
            onChoose = { choice ->
                cobaltViewModel.choose(choice) { title -> viewModel.announce(R.string.cobalt_download_started, title) }
                viewModel.dismissCobaltDownload()
            },
            onEngine = cobaltViewModel::selectEngine,
            onDismiss = viewModel::dismissCobaltDownload,
        )
    }

    pending?.let { options ->
        MediaDownloadSheet(
            streamSizes = options.streamSizes,
            innerTubeVideoFormats = options.videoFormats,
            innerTubeAudioFormats = options.audioFormats,
            video = options.video,
            subtitles = options.subtitles,
            onDismiss = viewModel::dismissDownload,
        )
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { }

    LaunchedEffect(snackbarHostState, viewModel) {
        viewModel.messages.collectLatest { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            val text =
                message.plainText
                    ?: message.arg?.let { context.getString(message.text, it) }
                    ?: context.getString(message.text)
            val undo = message.undo
            val result =
                snackbarHostState.showSnackbar(
                    message = text,
                    actionLabel = undo?.let { context.getString(R.string.undo) },
                    duration = SnackbarDuration.Short,
                )
            if (undo != null && result == SnackbarResult.ActionPerformed) {
                if (undo is QuickActionUndo.RestoreFromTrash) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val uris = undo.contentUris.map(Uri::parse)
                        val request = MediaStore.createTrashRequest(context.contentResolver, uris, false).intentSender
                        restoreLauncher.launch(IntentSenderRequest.Builder(request).build())
                    }
                } else {
                    viewModel.undo(undo)
                }
            }
        }
    }
}
