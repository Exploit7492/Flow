package io.github.aedev.flow.data.video.downloader.tags

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject

/**
 * Writes an ID3v2.4 tag at the front of an MP3 so Android's MediaStore, Media3 and desktop players
 * show title, artist, album, track number, date, lyrics and cover art — the ID3 twin of
 * [Mp4TagWriter], and for the same reason hand-rolled: media3-muxer 1.11 has no MP3 muxer at all,
 * so nothing on the classpath can write ID3 tags.
 *
 * An MP3's tag sits before the audio and carries its own length, so the file cannot be edited in
 * place: it is rewritten through a sibling temp file — the new tag, then the audio copied from
 * behind the old one. Flow's own fields travel as namespaced `TXXX` frames, the ID3 form of the MP4
 * `----` atoms, which [EmbeddedTags] reads back through the same [FlowTagFields] keys.
 */
class Id3TagWriter
    @Inject
    constructor() {
        @Throws(IOException::class)
        fun write(
            file: File,
            tags: DownloadTags,
            cover: ByteArray? = null,
        ) {
            val audioStart = existingTagLength(file)
            val temp = File(file.parentFile, "${file.name}$TEMP_SUFFIX")
            try {
                FileOutputStream(temp).use { out ->
                    out.write(buildTag(tags, cover))
                    FileInputStream(file).use { input ->
                        skipFully(input, audioStart)
                        input.copyTo(out, BUFFER_SIZE)
                    }
                }
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                temp.delete()
                throw e
            }
        }

        /** Where the audio starts: past a leading ID3v2 tag, or 0 when there is none. */
        private fun existingTagLength(file: File): Long {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(HEADER_BYTES)
                if (raf.read(header) < HEADER_BYTES) return 0
                if (!header.startsWith('I'.code, 'D'.code, '3'.code)) return 0
                val footer =
                    if (header[VERSION_OFFSET].toInt() == VERSION_4_BYTE && header[FLAGS_OFFSET].toInt() and FOOTER_FLAG != 0) {
                        HEADER_BYTES
                    } else {
                        0
                    }
                val size = HEADER_BYTES.toLong() + synchsafe(header, SIZE_OFFSET) + footer
                return size.takeIf { it <= file.length() } ?: 0
            }
        }

        private fun buildTag(
            tags: DownloadTags,
            cover: ByteArray?,
        ): ByteArray {
            val frames = ByteArrayOutputStream()
            frames.writeText(FRAME_TITLE, tags.title)
            frames.writeText(FRAME_ARTIST, tags.displayArtist())
            frames.writeText(FRAME_ALBUM, tags.album)
            frames.writeText(FRAME_ALBUM_ARTIST, tags.albumArtist)
            tags.trackNumber
                ?.takeIf { it > 0 }
                ?.let { frames.write(textFrame(FRAME_TRACK, tags.trackTotal?.let { total -> "$it/$total" } ?: "$it")) }
            frames.writeText(FRAME_DATE, tags.releaseDate)
            tags.sourceUrl?.let { frames.write(frame(FRAME_COMMENT, encoding() + LANGUAGE + ZERO + it.toByteArray(Charsets.UTF_8))) }
            tags.lyrics?.let { frames.write(frame(FRAME_LYRICS, encoding() + LANGUAGE + ZERO + it.toByteArray(Charsets.UTF_8))) }
            frames.writeText(FRAME_ENCODER, IlstAtoms.ENCODER_NAME)
            coverFrame(cover)?.let(frames::write)
            FlowTagFields.encode(tags).forEach { (name, value) ->
                frames.write(
                    frame(
                        FRAME_USER_TEXT,
                        encoding() + FlowTagFields.mdtaKey(name).toByteArray(Charsets.UTF_8) + ZERO + value.toByteArray(Charsets.UTF_8),
                    ),
                )
            }
            val body = frames.toByteArray()
            return ByteArrayOutputStream(HEADER_BYTES + body.size)
                .apply {
                    write(ID3.toByteArray(Charsets.ISO_8859_1))
                    write(VERSION_4)
                    write(REVISION)
                    write(0)
                    write(synchsafe(body.size))
                    write(body)
                }.toByteArray()
        }

        private fun ByteArrayOutputStream.writeText(
            id: String,
            value: String?,
        ) {
            if (value.isNullOrEmpty()) return
            write(textFrame(id, value))
        }

        private fun textFrame(
            id: String,
            value: String,
        ): ByteArray = frame(id, encoding() + value.toByteArray(Charsets.UTF_8))

        /** JPEG and PNG only, front cover, mirroring [IlstAtoms]: anything else is not embedded. */
        private fun coverFrame(cover: ByteArray?): ByteArray? {
            if (cover == null || cover.size > IlstAtoms.MAX_COVER_BYTES) return null
            val mime =
                when {
                    cover.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
                    cover.startsWith(0x89, 0x50, 0x4E, 0x47) -> "image/png"
                    else -> return null
                }
            val payload =
                encoding() + mime.toByteArray(Charsets.ISO_8859_1) + ZERO + byteArrayOf(FRONT_COVER.toByte()) + ZERO + cover
            return frame(FRAME_PICTURE, payload)
        }

        private fun frame(
            id: String,
            payload: ByteArray,
        ): ByteArray =
            ByteArrayOutputStream(FRAME_HEADER_BYTES + payload.size)
                .apply {
                    write(id.toByteArray(Charsets.ISO_8859_1))
                    write(synchsafe(payload.size))
                    write(0)
                    write(0)
                    write(payload)
                }.toByteArray()

        /** The 7-bit-per-byte length form ID3 uses for every size field. */
        private fun synchsafe(value: Int): ByteArray {
            require(value < SYNCHSAFE_LIMIT) { "ID3 size out of range: $value" }
            return byteArrayOf(
                ((value ushr 21) and MASK).toByte(),
                ((value ushr 14) and MASK).toByte(),
                ((value ushr 7) and MASK).toByte(),
                (value and MASK).toByte(),
            )
        }

        private fun synchsafe(
            bytes: ByteArray,
            offset: Int,
        ): Int =
            ((bytes[offset].toInt() and MASK) shl 21) or
                ((bytes[offset + 1].toInt() and MASK) shl 14) or
                ((bytes[offset + 2].toInt() and MASK) shl 7) or
                (bytes[offset + 3].toInt() and MASK)

        private fun skipFully(
            input: InputStream,
            count: Long,
        ) {
            var remaining = count
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else if (input.read() < 0) {
                    return
                } else {
                    remaining--
                }
            }
        }

        private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

        private companion object {
            val ID3 = "ID3"
            val VERSION_4 = byteArrayOf(4)
            val REVISION = byteArrayOf(0)
            const val VERSION_4_BYTE = 4
            val LANGUAGE = "eng".toByteArray(Charsets.ISO_8859_1)
            val ZERO = byteArrayOf(0)

            const val FRAME_TITLE = "TIT2"
            const val FRAME_ARTIST = "TPE1"
            const val FRAME_ALBUM = "TALB"
            const val FRAME_ALBUM_ARTIST = "TPE2"
            const val FRAME_TRACK = "TRCK"
            const val FRAME_DATE = "TDRC"
            const val FRAME_COMMENT = "COMM"
            const val FRAME_LYRICS = "USLT"
            const val FRAME_ENCODER = "TSSE"
            const val FRAME_PICTURE = "APIC"
            const val FRAME_USER_TEXT = "TXXX"

            const val HEADER_BYTES = 10
            const val FRAME_HEADER_BYTES = 10
            const val VERSION_OFFSET = 3
            const val FLAGS_OFFSET = 5
            const val SIZE_OFFSET = 6
            const val FOOTER_FLAG = 0x10
            const val UTF8 = 3
            const val FRONT_COVER = 3
            const val MASK = 0x7F
            const val SYNCHSAFE_LIMIT = 0x10000000
            const val BUFFER_SIZE = 64 * 1024
            const val TEMP_SUFFIX = ".id3tmp"

            /** The text-encoding byte ID3 frames open with: UTF-8. */
            fun encoding() = byteArrayOf(UTF8.toByte())
        }
    }
