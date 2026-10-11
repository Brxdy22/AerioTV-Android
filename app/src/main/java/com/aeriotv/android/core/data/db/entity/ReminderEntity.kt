package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per scheduled programme reminder. Mirrors iOS `programReminders`
 * @AppStorage map keyed by "channelName|title|startTs", same composite key
 * lives in [reminderKey] so the Android port preserves uniqueness semantics.
 *
 * The alarm itself is scheduled with AlarmManager at [startMillis] minus a
 * 5-minute pre-roll so users get a heads-up before the programme starts.
 *
 * `playlistId` is the new (DB v9) FK to PlaylistEntity. Cascade-delete on
 * playlist removal so a stale reminder doesn't outlive its source server.
 * Nullable + ON DELETE CASCADE so pre-v9 rows survive with null playlistId.
 * The AlarmManager registration is NOT auto-cancelled by this cascade, the
 * pending intent fires harmlessly with no row to fetch, and the broadcast
 * receiver no-ops on null lookup.
 */
@Entity(
    tableName = "reminder",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class ReminderEntity(
    @PrimaryKey val reminderKey: String,
    val channelName: String,
    val programTitle: String,
    val startMillis: Long,
    val endMillis: Long,
    /** Stable AlarmManager request code derived from `reminderKey.hashCode()`. */
    val alarmRequestCode: Int,
    val playlistId: String? = null,
)

/** Composite key matching the iOS shape so a reminder's identity is portable. */
fun reminderKey(channelName: String, programTitle: String, startMillis: Long): String =
    "$channelName|$programTitle|$startMillis"
