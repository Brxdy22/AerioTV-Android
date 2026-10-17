package com.aeriotv.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.network.DispatcharrUserSummary
import com.aeriotv.android.core.profiles.DispatcharrProfile
import com.aeriotv.android.core.profiles.ProfileLevel
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.ui.settings.SettingsCard
import com.aeriotv.android.ui.settings.SettingsDialogTextButton
import com.aeriotv.android.ui.settings.SettingsSectionFooter
import com.aeriotv.android.ui.settings.SettingsSectionHeader
import com.aeriotv.android.ui.settings.rememberIsTvDevice
import com.aeriotv.android.ui.settings.settingsRowCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Profiles UI (phase 1, 2026-10-16): the Profiles section on a Dispatcharr
 * playlist's settings page (every form factor, the TV switcher lives here),
 * and the phone / tablet Live TV header switcher. Copy: "Profiles",
 * "Add Profile", "Manage Profiles", "Switch Profile".
 */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val manager: ProfileManager,
    playlistDao: PlaylistDao,
    private val exoHolder: com.aeriotv.android.core.playback.AerioExoPlayerHolder,
) : ViewModel() {

    val activePlaylistId: Flow<String?> =
        playlistDao.observeActive().map { it.firstOrNull()?.id }.distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    val activePlaylistProfiles: Flow<List<DispatcharrProfile>> =
        activePlaylistId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else manager.profiles(id) }

    fun profiles(playlistId: String) = manager.profiles(playlistId)
    fun activeProfileId(playlistId: String) = manager.activeProfileId(playlistId)
    suspend fun isAdmin(playlistId: String) = manager.activeIsAdmin(playlistId)

    /** True while anything is playing (main player, mini player, background audio). */
    fun isPlaying(): Boolean {
        val p = exoHolder.playerInstance.value ?: return false
        return p.playbackState == androidx.media3.common.Player.STATE_READY ||
            p.playbackState == androidx.media3.common.Player.STATE_BUFFERING
    }

    fun switchTo(playlistId: String, profileId: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val r = manager.switchTo(playlistId, profileId)
            onDone(r.exceptionOrNull()?.let { it.message ?: "Switch failed" })
        }
    }

    suspend fun listUsers(playlistId: String) = manager.listServerUsers(playlistId)
    suspend fun add(playlistId: String, user: DispatcharrUserSummary, password: String?, apiKey: String?) =
        manager.addProfile(playlistId, user, password, apiKey)
    fun rename(profileId: String, name: String) = manager.rename(profileId, name)
    suspend fun remove(profileId: String) = manager.remove(profileId)
    fun manageProfiles() = manager.requestManageProfiles()
}

/** Phone / tablet Live TV header: avatar circle, only with 2+ profiles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSwitcherButton(modifier: Modifier = Modifier) {
    val vm: ProfilesViewModel = hiltViewModel()
    val playlistId by vm.activePlaylistId.collectAsStateWithLifecycle(initialValue = null)
    val profiles by vm.activePlaylistProfiles.collectAsStateWithLifecycle(initialValue = emptyList())
    val pid = playlistId ?: return
    if (profiles.size < 2) return
    val activeId by vm.activeProfileId(pid).collectAsStateWithLifecycle(initialValue = null)
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    var open by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    ProfileAvatar(
        name = active.displayName,
        size = 38,
        modifier = modifier.clip(CircleShape).clickable { open = true },
    )

    fun doSwitch(p: DispatcharrProfile) {
        busy = true
        vm.switchTo(pid, p.id) { err -> busy = false; error = err; if (err == null) open = false }
    }

    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    "Switch Profile",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                profiles.forEach { p ->
                    ProfileRow(
                        profile = p,
                        isActive = p.id == active.id,
                        onClick = {
                            if (p.id == active.id || busy) return@ProfileRow
                            if (vm.isPlaying()) confirm = p else doSwitch(p)
                        },
                    )
                }
                if (busy) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open = false; vm.manageProfiles() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.ManageAccounts, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text("Manage Profiles", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    confirm?.let { p ->
        StopPlaybackDialog(name = p.displayName, onDismiss = { confirm = null }) {
            confirm = null
            doSwitch(p)
        }
    }
}

@Composable
private fun StopPlaybackDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch Profile") },
        text = { Text("Stop playback and switch to $name?") },
        confirmButton = { SettingsDialogTextButton(label = "Switch", onClick = onConfirm) },
        dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = onDismiss) },
    )
}

@Composable
fun ProfileAvatar(name: String, size: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase().ifEmpty { "?" },
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = (size * 0.42f).sp,
        )
    }
}

@Composable
private fun ProfileRow(
    profile: DispatcharrProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .settingsRowCard(focused)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(profile.displayName, 32)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                add(profile.username)
                if (profile.streamLimit > 0) add("Streams: ${profile.streamLimit}")
            }.joinToString("  ")
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LevelBadge(ProfileLevel.of(profile.userLevel))
        if (isActive) {
            Spacer(Modifier.size(8.dp))
            Icon(Icons.Filled.Check, contentDescription = "Active", tint = MaterialTheme.colorScheme.primary)
        }
        trailing?.invoke()
    }
}

@Composable
private fun LevelBadge(level: ProfileLevel) {
    Text(
        text = level.label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Settings > Playlists > (Dispatcharr playlist) > Profiles. */
@Composable
fun ProfilesSection(playlistId: String) {
    val vm: ProfilesViewModel = hiltViewModel()
    val profiles by vm.profiles(playlistId).collectAsStateWithLifecycle(initialValue = emptyList())
    val activeId by vm.activeProfileId(playlistId).collectAsStateWithLifecycle(initialValue = null)
    var isAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(playlistId, activeId) { isAdmin = vm.isAdmin(playlistId) }
    val isTv = rememberIsTvDevice()
    // Nothing new for a single-profile non-admin; an Admin sees Add Profile.
    if (profiles.size < 2 && !isAdmin) return

    var confirmSwitch by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var actionsFor by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var renaming by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var removing by remember { mutableStateOf<DispatcharrProfile?>(null) }
    var adding by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doSwitch(p: DispatcharrProfile) {
        busy = true; status = null
        vm.switchTo(playlistId, p.id) { err -> busy = false; status = err }
    }

    Column {
        SettingsSectionHeader("Profiles")
        SettingsCard {
            profiles.forEach { p ->
                ProfileRow(
                    profile = p,
                    isActive = p.id == activeId,
                    onClick = {
                        if (busy) return@ProfileRow
                        // Tap a profile to switch to it; tap the active one
                        // for Rename / Remove.
                        if (p.id == activeId) actionsFor = p
                        else if (vm.isPlaying()) confirmSwitch = p
                        else doSwitch(p)
                    },
                    trailing = if (isTv) null else {
                        {
                            Spacer(Modifier.size(4.dp))
                            Icon(
                                Icons.Outlined.Edit,
                                contentDescription = "Rename or remove",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable { actionsFor = p }
                                    .padding(6.dp)
                                    .size(18.dp),
                            )
                        }
                    },
                )
            }
            if (isAdmin) {
                var focused by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focused = it.isFocused }
                        .settingsRowCard(focused)
                        .clickable { adding = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Text("Add Profile", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
            }
            if (busy) {
                Row(Modifier.padding(16.dp)) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
            }
            status?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
            }
        }
        SettingsSectionFooter(
            "Each profile signs in as its own Dispatcharr user. Favorites, recordings, and watch history are kept per profile on this device.",
        )
    }

    confirmSwitch?.let { p ->
        StopPlaybackDialog(name = p.displayName, onDismiss = { confirmSwitch = null }) {
            confirmSwitch = null; doSwitch(p)
        }
    }
    actionsFor?.let { p ->
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = { Text(p.displayName) },
            text = { Text(p.username) },
            confirmButton = {
                SettingsDialogTextButton(label = "Rename", onClick = { actionsFor = null; renaming = p })
            },
            dismissButton = {
                if (isAdmin && !p.isDefault) {
                    SettingsDialogTextButton(label = "Remove", destructive = true, onClick = { actionsFor = null; removing = p })
                } else {
                    SettingsDialogTextButton(label = "Cancel", onClick = { actionsFor = null })
                }
            },
        )
    }
    renaming?.let { p ->
        var name by remember(p.id) { mutableStateOf(p.displayName) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                SettingsDialogTextButton(label = "Save", enabled = name.isNotBlank(), onClick = {
                    vm.rename(p.id, name); renaming = null
                })
            },
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { renaming = null }) },
        )
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove Profile") },
            text = {
                Text("Remove ${p.displayName}? Favorites, recordings, and watch history for this profile on this device will be deleted.")
            },
            confirmButton = {
                SettingsDialogTextButton(label = "Remove", destructive = true, onClick = {
                    removing = null
                    scope.launch { status = vm.remove(p.id).exceptionOrNull()?.message }
                })
            },
            dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = { removing = null }) },
        )
    }
    if (adding) AddProfileDialog(playlistId, vm) { adding = false }
}

@Composable
private fun AddProfileDialog(playlistId: String, vm: ProfilesViewModel, onClose: () -> Unit) {
    var users by remember { mutableStateOf<List<DispatcharrUserSummary>?>(null) }
    var picked by remember { mutableStateOf<DispatcharrUserSummary?>(null) }
    var password by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(playlistId) {
        vm.listUsers(playlistId).fold({ users = it }, { error = it.message ?: "Could not load users" })
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Add Profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val u = picked
                if (u == null) {
                    when {
                        users == null && error == null -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        users?.isEmpty() == true -> Text("Every Dispatcharr user on this server already has a profile.")
                        busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        users != null -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(users.orEmpty(), key = { it.id }) { user ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            if (busy) return@clickable
                                            if (!user.apiKey.isNullOrBlank()) {
                                                // Server gave us this user's key: add now, no prompt.
                                                busy = true; error = null
                                                scope.launch {
                                                    vm.add(playlistId, user, null, null).fold(
                                                        { onClose() },
                                                        { busy = false; picked = user; error = it.message ?: "Could not add profile" },
                                                    )
                                                }
                                            } else {
                                                picked = user
                                            }
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ProfileAvatar(user.username, 28)
                                    Spacer(Modifier.size(10.dp))
                                    Text(user.username, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    LevelBadge(ProfileLevel.of(if (user.isSuperuser || user.isStaff) 10 else user.userLevel ?: 0))
                                }
                            }
                        }
                    }
                } else {
                    Text("Sign in as ${u.username} once. Enter the password or an API key.")
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text("Password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    OutlinedTextField(
                        value = apiKey, onValueChange = { apiKey = it }, singleLine = true,
                        label = { Text("API Key") },
                    )
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            val u = picked
            if (u != null) {
                SettingsDialogTextButton(
                    label = "Add Profile",
                    enabled = !busy && (password.isNotBlank() || apiKey.isNotBlank()),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            vm.add(playlistId, u, password.takeIf { it.isNotBlank() }, apiKey.trim().takeIf { it.isNotBlank() })
                                .fold({ onClose() }, { busy = false; error = it.message ?: "Could not add profile" })
                        }
                    },
                )
            }
        },
        dismissButton = { SettingsDialogTextButton(label = "Cancel", onClick = onClose) },
    )
}
