package io.github.aedev.flow.campaign

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.music.model.MusicTrack
import java.util.UUID

/**
 * The app's single campaign entry point.
 *
 * The backend address and key are per-build configuration, handed in once at startup rather than
 * hardcoded here. Until [init] runs, [nextForSlot] returns null and queues play without campaigns,
 * which is also exactly what a build with no backend configured does.
 */
object Campaigns {
    private const val TAG = "Campaigns"

    private const val PREFS = "campaign_prefs"
    private const val KEY_DEVICE = "campaign_device_id"

    @Volatile
    private var repository: CampaignRepository? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedDeviceId: String? = null

    /**
     * Enables campaign injection. Safe to call more than once; the last call wins. A blank url or
     * key disables campaigns rather than throwing.
     *
     * @param genreTileHost The mavins-web origin used to report an unresolved genre tile for
     *   review. Blank disables that reporting only; the tile still reaches the server as a raw term
     *   and fails closed there.
     */
    fun init(
        context: Context,
        supabaseUrl: String,
        anonKey: String,
        genreTileHost: String = "",
    ) {
        appContext = context.applicationContext
        if (supabaseUrl.isBlank() || anonKey.isBlank()) {
            Log.i(TAG, "campaigns disabled: no backend configured")
            repository = null
            return
        }
        repository =
            CampaignRepository(
                supabaseUrl = supabaseUrl.trimEnd('/'),
                anonKey = anonKey,
                genreTileHost = genreTileHost,
            )
    }

    /** True when a backend is configured and campaign injection is live. */
    val enabled: Boolean get() = repository != null

    /**
     * This install's listener identity. It has to survive a restart and an app update but not an
     * uninstall, so it is a UUID persisted in preferences — the same posture the party identity
     * uses. Generated lazily and cached, since it is read on every reported play.
     */
    fun deviceId(): String {
        cachedDeviceId?.let { return it }
        val context = appContext
        if (context == null) return UUID.randomUUID().toString()
        synchronized(this) {
            cachedDeviceId?.let { return it }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_DEVICE, null)
            val id = existing ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE, it).apply() }
            cachedDeviceId = id
            return id
        }
    }

    /** The campaign for one queue slot, or null when there is none to place. */
    suspend fun nextForSlot(genre: String? = null): MusicTrack? = repository?.fetchNextForQueueSlot(genre)

    /**
     * The campaign for one queue slot on a queue started from [genreTerm], the raw genre/mood term
     * the surface was tagged with (or null for an unscoped queue).
     *
     * The two cases stay distinct: a null term reaches the RPC as a null genre and asks for any
     * eligible campaign — injection is a monetization slot, not a genre feature. A non-null term is
     * resolved to its canonical genre id (or the raw term when unreviewed), so an R&B tile cannot
     * be served a Hip-Hop campaign.
     */
    suspend fun nextForSlotResolvingGenre(genreTerm: String?): MusicTrack? {
        val repository = repository ?: return null
        val genre = genreTerm?.let { GenreTileMappingCache.resolveTile(it, repository) }
        return repository.fetchNextForQueueSlot(genre)
    }

    /**
     * What a genre-tile tap should send: a resolved canonical genre, the raw tile title when it is
     * not yet reviewed, or null for a tile reviewed as a mood. See [GenreTileMappingCache.resolveTile].
     */
    suspend fun resolveGenreTile(tileTitle: String): String? {
        val r = repository ?: return null
        return GenreTileMappingCache.resolveTile(tileTitle, r)
    }

    /** Registers this device as a listener; returns the listener id, or null. */
    suspend fun ensureDeviceListener(deviceId: String): String? = repository?.ensureDeviceListener(deviceId)

    /** Reports a qualified campaign play. Fire-and-forget. */
    suspend fun recordStream(
        campaignId: String,
        userId: String? = null,
        listenDurationSeconds: Int = 0,
        countryCode: String? = null,
        isFullListen: Boolean = false,
    ) = repository?.recordCampaignStream(campaignId, userId, listenDurationSeconds, countryCode, isFullListen)
}
