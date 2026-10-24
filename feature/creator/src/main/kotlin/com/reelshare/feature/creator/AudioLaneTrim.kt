package com.reelshare.feature.creator

internal data class AudioLaneRange(val startMs: Long, val endMs: Long, val offsetMs: Long)

/** Edge trims keep the untrimmed samples at the same output time. */
internal object AudioLaneTrim {
    fun drag(range: AudioLaneRange, sourceDurationMs: Long, axis: TimelineAxis, first: Boolean, dx: Float): AudioLaneRange {
        val visibleEnd = minOf(axis.durationMs, range.offsetMs + range.endMs - range.startMs)
        val minimum = minOf(1000L, (visibleEnd - range.offsetMs).coerceAtLeast(1))
        return if (first) {
            val next = axis.timeAt(axis.xAt(range.offsetMs) + dx)
                .coerceIn(maxOf(0, range.offsetMs - range.startMs), visibleEnd - minimum)
            range.copy(startMs = range.startMs + next - range.offsetMs, offsetMs = next)
        } else {
            val next = axis.timeAt(axis.xAt(visibleEnd) + dx)
                .coerceIn(range.offsetMs + minimum, minOf(axis.durationMs, range.offsetMs + sourceDurationMs - range.startMs))
            range.copy(endMs = range.startMs + next - range.offsetMs)
        }
    }
}
