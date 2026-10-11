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
) {
    val staging by store.isStaging.collectAsStateWithLifecycle()
    val tiles by store.selected.collectAsStateWithLifecycle()
    if (!staging || tiles.isEmpty()) return
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
