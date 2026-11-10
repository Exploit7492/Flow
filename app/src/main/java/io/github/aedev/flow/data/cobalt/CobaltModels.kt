package io.github.aedev.flow.data.cobalt

import kotlinx.serialization.Serializable

/**
 * One request to a cobalt instance. Fields left null are omitted from the body, so cobalt's own
 * defaults apply; [downloadMode] is `auto` for video and `audio` for a sound-only download.
 */
@Serializable
data class CobaltRequest(
    val url: String,
    val videoQuality: String? = null,
    val audioFormat: String? = null,
    val audioBitrate: String? = null,
    val downloadMode: String? = null,
    val filenameStyle: String? = null,
)

/** cobalt's answer. [status] is `tunnel`, `redirect`, `picker`, `local` or `error`. */
@Serializable
data class CobaltResponse(
    val status: String = "",
    val url: String? = null,
    val filename: String? = null,
    val error: CobaltError? = null,
)

@Serializable
data class CobaltError(
    val code: String = "",
)

/** What a resolve ended on: a downloadable URL, or the code cobalt refused it with. */
sealed interface CobaltResult {
    data class Ready(
        val url: String,
        val filename: String?,
    ) : CobaltResult

    /** cobalt answered, but with no URL: an error, a picker post, or an unsupported service. */
    data class Unavailable(
        val code: String,
    ) : CobaltResult
}

/**
 * Whether a refusal is worth trying again. A blip — a dropped connection, an overloaded instance, a
 * body that did not parse — is; a settled answer such as an unsupported link or a source that needs
 * a login is not, because the next call returns it too.
 */
fun CobaltResult.isRetryable(): Boolean {
    val code = (this as? CobaltResult.Unavailable)?.code ?: return false
    return code.contains("network") ||
        code.contains("http.5") ||
        code.contains("http.429") ||
        code.contains("rate") ||
        code.contains("unreadable")
}

/**
 * An opened file from a cobalt tunnel. [response] must be closed by the caller once its body has
 * been read. cobalt tunnels answer every request with the whole body and ignore Range, so a
 * download reads this once, straight through.
 */
sealed interface CobaltStream {
    class Ready(
        val response: okhttp3.Response,
        val contentLength: Long,
        val fileName: String?,
    ) : CobaltStream

    data class Unavailable(
        val code: String,
    ) : CobaltStream
}

/** The video qualities cobalt takes, highest first. `max` is the best it can serve. */
object CobaltQualities {
    const val MAX = "max"
    val HEIGHTS = listOf("2160", "1440", "1080", "720", "480", "360", "240", "144")
}

/** The sound formats cobalt takes. `best` is whatever the source already carries, untouched. */
object CobaltAudio {
    const val BEST = "best"

    /** One audio row of the quality sheet: a container, and a bitrate for the lossy ones. */
    data class Choice(
        val format: String,
        val bitrate: String? = null,
    )

    /**
     * The audio ladder the sheet shows, highest fidelity first: the source's own sound untouched,
     * then the two MP3 bitrates. A row is identified by its [Choice]; [BEST] is the untouched row.
     * cobalt rejects `m4a` as a format and hands back an M4A container for [BEST], so `m4a` is not a
     * row of its own.
     */
    val LADDER = listOf(Choice(BEST), Choice("mp3", "256"), Choice("mp3", "128"))
}

/**
 * The POST body for a stored selection. An audio-only download asks cobalt for sound alone, which
 * is the only mode that returns an audio file; everything else takes cobalt's own video pick.
 */
fun io.github.aedev.flow.data.video.downloader.request.CobaltSelection.toCobaltRequest(audioOnly: Boolean): CobaltRequest =
    if (audioOnly) {
        CobaltRequest(
            url = url,
            downloadMode = "audio",
            audioFormat = audioFormat ?: CobaltAudio.BEST,
            audioBitrate = audioBitrate,
            filenameStyle = "basic",
        )
    } else {
        CobaltRequest(url = url, downloadMode = "auto", videoQuality = videoQuality, filenameStyle = "basic")
    }
