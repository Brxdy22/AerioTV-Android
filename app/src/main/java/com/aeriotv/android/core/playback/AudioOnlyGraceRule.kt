package com.aeriotv.android.core.playback

/**
 * Pure drop rule for [AudioOnlyTrackGuard], in wall-clock time since the first
 * audio sample of the connection.
 *
 * - A declared video PID that has sent zero bytes is dropped after
 *   [SILENT_GRACE_MS] (GH jonzey231/AerioTV#90, radio channels).
 * - A declared video PID that has sent any bytes is live and its format is
 *   only late (SPS/PPS behind audio in a proxy burst); it waits up to
 *   [LIVE_CAP_MS] before being given up on.
 */
object AudioOnlyGraceRule {
    const val SILENT_GRACE_MS = 4_000L
    const val LIVE_CAP_MS = 12_000L

    fun shouldDrop(elapsedWallMs: Long, videoReceivedBytes: Boolean): Boolean =
        elapsedWallMs >= if (videoReceivedBytes) LIVE_CAP_MS else SILENT_GRACE_MS
}
