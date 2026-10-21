package io.github.aedev.flow.campaign

import android.util.Log
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.YouTube
import io.github.aedev.flow.innertube.models.YouTubeClient
import io.github.aedev.flow.network.ProxyAwareClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Reads the campaign system this app is a consumer of.
 *
 * The campaigns themselves are published elsewhere — this app never creates, edits or pauses one.
 * Its whole job is to ask, per queue slot, which campaign should play next, to turn the answer into
 * a playable [MusicTrack], and to report the qualified plays it delivered.
 *
 * The endpoint is the `get_next_campaign_for_queue_slot` RPC, which picks the least-recently-served
 * eligible campaign and marks it served in one transaction. That atomicity is why the injection
 * layer calls this once per slot rather than fetching a batch: see [CampaignQueueInjection].
 *
 * @param supabaseUrl The project URL, without a trailing slash.
 * @param anonKey The public anon key. Blank in either disables campaigns entirely rather than
 *   failing — a build with no campaign backend configured plays a normal queue.
 */
class CampaignRepository(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val genreTileHost: String = "",
) {
    private val configured: Boolean
        get() = supabaseUrl.isNotBlank() && anonKey.isNotBlank()

    private val http = ProxyAwareClient()

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * The next campaign for one queue slot, or null when none is eligible.
     *
     * Null is an ordinary answer, not an error: the RPC's eligibility filter is fail-closed, so an
     * empty result means "nothing to place right now" and the slot is skipped. Every failure path
     * returns null too — a campaign that cannot be placed must never break the queue it rides on.
     *
     * @param genre The listener's current genre, when the queue was started from a genre surface.
     *   Null asks for a campaign without that constraint, which the RPC's own null branch handles.
     */
    suspend fun fetchNextForQueueSlot(genre: String? = null): MusicTrack? {
        if (!configured) return null
        return withContext(Dispatchers.IO) {
            try {
                val body =
                    buildString {
                        append("{\"p_genre\":")
                        if (genre == null) {
                            append("null")
                        } else {
                            append(JsonPrimitive(genre).toString())
                        }
                        append('}')
                    }
                resolveRow(rpc("get_next_campaign_for_queue_slot", body))
            } catch (e: Exception) {
                Log.w(TAG, "fetchNextForQueueSlot failed for genre=$genre: ${e.message}")
                null
            }
        }
    }

    /**
     * Turns the RPC's JSON array into a playable track.
     *
     * The RPC returns an array (zero or one row) rather than an object, so an empty array is the
     * "no campaign" case. The row's `resolved_song_id` wins when present; otherwise a video id is
     * extracted from the raw `source_url` (any real YouTube shape), and only then is a Spotify link
     * resolved at runtime. Either way the id is resolved through the player endpoint, which supplies
     * title, artist and artwork — the campaign row carries only the link.
     */
    private suspend fun resolveRow(body: String?): MusicTrack? {
        if (body == null) return null
        val array =
            try {
                json.parseToJsonElement(body) as? JsonArray
            } catch (e: Exception) {
                Log.w(TAG, "queue-slot response was not JSON: ${e.message}")
                return null
            }
        val row = array?.firstOrNull() as? JsonObject ?: return null

        val sourceUrl = row["source_url"]?.jsonPrimitive?.content.orEmpty()
        val campaignId = row["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val resolvedSongId = row["resolved_song_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val videoId =
            resolvedSongId
                ?: CampaignUrlResolver.extractVideoId(sourceUrl)
                ?: SpotifyLink.parse(sourceUrl)?.let { CampaignSpotifyResolver.resolve(it)?.videoId }
        if (videoId == null) {
            Log.w(TAG, "queue-slot row carried no usable video id")
            return null
        }

        // The campaign id rides on the resolved track so the play reporter can attribute a
        // qualified play without a second lookup.
        return trackForVideoId(videoId)?.copy(isCampaign = true, campaignId = campaignId)
    }

    /** Resolves a video id to a full track through the player endpoint, or null. */
    private suspend fun trackForVideoId(videoId: String): MusicTrack? {
        val response =
            YouTube.player(videoId, client = YouTubeClient.ANDROID).getOrNull()
                ?: YouTube.player(videoId, client = YouTubeClient.MOBILE).getOrNull()
                ?: return null
        val details = response.videoDetails ?: return null
        return MusicTrack(
            videoId = videoId,
            title = details.title?.takeIf { it.isNotBlank() } ?: return null,
            artist = details.author.orEmpty(),
            thumbnailUrl =
                details.thumbnail
                    ?.thumbnails
                    ?.maxByOrNull { (it.width ?: 0) * (it.height ?: 0) }
                    ?.url
                    .orEmpty(),
            duration = details.lengthSeconds.toIntOrNull() ?: 0,
            views = details.viewCount?.toLongOrNull() ?: 0L,
            channelId = details.channelId,
        )
    }

    /**
     * Registers (or re-registers) this device as a listener and returns the listener id the campaign
     * write RPCs expect. The RPC echoes the device id back. Null on failure — the caller then reports
     * a play without a user id, which the server tolerates.
     */
    suspend fun ensureDeviceListener(deviceId: String): String? =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext null
            try {
                rpc("ensure_device_listener", "{\"p_device_id\":\"$deviceId\"}")
                    ?.trim()
                    ?.trim('"')
                    ?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.w(TAG, "ensureDeviceListener failed: ${e.message}")
                null
            }
        }

    /**
     * Reports a qualified campaign play. Fire-and-forget by contract: a failure here must never
     * surface to the listener, and the play is already over, so there is nothing to retry against.
     */
    suspend fun recordCampaignStream(
        campaignId: String,
        userId: String? = null,
        listenDurationSeconds: Int = 0,
        countryCode: String? = null,
        isFullListen: Boolean = false,
    ) {
        if (!configured) return
        withContext(Dispatchers.IO) {
            try {
                val payload =
                    buildString {
                        append("{\"p_campaign_id\":")
                        append(JsonPrimitive(campaignId))
                        append(',')
                        append("\"p_user_id\":")
                        append(JsonPrimitive(userId ?: UUID.randomUUID().toString()))
                        append(',')
                        append("\"p_listen_duration_seconds\":")
                        append(listenDurationSeconds.coerceAtLeast(0))
                        append(',')
                        append("\"p_country_code\":")
                        append(JsonPrimitive(countryCode ?: "unknown"))
                        append(',')
                        append("\"p_is_full_listen\":")
                        append(isFullListen)
                        append('}')
                    }
                rpc("record_campaign_stream", payload)
            } catch (e: Exception) {
                Log.w(TAG, "recordCampaignStream failed for $campaignId: ${e.message}")
            }
        }
    }

    /**
     * The reviewed portion of `campaign_genre_tile_mapping`, as a `tile_title -> mapped_genre_id`
     * map (the value may be null for a tile an admin confirmed is a mood, not a genre). Empty on any
     * failure, which callers treat as "no confirmed mapping" — the same fail-closed default the rest
     * of this feature uses.
     */
    suspend fun fetchGenreTileMapping(): Map<String, String?> =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext emptyMap()
            try {
                val request =
                    Request
                        .Builder()
                        .url("$supabaseUrl/rest/v1/campaign_genre_tile_mapping?is_reviewed=eq.true&select=tile_title,mapped_genre_id")
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer $anonKey")
                        .get()
                        .build()
                http.get().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyMap()
                    val body = response.body.string().orEmpty()
                    val array =
                        runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
                            ?: return@use emptyMap()
                    array
                        .mapNotNull { element ->
                            val row = element as? JsonObject ?: return@mapNotNull null
                            val title =
                                row["tile_title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                                    ?: return@mapNotNull null
                            val mapped = row["mapped_genre_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                            title to mapped
                        }.toMap()
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchGenreTileMapping failed: ${e.message}")
                emptyMap()
            }
        }

    /**
     * Reports an unresolved genre tile so an admin can review it. Best-effort: the host is
     * mavins-web, not Supabase, so a blank host or a failed call is silently ignored — the tile still
     * reaches the server as a raw term and fails closed there.
     */
    suspend fun ingestGenreTile(tileTitle: String) {
        if (genreTileHost.isBlank() || tileTitle.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val payload = "{\"tile_title\":${JsonPrimitive(tileTitle)}}"
                val request =
                    Request
                        .Builder()
                        .url("${genreTileHost.trimEnd('/')}/api/campaigns/genre-tile-mapping/ingest")
                        .header("Content-Type", "application/json")
                        .post(payload.toRequestBody("application/json".toMediaType()))
                        .build()
                http.get().newCall(request).execute().use { }
            } catch (e: Exception) {
                Log.w(TAG, "ingestGenreTile failed for $tileTitle: ${e.message}")
            }
        }
    }

    /**
     * The campaigns eligible for the home banner right now.
     *
     * Mirrors Velune's own banner query: the live, unpaused, not-completed/cancelled rows of
     * `track_campaigns`, with the track and artist pulled in through PostgREST's embedded-resource
     * syntax (they are not columns on this table). A row that carries no playable video id — a
     * Spotify link the runtime resolver cannot place — is dropped rather than rendered as a card
     * that does nothing when tapped.
     */
    suspend fun fetchLiveCampaignsForBanner(): List<CampaignCard> =
        withContext(Dispatchers.IO) {
            if (!configured) return@withContext emptyList()
            try {
                val request =
                    Request
                        .Builder()
                        .url(
                            "$supabaseUrl/rest/v1/track_campaigns" +
                                "?select=id,source_url,resolved_song_id,total_streams,current_stage," +
                                "tracks(title,cover_url),users(artist_name)" +
                                "&is_active=eq.true&is_paused=eq.false" +
                                "&current_stage=neq.completed&current_stage=neq.cancelled"
                        )
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer $anonKey")
                        .get()
                        .build()
                val body =
                    http.get().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body.string()
                    } ?: return@withContext emptyList()
                val array =
                    runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
                        ?: return@withContext emptyList()
                array.mapNotNull { element ->
                    val row = element as? JsonObject ?: return@mapNotNull null
                    val id = (row["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val sourceUrl = (row["source_url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val resolvedSongId =
                        (row["resolved_song_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    val videoId =
                        resolvedSongId
                            ?: CampaignUrlResolver.extractVideoId(sourceUrl)
                            ?: SpotifyLink.parse(sourceUrl)?.let { CampaignSpotifyResolver.resolve(it)?.videoId }
                            ?: return@mapNotNull null
                    val track = row["tracks"] as? JsonObject
                    val artistRow = row["users"] as? JsonObject
                    val stage = (row["current_stage"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    CampaignCard(
                        id = id,
                        songId = videoId,
                        title =
                            (track?.get("title") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                                ?: "Untitled",
                        artist =
                            (artistRow?.get("artist_name") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                                ?: "Unknown Artist",
                        thumbnailUrl = (track?.get("cover_url") as? JsonPrimitive)?.contentOrNull.orEmpty(),
                        totalStreams = (row["total_streams"] as? JsonPrimitive)?.longOrNull ?: 0L,
                        currentStage = stage.takeIf { it.isNotBlank() } ?: "planting",
                        certified = stageIsCertified(stage),
                        isLive = true,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchLiveCampaignsForBanner failed: ${e.message}")
                emptyList()
            }
        }

    /**
     * The campaign's gathered stream total, read fresh for the banner's own refresh tick. Null on
     * any failure, so the card keeps the snapshot it loaded with rather than showing a zero.
     */
    suspend fun fetchCampaignTotalStreams(campaignId: String): Long? =
        withContext(Dispatchers.IO) {
            if (!configured || campaignId.isBlank()) return@withContext null
            try {
                val request =
                    Request
                        .Builder()
                        .url("$supabaseUrl/rest/v1/track_campaigns?select=total_streams&id=eq.$campaignId")
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer $anonKey")
                        .get()
                        .build()
                http.get().newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val array =
                        runCatching { json.parseToJsonElement(response.body.string()) as? JsonArray }
                            .getOrNull()
                            ?: return@use null
                    ((array.firstOrNull() as? JsonObject)?.get("total_streams") as? JsonPrimitive)?.longOrNull
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetchCampaignTotalStreams failed for $campaignId: ${e.message}")
                null
            }
        }

    /** The stages a reviewer has signed off on, matching the server's own stage rule. */
    private fun stageIsCertified(stage: String?): Boolean =
        when (stage) {
            "branching", "full_bloom" -> true
            else -> false
        }

    /** POST a Supabase RPC and return its raw body, or null on a non-2xx. */
    private fun rpc(
        name: String,
        jsonBody: String,
    ): String? {
        val request =
            Request
                .Builder()
                .url("$supabaseUrl/rest/v1/rpc/$name")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .header("Content-Type", "application/json")
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
        return http.get().newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body.string()
        }
    }

    private companion object {
        const val TAG = "Campaigns"
    }
}
