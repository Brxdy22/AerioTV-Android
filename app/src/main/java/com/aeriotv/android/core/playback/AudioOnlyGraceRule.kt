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

    /** Remembered audio-only channel: audio media time with zero video bytes. */
    const val REMEMBERED_AUDIO_MEDIA_MS = 2_000L
    /** Remembered audio-only channel: wall time with zero video bytes. */
    const val REMEMBERED_WALL_MS = 1_000L

    fun shouldDrop(elapsedWallMs: Long, videoReceivedBytes: Boolean): Boolean =
        elapsedWallMs >= if (videoReceivedBytes) LIVE_CAP_MS else SILENT_GRACE_MS

    /**
     * Policy-aware drop rule (GH AerioTV#90, SiriusXM channels up to 15 s to
     * start). Exact conditions, Apple parity (TSHLSRemuxer
     * AudioOnlyStartPolicy):
     * - [AudioOnlyStartPolicy.REQUESTED] (Radio Mode tune or channel step):
     *   the first audio sample. The user asked for audio; no video wait.
     * - [AudioOnlyStartPolicy.REMEMBERED] (an earlier tune of this channel
     *   ended audio-only and no video was seen on it since): the declared
     *   video PID has sent ZERO bytes while at least
     *   [REMEMBERED_AUDIO_MEDIA_MS] of audio media time AND
     *   [REMEMBERED_WALL_MS] of wall time passed. One video byte drops back
     *   to the unknown rule (and the holder clears the memory).
     * - [AudioOnlyStartPolicy.DECLARED] (the server flags the channel radio):
     *   the first audio sample, like REQUESTED.
     * - [AudioOnlyStartPolicy.UNKNOWN] (every other channel, including every
     *   normal video channel): unchanged, [shouldDrop] above.
     */
    fun shouldDrop(
        policy: AudioOnlyStartPolicy,
        elapsedWallMs: Long,
        audioMediaMs: Long,
        videoReceivedBytes: Boolean,
    ): Boolean = when (policy) {
        AudioOnlyStartPolicy.REQUESTED, AudioOnlyStartPolicy.DECLARED -> true
        AudioOnlyStartPolicy.REMEMBERED ->
            (!videoReceivedBytes && audioMediaMs >= REMEMBERED_AUDIO_MEDIA_MS && elapsedWallMs >= REMEMBERED_WALL_MS) ||
                shouldDrop(elapsedWallMs, videoReceivedBytes)
        AudioOnlyStartPolicy.UNKNOWN -> shouldDrop(elapsedWallMs, videoReceivedBytes)
    }
}

/** How a tune may call a video-declaring PMT audio-only. Read once per
 *  connection when the extractor is built. Hard rule (Logan 2026-10-14): a
 *  regular video channel is never UNKNOWN-overridden; only Radio Mode or a
 *  channel remembered audio-only gets a fast path. */
enum class AudioOnlyStartPolicy(val logName: String) {
    UNKNOWN("unknown (4 s wall grace, 12 s if the video PID sends bytes)"),
    REMEMBERED("remembered audio-only (2 s audio / 1 s wall, zero video bytes)"),
    REQUESTED("Radio Mode requested (no video wait)"),
    /** The server flags the channel radio (is_radio / radio="true" /
     *  radio_streams): drop the video track on the first audio sample;
     *  video appearing later restarts the tune as video. */
    DECLARED("declared radio"),
}
