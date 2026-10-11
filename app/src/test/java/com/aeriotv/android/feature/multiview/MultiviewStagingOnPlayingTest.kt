package com.aeriotv.android.feature.multiview

import com.aeriotv.android.core.data.M3UChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staging on top of the channel playing in the mini player (Logan 2026-10-11). */
class MultiviewStagingOnPlayingTest {
    private fun ch(name: String) = M3UChannel(id = "m3u:$name", name = name, url = "http://p/$name")
    private val espn2 = ch("ESPN2 HD")
    private val espnu = ch("ESPNU HD")
    private val news = ch("ESPNews HD")

    @Test fun playingChannelCountsFirstAndIsNotDuplicated() {
        val s = MultiviewStore()
        s.stageOnPlaying(espn2, espn2)
        assertTrue(s.selected.value.isEmpty())
        s.stageOnPlaying(espn2, espnu)
        assertEquals(listOf(espn2.id, espnu.id), s.selected.value.map { it.id })
        assertTrue(s.isStaging.value)
        s.stageOnPlaying(espn2, news)
        assertEquals(3, s.selected.value.size)
        assertEquals(espn2.id, s.takePlayingSeedForLaunch())
        assertNull(s.playingSeedId.value)
    }

    @Test fun removeExtraAndClearLeaveNothingButKeepPlayback() {
        val s = MultiviewStore()
        s.stageOnPlaying(espn2, espnu)
        s.stageOnPlaying(espn2, espnu)
        assertEquals(listOf(espn2.id), s.selected.value.map { it.id })
        s.stageOnPlaying(espn2, news)
        s.clearStaged()
        assertTrue(s.selected.value.isEmpty())
        assertFalse(s.isStaging.value)
        assertNull(s.takePlayingSeedForLaunch())
    }

    @Test fun doneKeepsExtrasForTheNextAdd() {
        val s = MultiviewStore()
        s.stageOnPlaying(espn2, espnu)
        s.setStaging(false) // Done
        s.stageOnPlaying(espn2, news)
        assertEquals(listOf(espn2.id, espnu.id, news.id), s.selected.value.map { it.id })
    }

    @Test fun menuStates() {
        val staged = listOf(MultiviewTile.live(espn2), MultiviewTile.live(espnu))
        assertEquals(MultiviewMenuState.AddDisabled, multiviewMenuState(espn2.id, espn2.id, staged))
        assertEquals(MultiviewMenuState.AddDisabled, multiviewMenuState(espn2.id, espn2.id, emptyList()))
        assertEquals(MultiviewMenuState.Remove, multiviewMenuState(espnu.id, espn2.id, staged))
        assertEquals(MultiviewMenuState.Add, multiviewMenuState(news.id, espn2.id, staged))
        assertEquals(MultiviewMenuState.Remove, multiviewMenuState(espn2.id, null, staged))
        // A stale pile not built on the playing channel reads Add.
        assertEquals(MultiviewMenuState.Add, multiviewMenuState(espnu.id, news.id, staged))
    }

    @Test fun playingChannelChangeRepointsSlotOne() {
        val s = MultiviewStore()
        s.stageOnPlaying(espn2, espnu)
        s.onPlayingChannelChanged(news)
        assertEquals(listOf(news.id, espnu.id), s.selected.value.map { it.id })
        s.onPlayingChannelChanged(espnu)
        assertEquals(listOf(espnu.id), s.selected.value.map { it.id })
    }
}
