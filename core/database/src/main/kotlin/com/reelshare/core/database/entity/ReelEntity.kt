package com.reelshare.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.reelshare.core.model.Reel

/**
 * Room database entity caching video reel metadata locally.
 * Serves as the single source of truth for Paging 3 Room integration.
 */
@Entity(tableName = "reels")
data class ReelEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val videoUrl: String,
    val thumbnailUrl: String,
    val authorId: String,
    val authorUsername: String,
    val authorDisplayName: String,
    val authorAvatarUrl: String,
    val isVerified: Boolean = false,
    val likeCount: Long = 0L,
    val commentCount: Long = 0L,
    val shareCount: Long = 0L,
    val isLiked: Boolean = false,
    val isBookmarked: Boolean = false,
    val isFollowing: Boolean = false,
    val audioTrackTitle: String = "Original Audio",
    val audioArtist: String = "",
    val tagsCsv: String = "", // Comma-separated tags
    val durationMs: Long = 0L,
    val pageOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toDomain(): Reel = Reel(
        id = id,
        title = title,
        description = description,
        videoUrl = videoUrl,
        thumbnailUrl = thumbnailUrl,
        authorId = authorId,
        authorUsername = authorUsername,
        authorDisplayName = authorDisplayName,
        authorAvatarUrl = authorAvatarUrl,
        isVerified = isVerified,
        likeCount = likeCount,
        commentCount = commentCount,
        shareCount = shareCount,
        isLiked = isLiked,
        isBookmarked = isBookmarked,
        isFollowing = isFollowing,
        audioTrackTitle = audioTrackTitle,
        audioArtist = audioArtist,
        tags = if (tagsCsv.isBlank()) emptyList() else tagsCsv.split(","),
        durationMs = durationMs,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(reel: Reel, pageOrder: Int = 0): ReelEntity = ReelEntity(
            id = reel.id,
            title = reel.title,
            description = reel.description,
            videoUrl = reel.videoUrl,
            thumbnailUrl = reel.thumbnailUrl,
            authorId = reel.authorId,
            authorUsername = reel.authorUsername,
            authorDisplayName = reel.authorDisplayName,
            authorAvatarUrl = reel.authorAvatarUrl,
            isVerified = reel.isVerified,
            likeCount = reel.likeCount,
            commentCount = reel.commentCount,
            shareCount = reel.shareCount,
            isLiked = reel.isLiked,
            isBookmarked = reel.isBookmarked,
            isFollowing = reel.isFollowing,
            audioTrackTitle = reel.audioTrackTitle,
            audioArtist = reel.audioArtist,
            tagsCsv = reel.tags.joinToString(","),
            durationMs = reel.durationMs,
            pageOrder = pageOrder,
            createdAt = reel.createdAt
        )
    }
}
