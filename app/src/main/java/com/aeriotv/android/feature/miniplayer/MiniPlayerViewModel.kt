package com.aeriotv.android.feature.miniplayer

import androidx.lifecycle.ViewModel
import com.aeriotv.android.core.data.M3UChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Hilt facade so composables can collect [MiniPlayerSession.state] without
 * carrying the singleton through every Composable signature. Keep this thin
 * the real state lives in the @Singleton.
 */
@HiltViewModel
class MiniPlayerViewModel @Inject constructor(
    val session: MiniPlayerSession,
) : ViewModel() {

    val state: StateFlow<MiniPlayerSession.State> = session.state

    fun setCurrentChannel(channel: M3UChannel) = session.setCurrentChannel(channel)
    fun showMiniPlayer() = session.showMiniPlayer()
    fun dismiss() = session.dismiss()
    fun resumeChannel(): M3UChannel? = session.resumeChannel()
}
