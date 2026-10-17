package com.aeriotv.android.core.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePinTest {
    private val salt = ByteArray(16) { it.toByte() }

    @Test fun normalizeNumbers() {
        assertEquals("2580", PinRules.normalize(PinType.Numbers, listOf("2", "5", "8", "0")))
        assertNull(PinRules.normalize(PinType.Numbers, listOf("2", "5", "8")))
        assertNull(PinRules.normalize(PinType.Numbers, List(7) { "1" }))
        assertNull(PinRules.normalize(PinType.Numbers, listOf("2", "5", "x", "0")))
    }

    @Test fun normalizeDirections() {
        assertEquals("UUDL", PinRules.normalize(PinType.Directions, listOf("Up", "Up", "Down", "Left")))
        assertEquals("RRRRRR", PinRules.normalize(PinType.Directions, List(6) { "Right" }))
        assertNull(PinRules.normalize(PinType.Directions, listOf("Up", "Up", "Down", "1")))
    }

    @Test fun hashIsStableSaltedAndBound() {
        val h = PinRules.hash(salt, "p1", PinType.Numbers, "2580")
        assertEquals(h, PinRules.hash(salt, "p1", PinType.Numbers, "2580"))
        assertEquals(64, h.length)
        assertNotEquals(h, PinRules.hash(ByteArray(16), "p1", PinType.Numbers, "2580"))
        assertNotEquals(h, PinRules.hash(salt, "p2", PinType.Numbers, "2580"))
        assertNotEquals(h, PinRules.hash(salt, "p1", PinType.Numbers, "2581"))
        assertFalse(h.contains("2580"))
        assertTrue(PinRules.matches(h, PinRules.hash(salt, "p1", PinType.Numbers, "2580")))
    }

    @Test fun backoffLocksAfterFiveAndDoubles() {
        var b = PinBackoff()
        val t = 1_000_000L
        repeat(4) { b = b.onWrong(t) }
        assertEquals(0, b.remainingSeconds(t))
        b = b.onWrong(t)
        assertEquals(30, b.remainingSeconds(t))
        assertEquals(0, b.remainingSeconds(t + 30_000))
        val t2 = t + 30_000
        repeat(5) { b = b.onWrong(t2) }
        assertEquals(60, b.remainingSeconds(t2))
        val t3 = t2 + 60_000
        repeat(5) { b = b.onWrong(t3) }
        assertEquals(120, b.remainingSeconds(t3))
        assertEquals(PinBackoff(), b.onOk())
    }

    @Test fun backoffCapsAndRoundTrips() {
        assertEquals(30_000L shl 10, PinBackoff.lockMs(50))
        val b = PinBackoff(3, 2, 12345L)
        assertEquals(b, PinBackoff.decode(b.encode()))
        assertEquals(PinBackoff(), PinBackoff.decode("garbage"))
    }
}
