package com.reelshare.feature.creator

/** Source selection and placement are independent; shortening a reel never destroys a take. */
internal data class AudioTrack(val id: String, val path: String, val name: String, val durationMs: Long,
    val startMs: Long = 0, val endMs: Long = durationMs, val offsetMs: Long = 0, val volume: Float = 1f,
    val fadeInMs: Long = 0, val fadeOutMs: Long = 0,
    val envelopeOffsetMs: Long = 0, val envelopeLengthMs: Long = 0) {
    fun bounded(): AudioTrack {
        val duration = durationMs.coerceIn(1000, 1_800_000)
        val start = startMs.coerceIn(0, duration - 1000)
        return copy(name = name.take(120), durationMs = duration, startMs = start,
            endMs = endMs.coerceIn(start + 1000, duration), offsetMs = offsetMs.coerceIn(0, 2_400_000),
            volume = if (volume.isFinite()) volume.coerceIn(0f, 1f) else 1f,
            envelopeOffsetMs = envelopeOffsetMs.coerceIn(0, duration), envelopeLengthMs = envelopeLengthMs.coerceIn(0, duration),
            fadeInMs = fadeInMs.coerceIn(0, 5000), fadeOutMs = fadeOutMs.coerceIn(0, 5000))
    }
    fun lengthOnReel(reelMs: Long): Long = minOf(endMs - startMs, reelMs - offsetMs).coerceAtLeast(0)
}
