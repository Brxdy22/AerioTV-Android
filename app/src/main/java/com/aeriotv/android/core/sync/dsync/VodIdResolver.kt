package com.aeriotv.android.core.sync.dsync

import android.content.Context
import android.util.Log
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.vod.VodCatalogStore
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.preferences.VodLibrarySnapshotStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Episode uuid/id pairs seen by the client (the series episode list), handed
 * to [VodIdResolver]. A plain hook so DispatcharrClient needs no new deps.
 */
object VodIdSink {
    @Volatile var onEpisodes: ((List<Pair<String, Int>>) -> Unit)? = null
}

/**
 * Dispatcharr Sync rows carry the server's INTEGER primary keys for VOD
 * (schema 4.3, 4.5, 4.6); local rows are keyed by the movie or episode uuid.
 * Movies resolve through the VOD catalog (its payload holds `id`); episodes
 * through a small saved map filled from every series episode list the app
 * loads, fetching one series' list on demand when a pair is unknown.
 * Series ids are already integers locally.
 */
@Singleton
class VodIdResolver @Inject constructor(
    @ApplicationContext context: Context,
    private val catalog: VodCatalogStore,
    private val snapshots: VodLibrarySnapshotStore,
    private val client: DispatcharrClient,
) {
    private val prefs = context.getSharedPreferences("aerio_vod_ids", Context.MODE_PRIVATE)
    private val fetchedSeries = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    init {
        VodIdSink.onEpisodes = { pairs -> record("e", pairs) }
    }

    private fun record(kind: String, pairs: List<Pair<String, Int>>) {
        if (pairs.isEmpty()) return
        val e = prefs.edit()
        pairs.forEach { (uuid, id) ->
            if (uuid.isBlank()) return@forEach
            e.putInt("$kind:$uuid", id).putString("${kind.uppercase()}:$id", uuid)
        }
        e.apply()
    }

    fun catalogKey(row: PlaylistEntity): String = snapshots.identity(row)

    /** Movie uuid -> Dispatcharr movie id. */
    suspend fun movieId(row: PlaylistEntity, uuid: String): Int? {
        if (prefs.contains("m:$uuid")) return prefs.getInt("m:$uuid", 0)
        val id = catalog.movie(catalogKey(row), uuid)?.id ?: return null
        record("m", listOf(uuid to id))
        return id
    }

    /** Dispatcharr movie ids -> local movie uuids (catalog scan only for unknown ids). */
    suspend fun movieUuids(row: PlaylistEntity, ids: Set<Int>): Map<Int, String> {
        val out = HashMap<Int, String>()
        val missing = HashSet<Int>()
        ids.forEach { id -> prefs.getString("M:$id", null)?.let { out[id] = it } ?: missing.add(id) }
        if (missing.isNotEmpty()) {
            val found = catalog.movieUuidsByIds(catalogKey(row), missing)
            record("m", found.map { (id, uuid) -> uuid to id })
            out += found
        }
        return out
    }

    /** Episode uuid -> id; loads [seriesId]'s episode list once when unknown. */
    suspend fun episodeId(base: String, apiKey: String, uuid: String, seriesId: Int?): Int? {
        if (prefs.contains("e:$uuid")) return prefs.getInt("e:$uuid", 0)
        if (seriesId == null || !loadSeries(base, apiKey, seriesId)) return null
        return if (prefs.contains("e:$uuid")) prefs.getInt("e:$uuid", 0) else null
    }

    /** Episode id -> local uuid; loads [seriesId]'s episode list once when unknown. */
    suspend fun episodeUuid(base: String, apiKey: String, id: Int, seriesId: Int?): String? {
        prefs.getString("E:$id", null)?.let { return it }
        if (seriesId == null || !loadSeries(base, apiKey, seriesId)) return null
        return prefs.getString("E:$id", null)
    }

    private suspend fun loadSeries(base: String, apiKey: String, seriesId: Int): Boolean {
        if (!fetchedSeries.add(seriesId)) return false
        return runCatching {
            val page = client.getSeriesEpisodesFirstPage(base, apiKey, seriesId)
            record("e", page.results.map { it.uuid to it.id })
            true
        }.onFailure { Log.w("AerioDsync", "[DSYNC] episode ids for series $seriesId: ${it.javaClass.simpleName}") }
            .getOrDefault(false)
    }
}
