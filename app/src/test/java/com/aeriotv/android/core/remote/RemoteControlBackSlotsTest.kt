package com.aeriotv.android.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlBackSlotsTest {

    @Test
    fun defaultsMatchTodaysBack() {
        val map = RemoteControlMap.DEFAULT
        assertEquals(GuideRemoteAction.BACK_LADDER, map.guideAction(RemoteSlot.BACK_SHORT))
        assertEquals(GuideRemoteAction.NONE, map.guideAction(RemoteSlot.BACK_LONG))
        assertEquals(PlayerRemoteAction.NONE, map.playerAction(RemoteSlot.BACK_SHORT))
    }

    @Test
    fun customBackRoundTrips() {
        val map = RemoteControlMap(
            preset = RemotePreset.CUSTOM,
            guide = mapOf(
                RemoteSlot.BACK_SHORT to GuideRemoteAction.FOCUS_GROUP_PILLS,
                RemoteSlot.BACK_LONG to GuideRemoteAction.JUMP_TO_NOW,
            ),
        )
        val back = RemoteControlMap.fromJson(map.toJson())
        assertEquals(GuideRemoteAction.FOCUS_GROUP_PILLS, back.guideAction(RemoteSlot.BACK_SHORT))
        assertEquals(GuideRemoteAction.JUMP_TO_NOW, back.guideAction(RemoteSlot.BACK_LONG))
    }

    @Test
    fun duplicatePressKeepsFirstAndLogs() {
        val logs = mutableListOf<String>()
        val saved = RemoteControlMap.log
        RemoteControlMap.log = { logs += it }
        try {
            val raw = """{"version":1,"preset":"custom","guideKeys":2,"player":{"backShort":"playPause"},""" +
                """"guide":{"backShort":"jumpToNow","backShort":"pageDown"}}"""
            val map = RemoteControlMap.fromJson(raw)
            assertEquals(GuideRemoteAction.JUMP_TO_NOW, map.guideAction(RemoteSlot.BACK_SHORT))
            assertEquals(listOf("[REMOTE] duplicate action for backShort dropped"), logs)
            assertFalse(RemoteSlot.BACK_SHORT in map.player)
        } finally {
            RemoteControlMap.log = saved
        }
    }

    @Test
    fun guideHintsShowMappedBack() {
        val map = RemoteControlMap(
            preset = RemotePreset.CUSTOM,
            guide = mapOf(RemoteSlot.BACK_LONG to GuideRemoteAction.JUMP_TO_NOW),
        )
        val hints = RemoteControlHints.guideStripHints(map, sidebarGroups = true, miniActive = false)
        assertTrue(RemoteHint("Back", "Top channel") in hints)
        assertTrue(RemoteHint("Hold Back", "Now") in hints)
    }
}
