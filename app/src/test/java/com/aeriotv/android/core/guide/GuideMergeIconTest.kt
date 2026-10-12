package com.aeriotv.android.core.guide

import com.aeriotv.android.core.data.EPGProgramme
import org.junit.Assert.assertEquals
import org.junit.Test

/** merge() keeps the art from whichever duplicate carries it. */
class GuideMergeIconTest {
    private fun p(icon: String?) = EPGProgramme(
        channelId = "c", title = "Game", description = "", startMillis = 0, endMillis = 3_600_000,
        category = "", iconUrl = icon,
    )

    @Test fun incomingIconIsKeptWhenExistingHasNone() =
        assertEquals("http://a/i.png", GuideMerge.merge(p(null), p("http://a/i.png")).iconUrl)

    @Test fun existingIconWins() =
        assertEquals("http://a/e.png", GuideMerge.merge(p("http://a/e.png"), p("http://a/i.png")).iconUrl)

    @Test fun blankExistingFallsThrough() =
        assertEquals("http://a/i.png", GuideMerge.merge(p(""), p("http://a/i.png")).iconUrl)

    @Test fun dedupCarriesIcon() =
        assertEquals("http://a/i.png", GuideMerge.dedup(listOf(p(null), p("http://a/i.png"))).single().iconUrl)
}
