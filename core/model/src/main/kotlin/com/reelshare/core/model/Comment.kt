package com.reelshare.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Comment(
    val id: String,
    val reelId: String,
    val userId: String,
    val userName: String,
    val userAvatarUrl: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val likeCount: Int = 0,
    val isLiked: Boolean = false
)

@Immutable
@Serializable
data class UserProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val bio: String = "",
    val followerCount: Long = 0L,
    val followingCount: Long = 0L,
    val totalLikes: Long = 0L,
    val isVerified: Boolean = false
)
