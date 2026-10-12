package com.aeriotv.android.core.data.repository

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Process-wide "is it a good moment to spend a background request?" gate for
 * the quiet EPG sweep in [PlaylistRepository.startEpgBackgroundSweep]
 * (Logan 2026-09-12).
 *
 * The sweep must be invisible: it pauses while the app is backgrounded and
 * while a tune is still waiting on its first frame, and resumes at the chunk
 * it stopped on. Two volatile flags polled by the sweep, deliberately not a
 * flow: nothing here should cost a subscription or a recomposition.
 */
object EpgSweepGate {
    /** True while ANY activity of the process is started (on screen).
     *  Driven by [ProcessLifecycleOwner] via [install], never by a nav
     *  destination's lifecycle: the MAIN destination reads STOPPED while the
     *  full-screen player route sits on top of it (Android#131), which made a
     *  RUNNING_CRITICAL trim during playback shed the guide as if the app
     *  were backgrounded. Starts true so a cold launch (which is by definition
     *  foreground) never stalls the sweep waiting for an ON_START it already
     *  missed. */
    @Volatile
    var appInForeground: Boolean = true
        private set

    private val _returnedToForeground = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits on every process ON_START after a process ON_STOP (not on the
     *  cold-launch start), so the guide owner can repaint a shed guide. */
    val returnedToForeground: SharedFlow<Unit> = _returnedToForeground

    @Volatile
    private var installed = false

    /** Hook the process lifecycle. Call once from Application.onCreate (main
     *  thread). Idempotent. */
    fun install() {
        if (installed) return
        installed = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onProcessStart()
            override fun onStop(owner: LifecycleOwner) = onProcessStop()
        })
    }

    /** Process became visible. Internal for the unit test. */
    internal fun onProcessStart() {
        val wasBackground = !appInForeground
        appInForeground = true
        if (wasBackground) _returnedToForeground.tryEmit(Unit)
    }

    /** Process left the screen (every activity stopped). */
    internal fun onProcessStop() {
        appInForeground = false
    }

    @Volatile
    private var tuneStartedAtMs: Long = 0L

    /** A tune has begun; the sweep holds off until its first frame. */
    fun onTuneStart() {
        tuneStartedAtMs = SystemClock.elapsedRealtime()
    }

    /** First frame (or STATE_READY) landed; the sweep may spend requests again. */
    fun onTunePlaying() {
        tuneStartedAtMs = 0L
    }

    /**
     * True while a tune is before its first frame. Ceilinged at
     * [TUNE_MAX_MS]: a missed [onTunePlaying] (a tune that failed, a screen
     * torn down mid-prime) must not wedge the sweep for the life of the
     * process.
     */
    val tuneInProgress: Boolean
        get() {
            val started = tuneStartedAtMs
            return started != 0L && SystemClock.elapsedRealtime() - started < TUNE_MAX_MS
        }

    /** The one question the sweep asks between chunks. */
    val sweepAllowed: Boolean
        get() = appInForeground && !tuneInProgress

    private const val TUNE_MAX_MS = 30_000L
}
