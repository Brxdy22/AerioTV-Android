package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.WatchProgressEntity
import com.aeriotv.android.core.profiles.ProfileScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

@Dao
interface WatchProgressDao {
    // Profiles (DB v37): scoped to ProfileScope.current(); see FavoriteChannelDao.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("SELECT * FROM watch_progress WHERE videoId = :videoId AND profileScope = :scope LIMIT 1")
    suspend fun getOnceIn(videoId: String, scope: String): WatchProgressEntity?
    suspend fun getOnce(videoId: String): WatchProgressEntity? = getOnceIn(videoId, ProfileScope.current())

    @Query("SELECT * FROM watch_progress WHERE videoId = :videoId AND profileScope = :scope LIMIT 1")
    fun observeIn(videoId: String, scope: String): Flow<WatchProgressEntity?>
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(videoId: String): Flow<WatchProgressEntity?> =
        ProfileScope.token.flatMapLatest { observeIn(videoId, it) }

    @Query("SELECT * FROM watch_progress WHERE vodType = 'episode' AND seriesId = :seriesId AND profileScope = :scope ORDER BY updatedAt DESC")
    fun observeEpisodesForSeriesIn(seriesId: String, scope: String): Flow<List<WatchProgressEntity>>
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeEpisodesForSeries(seriesId: String): Flow<List<WatchProgressEntity>> =
        ProfileScope.token.flatMapLatest { observeEpisodesForSeriesIn(seriesId, it) }

    @Query("SELECT * FROM watch_progress WHERE profileScope = :scope ORDER BY updatedAt DESC LIMIT :limit")
    fun observeRecentIn(scope: String, limit: Int): Flow<List<WatchProgressEntity>>
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeRecent(limit: Int = 20): Flow<List<WatchProgressEntity>> =
        ProfileScope.token.flatMapLatest { observeRecentIn(it, limit) }

    @Query("DELETE FROM watch_progress WHERE videoId = :videoId AND profileScope = :scope")
    suspend fun deleteIn(videoId: String, scope: String)
    suspend fun delete(videoId: String) = deleteIn(videoId, ProfileScope.current())

    @Query("SELECT * FROM watch_progress WHERE profileScope = :scope ORDER BY updatedAt DESC")
    suspend fun allOnceIn(scope: String): List<WatchProgressEntity>
    suspend fun allOnce(): List<WatchProgressEntity> = allOnceIn(ProfileScope.current())

    @Query("DELETE FROM watch_progress WHERE profileScope = :scope")
    suspend fun deleteScope(scope: String)
    suspend fun clear() = deleteScope(ProfileScope.current())
}
