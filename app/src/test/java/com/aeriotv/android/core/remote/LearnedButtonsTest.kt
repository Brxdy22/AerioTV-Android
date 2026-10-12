package com.aeriotv.android.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android#114: Learn Button storage, key filter and names. */
class LearnedButtonsTest {
    @Test
    fun roundTripKeepsActions() {
        val red = LearnedButton(183, PlayerRemoteAction.SHOW_PROGRAM_INFO, GuideRemoteAction.JUMP_TO_NOW)
        val f5 = LearnedButton(135, PlayerRemoteAction.CHANNEL_LIST, GuideRemoteAction.NONE)
        val decoded = LearnedButtons.fromJson(LearnedButtons.toJson(listOf(red, f5)))
        assertEquals(mapOf(183 to red, 135 to f5), decoded)
    }

    @Test
    fun malformedOrReservedRowsAreSkipped() {
        assertEquals(emptyMap<Int, LearnedButton>(), LearnedButtons.fromJson("not json"))
        assertEquals(emptyMap<Int, LearnedButton>(), LearnedButtons.fromJson(null))
        // DPAD_CENTER (23) is reserved; unknown action falls back to NONE.
        val raw = """[{"keyCode":23,"player":"optionsMenu"},{"keyCode":172,"player":"bogus","guide":"pageUp"}]"""
        val decoded = LearnedButtons.fromJson(raw)
        assertEquals(setOf(172), decoded.keys)
        assertEquals(PlayerRemoteAction.NONE, decoded.getValue(172).player)
        assertEquals(GuideRemoteAction.PAGE_UP, decoded.getValue(172).guide)
    }

    @Test
    fun learnableExcludesFixedKeys() {
        listOf(4, 3, 19, 20, 21, 22, 23, 66, 85, 89, 90, 166, 167, 7, 16, 144, 153, 24, 25).forEach {
            assertFalse("key $it", LearnedButtons.isLearnable(it))
        }
        // PROG_RED, GUIDE, INFO, F1, BUTTON_A, LAST_CHANNEL, TV
        listOf(183, 172, 165, 131, 96, 229, 170).forEach {
            assertTrue("key $it", LearnedButtons.isLearnable(it))
        }
    }

    @Test
    fun displayNames() {
        assertEquals("Red", LearnedButtons.displayName("KEYCODE_PROG_RED", 183))
        assertEquals("Last Channel", LearnedButtons.displayName("KEYCODE_LAST_CHANNEL", 229))
        assertEquals("F5", LearnedButtons.displayName("KEYCODE_F5", 135))
        assertEquals("Button A", LearnedButtons.displayName("KEYCODE_BUTTON_A", 96))
        assertEquals("Button 300", LearnedButtons.displayName("300", 300))
    }
}
