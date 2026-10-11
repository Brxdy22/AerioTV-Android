package com.aeriotv.android.feature.multiview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.aeriotv.android.core.data.M3UChannel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.feature.miniplayer.MiniPlayerSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Compose-friendly handle around the app-scoped [MultiviewStore]. Hilt
 * doesn't expose @Singleton classes directly to composables; the standard
 * pattern is a per-screen ViewModel that holds the singleton reference and
 * forwards calls. Keeps composables stateless w.r.t. multiview lifecycle.
 */
@HiltViewModel
class MultiviewStoreHandleVm @Inject constructor(
    private val store: MultiviewStore,
    private val multiviewCast: com.aeriotv.android.core.cast.multiview.MultiviewCastController,
    miniSession: MiniPlayerSession,
) : ViewModel() {
    /**
     * The single channel playing in the mini player (TV corner mini, phone and
     * tablet floating mini), or null. Null as well while a real Multiview runs
     * (the composite on a receiver, or the tiles in the background): those
     * keep taking adds as one more tile.
     */
    val playingSeed: StateFlow<M3UChannel?> = combine(
        miniSession.state,
        multiviewCast.session,
        MultiviewBackground.state,
    ) { mini, composite, background ->
        (mini as? MiniPlayerSession.State.Active)?.channel
            ?.takeIf { composite == null && background == null && it.url.isNotBlank() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            miniSession.state.collect { st ->
                (st as? MiniPlayerSession.State.Active)?.channel?.let(store::onPlayingChannelChanged)
            }
        }
    }

    val playingSeedId: StateFlow<String?> get() = store.playingSeedId
    fun clearStaged() = store.clearStaged()
    fun takePlayingSeedForLaunch(): String? = store.takePlayingSeedForLaunch()
    val selected: StateFlow<List<MultiviewTile>> get() = store.selected
    val audioFocusedIndex: StateFlow<Int> get() = store.audioFocusedIndex
    val maxTiles: Int get() = store.maxTiles
    val softLimit: Int get() = store.softLimit
    fun warningRecentlyShown(): Boolean = store.warningRecentlyShown()
    fun noteWarningShown() = store.noteWarningShown()
    fun toggle(channel: M3UChannel) = store.toggle(channel)
    val isStaging: StateFlow<Boolean> get() = store.isStaging
    fun setStaging(active: Boolean) = store.setStaging(active)
    /**
     * Live TV "Add to Multiview" (Apple e1dcbd2 parity): while a Multiview
     * runs (the phone composite on a receiver, or the tiles playing in the
     * background) the channel joins that pile. Only with nothing running does
     * the first add start a fresh staged pile, which used to replace the
     * running one.
     */
    fun stageToggle(channel: M3UChannel) {
        val tile = MultiviewTile.live(channel)
        if (multiviewCast.toggleTile(tile)) return
        if (MultiviewBackground.toggleTile(tile, store.maxTiles)) {
            // Last background tile removed: Multiview ended, the mirror too.
            if (MultiviewBackground.state.value == null) store.clear()
            return
        }
        val seed = playingSeed.value
        if (seed != null) {
            store.stageOnPlaying(seed, channel)
            return
        }
        store.stageToggle(channel)
    }
    fun isSelected(channel: M3UChannel): Boolean = store.isSelected(channel)
    fun addTile(tile: MultiviewTile): Boolean = store.addTile(tile)
    fun removeTile(tileId: String) = store.removeTile(tileId)
    fun replaceTile(tileId: String, replacement: MultiviewTile): Boolean =
        store.replaceTile(tileId, replacement)
    fun setAudioFocus(index: Int) = store.setAudioFocus(index)
    fun swap(from: Int, to: Int) = store.swap(from, to)
    fun move(from: Int, to: Int) = store.move(from, to)
    fun removeAt(index: Int) = store.removeAt(index)
    fun clear() = store.clear()
    fun seedCurrent(channel: M3UChannel) = store.seedCurrent(channel)
    fun restore(snapshot: List<MultiviewTile>, audioFocus: Int) = store.restore(snapshot, audioFocus)
}

/**
 * Stateless wrapper around the Hilt-injected handle so composables only see
 * the surface they need without an extra `: MultiviewStoreHandleVm` parameter
 * each. Equivalent to iOS's `@EnvironmentObject var multiviewStore`.
 */
// Task #190: @Stable so guide rows/cells taking the handle can SKIP. Honest:
// every field is a val (StateFlows + lambdas); state changes reach compose
// through collectAsState on the flows, never through this object mutating.
@androidx.compose.runtime.Stable
class MultiviewStoreHandle(
    val selected: StateFlow<List<MultiviewTile>>,
    val audioFocusedIndex: StateFlow<Int>,
    val maxTiles: Int,
    val softLimit: Int,
    val warningRecentlyShown: () -> Boolean,
    val noteWarningShown: () -> Unit,
    val toggle: (M3UChannel) -> Unit,
    /** Staging mode for the Live TV banner (iOS isStagingFromGuide). */
    val isStaging: StateFlow<Boolean>,
    val setStaging: (Boolean) -> Unit,
    /** Live TV menu add/remove: first add starts a fresh staged pile. */
    val stageToggle: (M3UChannel) -> Unit,
    val addTile: (MultiviewTile) -> Boolean,
    val removeTile: (String) -> Unit,
    /** Swap Stream: re-point an existing tile, keeping its slot. */
    val replaceTile: (String, MultiviewTile) -> Boolean,
    val setAudioFocus: (Int) -> Unit,
    val swap: (Int, Int) -> Unit,
    val move: (Int, Int) -> Unit,
    val removeAt: (Int) -> Unit,
    val clear: () -> Unit,
    val seedCurrent: (M3UChannel) -> Unit,
    val restore: (List<MultiviewTile>, Int) -> Unit,
    /** Channel playing in the mini player, while no real Multiview runs. */
    val playingSeed: StateFlow<M3UChannel?>,
    /** Banner / dock Clear: drops the staged extras, playback untouched. */
    val clearStaged: () -> Unit,
)

@Composable
fun rememberMultiviewStoreHandle(
    vm: MultiviewStoreHandleVm = hiltViewModel(),
): MultiviewStoreHandle = remember(vm) {
    MultiviewStoreHandle(
        selected = vm.selected,
        audioFocusedIndex = vm.audioFocusedIndex,
        maxTiles = vm.maxTiles,
        softLimit = vm.softLimit,
        warningRecentlyShown = vm::warningRecentlyShown,
        noteWarningShown = vm::noteWarningShown,
        toggle = vm::toggle,
        isStaging = vm.isStaging,
        setStaging = vm::setStaging,
        stageToggle = vm::stageToggle,
        addTile = vm::addTile,
        removeTile = vm::removeTile,
        replaceTile = vm::replaceTile,
        setAudioFocus = vm::setAudioFocus,
        swap = vm::swap,
        move = vm::move,
        removeAt = vm::removeAt,
        clear = vm::clear,
        seedCurrent = vm::seedCurrent,
        restore = vm::restore,
        playingSeed = vm.playingSeed,
        clearStaged = vm::clearStaged,
    )
}

/** Live TV long-press "Add to Multiview" item state (Apple LiveTVMultiviewMenuState). */
enum class MultiviewMenuState { Add, AddDisabled, Remove }

/**
 * Apple PlayerSession.liveTVMultiviewMenuState: the channel playing in the
 * mini player reads "Add to Multiview", disabled (it is already staged
 * channel 1, never "Remove"); other channels read Remove only when staged on
 * top of it. With nothing playing, staged channels read Remove.
 */
fun multiviewMenuState(
    channelId: String,
    playingId: String?,
    staged: List<MultiviewTile>,
): MultiviewMenuState = when {
    playingId != null && channelId == playingId -> MultiviewMenuState.AddDisabled
    playingId != null ->
        if (staged.firstOrNull()?.id == playingId && staged.any { it.id == channelId }) {
            MultiviewMenuState.Remove
        } else {
            MultiviewMenuState.Add
        }
    staged.any { it.id == channelId } -> MultiviewMenuState.Remove
    else -> MultiviewMenuState.Add
}
