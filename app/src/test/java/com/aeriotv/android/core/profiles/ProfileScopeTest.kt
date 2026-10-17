package com.aeriotv.android.core.profiles

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Profiles migration contract: the default profile reads the exact legacy keys
 * (no data moves), a second profile gets its own keys, and Drive sync under
 * defaultOnly() always sees the default profile.
 */
class ProfileScopeTest {

    private fun overlay(pid: String, id: String) = ProfileScope.Overlay(
        playlistId = pid, profileId = id, apiKey = "k2", username = "Test1", password = "p",
        userLevel = 1, dvrAccess = "none", catchupEnabled = true, vodMoviesEnabled = true,
        vodSeriesEnabled = true, accountProfileIds = "", isStaff = false, isSuperuser = false,
        customProperties = "", capabilitiesFetchedAt = 0L, capabilitiesSchema = 0, capabilitiesStale = false,
    )

    @After fun reset() = ProfileScope.activate(null)

    @Test fun defaultProfileUsesLegacyKeys() {
        ProfileScope.activate(null)
        assertEquals("", ProfileScope.current())
        assertEquals("recent_channel_ids", ProfileScope.keyName("recent_channel_ids"))
        assertEquals("live_group_token_P1", ProfileScope.keyName("live_group_token_P1"))
        assertEquals("P1", ProfileScope.cacheKey("P1"))
        assertNull(ProfileScope.overlayFor("P1"))
    }

    @Test fun secondProfileGetsItsOwnKeys() {
        ProfileScope.activate(overlay("P1", "B"))
        assertEquals("B", ProfileScope.current())
        assertEquals("recent_channel_ids@prof:B", ProfileScope.keyName("recent_channel_ids"))
        assertEquals("P1@B", ProfileScope.cacheKey("P1"))
        // Another playlist's channel cache is untouched.
        assertEquals("P2", ProfileScope.cacheKey("P2"))
        assertNull(ProfileScope.overlayFor("P2"))
    }

    @Test fun syncSeesOnlyTheDefaultProfile() = runBlocking {
        ProfileScope.activate(overlay("P1", "B"))
        withContext(ProfileScope.defaultOnly()) {
            assertEquals("", ProfileScope.current())
            assertNull(ProfileScope.overlayFor("P1"))
            assertTrue(ProfileScope.isForcedDefault())
        }
        assertEquals("B", ProfileScope.current())
    }

    @Test fun divertedWritesPersistThroughTheCallback() {
        var persisted: ProfileScope.Overlay? = null
        ProfileScope.onOverlayChanged = { persisted = it }
        ProfileScope.activate(overlay("P1", "B"))
        ProfileScope.updateOverlay { it.copy(apiKey = "fresh") }
        assertEquals("fresh", persisted?.apiKey)
        ProfileScope.onOverlayChanged = null
    }

    @Test fun levelBadges() {
        assertEquals("Admin", ProfileLevel.of(10).label)
        assertEquals("Standard", ProfileLevel.of(1).label)
        assertEquals("Limited", ProfileLevel.of(0).label)
    }
}
