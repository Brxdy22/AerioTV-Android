package com.aeriotv.android.core.data.db.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import com.aeriotv.android.core.profiles.ProfileScope

/**
 * Per-channel favorite flag. Mirrors iOS `favoriteChannelIDs` + `favoriteOrder`
 * @AppStorage arrays (architecture spec section C). `displayOrder` tracks the
 * user's manual reorder positions; defaults to addedAt so new favourites land
 * at the end of the existing list.
 */
@Entity(tableName = "favorite_channel", primaryKeys = ["channelId", "profileScope"])
data class FavoriteChannelEntity(
    val channelId: String,
    val channelName: String,
    val displayOrder: Long,
    val addedAt: Long,
    /** Profiles (DB v37): "" = default profile (every pre-v37 row). */
    @ColumnInfo(defaultValue = "")
    val profileScope: String = ProfileScope.current(),
)
