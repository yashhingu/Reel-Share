package com.reelshare.feature.creator

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioWindowTest {
    @Test fun selectedExcerptIsRetained() {
        assertEquals(AudioWindow(5000, 9000), AudioWindow.bounded(5000, 9000, 20_000, 12_000))
    }
    @Test fun musicCannotExtendReel() {
        assertEquals(AudioWindow(5000, 8000), AudioWindow.bounded(5000, 19_000, 20_000, 3000))
    }
    @Test fun shorterReelClampsExistingSelection() {
        assertEquals(AudioWindow(5000, 7000), AudioWindow.bounded(5000, 9000, 20_000, 2000))
    }
    @Test fun sourceEndRetainsAtLeastOneSecond() {
        assertEquals(AudioWindow(19_000, 20_000), AudioWindow.bounded(21_000, 25_000, 20_000, 12_000))
    }
    @Test fun fastShortReelKeepsMusicWithinItsNewDuration() {
        assertEquals(AudioWindow(0, 250), AudioWindow.bounded(0, 4000, 5000, 250))
        assertEquals(AudioWindow(4750, 5000), AudioWindow.bounded(5000, 6000, 5000, 250))
    }
    @Test fun negativeAndReversedHandlesAreBounded() {
        assertEquals(AudioWindow(0, 1000), AudioWindow.bounded(-2000, -3000, 20_000, 12_000))
        assertEquals(AudioWindow(5000, 6000), AudioWindow.bounded(5000, 4000, 20_000, 12_000))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectUnreadableAudioDuration() {
        AudioWindow.bounded(0, 0, 0, 3000)
    }
}
