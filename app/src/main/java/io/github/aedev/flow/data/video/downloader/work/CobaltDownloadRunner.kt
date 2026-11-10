package io.github.aedev.flow.data.video.downloader.work

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.cobalt.CobaltClient
import io.github.aedev.flow.data.cobalt.CobaltResult
import io.github.aedev.flow.data.cobalt.CobaltStream
import io.github.aedev.flow.data.cobalt.toCobaltRequest
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.video.DownloadProgressUpdate
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.downloader.request.CobaltSelection
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.data.video.downloader.tags.CoverArtLoader
import io.github.aedev.flow.data.video.downloader.tags.Mp4TagWriter
import io.github.aedev.flow.data.video.storage.DownloadCovers
import io.github.aedev.flow.data.video.storage.DownloadFiles
import io.github.aedev.flow.data.video.storage.DownloadPlacement
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one cobalt download: it resolves the source through the instance, streams the single file it
 * hands back into staging, tags it and places it. cobalt does the merging, so there is nothing to
 * remux and no per-stream resume — a stopped download starts its transfer over, keeping its request.
 */
@Singleton
class CobaltDownloadRunner
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val downloadDao: DownloadDao,
        private val downloads: VideoDownloadManager,
        private val cobalt: CobaltClient,
        private val tagWriter: Mp4TagWriter,
        private val coverArt: CoverArtLoader,
        private val placement: DownloadPlacement,
        private val notifier: DownloadNotifier,
    ) {
        suspend fun run(videoId: String) {
            val row = downloadDao.getDownloadWithItems(videoId) ?: return
            val item = row.items.firstOrNull() ?: return
            val request = DownloadRequest.decode(row.download.requestJson) ?: return
            val selection = request.cobalt ?: return
            val staging = DownloadStaging(downloads.stagingDirectory(), videoId)
            downloadDao.updateAllItemsStatus(videoId, DownloadItemStatus.DOWNLOADING)
            notifier.show(videoId, request.tags.title, DownloadPhase.Transferring(0L, 0L))
            try {
                when (val resolved = cobalt.resolve(selection.toCobaltRequest(request.wantsAudioOnly))) {
                    is CobaltResult.Unavailable -> fail(request, staging, messageFor(resolved.code), keepParts = false)
                    is CobaltResult.Ready -> fetch(request, staging, item.id, resolved.url)
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { onStopped(videoId, request, staging) }
                throw e
            }
        }

        private suspend fun fetch(
            request: DownloadRequest,
            staging: DownloadStaging,
            itemId: Int,
            url: String,
        ) = coroutineScope {
            val cover = async { coverArt.load(request.tags.thumbnailUrl, request.kind) }
            when (val stream = cobalt.open(url)) {
                is CobaltStream.Unavailable -> {
                    cover.cancel()
                    fail(request, staging, messageFor(stream.code), keepParts = false)
                }

                is CobaltStream.Ready -> {
                    val extension = extensionFor(stream.fileName, request)
                    val output = staging.output(extension)
                    val written = streamToFile(request, staging, itemId, stream, output)
                    if (written != null) {
                        finish(request, staging, itemId, output, extension, written, cover.await())
                    }
                }
            }
        }

        /** Streams [stream]'s whole body into [output]. Null once the transfer failed or was stopped. */
        private suspend fun streamToFile(
            request: DownloadRequest,
            staging: DownloadStaging,
            itemId: Int,
            stream: CobaltStream.Ready,
            output: File,
        ): Long? =
            try {
                stream.response.use { response ->
                    response.body.byteStream().use { input ->
                        writeTo(request, staging, itemId, input, output, stream.contentLength)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "${request.videoId}: cobalt transfer failed", e)
                fail(request, staging, context.getString(R.string.download_failed_try_again), keepParts = false)
                null
            }

        /** Streams the whole body into [output]. Null once the run was stopped or the write failed. */
        private suspend fun writeTo(
            request: DownloadRequest,
            staging: DownloadStaging,
            itemId: Int,
            input: java.io.InputStream,
            output: File,
            contentLength: Long,
        ): Long? {
            notifier.show(request.videoId, request.tags.title, DownloadPhase.Finishing)
            return withContext(Dispatchers.IO) {
                output.parentFile?.mkdirs()
                var written = 0L
                var lastEmit = 0L
                val buffer = ByteArray(BUFFER_SIZE)
                output.outputStream().use { sink ->
                    while (true) {
                        if (!currentCoroutineContext().isActive) return@withContext null
                        val read = input.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        written += read
                        if (written - lastEmit >= PROGRESS_STEP_BYTES) {
                            lastEmit = written
                            emit(request, itemId, written, contentLength)
                        }
                    }
                }
                emit(request, itemId, written, written)
                written.takeIf { it > 0L }
            }
        }

        private fun emit(
            request: DownloadRequest,
            itemId: Int,
            done: Long,
            total: Long,
        ) {
            downloads.emitProgress(DownloadProgressUpdate(request.videoId, itemId, done, total, DownloadItemStatus.DOWNLOADING))
            notifier.show(request.videoId, request.tags.title, DownloadPhase.Transferring(done, total))
        }

        private suspend fun finish(
            request: DownloadRequest,
            staging: DownloadStaging,
            itemId: Int,
            output: File,
            extension: String,
            size: Long,
            cover: ByteArray?,
        ) {
            notifier.show(request.videoId, request.tags.title, DownloadPhase.Finishing)
            try {
                tagWriter.write(output, request.tags, cover)
            } catch (e: IOException) {
                Log.w(TAG, "${request.videoId}: tags not written", e)
            }
            currentCoroutineContext().ensureActive()
            val placed = placement.place(output, request, extension)
            if (placed == null) {
                fail(request, staging, context.getString(R.string.download_failed_save), keepParts = true)
                return
            }
            withContext(NonCancellable) {
                val path = placed.path
                val mimeType = mimeTypeFor(extension)
                downloadDao.completeItem(itemId, path, placed.fileName, extension, mimeType, qualityLabel(request.cobalt), size)
                cover
                    ?.let {
                        DownloadCovers.save(
                            context,
                            request.videoId,
                            it,
                        )
                    }?.let { downloadDao.updateThumbnailPath(request.videoId, it) }
                if (!DownloadFiles.isDocument(path)) downloads.scanFile(path, mimeType)
                downloads.emitProgress(DownloadProgressUpdate(request.videoId, itemId, size, size, DownloadItemStatus.COMPLETED))
                notifier.show(request.videoId, request.tags.title, DownloadPhase.Complete(placed.fellBackTo))
                staging.clear()
            }
        }

        private suspend fun fail(
            request: DownloadRequest,
            staging: DownloadStaging,
            message: String,
            keepParts: Boolean,
        ) {
            withContext(NonCancellable) {
                if (keepParts) staging.clearOutputs() else staging.clear()
                downloadDao.updateAllItemsStatus(request.videoId, DownloadItemStatus.FAILED)
                notifier.show(request.videoId, request.tags.title, DownloadPhase.Failed(message))
            }
        }

        private suspend fun onStopped(
            videoId: String,
            request: DownloadRequest,
            staging: DownloadStaging,
        ) {
            when (stopActionFor(downloadDao.getDownloadWithItems(videoId)?.overallStatus)) {
                StopAction.DISCARD -> {
                    staging.clear()
                    notifier.dismiss(videoId)
                }

                StopAction.KEEP_PAUSED -> {
                    notifier.show(videoId, request.tags.title, DownloadPhase.Paused)
                }

                StopAction.NOTHING -> {
                    Unit
                }

                StopAction.WAIT -> {
                    downloadDao.updateAllItemsStatus(videoId, DownloadItemStatus.PENDING)
                    notifier.show(videoId, request.tags.title, DownloadPhase.Queued)
                }
            }
        }

        /** cobalt's error code, mapped to the closest wording the app already has for a failure. */
        private fun messageFor(code: String): String =
            context.getString(
                when {
                    code.contains("fetch.empty") || code.contains("content") -> R.string.download_failed_unavailable
                    code.contains("link.unsupported") || code.contains("service") -> R.string.link_not_supported
                    code.contains("network") || code.contains("http.5") -> R.string.download_failed_try_again
                    code.contains("rate") || code.contains("http.429") -> R.string.download_failed_refused
                    else -> R.string.download_failed_try_again
                },
            )

        private fun qualityLabel(selection: CobaltSelection?): String =
            selection?.videoQuality?.takeIf { it.isNotBlank() } ?: context.getString(R.string.download_quality_best)

        private companion object {
            const val TAG = "CobaltDownloadRunner"
            const val BUFFER_SIZE = 256 * 1024
            const val PROGRESS_STEP_BYTES = 256 * 1024L
        }
    }

/** cobalt hands back `mp4` for video and `m4a`/`mp3` for audio; the request decides when it is unclear. */
internal fun extensionFor(
    fileName: String?,
    request: DownloadRequest,
): String {
    val fromName = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
    if (fromName != null) return fromName
    return if (request.wantsAudioOnly) "m4a" else "mp4"
}

internal fun mimeTypeFor(extension: String): String =
    when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "video/mp4"
    }
