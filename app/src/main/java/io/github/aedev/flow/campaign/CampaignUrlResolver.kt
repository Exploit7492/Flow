package io.github.aedev.flow.campaign

/**
 * Extracts a YouTube video id from a campaign row's raw `source_url`.
 *
 * The queue-slot RPC's eligibility filter admits a row by the shape of its link, so a row that
 * reaches the client without a `resolved_song_id` is one whose link was stored as a plain YouTube
 * URL. Every shape a user actually pastes is understood — a watch link with `v=` first, a `youtu.be`
 * short, a `shorts` or `live` URL, or a bare 11-character id — rather than only the `v=`-first
 * watch shape, which silently drops the common `music.youtube.com/watch?v=ID` case when `v=` is not
 * the first query parameter, and every non-watch shape outright.
 */
object CampaignUrlResolver {
    // YouTube video ids are always 11 characters from this alphabet.
    private val VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")

    private val URL_PATTERNS =
        listOf(
            // `v=` may lead the query string or follow other parameters; zero or more `key=value&`
            // pairs are allowed ahead of it so both match.
            Regex("""(?:music\.)?youtube\.com/watch\?(?:[^&]*&)*v=([A-Za-z0-9_-]{11})"""),
            Regex("""(?:music\.)?youtube\.com/shorts/([A-Za-z0-9_-]{11})"""),
            Regex("""(?:music\.)?youtube\.com/live/([A-Za-z0-9_-]{11})"""),
            Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
        )

    /** The video id in [sourceUrl] in any of the shapes above, or a bare id, or null. */
    fun extractVideoId(sourceUrl: String): String? {
        val trimmed = sourceUrl.trim()
        if (trimmed.isEmpty()) return null
        for (pattern in URL_PATTERNS) {
            pattern
                .find(trimmed)
                ?.groupValues
                ?.getOrNull(1)
                ?.let { return it }
        }
        return if (VIDEO_ID_REGEX.matches(trimmed)) trimmed else null
    }
}
