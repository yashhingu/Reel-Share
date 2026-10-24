package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class AudioLaneTrimTest {
    private val axis = TimelineAxis(listOf(250, 7750))
    private val range = AudioLaneRange(1000, 5000, 2000)
    private fun drag(first: Boolean, time: Long): AudioLaneRange {
        val anchor = if (first) 2000L else 6000L
        return AudioLaneTrim.drag(range, 6000, axis, first, axis.xAt(time) - axis.xAt(anchor))
    }

    @Test fun leftTrimPreservesSamplePlacementAndCanRestoreAvailableSource() {
        val trimmed = drag(true, 3000)
        assertEquals(2000.0, trimmed.startMs.toDouble(), 1.0)
        assertEquals(3000.0, trimmed.offsetMs.toDouble(), 1.0)
        assertEquals(range.offsetMs - range.startMs, trimmed.offsetMs - trimmed.startMs)
        assertEquals(5000, trimmed.endMs)
        assertEquals(AudioLaneRange(0, 5000, 1000), drag(true, 0))
    }

    @Test fun rightTrimClampsToAvailableSourceAndMinimumDuration() {
        assertEquals(3000.0, drag(false, 4000).endMs.toDouble(), 1.0)
        assertEquals(6000, drag(false, 8000).endMs)
        assertEquals(2000, drag(false, 0).endMs)
        assertEquals(4000, drag(true, 8000).startMs)
    }

    @Test fun clippedTailUsesVisibleEdgeAndNeverExtendsBeyondReel() {
        val clipped = AudioLaneRange(0, 9000, 6000)
        assertEquals(AudioLaneRange(0, 2000, 6000), AudioLaneTrim.drag(clipped, 9000, axis, false, 1000f))
        val short = AudioLaneRange(0, 9000, 7500)
        assertEquals(500, AudioLaneTrim.drag(short, 9000, axis, false, -1000f).endMs)
    }
}
