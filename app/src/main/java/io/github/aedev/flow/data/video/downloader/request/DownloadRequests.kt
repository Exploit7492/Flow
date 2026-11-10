package io.github.aedev.flow.data.video.downloader.request

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.video.downloader.tags.DownloadKind
import io.github.aedev.flow.data.video.downloader.tags.DownloadTags

/** The request for a video, from what the card or player already knows about it. */
fun Video.toDownloadRequest(
    audioOnly: Boolean = false,
    targetHeight: Int? = null,
    videoCodec: String? = null,
    videoItag: Int? = null,
    audioItag: Int? = null,
    audioTrackId: String? = null,
    audioLanguage: String? = null,
    collectionId: String? = null,
    threads: Int? = null,
    subtitle: DownloadSubtitle? = null,
): DownloadRequest {
    val kind =
        when {
            isMusic -> DownloadKind.MUSIC
            isShort -> DownloadKind.SHORT
            else -> DownloadKind.VIDEO
        }
    return DownloadRequest(
        tags =
            DownloadTags(
                kind = kind,
                videoId = id,
                title = title,
                channelId = channelId.takeIf { it.isNotBlank() },
                channelName = channelName.takeIf { it.isNotBlank() },
                description = description.takeIf { it.isNotBlank() },
                sourceUrl = sourceUrlFor(kind, id),
                viewCount = viewCount.takeIf { it > 0 },
                likeCount = likeCount.takeIf { it > 0 },
                thumbnailUrl = thumbnailUrl.takeIf { it.isNotBlank() },
            ),
        audioOnly = audioOnly,
        targetHeight = targetHeight,
        videoCodec = videoCodec,
        videoItag = videoItag,
        audioItag = audioItag,
        audioTrackId = audioTrackId,
        audioLanguage = audioLanguage,
        collectionId = collectionId,
        threads = threads,
        durationSeconds = duration,
        subtitle = subtitle,
    )
}

/**
 * The request for a URL cobalt resolved: Flow only knows the URL and whatever the sheet shows, so
 * the tags carry those and the cobalt selection is stored for the runner to post back.
 */
fun CobaltSelection.toDownloadRequest(
    title: String,
    kind: DownloadKind = DownloadKind.VIDEO,
    thumbnailUrl: String? = null,
    channelName: String? = null,
    durationSeconds: Int = 0,
    audioOnly: Boolean = false,
    collectionId: String? = null,
): DownloadRequest {
    val id = cobaltVideoId(url)
    return DownloadRequest(
        tags =
            DownloadTags(
                kind = kind,
                videoId = id,
                title = title.ifBlank { id },
                channelName = channelName?.takeIf { it.isNotBlank() },
                sourceUrl = url,
                thumbnailUrl = thumbnailUrl?.takeIf { it.isNotBlank() },
            ),
        audioOnly = audioOnly,
        collectionId = collectionId,
        durationSeconds = durationSeconds,
        source = DownloadSource.COBALT,
        cobalt = this,
    )
}

/**
 * A stable id for a URL cobalt resolves, since a non-YouTube source has no video id. The same URL
 * hashes to the same id, so re-queuing it replaces the download rather than duplicating it.
 */
internal fun cobaltVideoId(url: String): String =
    "cobalt-" +
        url
            .trim()
            .hashCode()
            .toUInt()
            .toString(16)

/** The request for a song, carrying its album and credited artists into the file's tags. */
fun MusicTrack.toDownloadRequest(
    collectionId: String? = null,
    trackNumber: Int? = null,
    trackTotal: Int? = null,
    albumArtist: String? = null,
): DownloadRequest {
    val credited = artists.filter { it.name.isNotBlank() }.ifEmpty { listOf(MusicArtist(artist, channelId.takeIf { it.isNotBlank() })) }
    return DownloadRequest(
        tags =
            DownloadTags(
                kind = DownloadKind.MUSIC,
                videoId = videoId,
                title = title,
                artists = credited.map { it.name }.filter { it.isNotBlank() },
                channelId = channelId.takeIf { it.isNotBlank() },
                channelName = artist.takeIf { it.isNotBlank() },
                album = album.takeIf { it.isNotBlank() },
                albumId = albumId?.takeIf { it.isNotBlank() },
                albumArtist = albumArtist,
                trackNumber = trackNumber,
                trackTotal = trackTotal,
                playlistId = collectionId,
                sourceUrl = sourceUrlFor(DownloadKind.MUSIC, videoId),
                viewCount = views.takeIf { it > 0 },
                likeCount = likes.takeIf { it > 0 },
                thumbnailUrl = highResThumbnailUrl.takeIf { it.isNotBlank() },
            ),
        audioOnly = true,
        collectionId = collectionId,
        durationSeconds = duration,
        artists = credited.map { StoredArtist(it.name, it.id) },
    )
}

internal fun sourceUrlFor(
    kind: DownloadKind,
    videoId: String,
): String =
    when (kind) {
        DownloadKind.MUSIC -> "https://music.youtube.com/watch?v=$videoId"
        DownloadKind.SHORT -> "https://www.youtube.com/shorts/$videoId"
        DownloadKind.VIDEO -> "https://www.youtube.com/watch?v=$videoId"
    }
