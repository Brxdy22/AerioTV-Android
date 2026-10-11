package com.aeriotv.android.core.network

/**
 * Typed exception family for Dispatcharr Direct Connect, mirroring iOS
 * `DispatcharrDirectConnectError` (DispatcharrDirectConnect.swift line 271-305)
 * so callers can branch on cause instead of regex-matching exception messages:
 *
 *  - [InvalidCredentials]: 401/403 from POST /api/accounts/token/. The user
 *    typed the wrong username or password; the message carries the
 *    Dashboard-vs-XC distinction so UX shows the actionable copy.
 *  - [Unauthorized]: 401/403 from an api_key-authenticated call (channel
 *    list, EPG grid, DVR, VOD, etc.). Triggers
 *    [DispatcharrAuthBroker.silentRebootstrapApiKey], the admin probably
 *    rotated the user's api_key, so we re-login from stored creds, extract
 *    the fresh key, persist it, and replay.
 *  - [RefreshExpired]: 401/403 from POST /api/accounts/token/refresh/.
 *    The refresh token is itself stale (24 h+ idle); the warmup path
 *    falls back to a fresh login.
 *  - [UnexpectedResponse]: 200 OK but the JSON didn't decode. Catches
 *    the SPA-shell case where the URL points at a non-Dispatcharr host
 *    that 200s with an HTML login page.
 *  - [Transport]: any other network or HTTP failure.
 *
 * All four extend [Exception] so the existing try/catch chain at the
 * PlaylistRepository.loadAndPersist boundary continues to surface
 * `t.message` as the user-facing error.
 */
sealed class DispatcharrError(message: String) : Exception(message) {
    class InvalidCredentials(message: String) : DispatcharrError(message)
    class Unauthorized(message: String) : DispatcharrError(message)
    class RefreshExpired(message: String) : DispatcharrError(message)
    class UnexpectedResponse(message: String) : DispatcharrError(message)
    class Transport(message: String) : DispatcharrError(message)

    /**
     * A 403 from an endpoint whose permission class is a CAPABILITY check
     * rather than an authentication check (the DVR writes, which are
     * IsAdminOrDVRManager).
     *
     * Deliberately NOT [Unauthorized]: the auth broker rebootstraps the
     * api_key on Unauthorized and silently replays, which turned a real "your
     * account may not manage recordings" into a confusing retry loop. This one
     * is not retried; the caller re-probes /api/accounts/users/me/, re-derives
     * the capability, and either names the missing permission or -- when the
     * account's permissions say it SHOULD be allowed -- points at a network
     * restriction (per-user allowed_networks) instead.
     */
    class Forbidden(
        message: String,
        val capabilityHint: String? = null,
    ) : DispatcharrError(message)
}
