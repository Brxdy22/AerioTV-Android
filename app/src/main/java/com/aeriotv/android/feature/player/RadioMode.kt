package com.aeriotv.android.feature.player

import android.content.Context
import android.net.Uri
import android.util.Log
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.playback.AerioMediaPlaybackService
import com.aeriotv.android.feature.main.MainScaffoldEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Radio Mode (Android#127 follow-up, Apple parity): a live channel's audio
 * keeps playing with no video on screen.
 *
 * Runs on the app-scoped live holder, the same instance the full-screen
 * player and the mini use, so returning to the channel never re-tunes: the
 * video track is disabled through the holder's sticky Audio Only flag (the
 * no-video-frame net already ignores a deliberately disabled video track),
 * the persistent video window is hidden, and the foreground media service
 * keeps the process alive in the background with its MediaSession
 * notification (logo, name, Play/Pause, Stop).
 *
 * All calls are main-thread (ExoPlayer and window state).
 */
object RadioMode {
    data class Session(val channel: M3UChannel)

    private val _state = MutableStateFlow<Session?>(null)
    val state: StateFlow<Session?> = _state.asStateFlow()
    val isActive: Boolean get() = _state.value != null

    /** The active playlist's stream headers, kept current by the nav layer
     *  (menus that start Radio Mode have no playlist of their own). */
    @Volatile var headersProvider: (() -> Map<String, String>)? = null

    /** Neighbour in the current channel order (+1 next, -1 previous), kept
     *  current by the nav layer, for Channel Up / Down in Radio Mode. */
    @Volatile var neighborProvider: ((M3UChannel, Int) -> M3UChannel?)? = null

    /** Channel Up / Down while in Radio Mode. True when handled. */
    fun step(context: Context, delta: Int): Boolean {
        val cur = _state.value?.channel ?: return false
        val next = neighborProvider?.invoke(cur, delta) ?: return false
        switchTo(context, next, headersProvider?.invoke() ?: entry(context).exoPlayerHolder().httpHeaders, "")
        return true
    }

    private val _returnRequests = MutableSharedFlow<M3UChannel>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** "Return to Channel" (card tap, TV banner): the nav layer re-opens the
     *  player for this channel; the holder is still playing it. */
    val returnRequests: SharedFlow<M3UChannel> = _returnRequests.asSharedFlow()

    private fun entry(context: Context) = EntryPointAccessors.fromApplication(
        context.applicationContext,
        MainScaffoldEntryPoint::class.java,
    )

    /**
     * Enter Radio Mode on the channel the full-screen player is showing. The
     * caller pops the player route afterwards; PlayerScreen's dispose reads
     * [isActive] and treats the close like a minimize (no Keep Live demote,
     * the holder keeps playing).
     */
    fun start(context: Context, channel: M3UChannel) {
        val e = entry(context)
        val holder = e.exoPlayerHolder()
        _state.value = Session(channel)
        holder.remoteAudioOnly = true
        holder.setVideoTrackEnabled(false)
        e.exoWindowState().hide()
        Log.i(TAG, "[RADIO] start ${channel.name}")
        AerioMediaPlaybackService.startBackground(context)
        Log.i(TAG, "[RADIO] service started")
    }

    /**
     * Radio Mode from a channel menu (guide, list): tune [channel] audio-only
     * on the shared holder, or keep the stream it is already decoding, with
     * no player on screen. Any mini player is dismissed.
     */
    fun startFromMenu(
        context: Context,
        channel: M3UChannel,
        headers: Map<String, String>,
        subtitle: String,
    ) {
        val e = entry(context)
        e.exoPlayerHolder().remoteAudioOnly = true
        _state.value = Session(channel)
        switchTo(context, channel, headers, subtitle, log = false)
        start(context, channel)
    }

    /** Another channel picked while in Radio Mode: switch the audio, no player. */
    fun switchTo(
        context: Context,
        channel: M3UChannel,
        headers: Map<String, String>,
        subtitle: String,
        log: Boolean = true,
    ) {
        if (!isActive) return
        val holder = entry(context).exoPlayerHolder()
        if (holder.currentChannelId != channel.id || holder.isIdle()) {
            holder.httpHeaders = headers
            holder.markTunePress(channel.name)
            holder.playUrl(
                url = channel.url,
                title = channel.name,
                subtitle = subtitle,
                artworkUri = channel.tvgLogo.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Uri.parse(it) }.getOrNull() },
                drmLicenseType = channel.drmLicenseType,
                drmLicenseKey = channel.drmLicenseKey,
                channelId = channel.id,
            )
            holder.currentChannelId = channel.id
            // playUrl re-enables video unless the sticky flag says otherwise;
            // set it again in case a re-prime raced the switch.
            holder.setVideoTrackEnabled(false)
        }
        _state.value = Session(channel)
        if (log) Log.i(TAG, "[RADIO] switch -> ${channel.name}")
    }

    /** Leave Radio Mode with video back on; the caller opens the player. */
    fun requestReturn(context: Context) {
        val s = _state.value ?: return
        val holder = entry(context).exoPlayerHolder()
        holder.remoteAudioOnly = false
        holder.setVideoTrackEnabled(true)
        _state.value = null
        Log.i(TAG, "[RADIO] return to ${s.channel.name}")
        _returnRequests.tryEmit(s.channel)
    }

    /** End Radio Mode and its audio. Keep Recent Channels Live decides as for
     *  the mini player's close (the player dispose stored the candidate). */
    fun stop(context: Context, reason: String) {
        val s = _state.value ?: return
        val e = entry(context)
        val holder = e.exoPlayerHolder()
        _state.value = null
        holder.remoteAudioOnly = false
        runCatching { e.exoWindowState().hide() }
        runCatching { holder.stop() }
        AerioMediaPlaybackService.stop(context)
        Log.i(TAG, "[RADIO] service stopped")
        runCatching { e.timeshiftController().onMiniClosed("radio-$reason") }
        Log.i(TAG, "[RADIO] stop ($reason) ${s.channel.name}")
    }

    private const val TAG = "AerioTV"
}
