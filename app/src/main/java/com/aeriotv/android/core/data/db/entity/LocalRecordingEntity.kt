package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per local recording file. Populated by [LocalRecordingService] on
 * stream completion (or cancellation). Mirrors iOS Recording (Models.swift:680)
 * but only the local-destination fields, server-side recordings stay on
 * Dispatcharr and are queried fresh via the API.
 *
 * `playlistId` is the new (DB v9) FK to PlaylistEntity. Mirrors iOS commit
 * 2efd777 (2026-05-10) cascade-delete behaviour: removing a playlist drops
 * its local recording rows. The TS file on disk is NOT auto-deleted by this
 * cascade, file cleanup is the responsibility of the user-facing delete
 * action in DvrTabContent. Nullable + ON DELETE CASCADE so pre-v9 rows
 * survive the migration with null playlistId and never trigger an
 * unintended cascade.
 */
@Entity(
    tableName = "local_recording",
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
data class LocalRecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val channelName: String,
    val title: String,
    val filePath: String,
    val startedAt: Long,
    val endedAt: Long,
    val byteSize: Long,
    val status: String, // "completed" | "stopped" | "failed"
    val playlistId: String? = null,
)
