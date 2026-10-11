package com.aeriotv.android.core.security

import android.net.Uri
import android.util.Log
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import com.aeriotv.android.core.network.ActivePlaylistCredentials

/**
 * Coil interceptor that blocks image-loading requests whose source URL would
 * let a malicious playlist provider perform an SSRF-style probe or pull
 * untrusted content from a local scheme.
 *
 * Audit task #53. AerioTV renders logos / posters from URLs the playlist
 * source supplies (M3U `tvg-logo`, Dispatcharr channel logo, XMLTV
 * `<icon src=>`, VOD posters). All of those are attacker-influenced text
 * fields. Coil happily resolves `file://`, `content://`, `javascript:`,
 * `about:`, `data:text/html;...`, etc., none of which we ever want for an
 * image cell.
 *
 * Policy:
 *   - http / https → pass through.
 *   - data:image/... → pass through (used by some EPG providers for inline
 *     channel logos).
 *   - everything else (file, content, javascript, about, raw data:text,
 *     custom schemes, etc.) → block with an [ErrorResult].
 *
 * Non-URL request data (drawable resources, ByteBuffers, ImageBitmaps
 * supplied directly by the app's own code) is unaffected; the interceptor
 * only inspects requests whose data parses as a URI with a scheme.
 *
 * Composable Coil 3 image painters that loaded a blocked URL show the
 * caller's fallback / placeholder, matching the behavior of any other
 * fetch failure, no crash, no UI surprise.
 */
class SafeUrlInterceptor(
    private val credentials: ActivePlaylistCredentials,
) : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        val candidateString = when (data) {
            is String -> data
            is Uri -> data.toString()
            is android.net.Uri -> data.toString()
            else -> null
        }
        if (candidateString != null) {
            val parsed = runCatching { Uri.parse(candidateString) }.getOrNull()
            val scheme = parsed?.scheme?.lowercase()
            val blocked = when {
                scheme == null -> false // relative? let Coil handle / reject
                scheme == "http" || scheme == "https" -> false
                scheme == "data" -> {
                    // Allow only `data:image/...` -- everything else (text,
                    // html, application/...) has no business in an image cell.
                    !candidateString.startsWith("data:image", ignoreCase = true)
                }
                else -> true
            }
            // Scheme passed (http/https or data:image). For network schemes,
            // also vet the HOST: a hostile playlist provider can emit a poster
            // / logo URL that points at the user's LAN or localhost to probe
            // internal services via the image fetch (SSRF). Allow the user's
            // OWN server (even on a private IP) + the public TMDB CDN; reject
            // any OTHER loopback / link-local / RFC-1918 / IPv6-ULA host.
            val hostBlocked = !blocked &&
                (scheme == "http" || scheme == "https") &&
                isBlockedHost(parsed.host?.lowercase(), candidateString)
            if (blocked || hostBlocked) {
                val reason = if (hostBlocked) "host=${parsed.host}" else "scheme=$scheme"
                Log.w(TAG, "Blocked image fetch ($reason)")
                return ErrorResult(
                    image = null,
                    request = chain.request,
                    throwable = SecurityException("Image URL not allowed: $reason"),
                )
            }
        }
        return chain.proceed()
    }

    /** Returns true when [host] must be blocked: it is a loopback, link-local,
     *  RFC-1918, or IPv6-ULA address AND is neither the active server's own
     *  host nor a known public image CDN. Null/blank host on an http(s) URL is
     *  blocked (a network scheme with no host is malformed). */
    private fun isBlockedHost(host: String?, fullUrl: String): Boolean {
        if (host.isNullOrBlank()) return true
        // The user's own server is always trusted (LAN or public) -- it is the
        // trust boundary by definition. This is what lets a 192.168.x.x
        // Dispatcharr serve its own logos/posters without being rejected.
        if (credentials.isActiveServerHost(host)) return false
        // Public image CDNs (opt-in TMDB fallback art) are always allowed.
        if (host in ALLOWED_EXTERNAL_IMAGE_HOSTS) return false
        // Anything else that resolves to a non-routable / private range is the
        // SSRF surface: block it.
        return isLoopbackHost(host) || isLinkLocalHost(host) || isPrivateNetworkHost(host)
    }

    private fun isLoopbackHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host == "[::1]") return true
        if (host.startsWith("127.")) return parseIPv4Octets(host) != null
        return false
    }

    private fun isLinkLocalHost(host: String): Boolean {
        if (host.startsWith("169.254.")) return parseIPv4Octets(host) != null
        if (host.startsWith("fe80:") || host.startsWith("[fe80:")) return true
        return false
    }

    private fun isPrivateNetworkHost(host: String): Boolean {
        val octets = parseIPv4Octets(host)
        if (octets == null) {
            // IPv6 ULA fc00::/7 (fc.. / fd.. , bracketed or not)
            return host.startsWith("fc") || host.startsWith("fd") ||
                host.startsWith("[fc") || host.startsWith("[fd")
        }
        if (octets[0] == 10) return true                                  // 10.0.0.0/8
        if (octets[0] == 172 && octets[1] in 16..31) return true          // 172.16.0.0/12
        if (octets[0] == 192 && octets[1] == 168) return true             // 192.168.0.0/16
        return false
    }

    /** Parse a dotted-quad IPv4 host into 4 octets, or null when [host] is not
     *  a literal IPv4 address (a DNS name returns null and is treated as a
     *  public host -- the threat model is private-range probing, not DNS
     *  rebinding, matching iOS). */
    private fun parseIPv4Octets(host: String): IntArray? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val out = IntArray(4)
        for (i in 0 until 4) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n
        }
        return out
    }

    private companion object {
        const val TAG = "SafeUrlInterceptor"
        // Public, anonymous image CDNs the opt-in TMDB fallback uses. Never
        // rejected by the host gate so TMDB art renders even though it is not
        // the user's server. Mirrors iOS VODService.allowedExternalImageHosts.
        val ALLOWED_EXTERNAL_IMAGE_HOSTS = setOf(
            "image.tmdb.org",
            "www.themoviedb.org",
            "themoviedb.org",
        )
    }
}
