package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Per-video playback position persistence. Mirrors iOS `WatchProgress`
 * (Aerio/Models/VODModels.swift), same fields, same purpose, same
 * "5-minute-from-end = completed" heuristic encoded by callers.
 *
 * `videoId` is the Dispatcharr UUID for movies or episodes (or the source
 * URL hash for future M3U/Xtream VOD support). One row per video; upsert on
 * every periodic tick from the player.
 *
 * `playlistId` is the new (DB v9) FK to PlaylistEntity. Mirrors iOS commit
 * a31c35f (2026-05-10) cascade-delete behaviour: when the user removes a
 * playlist, its watch-progress rows go with it. Nullable + ON DELETE
 * CASCADE so pre-v9 rows survive the migration with playlistId = null and
 * never trigger an unintended cascade.
 */
@Entity(
    tableName = "watch_progress",
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
data class WatchProgressEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
    val playlistId: String? = null,
    // v1.7.3 parity (iOS Issue #19): episode metadata + an up-next queue so
    // Continue Watching surfaces in-progress episodes (with series art) and
    // advances to the next episode when one finishes. All DB-local: these are
    // deliberately NOT part of the Drive sync snapshot (the queue + stream URLs
    // are device-derived / can carry credentials), matching iOS where they ride
    // alongside the iCloud-synced fields without being synced.
    val vodType: String = "movie",   // "movie" or "episode"
    val seriesId: String? = null,    // parent series id for episode rows
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val streamUrl: String? = null,   // resolved stream for seeded next-episode rows
    val isFinished: Boolean = false,
    val upNextQueue: String? = null, // JSON [UpNextEntry]
)
