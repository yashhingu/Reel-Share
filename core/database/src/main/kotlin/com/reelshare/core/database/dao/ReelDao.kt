package com.reelshare.core.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.reelshare.core.database.entity.ReelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReelDao {

    @Query("SELECT * FROM reels ORDER BY pageOrder ASC, createdAt DESC")
    fun getPagingSource(): PagingSource<Int, ReelEntity>

    @Query("SELECT * FROM reels ORDER BY pageOrder ASC, createdAt DESC")
    fun getAllReels(): Flow<List<ReelEntity>>

    @Query("SELECT * FROM reels WHERE authorId = 'local_creator' ORDER BY createdAt DESC")
    fun getLocalPosts(): Flow<List<ReelEntity>>

    @Query("SELECT * FROM reels WHERE authorId = :authorId ORDER BY createdAt DESC")
    fun getAuthorPosts(authorId: String): Flow<List<ReelEntity>>

    @Query("SELECT * FROM reels WHERE id = :id LIMIT 1")
    suspend fun getReelById(id: String): ReelEntity?

    @Query("DELETE FROM reels WHERE id = :id AND authorId = 'local_creator'")
    suspend fun deleteLocalPost(id: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(reels: List<ReelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reel: ReelEntity)

    @Query("UPDATE reels SET isLiked = :isLiked, likeCount = MAX(0, likeCount + :likeDelta) WHERE id = :reelId")
    suspend fun updateLikeStatus(reelId: String, isLiked: Boolean, likeDelta: Long)

    @Query("UPDATE reels SET isBookmarked = :isBookmarked WHERE id = :reelId")
    suspend fun updateBookmarkStatus(reelId: String, isBookmarked: Boolean)

    @Query("UPDATE reels SET isFollowing = :isFollowing WHERE authorId = :authorId")
    suspend fun updateFollowStatus(authorId: String, isFollowing: Boolean)

    @Query("UPDATE reels SET commentCount = commentCount + 1 WHERE id = :reelId")
    suspend fun incrementCommentCount(reelId: String)

    @Query("DELETE FROM reels")
    suspend fun clearAll()

    @Query("DELETE FROM reels WHERE pageOrder >= 0 AND id LIKE '%_p%'")
    suspend fun clearLegacyRepeatedDemoReels()

}
