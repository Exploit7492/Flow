package io.github.aedev.flow.data.video.downloader.work

import java.io.File

/**
 * The working files of one download in app-private staging. Every name is keyed by video id, so two
 * downloads can never write to each other's files, whatever their titles.
 */
internal class DownloadStaging(
    directory: File,
    videoId: String,
) {
    private val stem = File(directory, videoId.replace(UNSAFE, "_")).path

    val videoPart = File("$stem.video.part")
    val audioPart = File("$stem.audio.part")
    val state = File("$stem.state.json")

    fun output(extension: String) = File("$stem.out.$extension")

    /** The flattened copy of a fragmented download, written before tagging and then renamed over the original. */
    fun flattened() = File("$stem.flat.mp4")

    /** Everything this download wrote to staging, including a half-made output. */
    fun clear() {
        listOf(videoPart, audioPart, state, flattened(), File("${state.path}.tmp")).forEach { it.delete() }
        clearOutputs()
    }

    /**
     * A half-made output, keeping the fetched parts for a retry. A cobalt download can name its
     * output with any extension the source hands back, so every `*.out.*` beside the stem goes.
     */
    fun clearOutputs() {
        val stemName = File(stem).name
        File(stem)
            .parentFile
            ?.listFiles { file -> file.name.startsWith("$stemName.out.") }
            ?.forEach { it.delete() }
    }

    /** The part files only, for a transfer that has to start over rather than resume. */
    fun clearParts() {
        listOf(videoPart, audioPart, state).forEach { it.delete() }
    }

    private companion object {
        val UNSAFE = Regex("[^A-Za-z0-9_-]")
    }
}
