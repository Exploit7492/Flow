package io.github.aedev.flow.data.video.downloader.tags

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Finishes a download with the FFmpeg binary bundled in the app, when one is present. The binary is
 * shipped as a shared library (Android cannot execute a file from the app's data directory), so it
 * is looked up in the native library directory under the names a packaged FFmpeg uses.
 *
 * This is a seam, not a re-implementation: it only ever copies streams (`-c copy`), never re-encodes,
 * so it cannot change the media, only the container. When no binary is bundled — the common case for
 * a build that does not want the ~35 MB per-ABI cost — [isAvailable] is false and callers fall back
 * to the app's own Kotlin remuxer, which produces the same plain MP4 without an external process.
 */
@Singleton
class FfmpegFinalizer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val binary: File? by lazy { locate() }

        fun isAvailable(): Boolean = binary != null

        /**
         * Copies [inputs] into [output] as a plain MP4, dropping any fragment index. One input is a
         * straight copy; two are a video and an audio track merged by the first video and first audio
         * mapping. True when the output was written; false when there is no binary or FFmpeg failed,
         * so the caller keeps the file it already had rather than a half-written copy.
         */
        fun remuxToMp4(
            inputs: List<File>,
            output: File,
        ): Boolean {
            val executable = binary ?: return false
            if (inputs.isEmpty()) return false
            output.delete()
            val maps =
                if (inputs.size >= 2) {
                    listOf("-map", "0:v?", "-map", "1:a?")
                } else {
                    listOf("-map", "0:v?", "-map", "0:a?")
                }
            return try {
                val command =
                    buildList {
                        add(executable.absolutePath)
                        addAll(listOf("-hide_banner", "-nostdin", "-y"))
                        inputs.forEach { input ->
                            add("-i")
                            add(input.absolutePath)
                        }
                        addAll(maps)
                        addAll(listOf("-c", "copy", output.absolutePath))
                    }
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                process.inputStream.use { it.readBytes() }
                if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    Log.w(TAG, "FFmpeg timed out on ${inputs.first().name}")
                    output.delete()
                    false
                } else if (process.exitValue() != 0 || !output.isFile || output.length() == 0L) {
                    Log.w(TAG, "FFmpeg exited ${process.exitValue()} on ${inputs.first().name}")
                    output.delete()
                    false
                } else {
                    true
                }
            } catch (e: Exception) {
                Log.w(TAG, "FFmpeg could not run on ${inputs.first().name}", e)
                output.delete()
                false
            }
        }

        private fun locate(): File? =
            BINARY_NAMES
                .asSequence()
                .map { File(context.applicationInfo.nativeLibraryDir, it) }
                .firstOrNull { it.isFile && it.canExecute() }

        private companion object {
            const val TAG = "FfmpegFinalizer"
            const val TIMEOUT_SECONDS = 10L * 60L

            /** The names a packaged FFmpeg takes when it ships as a native library. */
            val BINARY_NAMES = listOf("libffmpeg.so", "ffmpeg")
        }
    }
