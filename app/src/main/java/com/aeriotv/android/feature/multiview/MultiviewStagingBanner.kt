package com.aeriotv.android.feature.multiview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.clip
import com.aeriotv.android.ui.tv.tvFocusScale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.composed
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aeriotv.android.core.tv.rememberTvMenuGuard
import com.aeriotv.android.ui.settings.dpadFocusRing

/**
 * The "N channels staged for Multiview" banner with Clear, Play and Done
 * (iOS MultiviewStagingBanner, EPGGuideView.swift). Shared by the Live TV
 * Guide and List so staging from either view shows the same banner at the
 * top of the screen. Renders nothing while staging is off or the pile is
 * empty.
 *
 * On TV, focus is pulled to Play after every add (the D-pad cannot reliably
 * climb from the grid to the top of the screen), guarded so the OK release
 * that confirmed the menu action cannot launch Multiview by itself.
 */
@Composable
fun MultiviewStagingBanner(
    store: MultiviewStoreHandle,
    isTv: Boolean,
    onLaunchMultiview: () -> Unit,
    modifier: Modifier = Modifier,
    onReturnFocus: (() -> Boolean)? = null,
    playFocusRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    // Radio start from the guide (Logan 2026-10-15): when set, the radio
    // banner does NOT pull focus on start; this runs instead so focus stays
    // on the chosen cell. Up still reaches the banner.
    radioKeepFocus: (() -> Unit)? = null,
) {
    val staging by store.isStaging.collectAsStateWithLifecycle()
    val tiles by store.selected.collectAsStateWithLifecycle()
    val background by MultiviewBackground.state.collectAsStateWithLifecycle()
    // Radio Mode (Android#127): on TV this banner slot is its dock card.
    val radio by com.aeriotv.android.feature.player.RadioMode.state.collectAsStateWithLifecycle()
    val radioSession = radio
    if (isTv && radioSession != null && (!staging || tiles.isEmpty())) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        RadioTvBanner(
            channelName = radioSession.channel.name,
            onReturn = { com.aeriotv.android.feature.player.RadioMode.requestReturn(ctx) },
            onStop = {
                // tvOS parity (2026-10-15): Stop restores the origin cell
                // captured at Radio Mode start, not the row focused now.
                com.aeriotv.android.feature.player.RadioMode.stop(ctx, "card")
            },
            onReturnFocus = onReturnFocus,
            playFocusRequester = playFocusRequester,
            upTarget = upTarget,
            downTarget = downTarget,
            keepFocus = radioKeepFocus,
            modifier = modifier,
        )
        return
    }
    if (!staging || tiles.isEmpty()) {
        // TV: a Multiview left with "Back to Guide" plays on in the
        // background; this banner is the TV's dock card for it.
        val bg = background
        if (isTv && bg != null) {
            MultiviewBackgroundTvBanner(
                tiles = bg.tiles,
                onReturn = onLaunchMultiview,
                onStop = {
                    MultiviewBackground.stop()
                    if (!store.isStaging.value) store.clear()
                },
                onReturnFocus = onReturnFocus,
                playFocusRequester = playFocusRequester,
                upTarget = upTarget,
                downTarget = downTarget,
                modifier = modifier,
            )
        }
        return
    }
    val count = tiles.size
    val label = if (count == 1) "1 channel staged for Multiview" else "$count channels staged for Multiview"
    val guard = rememberTvMenuGuard()
    // TV Clear / Done remove the banner while one of its capsules holds
    // focus; the focus would then fall to the first focusable on screen (the
    // top nav's Search, [KEY] by=guide-screen(focus-not-in-grid) 2026-10-11).
    // Hand focus back to the list or guide BEFORE the banner leaves.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val returnFocus: () -> Unit = {
        val ok = onReturnFocus?.invoke() == true ||
            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
        com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=staging-banner result=$ok")
    }
    val ownPlayFocus = remember { FocusRequester() }
    val playFocus = playFocusRequester ?: ownPlayFocus
    // TV: the host routes Up from the grid / clock to Play; from the banner
    // Up goes to the pills and Down back to the grid (Logan 2026-10-11:
    // the banner was skipped, leaving Clear and Done unreachable).
    val tvNav = Modifier.focusProperties {
        if (upTarget != null) up = upTarget
        if (downTarget != null) down = downTarget
    }
    LaunchedEffect(count) {
        if (isTv) {
            guard.arm()
            repeat(10) {
                if (runCatching { playFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(16L)
            }
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val pill = RoundedCornerShape(50)
    val buttonPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
            .padding(horizontal = if (isTv) 24.dp else 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.GridView,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        // The label is the only flexible child, so the buttons never wrap
        // ("Pla / y" on narrow phones, Logan 2026-10-06). When the full label
        // overflows it falls back to the short "N staged" form (iOS
        // ViewThatFits), then ellipsizes.
        var useShort by remember(count) { mutableStateOf(false) }
        Text(
            text = if (useShort) "$count staged" else label,
            onTextLayout = { if (!useShort && it.hasVisualOverflow) useShort = true },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isTv) {
            // TV: compact capsules on the TvPill metrics (same height and
            // padding as the group pills, CircleShape, pill focus ring and
            // scale) so the ring follows the capsule (tvOS EPGGuideView
            // staging banner: Capsule backgrounds, 14 x 6 pt padding).
            TvBannerCapsule(
                onClick = guard.wrap {
                    returnFocus()
                    store.clearStaged()
                },
                fill = null,
                border = null,
                modifier = tvNav,
            ) { ink ->
                Text("Clear", fontSize = com.aeriotv.android.ui.tv.TvChrome.pillFontSize, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false,
                    color = ink ?: MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TvBannerCapsule(
                onClick = guard.wrap {
                    guard.arm()
                    store.setStaging(false)
                    onLaunchMultiview()
                },
                fill = accent,
                border = null,
                modifier = tvNav.focusRequester(playFocus),
            ) { _ ->
                val onAccent = MaterialTheme.colorScheme.onPrimary
                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = onAccent, modifier = Modifier.size(12.dp))
                Text("Play", fontSize = com.aeriotv.android.ui.tv.TvChrome.pillFontSize, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false, color = onAccent)
            }
            TvBannerCapsule(
                onClick = guard.wrap {
                    returnFocus()
                    store.setStaging(false)
                },
                fill = null,
                border = accent,
                modifier = tvNav,
            ) { _ ->
                Text("Done", fontSize = com.aeriotv.android.ui.tv.TvChrome.pillFontSize, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false, color = accent)
            }
        } else {
            TextButton(
                onClick = guard.wrap {
                    store.clear()
                    store.setStaging(false)
                },
                contentPadding = buttonPadding,
                modifier = Modifier.dpadFocusRing(shape = pill, washTint = accent),
            ) {
                Text("Clear", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = guard.wrap {
                    guard.arm()
                    store.setStaging(false)
                    onLaunchMultiview()
                },
                shape = pill,
                contentPadding = buttonPadding,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier.focusRequester(playFocus).dpadFocusRing(shape = pill, washTint = accent),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(4.dp))
                Text("Play", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(
                onClick = guard.wrap { store.setStaging(false) },
                shape = pill,
                contentPadding = buttonPadding,
                border = BorderStroke(1.dp, accent),
                modifier = Modifier.dpadFocusRing(shape = pill, washTint = accent),
            ) {
                Text("Done", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold, color = accent)
            }
        }
    }
}

/**
 * TV staging-banner button: a capsule on the [com.aeriotv.android.ui.tv.TvPill]
 * metrics. Focus ring 2 dp, white on a filled capsule and accent otherwise,
 * scale 1.05, exactly as the group pills. [content] receives the focused ink
 * (onSurface) for plain capsules, or null at rest.
 */
@Composable
private fun TvBannerCapsule(
    onClick: () -> Unit,
    fill: androidx.compose.ui.graphics.Color?,
    border: androidx.compose.ui.graphics.Color?,
    modifier: Modifier = Modifier,
    content: @Composable (androidx.compose.ui.graphics.Color?) -> Unit,
) {
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = androidx.compose.foundation.shape.CircleShape
    val ringWidth = com.aeriotv.android.ui.tv.TvChrome.ringWidth
    Row(
        modifier = modifier
            .tvFocusScale(focused, focusedScale = 1.05f)
            .clip(shape)
            .then(if (fill != null) Modifier.background(fill) else Modifier)
            .then(if (border != null && !focused) Modifier.border(1.dp, border, shape) else Modifier)
            .border(
                width = ringWidth,
                color = when {
                    !focused -> androidx.compose.ui.graphics.Color.Transparent
                    fill != null -> androidx.compose.ui.graphics.Color.White
                    else -> colors.primary
                },
                shape = shape,
            )
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(
                horizontal = com.aeriotv.android.ui.tv.TvChrome.pillHorizontalPadding,
                vertical = com.aeriotv.android.ui.tv.TvChrome.pillVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        content(if (focused) colors.onSurface else null)
    }
}

/**
 * TV counterpart of the background Multiview dock card: the same title
 * ("Multiview playing in background, N channels") and tile names, with
 * Return to Multiview (back to
 * the grid as it was) and Stop (end the tiles). The tile names follow the
 * live set, so adds and removes from the guide menu show here at once.
 */
@Composable
private fun MultiviewBackgroundTvBanner(
    tiles: List<MultiviewTile>,
    onReturn: () -> Unit,
    onStop: () -> Unit,
    onReturnFocus: (() -> Boolean)?,
    playFocusRequester: FocusRequester?,
    upTarget: FocusRequester?,
    downTarget: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val guard = rememberTvMenuGuard()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val returnFocus: () -> Unit = {
        val ok = onReturnFocus?.invoke() == true ||
            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
        com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=mv-background-banner result=$ok")
    }
    val ownFocus = remember { FocusRequester() }
    val returnFocusRequester = playFocusRequester ?: ownFocus
    // Back to Guide lands on Return to Multiview (Apple parity). The guide
    // restores its grid focus while it composes, so wait for that first,
    // then take focus; guarded so the OK that chose Back to Guide cannot
    // fire Return by itself.
    LaunchedEffect(Unit) {
        guard.arm()
        kotlinx.coroutines.delay(350L)
        repeat(20) {
            if (runCatching { returnFocusRequester.requestFocus() }.isSuccess) {
                com.aeriotv.android.ui.tv.TvFocusTrace.guide("mv-background-banner focus -> Return to Multiview")
                return@LaunchedEffect
            }
            kotlinx.coroutines.delay(50L)
        }
    }
    val tvNav = Modifier.focusProperties {
        if (upTarget != null) up = upTarget
        if (downTarget != null) down = downTarget
    }
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.GridView,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                text = multiviewBackgroundTitle(tiles.size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tiles.joinToString(", ") { it.displayName },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TvBannerCapsule(
            onClick = guard.wrap {
                guard.arm()
                onReturn()
            },
            fill = accent,
            border = null,
            modifier = tvNav.focusRequester(returnFocusRequester),
        ) { _ ->
            val onAccent = MaterialTheme.colorScheme.onPrimary
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = onAccent, modifier = Modifier.size(12.dp))
            Text("Return to Multiview", fontSize = com.aeriotv.android.ui.tv.TvChrome.pillFontSize, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false, color = onAccent)
        }
        TvBannerCapsule(
            onClick = guard.wrap {
                returnFocus()
                onStop()
            },
            fill = null,
            border = null,
            modifier = tvNav,
        ) { ink ->
            Text("Stop", fontSize = com.aeriotv.android.ui.tv.TvChrome.pillFontSize, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false,
                color = ink ?: MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Radio Mode banner on TV (Android#127): the background Multiview banner's
 *  slot and shape, with "Switch to Video Mode" and "Stop". */
@Composable
private fun RadioTvBanner(
    channelName: String,
    onReturn: () -> Unit,
    onStop: () -> Unit,
    onReturnFocus: (() -> Boolean)?,
    playFocusRequester: FocusRequester?,
    upTarget: FocusRequester?,
    downTarget: FocusRequester?,
    keepFocus: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val guard = rememberTvMenuGuard()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val returnFocus: () -> Unit = {
        val ok = onReturnFocus?.invoke() == true ||
            focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
        com.aeriotv.android.ui.tv.TvFocusTrace.guide("refocus after=radio-banner result=$ok")
    }
    val ownFocus = remember { FocusRequester() }
    val returnFocusRequester = playFocusRequester ?: ownFocus
    LaunchedEffect(Unit) {
        // Guard only the OK that just chose Radio Mode; a banner re-shown
        // after Home (Streamer 2026-10-12) swallowed the first Return press.
        if (android.os.SystemClock.uptimeMillis() - com.aeriotv.android.feature.player.RadioMode.startedAtMs < 1_500L) guard.arm()
        kotlinx.coroutines.delay(350L)
        if (keepFocus != null) {
            keepFocus()
            return@LaunchedEffect
        }
        repeat(20) {
            if (runCatching { returnFocusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50L)
        }
    }
    val tvNav = Modifier.focusProperties {
        if (upTarget != null) up = upTarget
        if (downTarget != null) down = downTarget
    }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val radioHolder = remember {
        dagger.hilt.android.EntryPointAccessors.fromApplication(
            ctx.applicationContext,
            com.aeriotv.android.feature.main.MainScaffoldEntryPoint::class.java,
        ).exoPlayerHolder()
    }
    // Failed stream: the player overlay's wording, never "Playing".
    val radioStreamDown by radioHolder.streamUnavailable
        .collectAsStateWithLifecycle()
    val radioFailedId by radioHolder.failedChannel.collectAsStateWithLifecycle()
    val radioUnavailable = radioStreamDown || radioFailedId != null
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f))
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Radio,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channelName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (radioUnavailable) RADIO_UNAVAILABLE else RADIO_SUBTITLE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TvBannerCapsule(
            onClick = guard.wrap {
                guard.arm()
                onReturn()
            },
            fill = accent,
            border = null,
            modifier = tvNav.radioTrace("banner:switch").focusRequester(returnFocusRequester),
        ) { _ ->
            val onAccent = MaterialTheme.colorScheme.onPrimary
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = onAccent, modifier = Modifier.size(12.dp))
            Text("Switch to Video Mode", fontSize = com.aeriotv.android.ui.tv.TvChrome.pillFontSize, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false, color = onAccent)
        }
        TvBannerCapsule(
            onClick = guard.wrap {
                returnFocus()
                onStop()
            },
            fill = null,
            border = null,
            modifier = tvNav.radioTrace("banner:stop"),
        ) { ink ->
            Text("Stop", fontSize = com.aeriotv.android.ui.tv.TvChrome.pillFontSize, fontWeight = FontWeight.SemiBold,
                maxLines = 1, softWrap = false,
                color = ink ?: MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Names a radio banner button in the [FOCUS] trace. */
private fun Modifier.radioTrace(name: String): Modifier = composed {
    var had by remember { androidx.compose.runtime.mutableStateOf(false) }
    this.then(Modifier.onFocusChanged {
        if (it.isFocused && !had) com.aeriotv.android.ui.tv.TvFocusTrace.focus(name)
        else if (!it.isFocused && had) com.aeriotv.android.ui.tv.TvFocusTrace.blurred(name)
        had = it.isFocused
    })
}
