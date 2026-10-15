package com.aeriotv.android.core.playback

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-channel memory "this channel was audio-only" (GH AerioTV#90), keyed by
 * channel id. Device-local, Apple parity (AudioOnlyChannelMemory, 7 day TTL,
 * 400 entries). Written when a tune of the channel ends audio-only on the
 * unknown or remembered rule, cleared the moment the channel shows video.
 * Never written by a Radio Mode cut (no evidence about the channel).
 */
object AudioOnlyChannelMemory {
    private const val TAG = "AerioExoPlayer"
    private const val PREFS = "aerio_audio_only_channels"
    const val TTL_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val MAX_ENTRIES = 400

    private val map = ConcurrentHashMap<String, Long>()
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        p.all.forEach { (k, v) -> (v as? Long)?.takeIf { now - it in 0 until TTL_MS }?.let { map[k] = it } }
        prefs = p
    }

    fun isRemembered(key: String?): Boolean {
        val stamp = key?.let { map[it] } ?: return false
        val age = System.currentTimeMillis() - stamp
        return age in 0 until TTL_MS
    }

    fun remember(key: String?) {
        if (key.isNullOrBlank()) return
        val now = System.currentTimeMillis()
        val edit = prefs?.edit()
        map.entries.filter { now - it.value >= TTL_MS }.forEach { map.remove(it.key); edit?.remove(it.key) }
        if (map.size >= MAX_ENTRIES) { map.clear(); edit?.clear() }
        val known = map.put(key, now) != null
        edit?.putLong(key, now)?.apply()
        if (!known) Log.i(TAG, "[AUDIO-ONLY] channel remembered ch=$key (next tune starts after 2 s of audio)")
    }

    fun forget(key: String?, reason: String) {
        if (key.isNullOrBlank() || map.remove(key) == null) return
        prefs?.edit()?.remove(key)?.apply()
        Log.i(TAG, "[AUDIO-ONLY] channel memory cleared ch=$key: $reason")
    }
}
