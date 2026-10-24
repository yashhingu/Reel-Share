package com.reelshare.core.model

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable

/**
 * Core domain representation of a short-form video Reel.
 * Annotated with @Immutable to assist Compose compiler in smart recomposition skipping.
 */
@Immutable
@Serializable
data class Reel(
    val id: String,
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
    val tags: List<String> = emptyList(),
    val durationMs: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Recomposition Guard wrapper:
 * Isolates high-frequency interactive counters (likes, comments, bookmarks) from the video surface
 * rendering hierarchy to prevent ExoPlayer surface re-renders during micro-interactions.
 */
@Stable
data class ReelInteractionState(
    val reelId: String,
    val isLiked: Boolean,
    val likeCount: Long,
    val isBookmarked: Boolean,
    val isFollowing: Boolean,
    val commentCount: Long,
    val shareCount: Long
) {
    companion object {
        fun fromReel(reel: Reel): ReelInteractionState = ReelInteractionState(
            reelId = reel.id,
            isLiked = reel.isLiked,
            likeCount = reel.likeCount,
            isBookmarked = reel.isBookmarked,
            isFollowing = reel.isFollowing,
            commentCount = reel.commentCount,
            shareCount = reel.shareCount
        )
    }
}
