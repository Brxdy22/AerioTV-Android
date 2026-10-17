package com.aeriotv.android.core.profiles

import android.util.Log
import com.aeriotv.android.core.data.capability.CAPABILITIES_SCHEMA
import com.aeriotv.android.core.data.capability.CapabilityCorrections
import com.aeriotv.android.core.data.db.dao.ChannelSnapshotDao
import com.aeriotv.android.core.data.db.dao.EncryptingPlaylistDao
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.LocalRecordingDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.isDispatcharrDirectConnect
import com.aeriotv.android.core.data.repository.PlaylistRepository
import com.aeriotv.android.core.data.vod.VodCatalogStore
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrError
import com.aeriotv.android.core.network.DispatcharrLoginGate
import com.aeriotv.android.core.network.DispatcharrTokenStore
import com.aeriotv.android.core.network.DispatcharrUserSummary
import com.aeriotv.android.core.network.MeResponse
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.timeshift.TimeshiftController
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Profiles for Dispatcharr playlists (phase 1, 2026-10-16). Owns the profile
 * records, the one-time migration (each Dispatcharr playlist gets exactly one
 * default profile named after its login), add / rename / remove, and the
 * switch flow. Xtream and M3U playlists never get a profile.
 *
 * PHASE 2 HOOKS: Drive sync of profiles (DriveSyncManager runs under
 * ProfileScope.defaultOnly() today), TV launch picker.
 */
@Singleton
class ProfileManager @Inject constructor(
    private val store: ProfileStore,
    private val playlistDao: PlaylistDao,
    private val repository: PlaylistRepository,
    private val client: DispatcharrClient,
    private val tokenStore: DispatcharrTokenStore,
    private val appPreferences: AppPreferences,
    private val favoriteDao: FavoriteChannelDao,
    private val watchProgressDao: WatchProgressDao,
    private val reminderDao: ReminderDao,
    private val localRecordingDao: LocalRecordingDao,
    private val channelSnapshotDao: ChannelSnapshotDao,
    private val vodCatalogStore: VodCatalogStore,
    private val timeshift: TimeshiftController,
    private val watchlistStore: com.aeriotv.android.core.preferences.WatchlistStore,
    private val hiddenTitlesStore: com.aeriotv.android.core.preferences.HiddenTitlesStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    @Volatile private var started = false

    /** Playlist ids whose channel list / groups / VOD must reload after a
     *  profile switch. A Channel so exactly one PlaylistViewModel handles it. */
    data class Reload(val playlistId: String, val gen: Long)
    private val reloads = Channel<Reload>(Channel.BUFFERED)
    val reloadRequests: Flow<Reload> = reloads.receiveAsFlow()

    /** True from the first tap of a switch until the reload for the new
     *  profile has finished. MainActivity draws a blocking scrim while set. */
    private val _switching = kotlinx.coroutines.flow.MutableStateFlow(false)
    val switching: kotlinx.coroutines.flow.StateFlow<Boolean> = _switching

    /** Fired when a switch completes: any player still open is closed. */
    private val closePlayer = Channel<Unit>(Channel.CONFLATED)
    val closePlayerRequests: Flow<Unit> = closePlayer.receiveAsFlow()

    // Each switch gets its own generation; the watchdog and the completion
    // only act on the generation they belong to, so a stale timer from an
    // earlier switch can never release a newer switch's scrim.
    private val switchGen = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var watchdog: kotlinx.coroutines.Job? = null

    /** Called by PlaylistViewModel when the reload for [gen] has landed. */
    fun finishSwitch(gen: Long) {
        if (gen != switchGen.get()) return
        watchdog?.cancel()
        watchdog = null
        _switching.value = false
        closePlayer.trySend(Unit)
    }

    /** Header switcher "Manage Profiles": open Settings > this playlist. */
    private val manage = Channel<Unit>(Channel.CONFLATED)
    val manageRequests: Flow<Unit> = manage.receiveAsFlow()
    fun requestManageProfiles() { manage.trySend(Unit) }

    /**
     * Synchronous restore, called first thing in Application.onCreate so no
     * scoped read ever sees the default profile's state by mistake.
     */
    fun restoreSync() {
        ProfileScope.onOverlayChanged = { o -> store.update(o.profileId) { it.withOverlay(o) } }
        scope.launch {
            store.profiles.collect { list ->
                ProfileScope.profileUsernames = list.filterNot { it.isDefault }
                    .groupBy { it.playlistId }
                    .mapValues { (_, v) -> v.map { it.username }.toSet() }
            }
        }
        val pid = store.lastActivePlaylistId ?: return
        val active = store.activeProfileId(pid)?.let(store::byId)
        if (active != null && !active.isDefault && active.playlistId == pid) {
            ProfileScope.activate(active.toOverlay())
            Log.i(TAG, "[PROFILE] restored ${active.displayName} on ${pid.take(8)}")
        }
    }

    /** Migration plus active-playlist tracking. Idempotent. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            runCatching { playlistDao.rawAll().forEach { ensureDefaultProfile(it) } }
                .onFailure { Log.w(TAG, "[PROFILE] migration pass failed", it) }
            playlistDao.observeActive()
                .map { it.firstOrNull()?.id }
                .distinctUntilChanged()
                .collect { pid -> onActivePlaylist(pid) }
        }
    }

    private suspend fun onActivePlaylist(pid: String?) = mutex.withLock {
        if (pid == null) { ProfileScope.activate(null); return@withLock }
        store.lastActivePlaylistId = pid
        playlistDao.rawById(pid)?.let { ensureDefaultProfile(it) }
        val active = store.activeProfileId(pid)?.let(store::byId)
        val current = ProfileScope.activeOverlay()
        if (active != null && !active.isDefault) {
            if (current?.profileId != active.id) ProfileScope.activate(active.toOverlay())
        } else if (current != null) {
            ProfileScope.activate(null)
        }
    }

    /** Every Dispatcharr playlist has exactly one default profile. Its state
     *  is the legacy (unscoped) state, so creating it moves no data. */
    private suspend fun ensureDefaultProfile(row: PlaylistEntity) {
        if (!row.isDispatcharrDirectConnect()) return
        if (store.forPlaylist(row.id).any { it.isDefault }) return
        var username = row.username.orEmpty()
        if (username.isBlank()) {
            // API-key login: ask the server who the key belongs to.
            val key = row.apiKey
            if (!key.isNullOrBlank()) {
                username = runCatching { client.fetchMe(repository.effectiveBaseUrl(row), key)?.username }
                    .getOrNull().orEmpty()
            }
        }
        store.upsert(
            DispatcharrProfile(
                id = UUID.randomUUID().toString(),
                playlistId = row.id,
                displayName = username.ifBlank { row.name },
                username = username,
                isDefault = true,
                userLevel = row.dispatcharrUserLevel,
                createdAt = 0L,
            ),
        )
        Log.i(TAG, "[PROFILE] migrated playlist ${row.id} -> default profile")
    }

    fun profiles(playlistId: String): Flow<List<DispatcharrProfile>> =
        store.profiles.map { store.forPlaylist(playlistId) }

    /** Active profile id per playlist (default profile when none is set). */
    fun activeProfileId(playlistId: String): Flow<String?> =
        combine(store.profiles, ProfileScope.token) { _, _ ->
            store.activeProfileId(playlistId)?.takeIf { store.byId(it) != null }
                ?: store.forPlaylist(playlistId).firstOrNull { it.isDefault }?.id
        }

    /** Admin profiles may add and remove profiles. Reads the ACTIVE user. */
    suspend fun activeIsAdmin(playlistId: String): Boolean {
        val row = playlistDao.byId(playlistId) ?: return false
        return row.dispatcharrUserLevel >= 10 || row.dispatcharrIsSuperuser || row.dispatcharrIsStaff
    }

    /** Add Profile step 1: the server's users, fetched with the admin key. */
    suspend fun listServerUsers(playlistId: String): Result<List<DispatcharrUserSummary>> = runCatching {
        val row = playlistDao.byId(playlistId) ?: error("Playlist not found")
        check(activeIsAdmin(playlistId)) { "Only an Admin profile can add profiles." }
        val base = repository.effectiveBaseUrl(row)
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: error("No API key for this playlist")
        val taken = store.forPlaylist(playlistId).map { it.username }.toSet()
        client.listUsers(base, key).filter { it.username !in taken }.sortedBy { it.username.lowercase() }
    }

    /**
     * Add Profile step 2: validate by signing in as [user] once (password, or
     * API key), then store the credential encrypted. Never changes the server.
     */
    suspend fun addProfile(
        playlistId: String,
        user: DispatcharrUserSummary,
        password: String?,
        apiKey: String?,
    ): Result<DispatcharrProfile> = runCatching {
        check(activeIsAdmin(playlistId)) { "Only an Admin profile can add profiles." }
        val row = playlistDao.rawById(playlistId) ?: error("Playlist not found")
        val base = repository.effectiveBaseUrl(row)
        val id = UUID.randomUUID().toString()
        // The admin users list carries each user's api_key: use it directly
        // (validated below by reading /me/ AS that user) and only fall back
        // to a typed password or key when the server has none.
        val listedKey = user.apiKey?.takeIf { it.isNotBlank() }
        val key = if (password.isNullOrBlank() && apiKey.isNullOrBlank() && listedKey != null) {
            authenticate(playlistId, id, base, user.username, null, listedKey)
        } else {
            authenticate(playlistId, id, base, user.username, password, apiKey)
        }
        val me = client.fetchMe(base, key) ?: error("Could not read ${user.username} from the server.")
        if (!me.username.equals(user.username, ignoreCase = true)) {
            error("This API key belongs to ${me.username}, not ${user.username}.")
        }
        val profile = DispatcharrProfile(
            id = id,
            playlistId = playlistId,
            displayName = user.username,
            username = me.username,
            password = password?.takeIf { it.isNotBlank() },
        ).applyMe(me, key)
        store.upsert(profile)
        appPreferences.seedProfileSettings(id)
        Log.i(TAG, "[PROFILE] added ${profile.username} on ${playlistId.take(8)} level=${ProfileLevel.of(profile.userLevel).label}")
        profile
    }

    fun rename(profileId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        store.update(profileId) { it.copy(displayName = clean) }
    }

    /** Remove a profile and its local state on this device. Never touches the
     *  server user. The default profile cannot be removed. */
    suspend fun remove(profileId: String): Result<Unit> = runCatching {
        val p = store.byId(profileId) ?: return@runCatching
        check(!p.isDefault) { "The default profile cannot be removed." }
        check(activeIsAdmin(p.playlistId)) { "Only an Admin profile can remove profiles." }
        if (store.activeProfileId(p.playlistId) == profileId) {
            val def = store.forPlaylist(p.playlistId).first { it.isDefault }
            switchTo(p.playlistId, def.id).getOrThrow()
        }
        runCatching { favoriteDao.deleteScope(profileId) }
        runCatching { watchProgressDao.deleteScope(profileId) }
        // Cancel the profile's scheduled reminder alarms BEFORE the rows go:
        // same PendingIntent identity as RemindersViewModel.cancelAlarm.
        runCatching {
            val mgr = context.getSystemService(android.app.AlarmManager::class.java)
            reminderDao.allOnceIn(profileId).forEach { r ->
                val pi = android.app.PendingIntent.getBroadcast(
                    context, r.alarmRequestCode,
                    android.content.Intent(context, com.aeriotv.android.feature.reminders.ReminderBroadcastReceiver::class.java),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                )
                mgr?.cancel(pi)
            }
            Log.i(TAG, "[PROFILE] cancelled reminder alarms for ${p.username}")
        }
        runCatching { reminderDao.deleteScope(profileId) }
        runCatching { watchlistStore.deleteScope(profileId) }
        runCatching { hiddenTitlesStore.deleteScope(profileId) }
        runCatching {
            localRecordingDao.allOnceIn(profileId).forEach { File(it.filePath).delete() }
            localRecordingDao.deleteScope(profileId)
        }
        runCatching { channelSnapshotDao.deleteForPlaylistKey("${p.playlistId}@$profileId") }
        runCatching { appPreferences.deleteProfileScope(profileId) }
        runCatching { vodCatalogStore.deleteForPlaylistUser(p.playlistId, p.username) }
        store.remove(profileId)
        Log.i(TAG, "[PROFILE] removed ${p.username} on ${p.playlistId.take(8)}; local state deleted")
    }

    /**
     * Switch [playlistId] to [profileId]. The caller has already confirmed
     * stopping playback; PlaylistViewModel stops it on the reload. Signs in
     * as the profile through DispatcharrLoginGate (one attempt per 60 s per
     * profile, 429 honored), re-reads permissions, swaps the scope, and asks
     * for a channel / group / VOD reload.
     */
    suspend fun switchTo(playlistId: String, profileId: String): Result<Unit> = runCatching {
        val gen = switchGen.incrementAndGet()
        watchdog?.cancel()
        watchdog = null
        _switching.value = true
        try {
            switchToLocked(playlistId, profileId, gen)
        } catch (t: Throwable) {
            if (gen == switchGen.get()) _switching.value = false
            throw t
        }
    }

    private suspend fun switchToLocked(playlistId: String, profileId: String, gen: Long) {
        val target = store.byId(profileId) ?: error("Profile not found")
        val from = store.activeProfileId(playlistId)?.let(store::byId)
            ?: store.forPlaylist(playlistId).firstOrNull { it.isDefault }
        val row = playlistDao.rawById(playlistId) ?: error("Playlist not found")
        val base = repository.effectiveBaseUrl(row)
        mutex.withLock {
            if (target.isDefault) {
                authenticate(playlistId, target.id, base, row.username.orEmpty(), row.password, row.apiKey)
                ProfileScope.activate(null)
                store.setActiveProfileId(playlistId, null)
            } else {
                val key = authenticate(playlistId, target.id, base, target.username, target.password, target.apiKey)
                val me = client.fetchMe(base, key) ?: error("Could not read ${target.username} from the server.")
                val updated = target.applyMe(me, key)
                store.upsert(updated)
                ProfileScope.activate(updated.toOverlay())
                store.setActiveProfileId(playlistId, updated.id)
            }
            tokenStore.clear(playlistId)
            CapabilityCorrections.clear(playlistId)
            timeshift.stopAllRetained("profile switch")
        }
        Log.i(TAG, "[PROFILE] switch ${from?.displayName ?: "?"} -> ${target.displayName}")
        // Re-evaluate permissions with the existing capability probe; its
        // write lands in the profile record through the DAO overlay.
        runCatching { repository.probeCapabilities(playlistId, force = true, trigger = "profile switch") }
        // Safety: never leave the scrim up if no PlaylistViewModel answers.
        watchdog = scope.launch {
            kotlinx.coroutines.delay(SWITCH_TIMEOUT_MS)
            if (_switching.value && gen == switchGen.get()) {
                Log.w(TAG, "[PROFILE] reload did not report back in ${SWITCH_TIMEOUT_MS / 1000}s; releasing the UI")
                finishSwitch(gen)
            }
        }
        logPermissions(playlistId, store.byId(target.id)?.username?.ifBlank { null } ?: row.username.orEmpty())
        reloads.send(Reload(playlistId, gen))
    }

    /** [username] is the Dispatcharr username, never the display name. */
    suspend fun logPermissions(playlistId: String, username: String) {
        val row = playlistDao.byId(playlistId) ?: return
        Log.i(
            TAG,
            "[PROFILE] permissions user=$username level=${ProfileLevel.of(row.dispatcharrUserLevel).label} " +
                "catchup=${row.dispatcharrCatchupEnabled} movies=${row.dispatcharrVodMoviesEnabled} " +
                "series=${row.dispatcharrVodSeriesEnabled} dvr=${row.dispatcharrDvrAccess.ifBlank { "default" }}",
        )
    }

    /** Sign in as one user; returns that user's API key. */
    private suspend fun authenticate(
        playlistId: String,
        profileId: String,
        base: String,
        username: String,
        password: String?,
        apiKey: String?,
    ): String {
        if (!password.isNullOrBlank() && username.isNotBlank()) {
            var rejected: DispatcharrError? = null
            val key = DispatcharrLoginGate.silentRelogin("$playlistId:$profileId") {
                try {
                    val pair = client.login(base, username, password)
                    client.fetchCurrentUserApiKey(base, pair.access)
                } catch (e: DispatcharrError.InvalidCredentials) {
                    throw e
                } catch (e: DispatcharrError) {
                    rejected = e
                    null
                }
            }
            rejected?.let { throw it }
            return key ?: throw DispatcharrError.InvalidCredentials(
                "Sign-in for $username was rejected. Try again in a minute.",
            )
        }
        val k = apiKey?.takeIf { it.isNotBlank() } ?: error("Enter a password or an API key.")
        client.fetchMe(base, k) ?: throw DispatcharrError.InvalidCredentials("The API key for $username was rejected.")
        return k
    }

    private fun DispatcharrProfile.applyMe(me: MeResponse, key: String): DispatcharrProfile {
        val props: JsonObject? = me.customProperties
        fun flag(name: String): Boolean = (props?.get(name) as? JsonPrimitive)?.booleanOrNull != false
        val level = if (me.isSuperuser || me.isStaff) 10 else (me.userLevel ?: 0)
        return copy(
            apiKey = key,
            username = me.username,
            userLevel = level,
            streamLimit = me.streamLimit,
            hideAdultContent = (props?.get("hide_adult_content") as? JsonPrimitive)?.booleanOrNull == true,
            dvrAccess = (props?.get("dvr_access") as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty(),
            catchupEnabled = flag("catchup_enabled"),
            vodMoviesEnabled = flag("vod_movies_enabled"),
            vodSeriesEnabled = flag("vod_series_enabled"),
            accountProfileIds = me.channelProfiles.joinToString(","),
            isStaff = me.isStaff,
            isSuperuser = me.isSuperuser,
            customProperties = props?.toString().orEmpty(),
            capabilitiesFetchedAt = System.currentTimeMillis(),
            capabilitiesSchema = CAPABILITIES_SCHEMA,
            capabilitiesStale = false,
        )
    }

    private suspend fun PlaylistDao.rawById(id: String): PlaylistEntity? =
        (this as? EncryptingPlaylistDao)?.byIdDefaultProfile(id) ?: byId(id)

    private suspend fun PlaylistDao.rawAll(): List<PlaylistEntity> =
        kotlinx.coroutines.withContext(ProfileScope.defaultOnly()) { allOnce() }

    private companion object {
        const val TAG = "AerioProfiles"
        const val SWITCH_TIMEOUT_MS = 45_000L
    }
}
