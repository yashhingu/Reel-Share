package com.reelshare.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reelshare.core.database.entity.CommentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CommentDao {
    @Query("SELECT * FROM comments WHERE reelId = :reelId ORDER BY timestamp DESC")
    fun observeForReel(reelId: String): Flow<List<CommentEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(comments: List<CommentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(comment: CommentEntity)

    @Query("SELECT * FROM comments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CommentEntity?

    @Query("DELETE FROM comments WHERE reelId = :reelId")
    suspend fun deleteForReel(reelId: String)
}
