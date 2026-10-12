package com.aeriotv.android.core.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Android#132: dummy-EPG programs carry their poster in custom_properties. */
class EpgEntryArtworkTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun entry(extra: String) = json.decodeFromString(
        DispatcharrEpgEntry.serializer(),
        """{"id":"dummy-12-20261012T180000","start_time":"2026-10-12T18:00:00Z","end_time":"2026-10-12T21:00:00Z","title":"Game"$extra}""",
    )

    @Test
    fun topLevelIconWins() {
        assertEquals("http://a/top.png", entry(""","icon":"http://a/top.png","custom_properties":{"icon":"http://a/cp.png"}""").artworkUrl)
    }

    @Test
    fun dummyProgramFallsBackToCustomPropertiesIcon() {
        assertEquals("http://a/cp.png", entry(""","custom_properties":{"icon":"http://a/cp.png","live":true}""").artworkUrl)
    }

    @Test
    fun imagesFallbackAcceptsStringsAndObjects() {
        assertEquals("http://a/1.jpg", entry(""","custom_properties":{"images":[{"url":""},{"url":"http://a/1.jpg"}]}""").artworkUrl)
        assertEquals("http://a/2.jpg", entry(""","custom_properties":{"images":["http://a/2.jpg"]}""").artworkUrl)
    }

    @Test
    fun nullCustomPropertiesHasNoArt() {
        assertNull(entry(""","custom_properties":null""").artworkUrl)
        assertNull(entry("").artworkUrl)
    }
}
