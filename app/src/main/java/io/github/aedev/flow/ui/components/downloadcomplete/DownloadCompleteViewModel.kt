package io.github.aedev.flow.ui.components.downloadcomplete

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.video.VideoDownloadManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A download that has just finished, with what the heads-up card shows. */
data class DownloadComplete(
    val videoId: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
)

/**
 * Watches the download table and reports each download that becomes finished while the app is
 * running. What was already finished when the app started is seeded silently, so opening the app
 * never replays a card for an old download.
 */
@HiltViewModel
class DownloadCompleteViewModel
    @Inject
    constructor(
        videoDownloadManager: VideoDownloadManager,
    ) : ViewModel() {
        private val _events = MutableSharedFlow<DownloadComplete>(extraBufferCapacity = 8)
        val events: SharedFlow<DownloadComplete> = _events.asSharedFlow()

        init {
            viewModelScope.launch {
                var previouslyCompleted = emptySet<String>()
                var seeded = false
                videoDownloadManager.allDownloads.collect { downloads ->
                    val completed = downloads.filter { it.overallStatus == DownloadItemStatus.COMPLETED }
                    if (seeded) {
                        completed
                            .filter { it.download.videoId !in previouslyCompleted }
                            .forEach { _events.tryEmit(it.toEvent()) }
                    }
                    seeded = true
                    previouslyCompleted = completed.map { it.download.videoId }.toSet()
                }
            }
        }

        private fun DownloadWithItems.toEvent(): DownloadComplete =
            DownloadComplete(
                videoId = download.videoId,
                title = download.title,
                uploader = download.uploader,
                thumbnailUrl = download.thumbnailUrl,
            )
    }
