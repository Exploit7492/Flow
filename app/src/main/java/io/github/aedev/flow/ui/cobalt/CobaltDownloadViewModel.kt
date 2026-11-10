package io.github.aedev.flow.ui.cobalt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.cobalt.CobaltAudio
import io.github.aedev.flow.data.repository.YouTubeRepository
import io.github.aedev.flow.data.video.downloader.request.CobaltSelection
import io.github.aedev.flow.data.video.downloader.request.toDownloadRequest
import io.github.aedev.flow.data.video.downloader.work.DownloadController
import io.github.aedev.flow.utils.YouTubeLink
import io.github.aedev.flow.utils.parseYouTubeLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import javax.inject.Inject

/** What the cobalt download sheet shows: the link, its details once known, and the current choice. */
data class CobaltDownloadUiState(
    val url: String = "",
    val title: String = "",
    val channelName: String? = null,
    val thumbnailUrl: String? = null,
    val durationSeconds: Int = 0,
    val isLoading: Boolean = true,
    /** A height cobalt takes, or null for its best. */
    val selectedQuality: String? = null,
    val audioOnly: Boolean = false,
    val audioFormat: String = CobaltAudio.BEST,
    val isStarting: Boolean = false,
)

/**
 * Backs the download sheet for a link cobalt resolves. It reads what it can about the link, holds
 * the quality choice, and queues the download through the same controller the YouTube path uses.
 */
@HiltViewModel
class CobaltDownloadViewModel
    @Inject
    constructor(
        private val repository: YouTubeRepository,
        private val downloadController: DownloadController,
    ) : ViewModel() {
        private val _state = MutableStateFlow(CobaltDownloadUiState())
        val state: StateFlow<CobaltDownloadUiState> = _state.asStateFlow()

        /** Starts a new sheet for [url], reading the link's details when Flow already knows the source. */
        fun open(url: String) {
            _state.value = CobaltDownloadUiState(url = url)
            viewModelScope.launch {
                val details = detailsFor(url)
                _state.update {
                    it.copy(
                        title = details.title.ifBlank { hostOf(url) ?: url },
                        channelName = details.channelName,
                        thumbnailUrl = details.thumbnailUrl,
                        durationSeconds = details.durationSeconds,
                        isLoading = false,
                    )
                }
            }
        }

        fun selectQuality(quality: String?) = _state.update { it.copy(selectedQuality = quality) }

        fun setAudioOnly(audioOnly: Boolean) = _state.update { it.copy(audioOnly = audioOnly) }

        fun selectAudioFormat(format: String) = _state.update { it.copy(audioFormat = format) }

        /** Queues the download with the current choice, then hands the title to the caller's confirmation. */
        fun start(onStarted: (String) -> Unit) {
            val current = _state.value
            if (current.isStarting) return
            _state.update { it.copy(isStarting = true) }
            val title = current.title.ifBlank { hostOf(current.url) ?: current.url }
            val selection =
                CobaltSelection(
                    url = current.url,
                    videoQuality = current.selectedQuality,
                    audioFormat = current.audioFormat.takeIf { current.audioOnly },
                )
            downloadController.submit(
                request =
                    selection.toDownloadRequest(
                        title = title,
                        thumbnailUrl = current.thumbnailUrl,
                        channelName = current.channelName,
                        durationSeconds = current.durationSeconds,
                        audioOnly = current.audioOnly,
                    ),
                replaceExisting = true,
            )
            onStarted(title)
        }

        /** The title, channel and cover for a link: YouTube's when it is one, the URL's host otherwise. */
        private suspend fun detailsFor(url: String): Details {
            val link = parseYouTubeLink(url)
            if (link is YouTubeLink.Video) {
                val video = runCatching { repository.getVideo(link.id) }.getOrNull()
                if (video != null) {
                    return Details(
                        title = video.title,
                        channelName = video.channelName.takeIf { name -> name.isNotBlank() },
                        thumbnailUrl = video.thumbnailUrl.takeIf { thumb -> thumb.isNotBlank() },
                        durationSeconds = video.duration,
                    )
                }
            }
            return Details(title = hostOf(url) ?: url)
        }

        private data class Details(
            val title: String,
            val channelName: String? = null,
            val thumbnailUrl: String? = null,
            val durationSeconds: Int = 0,
        )

        private companion object {
            fun hostOf(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()
        }
    }
