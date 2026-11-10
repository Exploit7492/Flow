package io.github.aedev.flow.data.video.downloader.tags

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import javax.inject.Inject

/**
 * Copies YouTube DASH streams (fragmented MP4 or WebM) into one plain MP4 with the `moov` box at
 * the end, interleaving samples by timestamp and embedding the Flow ids as `mdta` entries.
 * Accepts H.264, HEVC, VP9 and AV1 video and AAC audio only; Opus/Vorbis in MP4 is refused
 * (Media3 mis-reads Opus pre-skip in MP4, androidx/media#3431), so callers must pick AAC.
 */
@OptIn(UnstableApi::class)
class Mp4Remuxer
    @Inject
    constructor() {
        sealed interface Result {
            data class Success(
                val containerMimeType: String,
                val videoMimeType: String?,
                val audioMimeType: String,
            ) : Result

            data class Failure(
                val reason: Reason,
                val message: String,
            ) : Result
        }

        enum class Reason { INPUT_MISSING, NO_TRACK, UNSUPPORTED_AUDIO, UNSUPPORTED_VIDEO, CANCELLED, MUX_FAILED }

        private class Input(
            val extractor: MediaExtractor,
            val format: MediaFormat,
            val mime: String,
            val isVideo: Boolean,
        ) {
            var muxerTrack = C.INDEX_UNSET
            var done = false
            val durationUs: Long =
                if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else C.TIME_UNSET
        }

        private class Rejection(
            val failure: Result.Failure,
        ) : Exception(failure.message)

        fun remux(
            videoPath: String?,
            audioPath: String,
            outputPath: String,
            tags: DownloadTags? = null,
            isCancelled: () -> Boolean = { false },
            onProgress: ((Float) -> Unit)? = null,
        ): Result =
            writeOutput(outputPath, tags, isCancelled, onProgress) {
                buildList {
                    videoPath?.let { add(open(it, isVideo = true)) }
                    add(open(audioPath, isVideo = false))
                }
            }

        /**
         * Rewrites one already-muxed file into a plain progressive MP4 with its `moov` at the end.
         *
         * A cobalt download, and anything else that arrives as a fragmented MP4, carries `moof`/`mdat`
         * runs and a trailing `mfra` index whose entries are absolute offsets. Tagging such a file in
         * place moves the `moov` and invalidates every one of those offsets, so the file is flattened
         * here first: the fragments are re-interleaved by timestamp and the index is dropped. Fails
         * when a track cannot go into MP4, so the caller keeps the original rather than a broken copy.
         */
        fun flatten(
            inputPath: String,
            outputPath: String,
            tags: DownloadTags? = null,
            isCancelled: () -> Boolean = { false },
            onProgress: ((Float) -> Unit)? = null,
        ): Result = writeOutput(outputPath, tags, isCancelled, onProgress) { openAll(inputPath) }

        /** Opens the tracks [openInputs] selects, writes them into [outputPath], and cleans up on failure. */
        private fun writeOutput(
            outputPath: String,
            tags: DownloadTags?,
            isCancelled: () -> Boolean,
            onProgress: ((Float) -> Unit)?,
            openInputs: () -> List<Input>,
        ): Result {
            val inputs = mutableListOf<Input>()
            val output = File(outputPath)
            var completed = false
            return try {
                inputs += openInputs()
                mux(inputs, output, tags, isCancelled, onProgress)
                onProgress?.invoke(1f)
                completed = true
                val videoMime = inputs.firstOrNull { it.isVideo }?.mime
                Result.Success(
                    containerMimeType = if (videoMime == null) MimeTypes.AUDIO_MP4 else MimeTypes.VIDEO_MP4,
                    videoMimeType = videoMime,
                    audioMimeType = inputs.firstOrNull { !it.isVideo }?.mime.orEmpty(),
                )
            } catch (rejection: Rejection) {
                rejection.failure
            } catch (e: Exception) {
                Log.e(TAG, "Remux failed", e)
                Result.Failure(Reason.MUX_FAILED, e.message ?: e.javaClass.simpleName)
            } finally {
                inputs.forEach { runCatching { it.extractor.release() } }
                if (!completed) output.delete()
            }
        }

        private fun mux(
            inputs: List<Input>,
            output: File,
            tags: DownloadTags?,
            isCancelled: () -> Boolean,
            onProgress: ((Float) -> Unit)?,
        ) {
            output.parentFile?.mkdirs()
            FileOutputStream(output).use { stream ->
                val muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(stream)).setAttemptStreamableOutputEnabled(false).build()
                var closed = false
                try {
                    inputs.forEach { it.muxerTrack = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(it.format)) }
                    tags?.let { FlowTagFields.encode(it) }?.forEach { (field, value) ->
                        muxer.addMetadataEntry(
                            MdtaMetadataEntry(
                                FlowTagFields.mdtaKey(field),
                                value.toByteArray(Charsets.UTF_8),
                                MdtaMetadataEntry.TYPE_INDICATOR_STRING,
                            ),
                        )
                    }
                    val written = copyInterleaved(inputs, muxer, isCancelled, onProgress)
                    if (written == 0L) throw Rejection(Result.Failure(Reason.NO_TRACK, "No samples to write"))
                    muxer.close()
                    closed = true
                } finally {
                    if (!closed) runCatching { muxer.close() }
                }
            }
        }

        private fun open(
            path: String,
            isVideo: Boolean,
        ): Input {
            requireReadable(path)
            return openTrackOrNull(path, isVideo)
                ?: throw Rejection(Result.Failure(Reason.NO_TRACK, "No ${if (isVideo) "video/" else "audio/"} track in $path"))
        }

        /**
         * Opens every MP4-able track of one file, each on its own extractor: a `MediaExtractor` reads
         * from a single selected track, so the video and audio tracks are opened separately and the
         * muxer re-interleaves them. A missing track is skipped; an unsupported codec still rejects.
         */
        private fun openAll(path: String): List<Input> {
            requireReadable(path)
            val inputs = mutableListOf<Input>()
            try {
                openTrackOrNull(path, isVideo = true)?.let { inputs += it }
                openTrackOrNull(path, isVideo = false)?.let { inputs += it }
            } catch (e: Exception) {
                inputs.forEach { runCatching { it.extractor.release() } }
                throw e
            }
            if (inputs.isEmpty()) throw Rejection(Result.Failure(Reason.NO_TRACK, "No playable track in $path"))
            return inputs
        }

        private fun requireReadable(path: String) {
            val file = File(path)
            if (!file.isFile || file.length() == 0L) throw Rejection(Result.Failure(Reason.INPUT_MISSING, "Missing or empty input: $path"))
        }

        private fun openTrackOrNull(
            path: String,
            isVideo: Boolean,
        ): Input? {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(path)
                val prefix = if (isVideo) "video/" else "audio/"
                val index =
                    (0 until extractor.trackCount).firstOrNull {
                        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
                    }
                if (index == null) {
                    extractor.release()
                    return null
                }
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (isVideo && mime !in SUPPORTED_VIDEO) {
                    throw Rejection(Result.Failure(Reason.UNSUPPORTED_VIDEO, "Video codec $mime cannot go into MP4"))
                }
                if (!isVideo && mime != MimeTypes.AUDIO_AAC) {
                    throw Rejection(Result.Failure(Reason.UNSUPPORTED_AUDIO, "Audio codec $mime is not AAC"))
                }
                extractor.selectTrack(index)
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                return Input(extractor, format, mime, isVideo)
            } catch (e: Exception) {
                extractor.release()
                throw e
            }
        }

        private fun copyInterleaved(
            inputs: List<Input>,
            muxer: Mp4Muxer,
            isCancelled: () -> Boolean,
            onProgress: ((Float) -> Unit)?,
        ): Long {
            val totalUs = inputs.maxOf { it.durationUs }.takeIf { it > 0 }
            var buffer = ByteBuffer.allocateDirect(INITIAL_BUFFER_BYTES)
            var samples = 0L
            var lastReported = -1
            while (true) {
                if (isCancelled()) throw Rejection(Result.Failure(Reason.CANCELLED, "Remux cancelled"))
                var next: Input? = null
                var timeUs = Long.MAX_VALUE
                for (input in inputs) {
                    if (input.done) continue
                    val sampleTimeUs = input.extractor.sampleTime
                    if (sampleTimeUs == END_OF_STREAM) {
                        input.done = true
                    } else if (next == null || sampleTimeUs < timeUs) {
                        next = input
                        timeUs = sampleTimeUs
                    }
                }
                if (next == null) return samples
                val extractor = next.extractor
                buffer = ensureCapacity(buffer, next)
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    next.done = true
                    continue
                }
                buffer.position(0).limit(size)
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) C.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(next.muxerTrack, buffer, BufferInfo(timeUs, size, flags))
                samples++
                extractor.advance()
                if (onProgress != null && totalUs != null) {
                    val percent = (timeUs * 100 / totalUs).toInt().coerceIn(0, 99)
                    if (percent != lastReported) {
                        lastReported = percent
                        onProgress(percent / 100f)
                    }
                }
            }
        }

        private fun ensureCapacity(
            buffer: ByteBuffer,
            input: Input,
        ): ByteBuffer {
            val needed =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    input.extractor.sampleSize
                } else {
                    MediaFormatUtil.getInteger(input.format, MediaFormat.KEY_MAX_INPUT_SIZE, MAX_LEGACY_SAMPLE_BYTES).toLong()
                }
            if (needed <= buffer.capacity()) return buffer
            return ByteBuffer.allocateDirect((needed + needed / 4).toInt())
        }

        private companion object {
            const val TAG = "Mp4Remuxer"
            const val END_OF_STREAM = -1L
            const val INITIAL_BUFFER_BYTES = 1 shl 20
            const val MAX_LEGACY_SAMPLE_BYTES = 8 shl 20

            val SUPPORTED_VIDEO =
                setOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_AV1)
        }
    }
