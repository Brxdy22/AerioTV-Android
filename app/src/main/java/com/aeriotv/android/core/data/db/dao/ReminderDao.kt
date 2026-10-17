package com.aeriotv.android.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aeriotv.android.core.data.db.entity.ReminderEntity
import com.aeriotv.android.core.profiles.ProfileScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest

@Dao
interface ReminderDao {
    // Profiles (DB v37): scoped to ProfileScope.current(); see FavoriteChannelDao.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReminderEntity)

    @Query("DELETE FROM reminder WHERE reminderKey = :reminderKey AND profileScope = :scope")
    suspend fun deleteIn(reminderKey: String, scope: String)
    suspend fun delete(reminderKey: String) = deleteIn(reminderKey, ProfileScope.current())

    @Query("SELECT * FROM reminder WHERE reminderKey = :reminderKey AND profileScope = :scope LIMIT 1")
    suspend fun getOnceIn(reminderKey: String, scope: String): ReminderEntity?
    suspend fun getOnce(reminderKey: String): ReminderEntity? = getOnceIn(reminderKey, ProfileScope.current())

    @Query("SELECT * FROM reminder WHERE profileScope = :scope ORDER BY startMillis ASC")
    fun observeAllIn(scope: String): Flow<List<ReminderEntity>>
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<ReminderEntity>> = ProfileScope.token.flatMapLatest { observeAllIn(it) }

    @Query("SELECT EXISTS(SELECT 1 FROM reminder WHERE reminderKey = :reminderKey AND profileScope = :scope)")
    fun observeIsSetIn(reminderKey: String, scope: String): Flow<Boolean>
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeIsSet(reminderKey: String): Flow<Boolean> =
        ProfileScope.token.flatMapLatest { observeIsSetIn(reminderKey, it) }

    @Query("SELECT * FROM reminder WHERE profileScope = :scope ORDER BY startMillis ASC")
    suspend fun allOnceIn(scope: String): List<ReminderEntity>
    suspend fun allOnce(): List<ReminderEntity> = allOnceIn(ProfileScope.current())

    /** Every profile's reminders: the alarm scheduler must re-arm all of them. */
    @Query("SELECT * FROM reminder ORDER BY startMillis ASC")
    suspend fun allScopesOnce(): List<ReminderEntity>

    @Query("DELETE FROM reminder WHERE profileScope = :scope")
    suspend fun deleteScope(scope: String)
    suspend fun clear() = deleteScope(ProfileScope.current())
}
