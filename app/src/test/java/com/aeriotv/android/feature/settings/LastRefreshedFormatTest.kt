package com.aeriotv.android.feature.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * GH #136: "Last refreshed" for a fixed instant during Toronto DST.
 * 2026-10-12T19:00:00Z is 3:00 PM EDT (UTC-4).
 */
class LastRefreshedFormatTest {
    private val instant = 1_791_831_600_000L // 2026-10-12T19:00:00Z

    @Test fun torontoDuringDstShowsEdtWallTime() {
        val s = LastRefreshedFormat.format(instant, TimeZone.getTimeZone("America/Toronto"), Locale.US)
        assertTrue(s, s.contains("3:00:00"))
    }

    /** A device whose zone is the legacy fixed "EST" id (no DST) reads one
     *  hour earlier: the reported symptom. Documents the hypothesis only. */
    @Test fun fixedEstZoneIsOneHourBehind() {
        val s = LastRefreshedFormat.format(instant, TimeZone.getTimeZone("EST"), Locale.US)
        assertTrue(s, s.contains("2:00:00"))
    }
}
