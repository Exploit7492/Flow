package io.github.aedev.flow.data.video.downloader.tags

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultMuxer
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Muxes one audio track into a plain MP4 with Media3's [Transformer].
 *
 * [Mp4Remuxer] covers the common case by copying the samples straight across, which is lossless and
 * fast but only accepts AAC. A track that is not AAC — the Opus YouTube serves in WebM — has to be
 * decoded and re-encoded instead, and that is what the Transformer is for. The result is AAC in MP4
 * for the same reason: Media3 mis-reads Opus pre-skip in MP4 (androidx/media#3431).
 */
@OptIn(UnstableApi::class)
@Singleton
class AudioMp4Muxer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * Rewrites [inputPath]'s audio into [outputPath] as AAC in MP4. Never throws: a failure is a
         * result the caller reports, so a broken export leaves the download in its staging area
         * rather than half-written where it was going.
         */
        suspend fun mux(
            inputPath: String,
            outputPath: String,
            isCancelled: () -> Boolean = { false },
        ): Result {
            if (!File(inputPath).isFile) return Result.Failure(Reason.INPUT_MISSING)
            if (isCancelled()) return Result.Failure(Reason.CANCELLED)
            // The Transformer runs on the thread it was started from and needs that thread's
            // Looper, so the export is driven from the main looper rather than an IO dispatcher.
            return withContext(Dispatchers.Main) {
                File(outputPath).parentFile?.mkdirs()

                val editedItem =
                    EditedMediaItem
                        .Builder(MediaItem.fromUri(File(inputPath).toURI().toString()))
                        .setRemoveVideo(true)
                        .build()
                val request =
                    Transformer
                        .Builder(context)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setMuxerFactory(DefaultMuxer.Factory())
                        .build()

                val outcome =
                    suspendCancellableCoroutine { continuation ->
                        val listener =
                            object : Transformer.Listener {
                                override fun onCompleted(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                ) {
                                    if (continuation.isActive) continuation.resume(Result.Success)
                                }

                                override fun onError(
                                    composition: Composition,
                                    exportResult: ExportResult,
                                    exportException: ExportException,
                                ) {
                                    Log.w(TAG, "Audio mux failed: ${exportException.message}")
                                    if (continuation.isActive) continuation.resume(Result.Failure(Reason.MUX_FAILED))
                                }
                            }
                        request.addListener(listener)
                        continuation.invokeOnCancellation { request.cancel() }
                        request.start(editedItem, outputPath)
                    }

                if (outcome is Result.Success && isCancelled()) {
                    File(outputPath).delete()
                    return@withContext Result.Failure(Reason.CANCELLED)
                }
                if (outcome is Result.Failure) File(outputPath).delete()
                outcome
            }
        }

        sealed interface Result {
            data object Success : Result

            data class Failure(
                val reason: Reason,
            ) : Result
        }

        enum class Reason { INPUT_MISSING, CANCELLED, MUX_FAILED }

        private companion object {
            const val TAG = "AudioMp4Muxer"
        }
    }
