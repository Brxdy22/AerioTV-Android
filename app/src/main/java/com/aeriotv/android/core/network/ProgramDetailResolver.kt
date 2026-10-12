package com.aeriotv.android.core.network

import java.util.concurrent.ConcurrentHashMap

/**
 * Program detail lookup that survives regenerated program ids (Android TV
 * Discord report, 2026-10-12). Teamarr rebuilds its programs on every EPG
 * run, so Dispatcharr hands out new program ids each refresh while the cached
 * guide rows keep the old ones; /api/epg/programs/<old>/ then answers 404 and
 * the art was lost for the session. On a 404 the program is found again by
 * tvg_id and start time through /api/epg/programs/?tvg_id=, its detail is
 * fetched under the new id, and the old to new mapping is remembered (and
 * persisted by the caller) so the next lookup is direct.
 *
 * Pure logic over two fetch lambdas so it is unit-testable.
 */
object ProgramDetailResolver {
    /** Old program id to the id the server uses now. Process lifetime. */
    private val remapped = ConcurrentHashMap<Int, Int>()

    fun currentId(programId: Int): Int = remapped[programId] ?: programId

    internal fun clearForTest() = remapped.clear()

    data class Result(
        /** Detail for the program, or null when it could not be fetched. */
        val detail: DispatcharrProgramDetail?,
        /** The id the server answered for (new id after a re-resolve). */
        val programId: Int,
        /** Set when a 404 was re-resolved to a different id. */
        val staleFrom: Int? = null,
    ) {
        /** A failed lookup is never cached as "no art". */
        val failed: Boolean get() = detail == null
    }

    suspend fun resolve(
        programId: Int,
        tvgIds: List<String>,
        startMillis: Long,
        title: String,
        fetchDetail: suspend (Int) -> DispatcharrProgramDetail,
        listByTvgId: suspend (String) -> List<DispatcharrEpgEntry>,
        log: (String) -> Unit = {},
    ): Result {
        val id = currentId(programId)
        val first = runCatching { fetchDetail(id) }
        first.getOrNull()?.let { return Result(it, id) }
        if (first.exceptionOrNull() !is DispatcharrError.NotFound) return Result(null, id)
        for (tvg in tvgIds.filter { it.isNotBlank() }.distinct()) {
            val rows = runCatching { listByTvgId(tvg) }.getOrNull() ?: continue
            val match = rows.firstOrNull { row ->
                val start = parseStartMillis(row.startTime) ?: return@firstOrNull false
                kotlin.math.abs(start - startMillis) < 1_000L && row.programIdInt != null
            } ?: continue
            val newId = match.programIdInt ?: continue
            if (newId == id) continue
            val detail = runCatching { fetchDetail(newId) }.getOrNull()
            log("[PREVIEW-ART] stale program id $id -> $newId ($title)")
            if (detail != null) {
                remapped[programId] = newId
                if (id != programId) remapped[id] = newId
            }
            return Result(detail, newId, staleFrom = id)
        }
        log("[PREVIEW-ART] program id $id 404 with no match by tvg_id and start ($title)")
        return Result(null, id)
    }

    internal fun parseStartMillis(raw: String): Long? = runCatching {
        java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
    }.recoverCatching {
        java.time.Instant.parse(raw).toEpochMilli()
    }.getOrNull()
}
