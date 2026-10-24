package com.reelshare.feature.creator

/** Selects a section of a music source; placement on the reel is stored separately. */
internal data class AudioWindow(val startMs: Long, val endMs: Long) {
    companion object {
        fun bounded(startMs: Long, endMs: Long, sourceDurationMs: Long, reelDurationMs: Long): AudioWindow {
            require(sourceDurationMs >= 1000 && reelDurationMs > 0)
            val minimum = minOf(1000, reelDurationMs)
            val start = startMs.coerceIn(0, sourceDurationMs - minimum)
            val end = endMs.coerceIn(start + minimum, sourceDurationMs)
            return AudioWindow(start, minOf(end, start + reelDurationMs))
        }
    }
}
