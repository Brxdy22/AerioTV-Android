package com.aeriotv.android.core.profiles

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Who a per-profile channel snapshot was fetched as (phase 2b, 2026-10-17):
 * the Dispatcharr user id and the channel_profiles assignment in force at
 * fetch time. A fast switch paints the snapshot only while both still match,
 * so a snapshot from another user or from before an assignment change never
 * shows channels the profile can no longer see.
 */
data class SnapshotStamp(val userId: Int, val assignment: String) {
    fun encode(): String = "$userId|${SnapshotStaleness.normalize(assignment)}"

    companion object {
        fun decode(raw: String?): SnapshotStamp? {
            val p = raw?.split('|') ?: return null
            if (p.size != 2) return null
            return SnapshotStamp(p[0].toIntOrNull() ?: return null, p[1])
        }
    }
}

/** Pure staleness check, unit tested. */
object SnapshotStaleness {
    /** "3, 1,x,2" -> "1,2,3"; "" = All. */
    fun normalize(ids: String): String =
        ids.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct().sorted().joinToString(",")

    /** Null = fresh; otherwise the reason the snapshot must not be painted. */
    fun reason(stamp: SnapshotStamp?, currentUserId: Int, currentAssignment: String): String? = when {
        stamp == null -> "no stamp"
        currentUserId <= 0 -> "user id unknown"
        stamp.userId != currentUserId -> "user id ${stamp.userId} != $currentUserId"
        normalize(stamp.assignment) != normalize(currentAssignment) ->
            "channel profiles ${normalize(stamp.assignment).ifEmpty { "All" }} -> ${normalize(currentAssignment).ifEmpty { "All" }}"
        else -> null
    }
}

@Singleton
class ChannelSnapshotStamps @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("aerio_profiles", Context.MODE_PRIVATE)

    fun get(cacheKey: String): SnapshotStamp? = SnapshotStamp.decode(prefs.getString(KEY + cacheKey, null))

    fun put(cacheKey: String, stamp: SnapshotStamp) {
        prefs.edit().putString(KEY + cacheKey, stamp.encode()).apply()
    }

    fun remove(cacheKey: String) { prefs.edit().remove(KEY + cacheKey).apply() }

    private companion object { const val KEY = "snapmeta." }
}
