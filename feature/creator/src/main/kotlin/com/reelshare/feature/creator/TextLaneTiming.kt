package com.reelshare.feature.creator

/** A drag uses the original range throughout, avoiding accumulated rounding and edge drift. */
internal object TextLaneTiming {
    enum class Part { Move, Start, End }

    fun drag(entry: TimedText, axis: TimelineAxis, part: Part, dx: Float): TimedText {
        val duration = axis.durationMs.coerceAtLeast(100)
        val end = entry.endMs.coerceIn(100, duration)
        val start = entry.startMs.coerceIn(0, end - 100)
        val range = when (part) {
            Part.Move -> {
                val length = end - start
                val moved = axis.timeAt(axis.xAt(start) + dx).coerceIn(0, duration - length)
                moved to moved + length
            }
            Part.Start -> axis.timeAt(axis.xAt(start) + dx).coerceIn(0, end - 100) to end
            Part.End -> start to axis.timeAt(axis.xAt(end) + dx).coerceIn(start + 100, duration)
        }
        return CaptionWords.retime(entry, range.first, range.second)
    }
}
