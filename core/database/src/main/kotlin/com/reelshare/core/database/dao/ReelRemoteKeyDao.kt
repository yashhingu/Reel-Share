package com.reelshare.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reelshare.core.database.entity.ReelRemoteKeyEntity

@Dao
interface ReelRemoteKeyDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(remoteKeys: List<ReelRemoteKeyEntity>)

    @Query("SELECT * FROM reel_remote_keys WHERE reelId = :reelId")
    suspend fun getRemoteKeyByReelId(reelId: String): ReelRemoteKeyEntity?

    @Query("DELETE FROM reel_remote_keys")
    suspend fun clearRemoteKeys()
}
