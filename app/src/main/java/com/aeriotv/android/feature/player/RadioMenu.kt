package com.aeriotv.android.feature.player

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import com.aeriotv.android.core.data.M3UChannel
import com.aeriotv.android.core.tv.TvMenuAction
import com.aeriotv.android.feature.main.MainScaffoldEntryPoint
import dagger.hilt.android.EntryPointAccessors

/**
 * The channel long-press menu's Radio Mode row (guide grid and channel list,
 * every form factor). "Exit Radio Mode" on the channel Radio Mode is playing,
 * "Radio Mode" on any other channel (switches the audio when already in Radio
 * Mode). Null while casting (hidden, Apple parity) or for a channel with no
 * stream URL.
 */
fun radioMenuAction(
    context: Context,
    channel: M3UChannel,
    subtitle: String,
    beforeStart: () -> Unit = {},
): TvMenuAction? {
    if (channel.url.isBlank()) return null
    val entry = EntryPointAccessors.fromApplication(
        context.applicationContext,
        MainScaffoldEntryPoint::class.java,
    )
    if (entry.castSender().state.value is com.aeriotv.android.core.cast.AerioCastSender.State.Connected) return null
    val playingHere = RadioMode.state.value?.channel?.id == channel.id
    if (playingHere) {
        return TvMenuAction("Exit Radio Mode", Icons.Filled.Radio) { RadioMode.stop(context, "menu") }
    }
    return TvMenuAction("Radio Mode", Icons.Filled.Radio) {
        beforeStart()
        val headers = RadioMode.headersProvider?.invoke() ?: entry.exoPlayerHolder().httpHeaders
        if (RadioMode.isActive) RadioMode.switchTo(context, channel, headers, subtitle)
        else RadioMode.startFromMenu(context, channel, headers, subtitle)
    }
}
