package com.aeriotv.android.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aeriotv.android.feature.main.MainScaffoldEntryPoint
import com.aeriotv.android.feature.multiview.RADIO_SUBTITLE
import dagger.hilt.android.EntryPointAccessors

/**
 * Radio Mode sheet (phone and tablet), opened by tapping the Radio card
 * (Apple parity, cast sheet style): channel logo and name, "Playing channel
 * audio", Play/Pause, "Switch to Video Mode" (back to the full player with no
 * re-tune) and Stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val session = RadioMode.state.value ?: run { onDismiss(); return }
    val holder = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, MainScaffoldEntryPoint::class.java)
            .exoPlayerHolder()
    }
    var playing by remember { mutableStateOf(holder.player?.playWhenReady == true) }
    DisposableEffect(holder.player) {
        val p = holder.player
        val l = object : androidx.media3.common.Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { playing = playWhenReady }
        }
        p?.addListener(l)
        onDispose { p?.removeListener(l) }
    }
    com.aeriotv.android.ui.scale.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    val logo = session.channel.tvgLogo.takeIf { it.isNotBlank() }
                    if (logo != null) {
                        coil3.compose.AsyncImage(model = logo, contentDescription = null, modifier = Modifier.size(48.dp))
                    } else {
                        Icon(Icons.Filled.Radio, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        session.channel.name, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        RADIO_SUBTITLE, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            FilledIconButton(
                onClick = { holder.player?.let { it.playWhenReady = !it.playWhenReady } },
                modifier = Modifier.size(64.dp),
                shape = CircleShape,
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onDismiss(); RadioMode.requestReturn(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Switch to Video Mode")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onDismiss(); RadioMode.stop(context, "sheet") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop", color = MaterialTheme.colorScheme.error) }
        }
    }
}
