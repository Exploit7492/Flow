package io.github.aedev.flow.data.video.downloader.tags

import androidx.media3.common.Metadata
import androidx.media3.common.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.id3.Id3Decoder
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Id3TagWriterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val writer = Id3TagWriter()

    @Before
    fun configureMedia3ForJvm() {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(true)
        Log.setLogLevel(Log.LOG_LEVEL_OFF)
    }

    @After
    fun restoreMedia3Defaults() {
        ParsableByteArray.setShouldEnforceLimitOnLegacyMethods(null)
        Log.setLogLevel(Log.LOG_LEVEL_ALL)
    }

    private val tags =
        DownloadTags(
            kind = DownloadKind.MUSIC,
            videoId = "dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artists = listOf("Rick Astley"),
            channelId = "UCuAXFkgsw1L7xaCfnd5JJOw",
            channelName = "Rick Astley - Topic",
            album = "Whenever You Need Somebody",
            albumId = "MPREb_album",
            albumArtist = "Rick Astley",
            trackNumber = 1,
            trackTotal = 10,
            playlistId = "OLAK5uy_list",
            releaseDate = "1987-11-16",
            description = "Official audio",
            sourceUrl = "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            viewCount = 1_500_000_000L,
            likeCount = 18_000_000L,
            thumbnailUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            lyrics = "We're no strangers to love",
        )

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(256) { it.toByte() }

    /** A bare MP3: an audio frame header (0xFF 0xFB) followed by payload, no ID3 tag. */
    private val audio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64.toByte()) + ByteArray(4096) { (it * 7).toByte() }

    private var counter = 0

    private fun file(bytes: ByteArray = audio): File = folder.newFile("song${counter++}.mp3").apply { writeBytes(bytes) }

    /** The bytes of the leading ID3v2 tag, header included, or null when the file has none. */
    private fun tagBytes(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10 || String(bytes, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
        val size =
            ((bytes[6].toInt() and 0x7F) shl 21) or ((bytes[7].toInt() and 0x7F) shl 14) or ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)
        return bytes.copyOfRange(0, 10 + size)
    }

    private fun embeddedEntries(file: File): List<Metadata.Entry> {
        val tag = tagBytes(file.readBytes()) ?: return emptyList()
        val metadata = Id3Decoder().decode(tag, tag.size) ?: return emptyList()
        return List(metadata.length()) { metadata[it] }
    }

    private fun embedded(file: File): EmbeddedTags = EmbeddedTags.fromEntries(embeddedEntries(file))

    @Test
    fun `an untagged mp3 gets a leading tag and its audio is copied through unchanged`() {
        val file = file()
        writer.write(file, tags)

        val bytes = file.readBytes()
        val tag = tagBytes(bytes) ?: error("no tag written")
        assertThat(String(bytes, 0, 3, Charsets.ISO_8859_1)).isEqualTo("ID3")
        assertThat(bytes.copyOfRange(tag.size, bytes.size)).isEqualTo(audio)
    }

    @Test
    fun `media3 parses the written tag back into the same DownloadTags`() {
        val file = file()
        writer.write(file, tags)

        val result = embedded(file)

        assertThat(result.flow).isEqualTo(tags)
        assertThat(result.title).isEqualTo(tags.title)
        assertThat(result.artist).isEqualTo("Rick Astley")
        assertThat(result.album).isEqualTo(tags.album)
        assertThat(result.comment).isEqualTo(tags.sourceUrl)
        assertThat(result.lyrics).isEqualTo(tags.lyrics)
    }

    @Test
    fun `a jpeg cover is embedded as a front-cover APIC frame`() {
        val file = file()
        writer.write(file, tags, cover = jpeg)

        val picture = embeddedEntries(file).filterIsInstance<ApicFrame>().single()
        assertThat(picture.mimeType).isEqualTo("image/jpeg")
        assertThat(picture.pictureType).isEqualTo(3)
        assertThat(picture.pictureData).isEqualTo(jpeg)
        assertThat(embedded(file).cover).isEqualTo(jpeg)
    }

    @Test
    fun `a png cover is embedded and an oversized or unknown one is skipped`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(32)
        val pngFile = file()
        writer.write(pngFile, tags, cover = png)
        assertThat(embeddedEntries(pngFile).filterIsInstance<ApicFrame>().single().mimeType).isEqualTo("image/png")

        val bigFile = file()
        writer.write(bigFile, tags, cover = jpeg + ByteArray(IlstAtoms.MAX_COVER_BYTES))
        assertThat(embeddedEntries(bigFile).filterIsInstance<ApicFrame>()).isEmpty()

        val oddFile = file()
        writer.write(oddFile, tags, cover = ByteArray(64))
        assertThat(embeddedEntries(oddFile).filterIsInstance<ApicFrame>()).isEmpty()
    }

    @Test
    fun `writing twice replaces the tag instead of stacking a second one`() {
        val file = file()
        writer.write(file, tags.copy(title = "First"))
        writer.write(file, tags.copy(title = "Second"))
        val sizeAfterSecond = file.length()
        writer.write(file, tags.copy(title = "Second"))

        val bytes = file.readBytes()
        assertThat(String(bytes, 0, 3, Charsets.ISO_8859_1)).isEqualTo("ID3")
        assertThat(bytes.copyOfRange(tagBytes(bytes)!!.size, bytes.size)).isEqualTo(audio)
        assertThat(embedded(file).flow?.title).isEqualTo("Second")
        assertThat(file.length()).isEqualTo(sizeAfterSecond)
    }

    @Test
    fun `an existing tag from the source is dropped, keeping only the audio`() {
        val oldTag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 6) + "stale!".toByteArray()
        val file = file(oldTag + audio)
        writer.write(file, tags)

        val bytes = file.readBytes()
        assertThat(bytes.copyOfRange(tagBytes(bytes)!!.size, bytes.size)).isEqualTo(audio)
        assertThat(String(bytes, 0, bytes.size, Charsets.ISO_8859_1)).doesNotContain("stale!")
        assertThat(embedded(file).flow).isEqualTo(tags)
    }

    @Test
    fun `flow fields travel as namespaced TXXX frames, one per field`() {
        val file = file()
        writer.write(file, tags)

        val userText = embeddedEntries(file).filterIsInstance<TextInformationFrame>().filter { it.id == "TXXX" }
        val fields = userText.associate { it.description to it.values.firstOrNull() }

        assertThat(fields["io.github.aedev.flow.videoId"]).isEqualTo("dQw4w9WgXcQ")
        assertThat(fields["io.github.aedev.flow.kind"]).isEqualTo("MUSIC")
        assertThat(fields["io.github.aedev.flow.viewCount"]).isEqualTo("1500000000")
        assertThat(userText).hasSize(FlowTagFields.encode(tags).size)
    }

    @Test
    fun `a non-ascii title and lyrics survive the round trip`() {
        val unicode = tags.copy(title = "Café ☕ – Beyoncé", lyrics = "第一行\nZeile zwei")
        val file = file()
        writer.write(file, unicode)

        val result = embedded(file)
        assertThat(result.title).isEqualTo("Café ☕ – Beyoncé")
        assertThat(result.lyrics).isEqualTo("第一行\nZeile zwei")
        assertThat(result.flow).isEqualTo(unicode)
    }
}
