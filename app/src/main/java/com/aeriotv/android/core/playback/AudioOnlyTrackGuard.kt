package com.aeriotv.android.core.playback

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput

/**
 * Lets an audio-only live TS start when its PMT still declares a video stream
 * that never carries a picture (GH jonzey231/AerioTV#90, radio channels).
 *
 * Why this exists: TsExtractor (MODE_SINGLE_PMT) creates one track per PMT
 * stream and calls endTracks() right after the PMT. ProgressiveMediaPeriod
 * then refuses to finish preparing until EVERY track has an upstream format,
 * and the H.264 / HEVC readers only output a format once they have seen SPS
 * and PPS. A declared video PID that sends nothing therefore holds the player
 * in BUFFERING forever while audio bytes keep arriving: the endless
 * "Received N MB" spinner.
 *
 * The guard defers registering each video track with the real output until
 * its format arrives (buffering the bytes the reader writes before that, so
 * the first access unit is replayed intact), and defers endTracks() until all
 * declared video tracks have a format. The drop decision is made by
 * [AudioOnlyGraceRule] in WALL-CLOCK time since the first audio sample (a
 * proxy burst can carry several seconds of audio media time ahead of the
 * video SPS/PPS, so media time is not a usable clock). A declared video track
 * that has sent zero bytes after [AudioOnlyGraceRule.SILENT_GRACE_MS] is
 * dropped (radio case); one that has sent bytes is live with a late format
 * and waits up to [AudioOnlyGraceRule.LIVE_CAP_MS]. Once every pending track
 * is resolved, endTracks() goes through.
 *
 * [policy] (read once per tune by the holder) shortens the wait ONLY for a
 * Radio Mode tune or a channel remembered audio-only; see
 * [AudioOnlyGraceRule.shouldDrop] for the exact conditions. Every other
 * channel keeps the rule above unchanged.
 */
@UnstableApi
class AudioOnlyTrackGuard(
    private val inner: Extractor,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val policy: AudioOnlyStartPolicy = AudioOnlyStartPolicy.UNKNOWN,
    /** A declared video track was dropped (loader thread). Args: the policy
     *  and whether the PID had sent any bytes. */
    private val onDropped: (AudioOnlyStartPolicy, Boolean) -> Unit = { _, _ -> },
    /** The first video bytes after a drop on this connection (loader thread). */
    private val onVideoAfterDrop: (AudioOnlyStartPolicy) -> Unit = {},
    /** First video bytes before any drop (loader thread). */
    private val onVideoBytes: () -> Unit = {},
) : Extractor by inner {

    companion object {
        private const val TAG = "AerioExoPlayer"

        /** Pre-format bytes kept per pending video track (the tail only:
         *  the first sample is always the most recent bytes). */
        private const val PENDING_TAIL_BYTES = 4 * 1024 * 1024
    }

    private var guardOutput: GuardOutput? = null

    override fun init(output: ExtractorOutput) {
        val g = GuardOutput(output, log, nowMs, policy, onDropped, onVideoAfterDrop, onVideoBytes)
        guardOutput = g
        inner.init(g)
    }

    override fun seek(position: Long, timeUs: Long) {
        guardOutput?.onSeek()
        inner.seek(position, timeUs)
    }

    private class GuardOutput(
        private val real: ExtractorOutput,
        private val log: (String) -> Unit,
        private val nowMs: () -> Long,
        private val policy: AudioOnlyStartPolicy,
        private val onDropped: (AudioOnlyStartPolicy, Boolean) -> Unit,
        private val onVideoAfterDrop: (AudioOnlyStartPolicy) -> Unit,
        private val onVideoBytes: () -> Unit,
    ) : ExtractorOutput {
        private val tracks = HashMap<Int, TrackOutput>()
        private val pending = LinkedHashMap<Int, PendingVideoTrack>()
        private var endTracksRequested = false
        private var endTracksForwarded = false
        private var firstAudioWallMs = -1L
        private var firstAudioTimeUs = C.TIME_UNSET
        private var videoAfterDropReported = false
        private var videoBytesReported = false

        override fun track(id: Int, type: Int): TrackOutput {
            tracks[id]?.let { return it }
            val t: TrackOutput = when (type) {
                C.TRACK_TYPE_VIDEO -> {
                    if (endTracksForwarded) {
                        real.track(id, type)
                    } else {
                        PendingVideoTrack(id, type).also { pending[id] = it }
                    }
                }
                C.TRACK_TYPE_AUDIO -> AudioWatchTrack(real.track(id, type))
                else -> real.track(id, type)
            }
            tracks[id] = t
            return t
        }

        override fun endTracks() {
            endTracksRequested = true
            maybeForwardEndTracks()
        }

        override fun seekMap(seekMap: SeekMap) = real.seekMap(seekMap)

        fun onSeek() {
            pending.values.forEach { it.clearTail() }
            firstAudioWallMs = -1L
            firstAudioTimeUs = C.TIME_UNSET
        }

        private fun maybeForwardEndTracks() {
            if (endTracksForwarded || !endTracksRequested || pending.isNotEmpty()) return
            endTracksForwarded = true
            real.endTracks()
        }

        private fun onAudioSample(timeUs: Long) {
            if (endTracksForwarded || pending.isEmpty() || timeUs == C.TIME_UNSET) return
            val now = nowMs()
            if (firstAudioWallMs < 0) {
                // First audio sample: the window starts (unchanged for the
                // unknown rule; a PTS jump only makes the remembered media
                // condition fail, which falls back to the unknown rule).
                firstAudioWallMs = now
                firstAudioTimeUs = timeUs
                // Radio Mode: the first audio sample is enough.
                if (policy != AudioOnlyStartPolicy.REQUESTED && policy != AudioOnlyStartPolicy.DECLARED) return
            }
            if (!endTracksRequested) return
            val elapsed = now - firstAudioWallMs
            val mediaMs = (timeUs - firstAudioTimeUs) / 1_000L
            val toDrop = pending.values.filter {
                AudioOnlyGraceRule.shouldDrop(policy, elapsed, mediaMs, it.receivedBytes)
            }
            if (toDrop.isEmpty()) return
            for (t in toDrop) {
                log(
                    "[AUDIO-ONLY] detected: no video track (declared video PID " +
                        (if (t.receivedBytes) "sent bytes but no format" else "sent no bytes") +
                        " in ${elapsed}ms wall time / ${mediaMs}ms media of audio; dropping it for this " +
                        "connection, policy ${policy.logName})",
                )
                t.drop()
                pending.remove(t.id)
                onDropped(policy, t.receivedBytes)
            }
            maybeForwardEndTracks()
        }

        private inner class AudioWatchTrack(
            private val delegate: TrackOutput,
        ) : TrackOutput by delegate {
            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?,
            ) {
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                onAudioSample(timeUs)
            }
        }

        /** A video track not yet registered with the real output. */
        private inner class PendingVideoTrack(
            val id: Int,
            private val type: Int,
        ) : TrackOutput {
            private var target: TrackOutput? = null
            private var dropped = false
            /** Any payload byte seen for this PID on this connection. */
            var receivedBytes = false
                private set
            private var tail = ByteArray(0)
            private var tailLen = 0
            private val scratch = ByteArray(16 * 1024)

            fun drop() {
                dropped = true
                clearTail()
            }

            fun clearTail() {
                tail = ByteArray(0)
                tailLen = 0
            }

            private fun noteBytes(len: Int) {
                if (len <= 0) return
                if (dropped) {
                    if (!videoAfterDropReported) {
                        videoAfterDropReported = true
                        onVideoAfterDrop(policy)
                    }
                } else if (!videoBytesReported) {
                    videoBytesReported = true
                    onVideoBytes()
                }
            }

            private fun append(src: ByteArray, off: Int, len: Int) {
                if (len <= 0) return
                receivedBytes = true
                if (len >= PENDING_TAIL_BYTES) {
                    if (tail.size < PENDING_TAIL_BYTES) tail = ByteArray(PENDING_TAIL_BYTES)
                    System.arraycopy(src, off + len - PENDING_TAIL_BYTES, tail, 0, PENDING_TAIL_BYTES)
                    tailLen = PENDING_TAIL_BYTES
                    return
                }
                val needed = tailLen + len
                if (needed > PENDING_TAIL_BYTES) {
                    val discard = needed - PENDING_TAIL_BYTES
                    System.arraycopy(tail, discard, tail, 0, tailLen - discard)
                    tailLen -= discard
                }
                if (tail.size < tailLen + len) {
                    val grown = ByteArray(minOf(PENDING_TAIL_BYTES, maxOf(tail.size * 2, tailLen + len, 64 * 1024)))
                    System.arraycopy(tail, 0, grown, 0, tailLen)
                    tail = grown
                }
                System.arraycopy(src, off, tail, tailLen, len)
                tailLen += len
            }

            override fun format(format: Format) {
                target?.let { it.format(format); return }
                if (dropped) return
                if (endTracksForwarded) {
                    // Too late to add a track to a prepared period.
                    dropped = true
                    clearTail()
                    return
                }
                val t = real.track(id, type)
                target = t
                t.format(format)
                if (tailLen > 0) t.sampleData(ParsableByteArray(tail.copyOf(tailLen)), tailLen)
                clearTail()
                pending.remove(id)
                maybeForwardEndTracks()
            }

            override fun sampleData(
                input: DataReader,
                length: Int,
                allowEndOfInput: Boolean,
                sampleDataPart: Int,
            ): Int {
                target?.let { return it.sampleData(input, length, allowEndOfInput, sampleDataPart) }
                val n = input.read(scratch, 0, minOf(length, scratch.size))
                if (n == C.RESULT_END_OF_INPUT) {
                    if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                    throw java.io.EOFException()
                }
                noteBytes(n)
                if (!dropped) append(scratch, 0, n)
                return n
            }

            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
                target?.let { it.sampleData(data, length, sampleDataPart); return }
                noteBytes(length)
                if (dropped) {
                    data.skipBytes(length)
                    return
                }
                append(data.data, data.position, length)
                data.skipBytes(length)
            }

            override fun sampleMetadata(
                timeUs: Long,
                flags: Int,
                size: Int,
                offset: Int,
                cryptoData: TrackOutput.CryptoData?,
            ) {
                target?.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            }
        }
    }
}
