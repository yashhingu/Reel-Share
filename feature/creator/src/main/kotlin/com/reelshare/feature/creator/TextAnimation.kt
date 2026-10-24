package com.reelshare.feature.creator

/** GPU-only transforms: no new text bitmap for each animation frame. */
internal object TextAnimation {
    data class Frame(val alpha: Float = 1f, val scale: Float = 1f, val offsetY: Float = 0f)

    fun at(t: TimedText, ms: Long): Frame {
        if (ms < t.startMs || ms >= t.endMs) return Frame(alpha = 0f)
        val name = t.animationName
        if (name == "None") return Frame()
        val elapsed = ms - t.startMs
        val duration = t.endMs - t.startMs
        val length = t.animationMs.coerceAtMost(duration / 2).coerceAtLeast(1)
        val entry = (elapsed.toFloat() / length).coerceIn(0f, 1f)
        val exit = ((duration - elapsed).toFloat() / length).coerceIn(0f, 1f)
        val alpha = minOf(entry, exit)
        fun ease(v: Float) = 1 - (1 - v) * (1 - v) * (1 - v)
        return when (name) {
            "Pop" -> Frame(alpha, .82f + .18f * minOf(ease(entry), ease(exit)))
            "Slide" -> Frame(alpha, offsetY = .06f * (1 - minOf(ease(entry), ease(exit))))
            else -> Frame(alpha)
        }
    }
}
