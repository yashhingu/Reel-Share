package com.reelshare.feature.creator

import kotlin.math.ceil

/** Map output pixels back to the trimmed source, including minimum-width video clips. */
internal object TimelineWaveform {
    fun peak(data: FloatArray, durationMs: Long, sourceStartMs: Long, offsetMs: Long,
        lengthMs: Long, axis: TimelineAxis, left: Float, right: Float): Float {
        if (data.isEmpty() || durationMs <= 0 || lengthMs <= 0) return 0f
        val start = axis.timeAt(left).coerceAtLeast(offsetMs)
        val end = axis.timeAt(right).coerceAtMost(offsetMs + lengthMs)
        if (end <= start) return 0f
        val from = ((sourceStartMs + start - offsetMs).toDouble() * data.size / durationMs).toInt().coerceIn(0, data.size)
        val until = ceil((sourceStartMs + end - offsetMs).toDouble() * data.size / durationMs).toInt().coerceIn(from, data.size)
        var peak = 0f
        for (i in from until until) peak = maxOf(peak, data[i])
        return peak.coerceIn(0f, 1f)
    }
}
