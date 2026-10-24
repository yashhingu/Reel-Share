package com.reelshare.feature.creator

/** Piecewise widths keep short clips selectable without lying about their time. Units are dp. */
internal class TimelineAxis(durations: List<Long>, zoom: Float = 32f) {
    data class Segment(val startMs: Long, val durationMs: Long, val left: Float, val width: Float)
    val durationMs = durations.sum().coerceAtLeast(1)
    private val scale = zoom.coerceIn(8f, 64f).coerceAtMost(15_000f / (durationMs / 1000f))
    val segments: List<Segment>
    val width: Float
    init {
        var time = 0L
        var x = 0f
        segments = durations.map { duration ->
            val d = duration.coerceAtLeast(1)
            Segment(time, d, x, maxOf(48f, d / 1000f * scale)).also { time += d; x += it.width }
        }
        width = x.coerceAtLeast(1f)
    }
    fun xAt(ms: Long): Float {
        val t = ms.coerceIn(0, durationMs)
        val s = segments.firstOrNull { t < it.startMs + it.durationMs } ?: return width
        return s.left + s.width * (t - s.startMs) / s.durationMs
    }
    fun timeAt(x: Float): Long {
        val p = x.coerceIn(0f, width)
        val s = segments.firstOrNull { p < it.left + it.width } ?: return durationMs
        return (s.startMs + s.durationMs * ((p - s.left) / s.width)).toLong().coerceIn(0, durationMs)
    }
}

/** A stationary finger can still cross clips when the timeline scrolls beneath it. */
internal object TimelineReorder {
    fun target(axis: TimelineAxis, from: Int, dragPx: Float, scrollDeltaPx: Int, density: Float): Int {
        val segment = axis.segments[from]
        val center = segment.left + segment.width / 2 + (dragPx + scrollDeltaPx) / density
        return axis.segments.indexOfFirst { center < it.left + it.width }
            .takeIf { it >= 0 } ?: axis.segments.lastIndex
    }
}

internal object PrecisionTrim {
    fun window(durationMs: Long, centerMs: Long, spanMs: Long): LongRange {
        val length = spanMs.coerceIn(1, durationMs.coerceAtLeast(1))
        val start = (centerMs - length / 2).coerceIn(0, (durationMs - length).coerceAtLeast(0))
        return start..(start + length)
    }
}
