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

    // ---- policy-aware rule (SiriusXM start time, 2026-10-14) ----
    private val U = AudioOnlyStartPolicy.UNKNOWN
    private val M = AudioOnlyStartPolicy.REMEMBERED
    private val R = AudioOnlyStartPolicy.REQUESTED

    @Test fun unknownPolicyIsExactlyTheOldRule() {
        for (wall in longArrayOf(0, 1_000, 3_999, 4_000, 11_999, 12_000)) {
            for (bytes in booleanArrayOf(false, true)) {
                assertTrue(
                    AudioOnlyGraceRule.shouldDrop(U, wall, 60_000, bytes) ==
                        AudioOnlyGraceRule.shouldDrop(wall, bytes),
                )
            }
        }
    }

    @Test fun radioModeDropsOnFirstAudioSample() {
        assertTrue(AudioOnlyGraceRule.shouldDrop(R, 0, 0, false))
        assertTrue(AudioOnlyGraceRule.shouldDrop(R, 0, 0, true))
    }

    @Test fun rememberedNeedsTwoSecondsAudioAndOneSecondWall() {
        assertFalse(AudioOnlyGraceRule.shouldDrop(M, 999, 5_000, false))
        assertFalse(AudioOnlyGraceRule.shouldDrop(M, 3_000, 1_999, false))
        assertTrue(AudioOnlyGraceRule.shouldDrop(M, 1_000, 2_000, false))
    }

    @Test fun rememberedWithVideoBytesFallsBackToUnknown() {
        assertFalse(AudioOnlyGraceRule.shouldDrop(M, 5_000, 5_000, true))
        assertTrue(AudioOnlyGraceRule.shouldDrop(M, 12_000, 5_000, true))
    }

    @Test fun declaredDropsOnFirstAudioSample() {
        assertTrue(AudioOnlyGraceRule.shouldDrop(AudioOnlyStartPolicy.DECLARED, 0, 0, false))
        assertTrue(AudioOnlyGraceRule.shouldDrop(AudioOnlyStartPolicy.DECLARED, 0, 0, true))
    }
}
