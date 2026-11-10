package io.github.aedev.flow.data.cobalt

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.video.downloader.request.CobaltSelection
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.data.video.downloader.request.DownloadSource
import io.github.aedev.flow.data.video.downloader.tags.DownloadKind
import io.github.aedev.flow.data.video.downloader.tags.DownloadTags
import io.github.aedev.flow.data.video.downloader.work.extensionFor
import io.github.aedev.flow.data.video.downloader.work.mimeTypeFor
import org.junit.Test

/**
 * The cobalt ladder and the helpers that turn a selection into a request and a file. These are the
 * parts a network test cannot reach, and the parts the sheet's ordering and the download row's
 * quality label depend on.
 */
class CobaltModelsTest {
    @Test
    fun `the audio ladder is untouched sound then the mp3 bitrates`() {
        assertThat(CobaltAudio.LADDER)
            .containsExactly(
                CobaltAudio.Choice(CobaltAudio.BEST),
                CobaltAudio.Choice("mp3", "256"),
                CobaltAudio.Choice("mp3", "128"),
            ).inOrder()
    }

    @Test
    fun `a video selection posts cobalt's auto mode with the height it picked`() {
        val request = CobaltSelection(url = "https://x.test/a", videoQuality = "720").toCobaltRequest(audioOnly = false)
        assertThat(request.downloadMode).isEqualTo("auto")
        assertThat(request.videoQuality).isEqualTo("720")
        assertThat(request.audioFormat).isNull()
    }

    @Test
    fun `an audio selection posts the sound mode with its format and bitrate`() {
        val request =
            CobaltSelection(url = "https://x.test/a", audioFormat = "mp3", audioBitrate = "128")
                .toCobaltRequest(audioOnly = true)
        assertThat(request.downloadMode).isEqualTo("audio")
        assertThat(request.audioFormat).isEqualTo("mp3")
        assertThat(request.audioBitrate).isEqualTo("128")
        assertThat(request.videoQuality).isNull()
    }

    @Test
    fun `an audio selection with no format falls back to cobalt's own best`() {
        val request = CobaltSelection(url = "https://x.test/a").toCobaltRequest(audioOnly = true)
        assertThat(request.audioFormat).isEqualTo(CobaltAudio.BEST)
    }

    @Test
    fun `the extension follows cobalt's filename and the request otherwise`() {
        val video = requestFor(audioOnly = false)
        assertThat(extensionFor("song.mp3", video)).isEqualTo("mp3")
        assertThat(extensionFor("clip.MP4", video)).isEqualTo("mp4")
        assertThat(extensionFor(null, video)).isEqualTo("mp4")
        assertThat(extensionFor(null, requestFor(audioOnly = true))).isEqualTo("m4a")
    }

    @Test
    fun `a mime type is chosen from the extension`() {
        assertThat(mimeTypeFor("mp3")).isEqualTo("audio/mpeg")
        assertThat(mimeTypeFor("m4a")).isEqualTo("audio/mp4")
        assertThat(mimeTypeFor("opus")).isEqualTo("audio/ogg")
        assertThat(mimeTypeFor("webm")).isEqualTo("video/webm")
        assertThat(mimeTypeFor("mp4")).isEqualTo("video/mp4")
    }

    @Test
    fun `only a transient refusal is retried`() {
        assertThat(CobaltResult.Unavailable("error.api.network").isRetryable()).isTrue()
        assertThat(CobaltResult.Unavailable("error.api.rate_limit").isRetryable()).isTrue()
        assertThat(CobaltResult.Unavailable("error.api.link.unsupported").isRetryable()).isFalse()
        assertThat(CobaltResult.Ready("https://x.test/a", null).isRetryable()).isFalse()
    }

    private fun requestFor(audioOnly: Boolean): DownloadRequest =
        DownloadRequest(
            tags = DownloadTags(kind = DownloadKind.VIDEO, videoId = "id", title = "t"),
            audioOnly = audioOnly,
            source = DownloadSource.COBALT,
            cobalt = CobaltSelection(url = "https://x.test/a"),
        )
}
