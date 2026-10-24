package com.reelshare.feature.creator

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioLanePlacementTest {
    @Test fun crossesShortClipWithoutJumpingAtItsMinimumWidth() {
        val axis = TimelineAxis(listOf(250, 9000, 1000))
        val moved = AudioLanePlacement.shifted(axis, 0, axis.xAt(1250), 4000)
        assertEquals(1250.0, moved.toDouble(), 1.0)
        assertEquals(250.0, AudioLanePlacement.shifted(axis, 0, axis.xAt(250), 4000).toDouble(), 1.0)
    }

    @Test fun keepsAtLeastOneSecondAudibleAndClampsBothEdges() {
        val axis = TimelineAxis(listOf(9000))
        assertEquals(8000, AudioLanePlacement.shifted(axis, 3000, 100_000f, 9000))
        assertEquals(0, AudioLanePlacement.shifted(axis, 3000, -100_000f, 9000))
        assertEquals(500, AudioLanePlacement.maxOffset(1000, 500))
        assertEquals(0, AudioMix(musicOffsetMs = -4).bounded().musicOffsetMs)
        assertEquals(2_400_000, AudioMix(musicOffsetMs = Long.MAX_VALUE).bounded().musicOffsetMs)
    }
}
