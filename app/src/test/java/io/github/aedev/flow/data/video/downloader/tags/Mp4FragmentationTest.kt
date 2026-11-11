package io.github.aedev.flow.data.video.downloader.tags

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

/**
 * The fragmentation probe decides whether a download is flattened before tagging. A plain MP4 must
 * read as not fragmented, and cobalt's own `moof`/`mfra` layout must read as fragmented.
 */
class Mp4FragmentationTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun write(bytes: ByteArray) = folder.newFile("clip.mp4").apply { writeBytes(bytes) }

    @Test
    fun `plain moov-last file is not fragmented`() {
        val file = write(Mp4Fixtures.moovLast().bytes)
        RandomAccessFile(file, "r").use {
            assertFalse(Mp4Boxes.isFragmented(it.channel))
        }
    }

    @Test
    fun `fast start file is not fragmented`() {
        val file = write(Mp4Fixtures.moovFirst().bytes)
        RandomAccessFile(file, "r").use {
            assertFalse(Mp4Boxes.isFragmented(it.channel))
        }
    }

    @Test
    fun `moof and mfra layout is fragmented`() {
        val file = write(Mp4Fixtures.fragmented().bytes)
        RandomAccessFile(file, "r").use {
            assertTrue(Mp4Boxes.isFragmented(it.channel))
        }
    }

    @Test
    fun `empty file is not fragmented`() {
        val file = folder.newFile("empty.mp4")
        RandomAccessFile(file, "r").use {
            assertFalse(Mp4Boxes.isFragmented(it.channel))
        }
    }
}
