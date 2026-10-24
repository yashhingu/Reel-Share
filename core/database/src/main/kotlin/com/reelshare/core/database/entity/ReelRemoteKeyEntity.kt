package com.reelshare.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Paging 3 Remote Key entity for tracking pagination boundaries
 * across RemoteMediator network queries.
 */
@Entity(tableName = "reel_remote_keys")
data class ReelRemoteKeyEntity(
    @PrimaryKey val reelId: String,
    val prevKey: Int?,
    val nextKey: Int?,
    val createdAt: Long = System.currentTimeMillis()
)
