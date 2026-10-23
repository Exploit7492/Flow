package io.github.aedev.flow.campaign

import android.util.Log
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Turns the player's ticks into qualified campaign plays, reported to the backend.
 *
 * A campaign play is a paid event, so it is counted once, and only once enough of the track has
 * actually gone by. This is fed from the music service's own progress sampler, which only ticks
 * while audio is coming out — so a skip cannot inflate it and a pause cannot either. The
 * qualification rule (half the track, floored at 30s and capped at four minutes) matches
 * [io.github.aedev.flow.data.scrobble.ScrobbleRules], so the two agree on what a play is.
 *
 * Every campaign play is reported once per track, keyed on the campaign's own id, so replaying the
 * same promoted track later reports again but a single listen never double-counts. Reporting is
 * best-effort: a failure is logged and dropped, never surfaced to the listener and never retried,
 * because the play is already over.
 */
object CampaignPlaybackTracker {
    private const val TAG = "Campaigns"

    private const val PLAY_FLOOR_MS = 30_000L
    private const val PLAY_CEILING_MS = 4 * 60_000L

    /** A pause must not be counted when playback resumes; the gap is capped. */
    private const val MAX_STEP_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Play(
        val campaignId: String,
    ) {
        var playedMs = 0L
        var lastSampleAt = 0L
        var reported = false
    }

    private val plays = ConcurrentHashMap<String, Play>()

    /**
     * One tick of the player's sampler, called only for a campaign row. [durationMs] is the
     * decoder's figure when it has one; the row's stated runtime stands in until it does.
     *
     * Progress is measured as wall-clock time between ticks, not from the player's position, so
     * seeking cannot inflate it. The gap either side of a pause is capped so an hour on the lock
     * screen does not arrive as an hour of campaign listening.
     */
    @Synchronized
    fun onSample(
        track: MusicTrack,
        durationMs: Long,
    ) {
        val campaignId = track.campaignId ?: return
        val now = System.currentTimeMillis()
        val play = plays.getOrPut(campaignId) { Play(campaignId) }
        if (play.lastSampleAt == 0L) {
            play.lastSampleAt = now
            return
        }
        val step = (now - play.lastSampleAt).coerceIn(0L, MAX_STEP_MS)
        play.lastSampleAt = now
        if (step <= 0L) return
        play.playedMs += step
        if (play.reported) return

        val length = durationMs.takeIf { it > 0 } ?: (track.duration.toLong() * 1000L)
        val threshold =
            if (length > 0) {
                min(length / 2, PLAY_CEILING_MS).coerceAtLeast(PLAY_FLOOR_MS)
            } else {
                PLAY_FLOOR_MS
            }
        if (play.playedMs >= threshold) {
            play.reported = true
            val seconds = (play.playedMs / 1000L).toInt()
            val full = length > 0 && play.playedMs >= length * 9 / 10
            val country = Locale.getDefault().country
            scope.launch {
                try {
                    // The SDK provisions the listener row itself before reporting (a play event's
                    // foreign key needs it to exist), so this is one call rather than an
                    // ensure-then-report pair, and it cannot race the provisioning.
                    com.mavins.campaign.Campaigns.recordPlay(
                        campaignId = campaignId,
                        listenDurationSeconds = seconds,
                        countryCode = country,
                        isFullListen = full,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "campaign play report failed for $campaignId: ${e.message}")
                }
            }
        }
    }

    /** Called when a track stops; a stopped campaign must not carry progress into its next appearance. */
    fun onStopped() {
        plays.clear()
    }
}
