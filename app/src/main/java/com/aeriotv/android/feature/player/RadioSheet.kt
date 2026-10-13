package com.aeriotv.android.feature.player

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import com.aeriotv.android.core.cast.CastControl
import com.aeriotv.android.core.data.EPGProgramme
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.data.toInfoTarget
import com.aeriotv.android.feature.cast.CastRemoteSheet
import com.aeriotv.android.feature.main.MainScaffoldEntryPoint
import com.aeriotv.android.feature.main.floatingNavChrome
import com.aeriotv.android.feature.miniplayer.CastMiniController
import com.aeriotv.android.feature.multiview.RADIO_SUBTITLE
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch

private const val TAG = "AerioTV"

/**
 * Radio Mode card and remote sheet (phone and tablet, Apple parity): the cast
 * card itself ([CastMiniController] in the cast card's frame) and the full
 * [CastRemoteSheet], driven by the local holder instead of a receiver.
 *
 * Controls with no Radio meaning keep the sheet's own disabled look:
 * - Back / Forward (inline skips) are drawn but inert, and the timeline is
 *   read-only: the Live Rewind buffer session ends when the full-screen
 *   player closes (TimeshiftController.onFullscreenLiveStopped), so a Radio
 *   stream has no seekable window. Same states Cast shows with no window.
 * - Options: Audio Track works (the holder's audio groups). Subtitles,
 *   Aspect, Audio Only and Playback Speed are inert: there is no picture,
 *   it is already audio only, and a live stream plays at 1x.
 */
@Composable
fun RadioTransportCard(
    nowProgramme: (M3UChannel) -> EPGProgramme?,
    loadChannelStreams: suspend (Int) -> List<StreamOption>,
    loadCurrentStreamId: suspend (String) -> Int?,
    switchChannelStream: suspend (String, Int) -> Unit,
    recheckSwitchStreamAllowed: suspend (String) -> Boolean,
    isDispatcharrAdmin: Boolean,
    modifier: Modifier = Modifier,
    tabletWidth: Dp? = null,
) {
    val context = LocalContext.current
    val radio by RadioMode.state.collectAsStateWithLifecycle()
    val session = radio ?: return
    val channel = session.channel
    val holder = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, MainScaffoldEntryPoint::class.java)
            .exoPlayerHolder()
    }
    val player = holder.player
    var playing by remember { mutableStateOf(player?.playWhenReady == true) }
    var tracksTick by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) { tracksTick++ }
        }
        player?.addListener(l)
        onDispose { player?.removeListener(l) }
    }
    var sheetOpen by remember { mutableStateOf(false) }
    var sleepEndsAt by remember { mutableStateOf<Long?>(null) }
    var recordTarget by remember { mutableStateOf<com.aeriotv.android.core.data.ProgramInfoTarget?>(null) }
    var switchStreams by remember { mutableStateOf<List<StreamOption>?>(null) }
    var switchCurrentId by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val togglePlay: () -> Unit = { holder.player?.let { it.playWhenReady = !it.playWhenReady } }
    val logo = channel.tvgLogo.takeIf { it.isNotBlank() }
    val programme = nowProgramme(channel)

    // Sleep Timer: ends Radio Mode like Stop.
    LaunchedEffect(sleepEndsAt) {
        val end = sleepEndsAt ?: return@LaunchedEffect
        kotlinx.coroutines.delay((end - System.currentTimeMillis()).coerceAtLeast(0L))
        RadioMode.stop(context, "sleep timer")
    }

    Box(
        modifier = if (tabletWidth != null) {
            modifier.width(tabletWidth).floatingNavChrome(RoundedCornerShape(percent = 50))
        } else {
            modifier.widthIn(max = 600.dp).fillMaxWidth().padding(horizontal = 20.dp)
                .floatingNavChrome(RoundedCornerShape(percent = 50))
        },
    ) {
        CastMiniController(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            horizontalPadding = if (tabletWidth != null) 18.dp else 12.dp,
            title = channel.name,
            deviceName = null,
            artUri = logo,
            isPlaying = playing,
            subtitle = RADIO_SUBTITLE,
            transportIcon = Icons.Filled.Radio,
            stopDescription = "Stop",
            onTap = { Log.i(TAG, "[RADIO] card tap"); sheetOpen = true },
            onTogglePlayPause = togglePlay,
            onStop = { RadioMode.stop(context, "card") },
        )
    }

    if (sheetOpen) {
        @Suppress("UNUSED_EXPRESSION") tracksTick
        val audio = remember(tracksTick, player) {
            val out = mutableListOf<CastControl.Track>()
            player?.currentTracks?.groups?.forEachIndexed { gi, g ->
                if (g.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
                for (ti in 0 until g.length) {
                    val f = g.getTrackFormat(ti)
                    val label = f.label ?: f.language?.let { java.util.Locale.forLanguageTag(it).displayLanguage }
                        ?: "Track ${out.size + 1}"
                    out += CastControl.Track("$gi:$ti", label, g.isTrackSelected(ti))
                }
            }
            out
        }
        val canSwitchStream = isDispatcharrAdmin && channel.dispatcharrChannelId != null &&
            channel.id.startsWith("disp:")
        CastRemoteSheet(
            deviceName = null,
            channelTitle = channel.name,
            programmeTitle = programme?.title,
            logoUrl = logo,
            programmeStartMs = programme?.startMillis ?: 0L,
            programmeEndMs = programme?.endMillis ?: 0L,
            remoteState = CastControl.RemoteState(audio = audio, audioOnly = true),
            isPlaying = playing,
            position = CastControl.PositionSnapshot(canSeek = false, isPlaying = playing),
            transportIcon = Icons.Filled.Radio,
            statusLineOverride = RADIO_SUBTITLE,
            stopLabel = "Stop",
            canChangeChannel = true,
            showInlineSkip = true,
            inlineSkipEnabled = false,
            allowWebScrub = false,
            canSwitchStream = canSwitchStream,
            onTogglePlayPause = togglePlay,
            onChannelUp = { RadioMode.step(context, 1) },
            onChannelDown = { RadioMode.step(context, -1) },
            onStopCasting = { sheetOpen = false; RadioMode.stop(context, "sheet") },
            onSwitchToVideo = { sheetOpen = false; RadioMode.requestReturn(context) },
            onSetAudioTrack = { id ->
                val p = holder.player
                val (gi, ti) = id.split(':').map { it.toInt() }
                val g = p?.currentTracks?.groups?.getOrNull(gi)
                if (p != null && g != null) {
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, ti))
                        .build()
                }
            },
            onSetTextTrack = {},
            onSetSpeed = {},
            onSetAspect = {},
            onSetAudioOnly = {},
            onSwitchStream = {
                val chPk = channel.dispatcharrChannelId
                if (chPk != null) {
                    scope.launch {
                        if (!recheckSwitchStreamAllowed("Switch Stream open (Radio)")) {
                            android.widget.Toast.makeText(context, SWITCH_DENIED_MESSAGE, android.widget.Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        switchCurrentId = loadCurrentStreamId(channel.id.removePrefix("disp:"))
                        switchStreams = loadChannelStreams(chPk)
                    }
                }
            },
            sleepLabel = sleepEndsAt?.let { end ->
                val minutes = ((end - System.currentTimeMillis()) + 59_999L) / 60_000L
                when {
                    minutes < 60L -> "$minutes min remaining"
                    minutes % 60L == 0L -> "${minutes / 60L} h remaining"
                    else -> "${minutes / 60L} h ${minutes % 60L} min remaining"
                }
            } ?: "Off",
            onSleepMinutes = { m -> sleepEndsAt = if (m == 0) null else System.currentTimeMillis() + m * 60_000L },
            recordProgramTitle = programme?.title,
            onRecordCurrentProgram = programme?.let { pr -> { recordTarget = pr.toInfoTarget(channel.name, channel.dispatcharrChannelId) } },
            onSeekBy = {},
            onSeekToWall = {},
            onGoLive = {},
            onDismiss = { sheetOpen = false },
        )
    }

    recordTarget?.let { target ->
        com.aeriotv.android.feature.livetv.RecordProgramSheet(target = target, onDismiss = { recordTarget = null })
    }

    switchStreams?.let { streams ->
        SwitchStreamSheet(
            streams = streams,
            currentStreamId = switchCurrentId,
            onSelect = { streamId ->
                switchStreams = null
                val uuid = channel.id.removePrefix("disp:")
                val previousId = switchCurrentId
                switchCurrentId = streamId
                scope.launch {
                    val error = runCatching { switchChannelStream(uuid, streamId) }.exceptionOrNull() ?: return@launch
                    if (switchCurrentId == streamId) switchCurrentId = previousId
                    android.widget.Toast.makeText(
                        context,
                        switchStreamFailureMessage("switch the stream", SWITCH_DENIED_MESSAGE, error),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                    if ((error as? com.aeriotv.android.core.network.DispatcharrHttpFailure)?.status == 403) {
                        recheckSwitchStreamAllowed("403 on change_stream (Radio)")
                    }
                }
            },
            onDismiss = { switchStreams = null },
        )
    }
}
