package com.aeriotv.android.feature.onboarding

import com.aeriotv.android.ui.scale.subtext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.remember
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.aeriotv.android.ui.settings.TvSettingsMetrics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aeriotv.android.core.data.SourceType
import com.aeriotv.android.feature.onboarding.components.SourceTypeCard
import com.aeriotv.android.ui.adaptive.rememberViewport
import com.aeriotv.android.ui.settings.dpadFocusRing
import com.aeriotv.android.ui.settings.rememberIsTvDevice

/**
 * "Add Playlist" / onboarding source picker: Dispatcharr Direct Connect is
 * the headline card; "Other playlist types" reveals Xtream Codes and
 * M3U + EPG. Tapping a card pushes to the Configure form.
 *
 * Per the no-bundled-media rule, this surface ships zero hardcoded URLs - the
 * cards only describe types, the user picks one and supplies their own URL on
 * the next screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseSourceTypeScreen(
    onBack: () -> Unit,
    onChoose: (SourceType) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    "Add Playlist",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    // TV hides the back arrow below, which collapses the
                    // navigation-icon slot and drops the title onto Material's
                    // 16dp inset - inside the title-safe margin. Re-establish
                    // the same left edge the Settings rail uses.
                    modifier = if (rememberIsTvDevice()) {
                        Modifier.padding(start = TvSettingsMetrics.topAppBarTitleOverscanPadding)
                    } else {
                        Modifier
                    },
                )
            },
            navigationIcon = {
                // No back arrow on Android TV -- the remote BACK steps back
                // (out of the Add Playlist wizard). Phones/tablets keep it.
                if (!rememberIsTvDevice()) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
            ),
        )

        val vp = rememberViewport()
        // tvOS parity: the three source types are a single vertical stack of
        // rounded rows (not a 3-up grid), centered with a tighter max width so
        // the rows aren't stretched edge-to-edge on a wide TV.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = vp.gutter, vertical = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.TopCenter,
        ) {
            Column(
                modifier = if (vp.onboardingMaxWidth != androidx.compose.ui.unit.Dp.Unspecified)
                    Modifier.widthIn(max = vp.onboardingMaxWidth).fillMaxWidth()
                else
                    Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Choose Source Type",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "Select how you want to connect to your media source.",
                    style = MaterialTheme.typography.bodyMedium.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(4.dp))

                // Dispatcharr Direct Connect leads the list in the same
                // unselected card style as Xtream Codes and M3U + EPG; the
                // accent style appears only while pressed or focused.
                SourceTypeCard(
                    icon = Icons.Filled.Key,
                    title = "Dispatcharr Direct Connect",
                    subtitle = "Connect to your Dispatcharr server. Profiles, DVR, catch-up, and server sync.",
                    modifier = Modifier.tappable { onChoose(SourceType.DispatcharrUserPass) },
                )
                Text(
                    text = "*AerioTV is not officially affiliated with the Dispatcharr project",
                    style = MaterialTheme.typography.bodySmall.subtext(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                var showOthers by rememberSaveable { mutableStateOf(false) }
                if (!showOthers) {
                    OutlinedButton(
                        onClick = { showOthers = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .dpadFocusRing(RoundedCornerShape(50)),
                    ) {
                        Text("Other playlist types", style = MaterialTheme.typography.titleMedium)
                    }
                } else {
                    SourceTypeCard(
                        icon = Icons.Filled.Tv,
                        title = "Xtream Codes",
                        subtitle = "Xtream Codes API. Live TV, VOD movies & series.",
                        modifier = Modifier.tappable { onChoose(SourceType.XtreamCodes) },
                    )
                    SourceTypeCard(
                        icon = Icons.Filled.Description,
                        title = "M3U + EPG",
                        subtitle = "Any M3U playlist URL. Works with Dispatcharr, any IPTV provider.",
                        modifier = Modifier.tappable { onChoose(SourceType.M3uUrl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Modifier.tappable(onClick: () -> Unit): Modifier {
    // Clip to the SourceTypeCard rounded shape BEFORE the clickable so the D-pad
    // focus indication follows the rounded corners, not a squared rectangle.
    // Touch press shows the same accent wash + ring the TV focus ring uses.
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val accent = MaterialTheme.colorScheme.primary
    return this.clip(shape)
        .dpadFocusRing(shape, washTint = accent)
        .then(
            if (pressed) {
                Modifier
                    .background(accent.copy(alpha = 0.16f), shape)
                    .border(1.5.dp, accent, shape)
            } else {
                Modifier
            },
        )
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
}
