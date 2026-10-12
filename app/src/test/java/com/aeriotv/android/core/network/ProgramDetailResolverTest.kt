package com.aeriotv.android.core.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Regenerated Teamarr program ids: a 404 re-resolves by tvg_id and start. */
class ProgramDetailResolverTest {
    private val start = java.time.Instant.parse("2026-10-12T23:00:00Z").toEpochMilli()
    private val art = "https://game-thumbs.example/cover.png"
    private fun row(id: Int, startIso: String) =
        DispatcharrEpgEntry(id = JsonPrimitive(id), startTime = startIso, endTime = startIso, title = "Game")

    @Before fun reset() = ProgramDetailResolver.clearForTest()

    @Test
    fun notFoundReResolvesByTvgIdAndStart() = runBlocking {
        val calls = mutableListOf<Int>()
        val logs = mutableListOf<String>()
        val r = ProgramDetailResolver.resolve(
            programId = 100, tvgIds = listOf("teamarr.1"), startMillis = start, title = "Game",
            fetchDetail = { id ->
                calls += id
                if (id == 100) throw DispatcharrError.NotFound("404") else DispatcharrProgramDetail(id = id, icon = art)
            },
            listByTvgId = { listOf(row(150, "2026-10-12T22:00:00+00:00"), row(205, "2026-10-12T23:00:00+00:00")) },
            log = { logs += it },
        )
        assertEquals(art, r.detail?.bestPosterString)
        assertEquals(205, r.programId)
        assertEquals(100, r.staleFrom)
        assertEquals(listOf(100, 205), calls)
        assertTrue(logs.single().startsWith("[PREVIEW-ART] stale program id 100 -> 205"))
        // Next lookup goes straight to the new id.
        assertEquals(205, ProgramDetailResolver.currentId(100))
    }

    @Test
    fun notFoundWithNoMatchIsAFailureNotACachedMiss() = runBlocking {
        val logs = mutableListOf<String>()
        val r = ProgramDetailResolver.resolve(
            programId = 100, tvgIds = listOf("teamarr.1"), startMillis = start, title = "Game",
            fetchDetail = { throw DispatcharrError.NotFound("404") },
            listByTvgId = { listOf(row(150, "2026-10-12T22:00:00Z")) },
            log = { logs += it },
        )
        assertTrue(r.failed)
        assertNull(r.staleFrom)
        assertTrue(logs.single().contains("404 with no match"))
    }

    @Test
    fun otherFailuresDoNotReResolve() = runBlocking {
        var listed = false
        val r = ProgramDetailResolver.resolve(
            programId = 100, tvgIds = listOf("teamarr.1"), startMillis = start, title = "Game",
            fetchDetail = { throw DispatcharrError.Transport("HTTP 500") },
            listByTvgId = { listed = true; emptyList() },
        )
        assertTrue(r.failed)
        assertEquals(false, listed)
    }

    @Test
    fun detailFallsBackToCustomPropertiesIcon() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val d = json.decodeFromString(
            DispatcharrProgramDetail.serializer(),
            """{"id":5,"custom_properties":{"icon":"http://a/cp.png"}}""",
        )
        assertEquals("http://a/cp.png", d.bestPosterString)
    }
}
