package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aeriotv.android.core.data.db.entity.ChannelSnapshotEntity

@Dao
interface ChannelSnapshotDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ChannelSnapshotEntity>)

    /** Cached channels for a playlist, in the same order they were saved. */
    @Query("SELECT * FROM channel_snapshot WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun forPlaylist(playlistId: String): List<ChannelSnapshotEntity>

    /** Most recent fetch time for this source, or null when nothing is cached. */
    @Query("SELECT MAX(fetchedAt) FROM channel_snapshot WHERE playlistId = :playlistId")
    suspend fun newestFetchedAt(playlistId: String): Long?

    /**
     * Largest catch-up window any channel of this playlist exposes, in days
     * (null when nothing is cached, 0 when no channel has an archive). The
     * EPG catch-up reach prune and the grid history clamp both need only this
     * one number, so they ask for it instead of reading the whole snapshot.
     */
    @Query("SELECT MAX(catchupDays) FROM channel_snapshot WHERE playlistId = :playlistId")
    suspend fun maxCatchupDays(playlistId: String): Int?

    /**
     * GH #81: the playlist's distinct provider group names, for surfaces that
     * only need the group list (Settings > Default Group) and must not pull the
     * whole channel snapshot into memory to get it.
     */
    @Query(
        "SELECT DISTINCT groupTitle FROM channel_snapshot " +
            "WHERE playlistId = :playlistId AND groupTitle != '' ORDER BY groupTitle COLLATE NOCASE ASC",
    )
    suspend fun distinctGroupTitles(playlistId: String): List<String>

    /** Mark as Radio Channel: flip one cached channel's radio flag in place. */
    @Query("UPDATE channel_snapshot SET isRadio = :isRadio WHERE playlistId = :playlistId AND channelId = :channelId")
    suspend fun setRadio(playlistId: String, channelId: String, isRadio: Boolean)

    @Query("DELETE FROM channel_snapshot WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: String)

    /**
     * Replace the whole cached snapshot for one playlist in a single transaction
     * so a reader never sees a half-written batch (matches EpgProgrammeDao
     * replaceForPlaylist).
     */
    @Transaction
    suspend fun replaceForPlaylist(playlistId: String, rows: List<ChannelSnapshotEntity>) {
        deleteForPlaylist(playlistId)
        insertAll(rows)
    }
}
