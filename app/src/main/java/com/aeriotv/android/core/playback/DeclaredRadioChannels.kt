package com.aeriotv.android.core.playback

import java.util.concurrent.ConcurrentHashMap

/**
 * Channel ids the ACTIVE playlist's server declares radio
 * ([com.aeriotv.android.core.data.M3UChannel.isRadio]: Dispatcharr
 * effective_is_radio, M3U radio="true", XC stream_type radio_streams).
 * Replaced whole whenever the active playlist's channel list changes, so it
 * is playlist-scoped. Read by the player holder when it builds the extractor
 * (AudioOnlyStartPolicy.DECLARED). [videoSeen] holds ids whose declared tune
 * carried video anyway (the safety net restarted it as video); those fall
 * back to the normal rule until the list is replaced.
 */
object DeclaredRadioChannels {
    @Volatile private var ids: Set<String> = emptySet()
    private val videoSeen = ConcurrentHashMap.newKeySet<String>()

    fun replace(newIds: Set<String>) {
        if (newIds == ids) return
        ids = newIds
        videoSeen.retainAll(newIds)
    }

    fun set(id: String, radio: Boolean) {
        ids = if (radio) ids + id else ids - id
        if (!radio) videoSeen.remove(id)
    }

    /** Flagged by the server (regardless of the video-seen fallback). */
    fun isFlagged(id: String?): Boolean = id != null && id in ids

    /** Flagged and not yet proven to carry video on this list. */
    fun isDeclared(id: String?): Boolean = isFlagged(id) && id !in videoSeen

    fun markVideoSeen(id: String?) {
        if (id != null && id in ids) videoSeen.add(id)
    }
}
