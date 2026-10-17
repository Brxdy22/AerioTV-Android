package com.aeriotv.android.core.sync.dsync

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DsyncSettingsValidatorTest {
    private fun ok(k: String, v: JsonPrimitive) = DsyncSettingsValidator.accepts(k, v)

    @Test fun enums() {
        listOf("dark", "light", "system").forEach { assertTrue(ok("appearanceMode", JsonPrimitive(it))) }
        assertFalse(ok("appearanceMode", JsonPrimitive("Dark")))
        listOf("", "LiveTV", "DVR", "Movies", "TVShows", "Settings").forEach { assertTrue(ok("defaultTab", JsonPrimitive(it))) }
        assertFalse(ok("defaultTab", JsonPrimitive("Home")))
        listOf("system", "12", "24").forEach { assertTrue(ok("timeFormat", JsonPrimitive(it))) }
        assertFalse(ok("timeFormat", JsonPrimitive(24)))
        assertTrue(ok("guideGroupSelector", JsonPrimitive("pills")))
        assertTrue(ok("phoneGroupSelector", JsonPrimitive("sidebar")))
        assertFalse(ok("phoneGroupSelector", JsonPrimitive("drawer")))
        assertTrue(ok("guideSidebarLayout", JsonPrimitive("shift")))
        assertFalse(ok("guideSidebarLayout", JsonPrimitive("push")))
        listOf("off", "current", "all").forEach { assertTrue(ok("guideProgramInfoButton", JsonPrimitive(it))) }
        assertFalse(ok("guideProgramInfoButton", JsonPrimitive("on")))
        assertTrue(ok("selectedTheme", JsonPrimitive("Midnight")))
        assertFalse(ok("selectedTheme", JsonPrimitive("midnight")))
    }

    @Test fun ranges() {
        assertTrue(ok("textScale", JsonPrimitive(0.85)))
        assertTrue(ok("subtextScale", JsonPrimitive(1.5)))
        assertFalse(ok("textScale", JsonPrimitive(1.6)))
        assertFalse(ok("subtextScale", JsonPrimitive(0.8)))
        assertFalse(ok("textScale", JsonPrimitive("1.1")))
        assertTrue(ok("textContrast", JsonPrimitive(0)))
        assertTrue(ok("textContrast", JsonPrimitive(1.0)))
        assertFalse(ok("textContrast", JsonPrimitive(1.2)))
        assertFalse(ok("textContrast", JsonPrimitive(-0.1)))
    }

    @Test fun booleansMustBeReal() {
        assertTrue(ok("trueBlack", JsonPrimitive(true)))
        assertFalse(ok("trueBlack", JsonPrimitive("true")))
        assertFalse(ok("trueBlack", JsonPrimitive(1)))
        DsyncSettingsValidator.BOOLEANS.forEach { assertTrue(it, ok(it, JsonPrimitive(false))) }
    }

    @Test fun filterSplitsAndDescribes() {
        val r = DsyncSettingsValidator.filter(
            mapOf(
                "appearanceMode" to JsonPrimitive("light"),
                "trueBlack" to JsonPrimitive("yes"),
                "textScale" to JsonPrimitive(3),
            ),
        )
        assertEquals(setOf("appearanceMode"), r.accepted.keys)
        assertEquals(listOf("trueBlack=\"yes\"", "textScale=3"), r.ignored)
    }
}
