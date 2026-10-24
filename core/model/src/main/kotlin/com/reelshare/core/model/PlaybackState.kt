package com.reelshare.core.model

import androidx.compose.runtime.Immutable

@Immutable
data class PlaybackState(
    val reelId: String = "",
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isMuted: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null
)
