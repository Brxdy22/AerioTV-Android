package com.aeriotv.android.core.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotStalenessTest {
    @Test fun freshWhenUserAndAssignmentMatch() {
        assertNull(SnapshotStaleness.reason(SnapshotStamp(7, "44,3"), 7, "3,44"))
        assertNull(SnapshotStaleness.reason(SnapshotStamp(7, ""), 7, ""))
    }

    @Test fun staleCases() {
        assertEquals("no stamp", SnapshotStaleness.reason(null, 7, ""))
        assertEquals("user id unknown", SnapshotStaleness.reason(SnapshotStamp(7, ""), 0, ""))
        assertEquals("user id 1 != 7", SnapshotStaleness.reason(SnapshotStamp(1, ""), 7, ""))
        assertEquals("channel profiles All -> 44", SnapshotStaleness.reason(SnapshotStamp(7, ""), 7, "44"))
        assertNotNull(SnapshotStaleness.reason(SnapshotStamp(7, "44"), 7, "44,45"))
    }

    @Test fun stampRoundTrip() {
        val s = SnapshotStamp(12, "5, 2,x")
        assertEquals("12|2,5", s.encode())
        assertEquals(SnapshotStamp(12, "2,5"), SnapshotStamp.decode(s.encode()))
        assertNull(SnapshotStamp.decode("garbage"))
        assertNull(SnapshotStamp.decode(null))
    }
}
