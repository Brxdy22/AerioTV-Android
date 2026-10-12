package com.aeriotv.android.core.data.repository

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android#131: the foreground flag follows the process lifecycle only. A
 * process ON_STOP (every activity off screen) is the one thing that makes the
 * guide sheddable; returning emits once so a shed guide is repainted.
 */
class EpgSweepGateTest {
    @After fun reset() = EpgSweepGate.onProcessStart()

    @Test fun startsForeground() {
        EpgSweepGate.onProcessStart()
        assertTrue(EpgSweepGate.appInForeground)
        assertTrue(EpgSweepGate.sweepAllowed)
    }

    @Test fun processStopIsBackground() {
        EpgSweepGate.onProcessStop()
        assertFalse(EpgSweepGate.appInForeground)
        assertFalse(EpgSweepGate.sweepAllowed)
    }

    @Test fun returnFromBackgroundEmitsOnce() = runBlocking {
        EpgSweepGate.onProcessStop()
        val got = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(1_000) { EpgSweepGate.returnedToForeground.first() }
        }
        EpgSweepGate.onProcessStart()
        assertEquals(Unit, got.await())
        assertTrue(EpgSweepGate.appInForeground)
    }

    @Test fun startWhileForegroundDoesNotEmit() = runBlocking {
        EpgSweepGate.onProcessStart()
        val got = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeoutOrNull(200) { EpgSweepGate.returnedToForeground.first() }
        }
        EpgSweepGate.onProcessStart()
        assertNull(got.await())
    }
}
