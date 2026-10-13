package com.aeriotv.android.feature.player

/**
 * Routes a LONG D-pad Left/Right that STARTS with the VOD player chrome
 * hidden into the same scrub path a long Left/Right takes while the chrome
 * is showing (Google TV Streamer report, 2026-09-14).
 *
 * Before this, a hidden-chrome Left/Right only revealed the chrome and parked
 * focus on Play/Pause, so the auto-repeats that follow walked the control row
 * instead of scrubbing: the user had to surface the controls first and press
 * again. A repeat carries no memory of the state the press began in, so the
 * first (non-repeat) KeyDown of every Left/Right arms this latch, and the
 * repeats that follow read it.
 *
 * Lives here rather than as another remembered value in VODPlayerScreen: that
 * composable sits at the JVM verifier register limit. One VOD player is on
 * screen at a time, and every press re-arms the latch from the live chrome
 * state, so a stale value cannot survive into the next press.
 */
internal object VodHiddenChromeScrub {
    private var armed = false

    /** Settings > Player > Skip Without Controls (GH #94), kept hot by
     *  MainActivity. The VOD player (movies, episodes, recordings) never
     *  enters ExoWindowState Fullscreen, so the activity-layer skip that serves
     *  live TV never sees its keys; the VOD handler asks here instead. */
    @Volatile var skipWithoutControls = false

    /** Left / Right short-press player actions from a Custom remote map
     *  (null = not custom-mapped). GH #135: the VOD
     *  player never enters ExoWindowState Fullscreen, so MainActivity's live
     *  map dispatch never sees its keys; the VOD handler asks here. Kept hot
     *  by MainActivity. */
    @Volatile var customLeftShort: com.aeriotv.android.core.remote.PlayerRemoteAction? = null
    @Volatile var customRightShort: com.aeriotv.android.core.remote.PlayerRemoteAction? = null

    private var lastSkipAt = 0L

    /** Same repeat pacing as the live player's hidden-chrome skip. */
    private const val SKIP_REPEAT_MS = 250L

    const val NOT_SKIP = 0
    /** Seek back by the Skip Back interval, chrome stays hidden. */
    const val SKIP_BACK = 1
    const val SWALLOW = 2
    /** Seek forward by the Skip Forward interval, chrome stays hidden. */
    const val SKIP_FORWARD = 3
    /** Custom map: Play/Pause. */
    const val PLAY_PAUSE = 4

    /**
     * Call for each handled Left/Right KeyDown before [shouldScrub].
     *
     * With the chrome hidden a Custom map's Left/Right short slot wins:
     * SEEK_BACKWARD / SEEK_FORWARD skip by the Skip interval (in the
     * action's direction), PLAY_PAUSE toggles. Any other mapping, or no
     * custom mapping for that key, falls to Skip Without Controls (skip in
     * the key's direction), else [NOT_SKIP]. Held repeats inside the pacing
     * window return [SWALLOW].
     */
    fun skipDecision(isLeft: Boolean, isRepeat: Boolean, chromeVisible: Boolean, now: Long): Int =
        decide(
            isLeft, isRepeat, chromeVisible, now,
            if (isLeft) customLeftShort else customRightShort,
            skipWithoutControls,
        )

    internal fun decide(
        isLeft: Boolean,
        isRepeat: Boolean,
        chromeVisible: Boolean,
        now: Long,
        mapped: com.aeriotv.android.core.remote.PlayerRemoteAction?,
        skipWithoutControls: Boolean,
    ): Int {
        if (chromeVisible) return NOT_SKIP
        val result = when (mapped) {
            com.aeriotv.android.core.remote.PlayerRemoteAction.SEEK_BACKWARD -> SKIP_BACK
            com.aeriotv.android.core.remote.PlayerRemoteAction.SEEK_FORWARD -> SKIP_FORWARD
            com.aeriotv.android.core.remote.PlayerRemoteAction.PLAY_PAUSE -> PLAY_PAUSE
            else -> if (skipWithoutControls) (if (isLeft) SKIP_BACK else SKIP_FORWARD) else NOT_SKIP
        }
        if (result == NOT_SKIP) return NOT_SKIP
        if (result == PLAY_PAUSE) return if (isRepeat) SWALLOW else PLAY_PAUSE
        if (isRepeat && now - lastSkipAt < SKIP_REPEAT_MS) return SWALLOW
        lastSkipAt = now
        return result
    }

    internal fun resetForTest() { lastSkipAt = 0L }

    /**
     * Call once per handled Left/Right KeyDown, before the chrome is revealed.
     *
     * @param isRepeat auto-repeat KeyDown (nativeKeyEvent.repeatCount > 0).
     * @param chromeVisible chrome state as the press was delivered.
     * @return true when this KeyDown should scrub instead of moving focus.
     */
    fun shouldScrub(isRepeat: Boolean, chromeVisible: Boolean): Boolean {
        if (!isRepeat) {
            // Press start: short Left/Right keeps today's behavior either way.
            armed = !chromeVisible
            return false
        }
        return armed
    }
}
