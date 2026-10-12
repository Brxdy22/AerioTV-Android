package com.aeriotv.android.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOnlyGraceRuleTest {
    @Test fun silentVideoPidKeptBeforeFourSeconds() {
        assertFalse(AudioOnlyGraceRule.shouldDrop(254, false))
        assertFalse(AudioOnlyGraceRule.shouldDrop(3_999, false))
    }

    @Test fun silentVideoPidDroppedAtFourSeconds() {
        assertTrue(AudioOnlyGraceRule.shouldDrop(4_000, false))
    }

    @Test fun liveVideoPidWaitsPastSilentGrace() {
        assertFalse(AudioOnlyGraceRule.shouldDrop(4_000, true))
        assertFalse(AudioOnlyGraceRule.shouldDrop(11_999, true))
    }

    @Test fun liveVideoPidGivenUpAtCap() {
        assertTrue(AudioOnlyGraceRule.shouldDrop(12_000, true))
    }
}
