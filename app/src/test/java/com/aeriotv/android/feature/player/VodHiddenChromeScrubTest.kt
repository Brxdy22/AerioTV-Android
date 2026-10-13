package com.aeriotv.android.feature.player

import com.aeriotv.android.core.remote.PlayerRemoteAction
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** GH #135: hidden-chrome Left/Right in the VOD and recording player. */
class VodHiddenChromeScrubTest {
    private val s = VodHiddenChromeScrub

    @Before fun reset() = s.resetForTest()

    @Test fun customSeekMapSkipsWithSkipWithoutControlsOff() {
        assertEquals(s.SKIP_BACK, s.decide(true, false, false, 1_000, PlayerRemoteAction.SEEK_BACKWARD, false))
        assertEquals(s.SKIP_FORWARD, s.decide(false, false, false, 2_000, PlayerRemoteAction.SEEK_FORWARD, false))
    }

    @Test fun customSeekMapSkipsWithSkipWithoutControlsOn() =
        assertEquals(s.SKIP_FORWARD, s.decide(false, false, false, 1_000, PlayerRemoteAction.SEEK_FORWARD, true))

    @Test fun mappedDirectionFollowsTheAction() =
        assertEquals(s.SKIP_FORWARD, s.decide(true, false, false, 1_000, PlayerRemoteAction.SEEK_FORWARD, false))

    @Test fun chromeVisibleKeepsNormalMeaning() =
        assertEquals(s.NOT_SKIP, s.decide(true, false, true, 1_000, PlayerRemoteAction.SEEK_BACKWARD, true))

    @Test fun heldRepeatIsPaced() {
        assertEquals(s.SKIP_BACK, s.decide(true, false, false, 1_000, PlayerRemoteAction.SEEK_BACKWARD, false))
        assertEquals(s.SWALLOW, s.decide(true, true, false, 1_100, PlayerRemoteAction.SEEK_BACKWARD, false))
        assertEquals(s.SKIP_BACK, s.decide(true, true, false, 1_300, PlayerRemoteAction.SEEK_BACKWARD, false))
    }

    @Test fun nonSeekMappingFallsToSkipWithoutControls() {
        assertEquals(s.SKIP_BACK, s.decide(true, false, false, 1_000, PlayerRemoteAction.CHANNEL_DOWN, true))
        assertEquals(s.NOT_SKIP, s.decide(true, false, false, 2_000, PlayerRemoteAction.CHANNEL_DOWN, false))
    }

    @Test fun defaultMapUsesSkipWithoutControls() {
        assertEquals(s.SKIP_FORWARD, s.decide(false, false, false, 1_000, null, true))
        assertEquals(s.NOT_SKIP, s.decide(false, false, false, 2_000, null, false))
    }

    @Test fun playPauseMapping() =
        assertEquals(s.PLAY_PAUSE, s.decide(true, false, false, 1_000, PlayerRemoteAction.PLAY_PAUSE, false))
}
