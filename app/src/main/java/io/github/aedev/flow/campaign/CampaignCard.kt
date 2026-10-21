package io.github.aedev.flow.campaign

import io.github.aedev.flow.data.music.model.MusicTrack

/**
 * A single promoted-content card for the home banner.
 *
 * Ported from Velune's `CampaignCard` (the reference implementation of this feature). The fields
 * are the ones the banner actually draws; the ranking fields the SDK's canonical card carries for
 * its other campaign surfaces (`trendingScore`, `geographicTier`) are deliberately absent here,
 * because Flow's banner never reads them — the row order is not a ranking signal.
 */
data class CampaignCard(
    /** The campaign's own id, used to attribute a play and to re-read the total. */
    val id: String,
    /** The YouTube video id the card plays. */
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    /** The campaign's gathered stream total, as of the last read. */
    val totalStreams: Long,
    /** The lifecycle stage, e.g. `planting` or `full_bloom`. */
    val currentStage: String,
    /** Whether a human reviewer approved this campaign. Derived from the stage. */
    val certified: Boolean,
    /** Currently live and unpaused. The query's own filter guarantees this. */
    val isLive: Boolean,
)

/**
 * The playable row a banner tap starts. The campaign's own id rides along as
 * [MusicTrack.campaignId] so the play reporter can attribute a qualified play, and
 * [MusicTrack.isCampaign] marks the row as promoted — the same two flags the queue-injection path
 * sets, so a banner play and an injected play are attributed identically.
 */
fun CampaignCard.toMusicTrack(): MusicTrack? {
    if (songId.isBlank()) return null
    return MusicTrack(
        videoId = songId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$songId/hqdefault.jpg",
        duration = 0,
        isCampaign = true,
        campaignId = id,
    )
}
