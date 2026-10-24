package com.reelshare.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.reelshare.core.model.Comment

@Entity(tableName = "comments", indices = [Index("reelId")])
data class CommentEntity(
    @PrimaryKey val id: String,
    val reelId: String,
    val userId: String,
    val userName: String,
    val userAvatarUrl: String,
    val text: String,
    val timestamp: Long,
    val likeCount: Int,
    val isLiked: Boolean
) {
    fun toDomain() = Comment(id, reelId, userId, userName, userAvatarUrl, text, timestamp, likeCount, isLiked)

    companion object {
        fun fromDomain(comment: Comment) = CommentEntity(
            comment.id, comment.reelId, comment.userId, comment.userName,
            comment.userAvatarUrl, comment.text, comment.timestamp, comment.likeCount, comment.isLiked
        )
    }
}
