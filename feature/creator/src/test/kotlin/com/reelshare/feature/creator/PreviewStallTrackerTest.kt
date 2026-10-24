package com.reelshare.feature.creator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewStallTrackerTest {
    @Test fun slowDecoderStartupDoesNotLookLikeAPlaybackStall() {
        val tracker = PreviewStallTracker()
        assertNull(tracker.tick(0, 0, true, true, false))
        assertNull(tracker.tick(20_000, 0, true, true, false))
        assertNull(tracker.tick(21_000, 0, true, true, true))
        assertNull(tracker.tick(35_000, 0, true, true, true))
        assertEquals(PreviewStallTracker.Timeout.PLAYING, tracker.tick(36_001, 0, true, true, true))
    }

    @Test fun preparationStillHasABoundedTimeout() {
        val tracker = PreviewStallTracker()
        assertNull(tracker.tick(0, 0, true, true, false))
        assertNull(tracker.tick(45_000, 0, true, true, false))
        assertEquals(PreviewStallTracker.Timeout.PREPARING, tracker.tick(45_001, 0, true, true, false))
    }

    @Test fun pauseAndProgressResetTheClock() {
        val tracker = PreviewStallTracker()
        assertNull(tracker.tick(0, 0, true, true, true))
        assertNull(tracker.tick(14_000, 0, true, true, true))
        assertNull(tracker.tick(15_000, 0, false, true, true))
        assertNull(tracker.tick(29_000, 0, true, true, true))
        assertNull(tracker.tick(40_000, 1_000, true, true, true))
        assertNull(tracker.tick(55_000, 1_000, true, true, true))
        assertEquals(PreviewStallTracker.Timeout.PLAYING, tracker.tick(55_001, 1_000, true, true, true))
    }

    @Test fun unfinishedCompositionIsNotTimed() {
        val tracker = PreviewStallTracker()
        assertNull(tracker.tick(0, 0, true, false, false))
        assertNull(tracker.tick(60_000, 0, true, false, false))
        assertNull(tracker.tick(61_000, 0, true, true, false))
    }
}
