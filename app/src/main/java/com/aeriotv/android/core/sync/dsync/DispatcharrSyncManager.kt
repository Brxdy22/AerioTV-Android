package com.aeriotv.android.core.sync.dsync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.aeriotv.android.BuildConfig
import com.aeriotv.android.core.data.ChannelCollection
import com.aeriotv.android.core.data.db.dao.ChannelSnapshotDao
import com.aeriotv.android.core.data.db.dao.FavoriteChannelDao
import com.aeriotv.android.core.data.db.dao.PlaylistDao
import com.aeriotv.android.core.data.db.dao.ReminderDao
import com.aeriotv.android.core.data.db.dao.WatchProgressDao
import com.aeriotv.android.core.data.db.entity.FavoriteChannelEntity
import com.aeriotv.android.core.data.db.entity.PlaylistEntity
import com.aeriotv.android.core.data.db.entity.ReminderEntity
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.data.db.entity.isDispatcharrDirectConnect
import com.aeriotv.android.core.data.db.entity.reminderKey
import com.aeriotv.android.core.network.DispatcharrClient
import com.aeriotv.android.core.network.DispatcharrUserSummary
import com.aeriotv.android.core.preferences.AppPreferences
import com.aeriotv.android.core.preferences.HiddenTitlesStore
import com.aeriotv.android.core.preferences.WatchlistStore
import com.aeriotv.android.core.profiles.PinRules
import com.aeriotv.android.core.profiles.ProfileManager
import com.aeriotv.android.core.profiles.effectiveRole
import com.aeriotv.android.core.profiles.ProfilePinStore
import com.aeriotv.android.core.profiles.ProfileScope
import com.aeriotv.android.core.profiles.ProfileStore
import com.aeriotv.android.feature.reminders.ReminderBroadcastReceiver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Dispatcharr Sync (docs/DispatcharrSyncSchema.md, shared with the Apple
 * app): the third sync provider. Each category lives in the active profile's
 * own Dispatcharr App Data (`users/me/app-data/<key>/`, that profile's API
 * key), so every profile syncs as its own Dispatcharr user.
 *
 * One session per (Dispatcharr Direct Connect playlist, active profile). A
 * session pulls first; a key is never written before it was read in that
 * session. Cadence and error handling follow section 7 exactly.
 */
@Singleton
class DispatcharrSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val playlistDao: PlaylistDao,
    private val repository: Provider<com.aeriotv.android.core.data.repository.PlaylistRepository>,
    private val client: DispatcharrClient,
    private val favoriteDao: FavoriteChannelDao,
    private val watchProgressDao: WatchProgressDao,
    private val reminderDao: ReminderDao,
    private val channelSnapshotDao: ChannelSnapshotDao,
    private val watchlistStore: WatchlistStore,
    private val hiddenTitlesStore: HiddenTitlesStore,
    private val profileStore: ProfileStore,
    private val pinStore: ProfilePinStore,
    private val profileManager: Provider<ProfileManager>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = context.getSharedPreferences("aerio_dsync", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val isTv: Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature("android.software.leanback_only")

    /** What Settings shows on the Dispatcharr Sync row. */
    data class Ui(
        val active: Boolean = false,
        val lastSyncAt: Long = 0L,
        /** "Sync data too large", "Sync storage on the server is full", "Sign in again to sync", or null. */
        val message: String? = null,
        val running: Boolean = false,
    )

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    /** Random install id (section 3), never tied to hardware ids. */
    val deviceId: String by lazy {
        state.getString(K_DEVICE, null) ?: UUID.randomUUID().toString().uppercase().also {
            state.edit().putString(K_DEVICE, it).apply()
        }
    }

    // ---- session ----

    private data class Target(val playlistId: String, val profileToken: String)
    private val HOLD = Target("\u0000hold", "")

    /**
     * One sync session, bound to ONE Dispatcharr user: the profile token and
     * the API key captured at its start. Nothing it queued may ever go out
     * under another profile (2026-10-18 cross-profile write on switch).
     */
    private inner class Session(val playlistId: String, val profileKey: String, val token: String) {
        val mutex = Mutex()
        @Volatile var bound: Conn? = null
        /** No key is written before this session's first complete pull. */
        @Volatile var firstPullDone = false
        /** The last list call answered (false while offline). */
        @Volatile var listOk = false
        /** Keys whose last send failed, or that were unsent when the session began. */
        val resend = java.util.Collections.synchronizedSet(HashSet<DsyncKey>())
        @Volatile var reconnectJob: Job? = null
        val read = HashSet<DsyncKey>()
        val blocked = HashSet<DsyncKey>()
        val tooLarge = HashSet<DsyncKey>()
        var serverFull = false
        var paused = false
        val serverBytes = HashMap<String, Long>()
        val pending = HashMap<DsyncKey, Job>()
        val retries = HashMap<DsyncKey, Int>()
        val suppressUntil = HashMap<DsyncKey, Long>()
        var users: List<DispatcharrUserSummary>? = null
        var lastPullAt = 0L
    }

    @Volatile private var session: Session? = null
    @Volatile private var started = false
    @Volatile private var foreground = true
    @Volatile private var backgroundSince = 0L

    /** Called once from Application.onCreate (main thread). */
    fun start() {
        if (started) return
        started = true
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    foreground = true
                    val s = session ?: return
                    if (System.currentTimeMillis() - s.lastPullAt >= FOREGROUND_PULL_MS) {
                        scope.launch { pullAll(s, "foreground") }
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    foreground = false
                    backgroundSince = System.currentTimeMillis()
                    session?.let { s -> scope.launch { flush(s) } }
                }
            })
        }
        profileManager.get().onProfilesMutated = { pid, user, what -> onProfilesMutated(pid, user, what) }
        registerNetworkCallback()
        scope.launch {
            combine(
                prefs.syncProvider,
                playlistDao.observeActive().map { rows -> rows.firstOrNull()?.takeIf { it.isDispatcharrDirectConnect() }?.id },
                ProfileScope.token,
                kotlinx.coroutines.flow.combine(profileManager.get().switching, profileManager.get().launchPicker) { sw, pk -> sw || pk != null },
            ) { provider, pid, token, switching ->
                // A profile switch (or the TV launch picker) is in flight: hold until its sign-in and
                // permissions are in place (the scrim drops), then start.
                if (switching) HOLD else if (provider == PROVIDER && pid != null) Target(pid, token) else null
            }
                .distinctUntilChanged()
                .collectLatest { t ->
                    session?.let { old ->
                        // Send what the old session queued while its profile is
                        // still the active one; the gate drops anything once
                        // the token moved (the dirty flag keeps it for later).
                        Log.i(TAG, "[DSYNC] session end user=${old.profileKey.take(8)} (${if (t === HOLD) "switch" else "change"})")
                        runCatching { flush(old) }
                        synchronized(old.pending) { old.pending.values.forEach { it.cancel() }; old.pending.clear() }
                    }
                    session = null
                    if (t === HOLD) return@collectLatest
                    if (t == null) {
                        _ui.value = Ui()
                        return@collectLatest
                    }
                    val prev = lastTarget
                    lastTarget = t
                    val why = if (prev != null && prev.playlistId == t.playlistId && prev.profileToken != t.profileToken) "profile" else "launch"
                    runSession(t, why)
                }
        }
    }

    @Volatile private var lastTarget: Target? = null

    private fun registerNetworkCallback() {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    val s = session ?: return
                    reconnect(s)
                }
            })
        }
    }

    /**
     * The network came back: the interface is often up before routes and DNS
     * are, so retry the pull at 2 s, 5 s, 15 s, then every 60 s until the list
     * call answers (or the session ends).
     */
    private fun reconnect(s: Session) {
        s.reconnectJob?.cancel()
        s.reconnectJob = scope.launch {
            var i = 0
            while (isActive && session === s) {
                delay(RECONNECT_MS[minOf(i, RECONNECT_MS.size - 1)])
                pullAll(s, "reconnect")
                if (s.listOk) return@launch
                i++
                Log.i(TAG, "[DSYNC] reconnect pull failed; retry ${i + 1}")
            }
        }
    }

    /** ProfileManager mutation hook: stamp the row and re-send `profiles` from an admin session. */
    private fun onProfilesMutated(playlistId: String, username: String, what: String) {
        // Called from UI actions on the main thread: all disk work happens on IO.
        val now = System.currentTimeMillis() / 1000
        scope.launch {
            val e = state.edit().putLong(rowKey(playlistId, username), now)
            if (what == "remove") e.putLong(removedKey(playlistId, username), now) else e.remove(removedKey(playlistId, username))
            e.apply()
            val s = session?.takeIf { it.playlistId == playlistId }
            Log.i(TAG, "[DSYNC] profiles dirty ($what ${username})${if (s == null) "; no session" else ""}")
            if (s == null) return@launch
            if (!isAdmin(s)) return@launch
            setDirty(s, DsyncKey.Profiles, true)
            schedule(s, DsyncKey.Profiles, DEBOUNCE_MS)
        }
    }

    private fun rowKey(playlistId: String, username: String) = "prow|$playlistId|${username.lowercase()}"
    private fun removedKey(playlistId: String, username: String) = "pgone|$playlistId|${username.lowercase()}"

    private suspend fun runSession(t: Target, trigger: String) {
        val profileKey = currentProfileId(t.playlistId) ?: t.profileToken.ifEmpty { "default" }
        val s = Session(t.playlistId, profileKey, t.profileToken)
        session = s
        s.bound = conn(s)
        _ui.value = Ui(active = true, lastSyncAt = state.getLong(k("last", s), 0L))
        var who = profileStore.byId(profileKey)
        if (who != null && who.userId <= 0) {
            // Not read yet (a default profile never switched to): ask /me/ once.
            conn(s)?.let { c ->
                runCatching { client.fetchMe(c.base, c.apiKey) }.getOrNull()
                    ?.takeIf { it.username.equals(who?.username, ignoreCase = true) }
                    ?.let { me -> profileStore.update(profileKey) { it.copy(userId = me.id) }; who = profileStore.byId(profileKey) }
            }
        }
        Log.i(
            TAG,
            "[DSYNC] session start playlist=${t.playlistId.take(8)} user=${who?.username ?: "?"} " +
                "userId=${who?.userId?.takeIf { it > 0 } ?: "?"}",
        )
        pullAll(s, trigger)
        kotlinx.coroutines.coroutineScope {
            DsyncKey.entries.forEach { key ->
                launch {
                    sourceFor(key).distinctUntilChanged().drop(1).collect { onLocalChange(s, key) }
                }
            }
            launch {
                while (isActive) {
                    delay(PERIODIC_PULL_MS)
                    if (foreground) pullAll(s, "periodic")
                }
            }
        }
    }

    /** Settings > Sync Now: pull, then flush anything waiting. */
    fun syncNow() {
        val s = session ?: return
        scope.launch {
            s.paused = false
            pullAll(s, "manual")
            flush(s)
        }
    }

    private fun currentProfileId(playlistId: String): String? =
        profileStore.activeProfileId(playlistId)?.takeIf { profileStore.byId(it) != null }
            ?: profileStore.forPlaylist(playlistId).firstOrNull { it.isDefault }?.id

    // ---- per-key enablement ----

    private suspend fun enabled(key: DsyncKey, s: Session): Boolean {
        val cat = key.category
        if (cat != null && !prefs.syncCategoryEnabledOnce(cat)) return false
        return when (key) {
            DsyncKey.RemoteMap -> prefs.syncRemoteControlMapOnce()
            DsyncKey.RadioDefault -> prefs.syncRadioModeByDefaultOnce()
            DsyncKey.Profiles -> isAdmin(s)
            else -> true
        }
    }

    private suspend fun isAdmin(s: Session): Boolean = runCatching { profileManager.get().activeIsAdmin(s.playlistId) }.getOrDefault(false)

    // ---- connection ----

    private data class Conn(val base: String, val apiKey: String, val row: PlaylistEntity)

    /** The session's own credential, captured while its profile was active. */
    private suspend fun conn(s: Session): Conn? {
        s.bound?.let { return it }
        if (ProfileScope.token.value != s.token) return null
        return freshConn(s)?.also { s.bound = it }
    }

    private suspend fun freshConn(s: Session): Conn? {
        if (ProfileScope.token.value != s.token) return null
        val row = playlistDao.byId(s.playlistId) ?: return null
        val key = row.apiKey?.takeIf { it.isNotBlank() } ?: return null
        return Conn(repository.get().effectiveBaseUrl(row), key, row)
    }

    // ---- persisted per (playlist, profile, key) state ----

    private fun k(name: String, s: Session, key: DsyncKey? = null) =
        "$name|${s.playlistId}|${s.profileKey}" + (key?.let { "|${it.suffix}" } ?: "")

    private fun rawOf(s: Session, key: DsyncKey): JsonObject? =
        state.getString(k("raw", s, key), null)?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun lastDataOf(s: Session, key: DsyncKey): String? = state.getString(k("data", s, key), null)

    private fun saveAfter(s: Session, key: DsyncKey, raw: JsonObject, at: Long, dev: String, data: JsonObject) {
        state.edit()
            .putString(k("raw", s, key), raw.toString())
            .putLong(k("at", s, key), at)
            .putString(k("dev", s, key), dev)
            .putString(k("data", s, key), data.toString())
            .apply()
    }

    /** Persistent (survives process death). [dirtyAt] = the last local change, Unix seconds. */
    private fun setDirty(s: Session, key: DsyncKey, dirty: Boolean) {
        val e = state.edit().putBoolean(k("dirty", s, key), dirty)
        if (dirty) e.putLong(k("dirtyAt", s, key), System.currentTimeMillis() / 1000)
        e.commit()
    }

    private fun dirtyAt(s: Session, key: DsyncKey) = state.getLong(k("dirtyAt", s, key), 0L)

    private fun isDirty(s: Session, key: DsyncKey) = state.getBoolean(k("dirty", s, key), false)

    private fun stampLast(s: Session) {
        val now = System.currentTimeMillis()
        state.edit().putLong(k("last", s), now).apply()
        _ui.value = _ui.value.copy(lastSyncAt = now)
    }

    private fun refreshMessage(s: Session) {
        val msg = when {
            s.paused -> "Sign in again to sync"
            s.tooLarge.isNotEmpty() -> "Sync data too large"
            s.serverFull -> "Sync storage on the server is full"
            else -> null
        }
        if (session === s) _ui.value = _ui.value.copy(message = msg)
    }

    // ---- pull (section 7.1, 7.2) ----

    private suspend fun pullAll(s: Session, reason: String) = s.mutex.withLock {
        if (session !== s) return@withLock
        val c = conn(s) ?: return@withLock
        s.listOk = false
        _ui.value = _ui.value.copy(running = true)
        try {
            val list = runCatching { client.appDataList(c.base, c.apiKey) }.getOrNull()
            if (list == null || list.status >= 500) {
                Log.w(TAG, "[DSYNC] list failed status=${list?.status ?: "network"} ($reason)")
                return@withLock
            }
            if (list.status == 401 || list.status == 403) {
                s.paused = true
                refreshMessage(s)
                Log.w(TAG, "[DSYNC] list status=${list.status}; paused for this profile")
                return@withLock
            }
            if (!list.ok) {
                Log.w(TAG, "[DSYNC] list status=${list.status} code=${list.code}")
                return@withLock
            }
            s.paused = false
            s.listOk = true
            s.serverBytes.clear()
            runCatching {
                val root = json.parseToJsonElement(list.body.orEmpty()) as? JsonObject
                (root?.get("apps") as? JsonArray)?.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    val name = o["key"].asStr() ?: return@forEach
                    s.serverBytes[name] = o["bytes"].asLong() ?: 0L
                }
            }
            for (key in DsyncKey.entries) {
                if (session !== s) return@withLock
                if (!enabled(key, s)) continue
                pullKey(s, c, key)
            }
            s.lastPullAt = System.currentTimeMillis()
            s.firstPullDone = true
            stampLast(s)
            Log.i(TAG, "[DSYNC] pull done ($reason) read=${s.read.size} server=${s.serverBytes.size}")
            // A key absent on the server, or changed here before this session
            // could write it, goes up now.
            for (key in s.read) {
                val dirty = isDirty(s, key)
                if (dirty && (reason == "reconnect" || reason == "launch" || reason == "profile")) s.resend += key
                if (dirty || key.wire !in s.serverBytes) schedule(s, key, 0L)
            }
        } finally {
            _ui.value = _ui.value.copy(running = false)
            refreshMessage(s)
        }
    }

    private suspend fun pullKey(s: Session, c: Conn, key: DsyncKey) {
        if (key.wire !in s.serverBytes) {
            // Absent counts as read (empty).
            s.read += key
            return
        }
        val r = runCatching { client.appDataGet(c.base, c.apiKey, key.wire) }.getOrNull()
        if (r == null || r.status >= 500) {
            Log.w(TAG, "[DSYNC] get ${key.suffix} status=${r?.status ?: "network"}")
            return
        }
        if (r.status == 404) {
            s.read += key
            return
        }
        if (!r.ok) {
            Log.w(TAG, "[DSYNC] get ${key.suffix} status=${r.status} code=${r.code}")
            return
        }
        val bytes = r.body?.toByteArray(Charsets.UTF_8)?.size ?: 0
        val raw = runCatching { json.parseToJsonElement(r.body.orEmpty()) as? JsonObject }.getOrNull()
        if (raw == null) {
            Log.w(TAG, "[DSYNC] malformed ${key.wire}")
            return
        }
        when (val d = DsyncEnvelopeCodec.decode(raw, key.schemaVersion)) {
            DsyncDecode.Malformed -> Log.w(TAG, "[DSYNC] malformed ${key.wire}")
            is DsyncDecode.TooNew -> {
                s.blocked += key
                Log.w(TAG, "[DSYNC] skip ${key.wire} v${d.version} > v${key.schemaVersion}")
            }
            is DsyncDecode.Ok -> {
                val env = d.envelope
                val localAt = state.getLong(k("at", s, key), 0L)
                val localDev = state.getString(k("dev", s, key), "").orEmpty()
                val wins = DsyncEnvelopeCodec.remoteWins(localAt, localDev, env.updatedAt, env.deviceId)
                // profiles merges per row on every pull; the rows carry their own updatedAt.
                val decision = if (key == DsyncKey.Profiles) DsyncGate.DirtyPull.Merge
                else DsyncGate.onNewerPull(key, isDirty(s, key), dirtyAt(s, key), env.updatedAt)
                if (key == DsyncKey.Profiles || (wins && decision == DsyncGate.DirtyPull.Merge)) {
                    val merged = runCatching { mergedEnvelope(s, key, env) }
                        .onFailure { Log.w(TAG, "[DSYNC] merge ${key.suffix} failed: ${it.javaClass.simpleName}") }
                        .getOrNull() ?: return
                    val applied = runCatching { apply(s, key, merged) }.getOrDefault(ApplyResult.Failed)
                    if (applied != ApplyResult.Applied) {
                        if (applied == ApplyResult.Malformed) Log.w(TAG, "[DSYNC] malformed ${key.wire}")
                        return
                    }
                    s.suppressUntil[key] = System.currentTimeMillis() + SUPPRESS_MS
                    saveAfter(s, key, raw, env.updatedAt, env.deviceId, env.data)
                    if (key != DsyncKey.Profiles) {
                        setDirty(s, key, true)
                        Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=$bytes merged with local change")
                    } else {
                        Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=$bytes merged per row")
                    }
                    // The merged result (or a newer local row) goes back up.
                    s.read += key
                    return
                }
                if (wins && decision == DsyncGate.DirtyPull.KeepLocal) {
                    state.edit().putString(k("raw", s, key), raw.toString()).apply()
                    Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=$bytes kept local (local change newer)")
                    s.read += key
                    return
                }
                if (wins) {
                    if (isDirty(s, key)) setDirty(s, key, false)
                    val applied = runCatching { apply(s, key, env) }
                        .onFailure { Log.w(TAG, "[DSYNC] apply ${key.suffix} failed: ${it.javaClass.simpleName}") }
                        .getOrDefault(ApplyResult.Failed)
                    when (applied) {
                        ApplyResult.Applied -> {
                            s.suppressUntil[key] = System.currentTimeMillis() + SUPPRESS_MS
                            saveAfter(s, key, raw, env.updatedAt, env.deviceId, env.data)
                            Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=$bytes applied")
                        }
                        ApplyResult.Malformed -> {
                            Log.w(TAG, "[DSYNC] malformed ${key.wire}")
                            return
                        }
                        // The channel list is not loaded yet: do not count as read.
                        ApplyResult.NotReady -> return
                        ApplyResult.Failed -> return
                    }
                } else {
                    // Keep the newest raw for round-trip and the progress/recents row merge.
                    state.edit().putString(k("raw", s, key), raw.toString()).apply()
                    Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=$bytes not newer")
                }
                s.read += key
            }
        }
    }

    // ---- write (section 7.3 to 7.8) ----

    private fun onLocalChange(s: Session, key: DsyncKey) {
        if (session !== s) return
        // Flows re-key on a profile switch: that emission is the NEW profile's data.
        if (ProfileScope.token.value != s.token) return
        if ((s.suppressUntil[key] ?: 0L) > System.currentTimeMillis()) return
        s.tooLarge -= key
        setDirty(s, key, true)
        schedule(s, key, DEBOUNCE_MS)
    }

    private fun schedule(s: Session, key: DsyncKey, delayMs: Long) {
        synchronized(s.pending) {
            s.pending.remove(key)?.cancel()
            s.pending[key] = scope.launch {
                delay(delayMs)
                synchronized(s.pending) { s.pending.remove(key) }
                write(s, key)
            }
        }
    }

    private suspend fun flush(s: Session) {
        val keys = synchronized(s.pending) {
            val ks = s.pending.keys.toList()
            ks.forEach { s.pending.remove(it)?.cancel() }
            ks
        }
        keys.forEach { write(s, it) }
    }

    private suspend fun write(s: Session, key: DsyncKey) {
        s.mutex.withLock { writeLocked(s, key, retry413 = true) }
    }

    private suspend fun writeLocked(s: Session, key: DsyncKey, retry413: Boolean) {
        if (session !== s || s.paused) return
        if (!DsyncGate.mayWrite(s.token, ProfileScope.token.value, s.firstPullDone, key in s.read)) {
            // Section 7.2 and the switch rule: the dirty flag stays set for this profile's next session.
            if (ProfileScope.token.value != s.token) Log.i(TAG, "[DSYNC] drop ${key.suffix}: profile changed before send")
            return
        }
        if (key in s.blocked || !enabled(key, s)) return
        val c = conn(s) ?: return
        if (key in DsyncGate.ROW_MERGE_KEYS) {
            // Every PUT of a row key reads the server copy first and merges rows,
            // so a resend after an offline spell can never erase another device's rows.
            if (key in s.resend) Log.i(TAG, "[DSYNC] retry ${key.suffix} after offline")
            if (!refreshBeforePut(s, c, key)) {
                s.resend += key
                val n = (s.retries[key] ?: 0)
                if (n < BACKOFF_MS.size) {
                    s.retries[key] = n + 1
                    schedule(s, key, BACKOFF_MS[n])
                }
                return
            }
        }
        val raw = rawOf(s, key)
        val base = raw?.get("data") as? JsonObject
        val now = System.currentTimeMillis() / 1000
        val data = runCatching { build(s, key, base, now) }
            .onFailure { Log.w(TAG, "[DSYNC] build ${key.suffix} failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return
        if (session !== s || ProfileScope.token.value != s.token) return
        if (data.toString() == lastDataOf(s, key)) {
            setDirty(s, key, false)
            return
        }
        if (s.serverFull && key.wire !in s.serverBytes) {
            refreshMessage(s)
            return
        }
        fun wrap(k: DsyncKey, d: JsonObject) =
            DsyncEnvelopeCodec.encode(k, if (k == key) raw else rawOf(s, k), deviceId, BuildConfig.VERSION_NAME, now, d)

        var fitted = DsyncTrim.fitKey(key, data, now, force = !retry413) { wrap(key, it) }
        if (fitted == null) {
            s.tooLarge += key
            refreshMessage(s)
            Log.w(TAG, "[DSYNC] ${key.suffix} over 32 KiB after trim; stopped until the next change")
            return
        }
        // 128 KiB total: other keys at their server sizes, this device's keys re-trimmed in order.
        val others = s.serverBytes.filterKeys { it != key.wire && DsyncKey.fromWire(it) == null }.values.sum()
        val ours = LinkedHashMap<DsyncKey, JsonObject>()
        DsyncKey.entries.forEach { k2 ->
            if (k2 == key) ours[k2] = fitted!!
            else if (k2.wire in s.serverBytes) (rawOf(s, k2)?.get("data") as? JsonObject)?.let { ours[k2] = it }
        }
        val total = DsyncTrim.fitTotal(ours, others, now) { k2, d -> wrap(k2, d) }
        if (total == null) {
            s.tooLarge += key
            refreshMessage(s)
            Log.w(TAG, "[DSYNC] user total over 128 KiB after trim")
            return
        }
        fitted = total[key] ?: fitted
        // Trim the other keys first when the total step touched them.
        total.forEach { (k2, d) ->
            if (k2 != key && d.toString() != (rawOf(s, k2)?.get("data") as? JsonObject)?.toString()) {
                put(s, c, k2, wrap(k2, d), d)
            }
        }
        val env = wrap(key, fitted!!)
        when (val st = put(s, c, key, env, fitted)) {
            PutStatus.Ok -> {
                s.resend -= key
                setDirty(s, key, false)
                s.retries.remove(key)
                s.tooLarge -= key
            }
            PutStatus.TooLarge -> {
                if (retry413) {
                    writeLocked(s, key, retry413 = false)
                } else {
                    s.tooLarge += key
                    Log.w(TAG, "[DSYNC] ${key.suffix} still too large; stopped until the next change")
                }
            }
            PutStatus.Full -> s.serverFull = true
            PutStatus.Auth -> s.paused = true
            PutStatus.Retry -> {
                s.resend += key
                val n = (s.retries[key] ?: 0)
                if (n < BACKOFF_MS.size) {
                    s.retries[key] = n + 1
                    schedule(s, key, BACKOFF_MS[n])
                }
            }
            PutStatus.Rejected -> Unit
            null -> Unit
        }
        refreshMessage(s)
    }

    private enum class PutStatus { Ok, TooLarge, Full, Auth, Retry, Rejected }

    /**
     * GET [key]; when the server copy changed since this device last read or
     * wrote it, merge its rows into local state first. False = the server
     * could not be reached (keep the change, retry later).
     */
    private suspend fun refreshBeforePut(s: Session, c: Conn, key: DsyncKey): Boolean {
        val r = runCatching { client.appDataGet(c.base, c.apiKey, key.wire) }.getOrNull()
        if (r == null || r.status >= 500) {
            Log.w(TAG, "[DSYNC] pre-put get ${key.suffix} status=${r?.status ?: "network"}")
            return false
        }
        if (r.status == 404) return true
        if (!r.ok) return r.status != 401 && r.status != 403
        val raw = runCatching { json.parseToJsonElement(r.body.orEmpty()) as? JsonObject }.getOrNull() ?: return true
        val env = (DsyncEnvelopeCodec.decode(raw, key.schemaVersion) as? DsyncDecode.Ok)?.envelope ?: return true
        val at = state.getLong(k("at", s, key), 0L)
        val dev = state.getString(k("dev", s, key), "").orEmpty()
        if (env.updatedAt == at && env.deviceId == dev && (rawOf(s, key)?.get("data") as? JsonObject) == env.data) return true
        if (ProfileScope.token.value != s.token) return false
        val merged = runCatching { mergedEnvelope(s, key, env) }.getOrNull() ?: return false
        val applied = runCatching { apply(s, key, merged) }.getOrDefault(ApplyResult.Failed)
        if (applied != ApplyResult.Applied) return applied == ApplyResult.Malformed
        s.suppressUntil[key] = System.currentTimeMillis() + SUPPRESS_MS
        saveAfter(s, key, raw, env.updatedAt, env.deviceId, env.data)
        Log.i(TAG, "[DSYNC] pull ${key.suffix} bytes=${r.body?.length ?: 0} merged with local change (before put)")
        return true
    }

    private suspend fun put(s: Session, c: Conn, key: DsyncKey, env: JsonObject, data: JsonObject): PutStatus? {
        val body = env.toString()
        val bytes = body.toByteArray(Charsets.UTF_8).size
        var r = runCatching { client.appDataPut(c.base, c.apiKey, key.wire, body) }.getOrNull()
        if (r != null && (r.status == 401 || r.status == 403)) {
            // Section 7.7: take the current credential (a re-login stores a new
            // key on the profile) and try once more before pausing.
            val again = freshConn(s)?.also { s.bound = it }
            if (again != null && again.apiKey != c.apiKey) {
                r = runCatching { client.appDataPut(again.base, again.apiKey, key.wire, body) }.getOrNull()
            }
        }
        Log.i(TAG, "[DSYNC] put ${key.suffix} bytes=$bytes status=${r?.status ?: "network"}${r?.code?.let { " code=$it" } ?: ""}")
        if (r == null || r.status >= 500) return PutStatus.Retry
        return when {
            r.ok -> {
                val at = System.currentTimeMillis() / 1000
                saveAfter(s, key, env, at, deviceId, data)
                state.edit().putLong(k("at", s, key), (env["updatedAt"].asStr()?.let(DsyncEnvelopeCodec::parseIso)) ?: at).apply()
                s.serverBytes[key.wire] = bytes.toLong()
                stampLast(s)
                PutStatus.Ok
            }
            r.status == 413 -> PutStatus.TooLarge
            r.status == 409 -> PutStatus.Full
            r.status == 401 || r.status == 403 -> PutStatus.Auth
            else -> PutStatus.Rejected
        }
    }

    // ---- local sources (change detection) ----

    private fun sourceFor(key: DsyncKey): Flow<Any?> = when (key) {
        DsyncKey.Favorites -> favoriteDao.observeAll().map { rows -> rows.map { it.channelId to it.displayOrder } }
        DsyncKey.Recents -> prefs.recentChannelIds.map { it.also { ids -> stampRecents(ids) } }
        DsyncKey.Progress -> watchProgressDao.observeRecent(1000).map { rows -> rows.map { listOf(it.videoId, it.positionMs, it.updatedAt, it.isFinished) } }
        DsyncKey.Reminders -> reminderDao.observeAll().map { rows -> rows.map { it.reminderKey } }
        DsyncKey.Watchlist -> watchlistStore.observe(null).map { rows -> rows.map { it.key to it.playlistId } }
        DsyncKey.HiddenTitles -> hiddenTitlesStore.observe(null)
        DsyncKey.HiddenGroups -> combine(prefs.hiddenGroups, prefs.hiddenMovieGroups, prefs.hiddenSeriesGroups) { a, b, c -> Triple(a, b, c) }
        DsyncKey.GroupOrder -> combine(prefs.groupOrder, prefs.groupSortMode) { a, b -> a to b }
        DsyncKey.Settings -> prefs.dsyncSettings
        DsyncKey.RemoteMap -> prefs.remoteControlMap
        DsyncKey.RadioDefault -> prefs.radioModeByDefault
        DsyncKey.Collections -> prefs.channelCollections
        DsyncKey.Profiles -> combine(profileStore.profiles, pinStore.changes) { p, v -> p.map { listOf(it.username, it.displayName) } to v }
    }

    // ---- channel id mapping (schema 4: Dispatcharr channel `id` int) ----

    private data class ChannelMap(
        val toInt: Map<String, Int>,
        val toLocal: Map<Int, String>,
        val nameOf: Map<Int, String>,
        val intByName: Map<String, Int>,
    )

    private suspend fun channelMap(s: Session): ChannelMap {
        val rows = channelSnapshotDao.forPlaylist(s.playlistId)
        val toInt = HashMap<String, Int>()
        val toLocal = HashMap<Int, String>()
        val nameOf = HashMap<Int, String>()
        val byName = HashMap<String, Int>()
        rows.forEach { r ->
            val id = r.dispatcharrChannelId ?: return@forEach
            toInt[r.channelId] = id
            toLocal.putIfAbsent(id, r.channelId)
            nameOf.putIfAbsent(id, r.name)
            byName.putIfAbsent(r.name, id)
        }
        return ChannelMap(toInt, toLocal, nameOf, byName)
    }

    private fun stampRecents(ids: List<String>) {
        val s = session ?: return
        val now = System.currentTimeMillis() / 1000
        val stamps = recentStamps(s).toMutableMap()
        val head = ids.firstOrNull() ?: return
        // The head moved or is new: it was just watched.
        val prevHead = state.getString(k("rhead", s), null)
        if (prevHead != head || head !in stamps) stamps[head] = now
        ids.forEachIndexed { i, id -> stamps.putIfAbsent(id, now - i) }
        val keep = stamps.filterKeys { it in ids.toSet() }
        state.edit()
            .putString(k("rstamp", s), JsonObject(keep.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
            .putString(k("rhead", s), head)
            .apply()
    }

    private fun recentStamps(s: Session): Map<String, Long> =
        runCatching {
            (json.parseToJsonElement(state.getString(k("rstamp", s), "{}")!!) as JsonObject)
                .mapNotNull { (id, v) -> v.asLong()?.let { id to it } }.toMap()
        }.getOrDefault(emptyMap())

    private suspend fun scopeHost(s: Session): String? =
        playlistDao.byId(s.playlistId)?.let { WatchlistStore.scopeFor(it) }

    // ---- build (local -> data), section 4 and 6.2 ----

    private suspend fun build(s: Session, key: DsyncKey, base: JsonObject?, now: Long): JsonObject? = when (key) {
        DsyncKey.Favorites -> {
            val m = channelMap(s)
            if (m.toInt.isEmpty()) null else {
                val local = favoriteDao.allOnce().sortedBy { it.displayOrder }.mapNotNull { m.toInt[it.channelId] }
                // Remote ids this device cannot map (another channel set) are kept.
                val keep = base?.let(DsyncPayloads::decodeFavorites).orEmpty().filter { it !in m.toLocal }
                DsyncPayloads.encodeFavorites(base, (local + keep).distinct())
            }
        }
        DsyncKey.Recents -> {
            val m = channelMap(s)
            if (m.toInt.isEmpty()) null else {
                val stamps = recentStamps(s)
                val ids = prefs.recentChannelIds.first()
                val local = ids.mapIndexedNotNull { i, id -> m.toInt[id]?.let { RecentRow(it, stamps[id] ?: (now - i)) } }
                val remote = base?.let(DsyncPayloads::decodeRecents).orEmpty()
                DsyncPayloads.encodeRecents(base, DsyncMerge.recents(local, remote))
            }
        }
        DsyncKey.Progress -> {
            val local = watchProgressDao.allOnce()
                .filter { (it.playlistId == null || it.playlistId == s.playlistId) && !it.videoId.contains("://") }
                .filter { it.vodType == "movie" || it.vodType == "episode" }
                .map { p ->
                    val ep = p.vodType == "episode"
                    ProgressRow(
                        vodId = p.videoId, type = if (ep) "e" else "m",
                        position = p.positionMs / 1000, duration = p.durationMs / 1000,
                        updatedAt = p.updatedAt / 1000, finished = p.isFinished,
                        seriesId = if (ep) p.seriesId else null,
                        season = if (ep) p.seasonNumber else 0, episode = if (ep) p.episodeNumber else 0,
                        title = p.title,
                    )
                }
            val remote = base?.let(DsyncPayloads::decodeProgress).orEmpty()
            DsyncPayloads.encodeProgress(base, DsyncMerge.progress(local, remote), now)
        }
        DsyncKey.Reminders -> {
            val m = channelMap(s)
            if (m.toInt.isEmpty()) null else {
                val local = reminderDao.allOnce()
                    .filter { it.playlistId == null || it.playlistId == s.playlistId }
                    .mapNotNull { r ->
                        val cid = m.intByName[r.channelName] ?: return@mapNotNull null
                        ReminderRow(cid, r.startMillis / 1000, r.endMillis / 1000, r.programTitle, r.channelName)
                    }
                val remote = base?.let(DsyncPayloads::decodeReminders).orEmpty()
                val keep = remote.filter { it.channelId !in m.toLocal }
                val rows = DsyncPayloads.carryTails(local, remote, { it.channelId to it.start }, { it.tail }) { r, t -> r.copy(tail = t) }
                DsyncPayloads.encodeReminders(base, rows + keep, now)
            }
        }
        DsyncKey.Watchlist -> {
            val host = scopeHost(s)
            val local = watchlistStore.allOnce().filter { it.isLive && (it.playlistId == host || it.playlistId == null) }
                .mapNotNull { e ->
                    val t = when { e.key.startsWith("m:") -> "m"; e.key.startsWith("s:") -> "s"; else -> null } ?: return@mapNotNull null
                    VodMarkRow(e.key.substring(2), t, e.addedAt / 1000)
                }
            val remote = base?.let(DsyncPayloads::decodeVodMarks).orEmpty()
            val rows = DsyncPayloads.carryTails(local, remote, { it.type to it.vodId }, { it.tail }) { r, t -> r.copy(tail = t) }
            DsyncPayloads.encodeVodMarks(base, rows, DsyncLimits.WATCHLIST)
        }
        DsyncKey.HiddenTitles -> {
            val host = scopeHost(s)
            val local = hiddenTitlesStore.allOnce().filter { it.isLive && (it.playlistId == host || it.playlistId == null) }
                .mapNotNull { e ->
                    val t = when { e.key.startsWith("m:") -> "m"; e.key.startsWith("s:") -> "s"; else -> null } ?: return@mapNotNull null
                    VodMarkRow(e.key.substring(2), t, e.hiddenAt / 1000)
                }
            val remote = base?.let(DsyncPayloads::decodeVodMarks).orEmpty()
            val rows = DsyncPayloads.carryTails(local, remote, { it.type to it.vodId }, { it.tail }) { r, t -> r.copy(tail = t) }
            DsyncPayloads.encodeVodMarks(base, rows, DsyncLimits.HIDDEN_TITLES)
        }
        DsyncKey.HiddenGroups -> DsyncPayloads.encodeHiddenGroups(
            base,
            DsyncPayloads.HiddenGroups(
                prefs.hiddenGroups.first().sorted(),
                prefs.hiddenMovieGroups.first().sorted(),
                prefs.hiddenSeriesGroups.first().sorted(),
            ),
        )
        DsyncKey.GroupOrder -> {
            val mode = prefs.groupSortMode.first().lowercase().takeIf { it in DsyncPayloads.GROUP_MODES } ?: "default"
            DsyncPayloads.encodeGroupOrder(base, DsyncPayloads.GroupOrder(mode, prefs.groupOrder.first()))
        }
        DsyncKey.Settings -> DsyncPayloads.encodeSettings(base, SETTINGS_OWNED, prefs.dsyncSettingsOnce())
        DsyncKey.RemoteMap -> {
            // Phones and tablets round-trip the TV map untouched: never write it.
            if (!isTv) null else {
                val blob = prefs.remoteControlMap.first()
                val tv = if (blob.isBlank()) JsonObject(emptyMap())
                else runCatching { json.parseToJsonElement(blob) as? JsonObject }.getOrNull() ?: return null
                DsyncPayloads.encodeRemoteMap(base, tv)
            }
        }
        DsyncKey.RadioDefault -> DsyncPayloads.encodeRadioDefault(base, prefs.radioModeByDefault.first())
        DsyncKey.Collections -> {
            val m = channelMap(s)
            if (m.toInt.isEmpty()) null else {
                val remote = base?.let(DsyncPayloads::decodeCollections).orEmpty().associateBy { it.id }
                val rows = prefs.channelCollections.first().map { c ->
                    val r = remote[c.id]
                    val stale = r?.members.orEmpty().filter { it !in m.toLocal }
                    CollectionRow(
                        id = c.id, name = c.name,
                        placement = if (c.placement == ChannelCollection.PLACEMENT_BEGINNING) "b" else "e",
                        members = (c.memberIds.mapNotNull { m.toInt[it] } + stale).distinct(),
                        tail = r?.tail ?: Tail(),
                    )
                }
                DsyncPayloads.encodeCollections(base, rows)
            }
        }
        DsyncKey.Profiles -> buildProfiles(s, base)
    }

    private suspend fun adminUsers(s: Session): List<DispatcharrUserSummary> {
        s.users?.let { return it }
        val c = conn(s) ?: return emptyList()
        val list = runCatching { client.listUsers(c.base, c.apiKey) }.getOrDefault(emptyList())
        if (list.isNotEmpty()) s.users = list
        return list
    }

    private suspend fun buildProfiles(s: Session, base: JsonObject?): JsonObject? {
        if (!isAdmin(s)) return null
        val users = adminUsers(s)
        if (users.isEmpty()) return null
        val remote = base?.let(DsyncPayloads::decodeProfiles).orEmpty()
        val rows = profileStore.forPlaylist(s.playlistId).mapNotNull { p ->
            val user = users.firstOrNull { it.username.equals(p.username, ignoreCase = true) } ?: return@mapNotNull null
            val prev = remote.firstOrNull { it.userId == user.id }
            val rec = pinStore.syncRecord(p.id)
            val (type, hash, salt) = when {
                rec != null -> Triple(PinRules.wireType(rec.type), rec.hash, rec.salt.orEmpty())
                // A v1 PIN cannot sync until its next correct entry: keep what the server has.
                pinStore.isLegacy(p.id) && prev != null -> Triple(prev.pinType, prev.pinHash, prev.pinSalt)
                else -> Triple("none", "", "")
            }
            var at = state.getLong(rowKey(s.playlistId, p.username), 0L)
            if (at <= 0L) {
                at = prev?.updatedAt?.takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
                state.edit().putLong(rowKey(s.playlistId, p.username), at).apply()
            }
            ProfileRow(
                user.id, user.username, p.displayName, type, hash, salt,
                role = p.effectiveRole().wire,
                updatedAt = at,
                tail = prev?.tail ?: Tail(),
            )
        }
        // Rows for users this device has no profile for stay, unless this
        // device removed that profile (then the row leaves the list).
        val mine = rows.map { it.userId }.toSet()
        val kept = remote.filter { it.userId !in mine && state.getLong(removedKey(s.playlistId, it.username), 0L) <= it.updatedAt }
        return DsyncPayloads.encodeProfiles(base, rows + kept)
    }

    // ---- apply (data -> local), section 6.1 full replace ----

    private enum class ApplyResult { Applied, Malformed, NotReady, Failed }

    /**
     * A newer server value for a key with an unsent local change: the row
     * union of both sides (favorites, collections); progress and recents
     * already merge per row inside [apply]; profiles merge per row there too.
     */
    private suspend fun mergedEnvelope(s: Session, key: DsyncKey, env: DsyncEnvelope): DsyncEnvelope? = when (key) {
        DsyncKey.Favorites -> {
            val m = channelMap(s)
            if (m.toInt.isEmpty()) null else {
                val remote = DsyncPayloads.decodeFavorites(env.data)
                if (remote == null) null else {
                    val local = favoriteDao.allOnce().sortedBy { it.displayOrder }.mapNotNull { m.toInt[it.channelId] }
                    env.copy(data = DsyncPayloads.encodeFavorites(env.data, DsyncGate.favoritesUnion(local, remote)))
                }
            }
        }
        DsyncKey.Collections -> {
            val m = channelMap(s)
            val remote = DsyncPayloads.decodeCollections(env.data)
            if (m.toInt.isEmpty() || remote == null) null else {
                val local = prefs.channelCollections.first().map { c ->
                    CollectionRow(
                        id = c.id, name = c.name,
                        placement = if (c.placement == ChannelCollection.PLACEMENT_BEGINNING) "b" else "e",
                        members = c.memberIds.mapNotNull { m.toInt[it] },
                    )
                }
                env.copy(data = DsyncPayloads.encodeCollections(env.data, DsyncGate.collectionsUnion(local, remote)))
            }
        }
        DsyncKey.Reminders -> {
            val m = channelMap(s)
            val remote = DsyncPayloads.decodeReminders(env.data)
            if (m.toInt.isEmpty() || remote == null) null else {
                val local = reminderDao.allOnce()
                    .filter { it.playlistId == null || it.playlistId == s.playlistId }
                    .mapNotNull { r ->
                        val cid = m.intByName[r.channelName] ?: return@mapNotNull null
                        ReminderRow(cid, r.startMillis / 1000, r.endMillis / 1000, r.programTitle, r.channelName)
                    }
                val nowS = System.currentTimeMillis() / 1000
                env.copy(data = DsyncPayloads.encodeReminders(env.data, DsyncGate.remindersUnion(local, remote), nowS))
            }
        }
        else -> env
    }

    private suspend fun apply(s: Session, key: DsyncKey, env: DsyncEnvelope): ApplyResult {
        val d = env.data
        when (key) {
            DsyncKey.Favorites -> {
                val ids = DsyncPayloads.decodeFavorites(d) ?: return ApplyResult.Malformed
                val m = channelMap(s)
                if (m.toInt.isEmpty()) return ApplyResult.NotReady
                val wanted = ids.mapNotNull { m.toLocal[it] }
                val local = favoriteDao.allOnce()
                val now = System.currentTimeMillis()
                local.filter { it.channelId in m.toInt && it.channelId !in wanted }.forEach { favoriteDao.delete(it.channelId) }
                wanted.forEachIndexed { i, lid ->
                    val cur = local.firstOrNull { it.channelId == lid }
                    val name = m.nameOf[m.toInt[lid]] ?: cur?.channelName.orEmpty()
                    favoriteDao.upsert(
                        FavoriteChannelEntity(channelId = lid, channelName = name, displayOrder = i.toLong(), addedAt = cur?.addedAt ?: now),
                    )
                }
            }
            DsyncKey.Recents -> {
                val remote = DsyncPayloads.decodeRecents(d) ?: return ApplyResult.Malformed
                val m = channelMap(s)
                if (m.toInt.isEmpty()) return ApplyResult.NotReady
                val stamps = recentStamps(s)
                val ids = prefs.recentChannelIds.first()
                val nowS = System.currentTimeMillis() / 1000
                val local = ids.mapIndexedNotNull { i, id -> m.toInt[id]?.let { RecentRow(it, stamps[id] ?: (nowS - i)) } }
                val merged = DsyncMerge.recents(local, remote).mapNotNull { r -> m.toLocal[r.channelId]?.let { it to r.watchedAt } }
                val others = ids.filter { it !in m.toInt }
                prefs.setRecentChannels(merged.map { it.first } + others)
                val st = stamps.toMutableMap().apply { merged.forEach { (id, at) -> put(id, at) } }
                state.edit()
                    .putString(k("rstamp", s), JsonObject(st.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
                    .putString(k("rhead", s), merged.firstOrNull()?.first ?: others.firstOrNull())
                    .apply()
            }
            DsyncKey.Progress -> {
                val rows = DsyncPayloads.decodeProgress(d) ?: return ApplyResult.Malformed
                rows.forEach { r ->
                    if (r.vodId.contains("://")) return@forEach
                    val local = watchProgressDao.getOnce(r.vodId)
                    if (local != null && r.updatedAt * 1000 <= local.updatedAt) return@forEach
                    val ep = r.type == "e"
                    val baseRow = local ?: WatchProgressEntity(
                        videoId = r.vodId, title = r.title, posterUrl = null,
                        positionMs = 0, durationMs = 0, updatedAt = 0, playlistId = s.playlistId,
                    )
                    watchProgressDao.upsert(
                        baseRow.copy(
                            title = if (local != null && local.title.length > r.title.length && local.title.startsWith(r.title)) local.title else r.title,
                            positionMs = r.position * 1000,
                            durationMs = if (r.duration > 0) r.duration * 1000 else baseRow.durationMs,
                            updatedAt = r.updatedAt * 1000,
                            vodType = if (ep) "episode" else "movie",
                            seriesId = r.seriesId ?: baseRow.seriesId,
                            seasonNumber = if (r.season > 0) r.season else baseRow.seasonNumber,
                            episodeNumber = if (r.episode > 0) r.episode else baseRow.episodeNumber,
                            isFinished = r.finished,
                        ),
                    )
                }
            }
            DsyncKey.Reminders -> {
                val rows = DsyncPayloads.decodeReminders(d) ?: return ApplyResult.Malformed
                val m = channelMap(s)
                if (m.toInt.isEmpty()) return ApplyResult.NotReady
                applyReminders(s, rows, m)
            }
            DsyncKey.Watchlist -> {
                val rows = DsyncPayloads.decodeVodMarks(d) ?: return ApplyResult.Malformed
                val host = scopeHost(s)
                watchlistStore.replaceScope(
                    host,
                    rows.filter { it.type == "m" || it.type == "s" }.map { r ->
                        WatchlistStore.Entry(
                            key = "${r.type}:${r.vodId}", title = "", isMovie = r.type == "m",
                            playlistId = host, addedAt = r.at * 1000,
                        )
                    },
                )
            }
            DsyncKey.HiddenTitles -> {
                val rows = DsyncPayloads.decodeVodMarks(d) ?: return ApplyResult.Malformed
                hiddenTitlesStore.replaceScope(
                    scopeHost(s),
                    rows.filter { it.type == "m" || it.type == "s" }.associate { "${it.type}:${it.vodId}" to it.at * 1000 },
                )
            }
            DsyncKey.HiddenGroups -> {
                val g = DsyncPayloads.decodeHiddenGroups(d) ?: return ApplyResult.Malformed
                prefs.setHiddenGroups(g.live.toSet())
                prefs.setHiddenMovieGroups(g.movies.toSet())
                prefs.setHiddenSeriesGroups(g.series.toSet())
            }
            DsyncKey.GroupOrder -> {
                val g = DsyncPayloads.decodeGroupOrder(d) ?: return ApplyResult.Malformed
                prefs.setGroupSortMode(
                    when (g.mode) { "alphabetical" -> "Alphabetical"; "manual" -> "Manual"; else -> "Default" },
                )
                prefs.setGroupOrder(g.order)
            }
            DsyncKey.Settings -> {
                val v = DsyncPayloads.decodeSettings(d) ?: return ApplyResult.Malformed
                // Only owned names are validated; foreign names round-trip untouched.
                val r = DsyncSettingsValidator.filter(v.filterKeys { it in SETTINGS_OWNED })
                r.ignored.forEach { Log.i(TAG, "[DSYNC] settings: ignored $it") }
                prefs.applyDsyncSettings(r.accepted)
            }
            DsyncKey.RemoteMap -> {
                val tv = DsyncPayloads.decodeRemoteMap(d) ?: return ApplyResult.Malformed
                if (isTv) prefs.setRemoteControlMap(if (tv.isEmpty()) "" else tv.toString())
            }
            DsyncKey.RadioDefault -> {
                val on = DsyncPayloads.decodeRadioDefault(d) ?: return ApplyResult.Malformed
                prefs.setRadioModeByDefault(on)
            }
            DsyncKey.Collections -> {
                val rows = DsyncPayloads.decodeCollections(d) ?: return ApplyResult.Malformed
                val m = channelMap(s)
                if (m.toInt.isEmpty()) return ApplyResult.NotReady
                prefs.setChannelCollections(
                    rows.map { r ->
                        ChannelCollection(
                            id = r.id, name = r.name,
                            memberIds = r.members.mapNotNull { m.toLocal[it] },
                            placement = if (r.placement == "b") ChannelCollection.PLACEMENT_BEGINNING else ChannelCollection.PLACEMENT_END,
                        )
                    },
                )
            }
            DsyncKey.Profiles -> {
                val rows = DsyncPayloads.decodeProfiles(d) ?: return ApplyResult.Malformed
                if (!isAdmin(s)) return ApplyResult.Applied
                profileManager.get().mergeSyncedProfiles(
                    s.playlistId, rows, adminUsers(s),
                    localUpdatedAt = { u -> state.getLong(rowKey(s.playlistId, u), 0L) },
                    adopted = { u, at -> if (at > 0) state.edit().putLong(rowKey(s.playlistId, u), at).apply() },
                )
            }
        }
        return ApplyResult.Applied
    }

    private suspend fun applyReminders(s: Session, rows: List<ReminderRow>, m: ChannelMap) {
        val now = System.currentTimeMillis()
        val wanted = rows.filter { it.end * 1000 >= now }.associateBy { it.channelId to it.start }
        val local = reminderDao.allOnce().filter { it.playlistId == null || it.playlistId == s.playlistId }
        val have = HashSet<Pair<Int, Long>>()
        local.forEach { r ->
            val cid = m.intByName[r.channelName] ?: return@forEach // unresolvable: never removed by a pull
            val id = cid to r.startMillis / 1000
            if (id in wanted) have += id
            else {
                cancelAlarm(r.alarmRequestCode)
                reminderDao.delete(r.reminderKey)
            }
        }
        wanted.forEach { (id, r) ->
            if (id in have) return@forEach
            val localId = m.toLocal[r.channelId] ?: return@forEach
            val name = m.nameOf[r.channelId] ?: r.channelName
            val startMs = r.start * 1000
            val key = reminderKey(name, r.title, startMs)
            reminderDao.upsert(
                ReminderEntity(
                    reminderKey = key, channelName = name, programTitle = r.title,
                    startMillis = startMs, endMillis = r.end * 1000,
                    alarmRequestCode = key.hashCode(), playlistId = s.playlistId,
                ),
            )
            scheduleAlarm(key, name, r.title, localId, startMs)
        }
    }

    /** Same PendingIntent identity and 5-minute lead as RemindersViewModel. */
    private fun scheduleAlarm(key: String, channelName: String, title: String, channelId: String, startMs: Long) {
        val mgr = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = (startMs - 5 * 60 * 1000L).coerceAtLeast(System.currentTimeMillis() + 1_000L)
        val intent = Intent(context, ReminderBroadcastReceiver::class.java).apply {
            putExtra(ReminderBroadcastReceiver.EXTRA_REMINDER_KEY, key)
            putExtra(ReminderBroadcastReceiver.EXTRA_TITLE, title)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_NAME, channelName)
            putExtra(ReminderBroadcastReceiver.EXTRA_CHANNEL_ID, channelId)
        }
        val pi = PendingIntent.getBroadcast(
            context, key.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        runCatching { mgr.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
    }

    private fun cancelAlarm(requestCode: Int) {
        val mgr = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, requestCode, Intent(context, ReminderBroadcastReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        mgr.cancel(pi)
    }

    companion object {
        private const val TAG = "AerioDsync"
        const val PROVIDER = "dispatcharr"
        private const val K_DEVICE = "deviceId"
        const val DEBOUNCE_MS = 5_000L
        const val FOREGROUND_PULL_MS = 5L * 60 * 1000
        const val PERIODIC_PULL_MS = 15L * 60 * 1000
        private const val SUPPRESS_MS = 3_000L
        val BACKOFF_MS = longArrayOf(30_000L, 120_000L, 600_000L)
        val RECONNECT_MS = longArrayOf(2_000L, 5_000L, 15_000L, 60_000L)

        /** Schema 4.9 names with an Android key. */
        val SETTINGS_OWNED: Set<String> = setOf(
            "selectedTheme", "appearanceMode", "trueBlack", "defaultTab", "timeFormat",
            "textScale", "subtextScale", "textContrast", "guideProgramInfoButton",
            "programInfoButtonGuide", "programInfoButtonList", "showEpgBadgesTv", "showRemoteHints",
            "guideGroupSelector", "guideSidebarLayout", "guideTuneInMini",
            "playerInfoCard.showOnChannelChange", "phoneGroupSelector", "liveTvEdgeSwipeOpensSidebar",
            "skipLoadingScreen", "appleTVChannelFlip",
        )
    }
}
