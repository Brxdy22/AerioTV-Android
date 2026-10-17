package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.LocalRecordingEntity
import com.aeriotv.android.core.profiles.ProfileScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

@Dao
interface LocalRecordingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: LocalRecordingEntity): Long

    // Profiles (DB v37): the recordings index is per profile.
    @Query("SELECT * FROM local_recording WHERE profileScope = :scope ORDER BY startedAt DESC")
    fun observeAllIn(scope: String): Flow<List<LocalRecordingEntity>>
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<LocalRecordingEntity>> = ProfileScope.token.flatMapLatest { observeAllIn(it) }

    @Query("DELETE FROM local_recording WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM local_recording WHERE profileScope = :scope")
    suspend fun allOnceIn(scope: String): List<LocalRecordingEntity>

    @Query("DELETE FROM local_recording WHERE profileScope = :scope")
    suspend fun deleteScope(scope: String)
}
