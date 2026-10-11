package com.aeriotv.android.core.network

import android.util.Log
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Per-playlist live JWT cache. Holds the access + refresh pair plus a
 * best-effort expiry timestamp parsed from the access token's `exp` claim
 * so callers can pre-emptively refresh before the server returns 401.
 * Process-scoped only, nothing is persisted to disk; the playlist row's
 * username/password are the only durable credential and re-login from them
 * is the recovery path when the cache is cold.
 *
 * Mirrors iOS [DispatcharrTokenStore](Aerio/Networking/DispatcharrDirectConnect.swift)
 * lines 96-267. Differences from iOS:
 *  - Keyed by playlist UUID string (not `UUID` value type) since Android's
 *    PlaylistEntity stores it as String.
 *  - ConcurrentHashMap instead of NSLock, same thread-safety property,
 *    less verbose, fine for our access pattern (low contention since
 *    Dispatcharr calls run inside coroutines on Dispatchers.IO).
 *  - JWT exp parsing uses java.util.Base64.getUrlDecoder + kotlinx
 *    serialization instead of Foundation Base64 + JSONSerialization.
 */
@Singleton
class DispatcharrTokenStore @Inject constructor() {

    private data class TokenPair(
        val access: String,
        val refresh: String,
        val accessExpiresAt: Instant,
    )

    private val pairs = ConcurrentHashMap<String, TokenPair>()

    /** Current access token for [playlistId], or null when never logged in. */
    fun accessToken(playlistId: String): String? = pairs[playlistId]?.access

    /** Current refresh token for [playlistId], or null when never logged in. */
    fun refreshToken(playlistId: String): String? = pairs[playlistId]?.refresh

    /**
     * True when the cached access token's `exp` claim is in the past or
     * within [slackSeconds] seconds of expiring. Defaults to 30 seconds of
     * slack so a request that lands right at the boundary doesn't race the
     * server's clock. Returns true when no token is cached, since the caller
     * should treat that as "needs login".
     */
    fun accessIsExpired(playlistId: String, slackSeconds: Long = 30): Boolean {
        val pair = pairs[playlistId] ?: return true
        return pair.accessExpiresAt.isBefore(Instant.now().plusSeconds(slackSeconds))
    }

    /** Cache a freshly-issued JWT pair after a successful login. */
    fun store(playlistId: String, access: String, refresh: String) {
        val exp = expiry(access) ?: Instant.now().plusSeconds(1500)
        pairs[playlistId] = TokenPair(access = access, refresh = refresh, accessExpiresAt = exp)
    }

    /**
     * Update only the access token after a refresh. The refresh token stays
     * the same, Dispatcharr does not rotate it on `/api/accounts/token/refresh/`,
     * only emits a new access.
     */
    fun storeRefreshedAccess(playlistId: String, access: String) {
        val existing = pairs[playlistId] ?: return
        val exp = expiry(access) ?: Instant.now().plusSeconds(1500)
        pairs[playlistId] = existing.copy(access = access, accessExpiresAt = exp)
    }

    /** Drop the cached pair for one playlist. Call on delete / explicit logout. */
    fun clear(playlistId: String) {
        pairs.remove(playlistId)
    }

    /** Drop every cached pair. Used by the iCloud-equivalent data-clear flow. */
    fun clearAll() {
        pairs.clear()
    }

    /**
     * Best-effort decode of a JWT's `exp` claim (Unix epoch seconds). Returns
     * null on any parse failure; callers fall back to a 25-minute heuristic
     * (5 min before the Dispatcharr 30-min default) so we still pre-emptively
     * refresh without trusting a malformed token.
     */
    private fun expiry(jwt: String): Instant? {
        val parts = jwt.split(".")
        if (parts.size < 2) return null
        val payload = parts[1]
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        return try {
            val bytes = Base64.getUrlDecoder().decode(padded)
            val obj = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
                ?: return null
            val exp = obj["exp"]?.jsonPrimitive?.longOrNull ?: return null
            Instant.ofEpochSecond(exp)
        } catch (t: Throwable) {
            Log.w(TAG, "JWT exp decode failed: ${t.message}")
            null
        }
    }

    private companion object {
        const val TAG = "DispatcharrTokenStore"
    }
}
