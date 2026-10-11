package com.aeriotv.android.feature.multiview

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer
import com.aeriotv.android.core.playback.AerioExoPlayerHolder
import com.aeriotv.android.feature.player.ExoWindowState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live channel that was playing in the mini player, kept as a Multiview
 * tile (Logan 2026-10-11, Apple parity: Play from a pile staged on the mini
 * player opens Multiview with the playing channel plus the extras, without
 * re-tuning the playing channel). The tile does not build its own ExoPlayer:
 * the app's live [AerioExoPlayerHolder] keeps playing, and its persistent
 * window moves to the tile's rect ([ExoWindowState.Mode.Tile]).
 *
 * Main thread only. When the held tile leaves the grid (removed, replaced,
 * Multiview closed or sent to the background) the holder is stopped and the
 * window hidden, the same teardown Multiview always did on entry.
 */
object MultiviewHeld {
    private const val TAG = "AerioMV"

    private val _tileId = MutableStateFlow<String?>(null)
    /** Tile id whose video is the live holder's, or null. */
    val tileId: StateFlow<String?> = _tileId.asStateFlow()

    private var mounted = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun entry(context: Context) = dagger.hilt.android.EntryPointAccessors.fromApplication(
        context.applicationContext,
        com.aeriotv.android.feature.main.MainScaffoldEntryPoint::class.java,
    )

    /**
     * Multiview route entry. Keeps the holder playing for [seedId] when it is
     * still the channel on the holder; returns false (caller does the classic
     * teardown) otherwise.
     */
    fun begin(context: Context, seedId: String?): Boolean {
        if (seedId == null) return false
        val e = entry(context)
        val holder = e.exoPlayerHolder()
        if (holder.currentChannelId != seedId || holder.isIdle()) {
            Log.i(TAG, "[MV-Tile] held tile skipped: holder on ${holder.currentChannelId} idle=${holder.isIdle()} seed=$seedId")
            return false
        }
        _tileId.value = seedId
        e.exoWindowState().requestTile()
        Log.i(TAG, "[MV-Tile] Multiview keeps the playing player for $seedId (no re-tune)")
        return true
    }

    /** Stop the held player now (it left the grid). */
    fun release(context: Context, reason: String) {
        if (_tileId.value == null) return
        Log.i(TAG, "[MV-Tile] held tile released ($reason); stopping the live player")
        _tileId.value = null
        val e = entry(context)
        e.exoPlayerHolder().player?.volume = 1f
        runCatching { e.exoWindowState().hide() }
        runCatching { e.exoPlayerHolder().stop() }
        com.aeriotv.android.core.playback.AerioMediaPlaybackService.stop(context.applicationContext)
    }

    internal fun mount() { mounted++ }

    /** A held tile composable left; release unless another slot took it
     *  over in the same frame (Move Tile re-points positional tiles). */
    internal fun unmount(context: Context) {
        mounted--
        main.post { if (mounted <= 0) release(context, "tile left the grid") }
    }
}

/**
 * Tile body for the held channel: transparent (the Multiview screen clears
 * this rect, the live window draws under it), publishes its rect, and drives
 * the holder's volume / pause from the grid's audio focus.
 */
@Composable
internal fun HeldTileSurface(
    isAudioFocused: Boolean,
    paused: Boolean,
    onPlayer: (ExoPlayer?) -> Unit,
) {
    val context = LocalContext.current
    val e = remember(context) {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            context.applicationContext,
            com.aeriotv.android.feature.main.MainScaffoldEntryPoint::class.java,
        )
    }
    val holder: AerioExoPlayerHolder = remember(e) { e.exoPlayerHolder() }
    val window: ExoWindowState = remember(e) { e.exoWindowState() }
    val player by holder.playerInstance.collectAsState()
    DisposableEffect(Unit) {
        MultiviewHeld.mount()
        onDispose { MultiviewHeld.unmount(context) }
    }
    DisposableEffect(player) {
        onPlayer(player)
        // Something else stopped the live player (TV HOME, Android Auto):
        // hand the tile back to a fresh tile player instead of a black cell.
        val stopped = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_IDLE && holder.currentChannelId == null) {
                    MultiviewHeld.release(context, "live player stopped elsewhere")
                }
            }
        }
        player?.addListener(stopped)
        onDispose {
            player?.removeListener(stopped)
            onPlayer(null)
        }
    }
    LaunchedEffect(player, isAudioFocused) { player?.volume = if (isAudioFocused) 1f else 0f }
    // Another tile fullscreen pauses this one: the window leaves the screen
    // (a surface under the window would otherwise show over that tile).
    val lastBounds = remember { arrayOf(androidx.compose.ui.geometry.Rect.Zero) }
    val pausedNow = androidx.compose.runtime.rememberUpdatedState(paused)
    LaunchedEffect(player, paused) {
        holder.setPaused(paused)
        window.tileRectPx.value = if (paused) androidx.compose.ui.geometry.Rect.Zero else lastBounds[0]
    }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                lastBounds[0] = it.boundsInRoot()
                if (!pausedNow.value) window.tileRectPx.value = lastBounds[0]
            },
    )
}
