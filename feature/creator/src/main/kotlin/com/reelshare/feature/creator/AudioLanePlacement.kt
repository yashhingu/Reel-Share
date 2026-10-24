package com.reelshare.feature.creator

/** Translate a lane drag through the filmstrip's piecewise output-time axis. */
internal object AudioLanePlacement {
    fun maxOffset(reelMs: Long, sourceLengthMs: Long): Long =
        (reelMs - minOf(1000L, sourceLengthMs.coerceAtLeast(1))).coerceAtLeast(0)

    fun shifted(axis: TimelineAxis, originalMs: Long, dragDp: Float, sourceLengthMs: Long): Long =
        axis.timeAt(axis.xAt(originalMs) + dragDp).coerceIn(0, maxOffset(axis.durationMs, sourceLengthMs))
}
