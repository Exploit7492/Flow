package io.github.aedev.flow.campaign

import com.mavins.campaign.CampaignCard
import io.github.aedev.flow.data.music.model.MusicTrack

/**
 * The playable row a banner tap starts.
 *
 * The campaign's own id rides along as [MusicTrack.campaignId] so the play reporter can attribute a
 * qualified play, and [MusicTrack.isCampaign] marks the row as promoted — the same two flags the
 * queue-injection path sets, so a banner play and an injected play are attributed identically.
 *
 * The card itself comes from the campaign SDK; this is only the adapter to this app's own track
 * type, which is the one thing the SDK cannot know.
 */
fun CampaignCard.toMusicTrack(): MusicTrack? {
    if (songId.isBlank()) return null
    return MusicTrack(
        videoId = songId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        duration = 0,
        isCampaign = true,
        campaignId = id,
    )
}
