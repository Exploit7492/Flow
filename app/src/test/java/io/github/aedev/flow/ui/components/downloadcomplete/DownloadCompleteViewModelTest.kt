package io.github.aedev.flow.ui.components.downloadcomplete

import io.github.aedev.flow.data.local.entity.DownloadEntity
import io.github.aedev.flow.data.local.entity.DownloadFileType
import io.github.aedev.flow.data.local.entity.DownloadItemEntity
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.local.entity.DownloadWithItems
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The completion card fires only for a download that finishes while the app is running. */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadCompleteViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val manager = mockk<VideoDownloadManager>(relaxed = true)
    private val downloads = MutableStateFlow<List<DownloadWithItems>>(emptyList())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { manager.allDownloads } returns downloads
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a download that finishes while running is reported`() =
        runTest {
            downloads.value = listOf(download("v1", DownloadItemStatus.COMPLETED))
            val viewModel = DownloadCompleteViewModel(manager)
            val seen = mutableListOf<DownloadComplete>()
            val job = launch { viewModel.events.collect { seen += it } }
            advanceUntilIdle()

            assertEquals(emptyList<DownloadComplete>(), seen)

            downloads.value = listOf(download("v1", DownloadItemStatus.COMPLETED), download("v2", DownloadItemStatus.COMPLETED))
            advanceUntilIdle()

            assertEquals(listOf("v2"), seen.map { it.videoId })
            job.cancel()
        }

    @Test
    fun `a download already finished at startup is not reported`() =
        runTest {
            downloads.value = listOf(download("v1", DownloadItemStatus.COMPLETED))
            val viewModel = DownloadCompleteViewModel(manager)
            val seen = mutableListOf<DownloadComplete>()
            val job = launch { viewModel.events.collect { seen += it } }
            advanceUntilIdle()

            assertEquals(emptyList<DownloadComplete>(), seen)
            job.cancel()
        }

    @Test
    fun `a download that is still running is not reported`() =
        runTest {
            val viewModel = DownloadCompleteViewModel(manager)
            val seen = mutableListOf<DownloadComplete>()
            val job = launch { viewModel.events.collect { seen += it } }
            advanceUntilIdle()

            downloads.value = listOf(download("v1", DownloadItemStatus.DOWNLOADING))
            advanceUntilIdle()

            assertEquals(emptyList<DownloadComplete>(), seen)
            job.cancel()
        }

    private fun download(
        videoId: String,
        status: DownloadItemStatus,
    ): DownloadWithItems =
        DownloadWithItems(
            download =
                DownloadEntity(
                    videoId = videoId,
                    title = "Title $videoId",
                    uploader = "Channel $videoId",
                    thumbnailUrl = "https://example.test/$videoId.jpg",
                ),
            items =
                listOf(
                    DownloadItemEntity(
                        videoId = videoId,
                        fileType = DownloadFileType.VIDEO,
                        fileName = "$videoId.mp4",
                        filePath = "/tmp/$videoId.mp4",
                        status = status,
                    ),
                ),
        )
}
