package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class TimelineAxisTest {
    @Test fun timeMappingIncludesMinimumWidthsWithoutDriftingAtCuts() {
        val axis = TimelineAxis(listOf(250, 9000, 1000))
        assertEquals(48f, axis.segments.first().width, 0f)
        for (ms in listOf(0L, 125, 250, 1000, 9250, 10250)) {
            assertEquals(ms.toDouble(), axis.timeAt(axis.xAt(ms)).toDouble(), 1.0)
        }
        assertEquals(axis.segments[1].left, axis.xAt(250), .001f)
        assertEquals(0, axis.timeAt(-1f))
        assertEquals(10250, axis.timeAt(Float.MAX_VALUE))
    }
    @Test fun maximumSlowTimelineFitsComposeConstraintsAtHighDensity() {
        val axis = TimelineAxis(List(12) { 200_000L }, 64f)
        assertTrue(axis.width < 16_000)
        assertEquals(2_400_000, axis.timeAt(axis.width))
    }

    @Test fun reorderingAccountsForTimelineMovingUnderStationaryFinger() {
        val axis = TimelineAxis(List(5) { 2000L })
        val oneClipPx = (axis.segments[0].width * 3).toInt()
        assertEquals(0, TimelineReorder.target(axis, 0, 0f, 0, 3f))
        assertEquals(1, TimelineReorder.target(axis, 0, 0f, oneClipPx, 3f))
        assertEquals(3, TimelineReorder.target(axis, 1, 0f, oneClipPx * 2, 3f))
        assertEquals(0, TimelineReorder.target(axis, 3, 0f, -oneClipPx * 5, 3f))
    }

    @Test fun precisionWindowStaysWithinSourceAtBothEnds() {
        assertEquals(0L..5000L, PrecisionTrim.window(600_000, 0, 5000))
        assertEquals(595_000L..600_000L, PrecisionTrim.window(600_000, 600_000, 5000))
        assertEquals(292_500L..307_500L, PrecisionTrim.window(600_000, 300_000, 15_000))
        assertEquals(0L..3000L, PrecisionTrim.window(3000, 2000, 15_000))
    }
}
