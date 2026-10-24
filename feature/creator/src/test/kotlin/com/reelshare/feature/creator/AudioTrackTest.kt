package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class AudioTrackTest {
    @Test fun delayedTrackNeverExtendsTheReel() {
        val take = AudioTrack("a", "/private/take", "Voice", 10_000, 2000, 9000, 3500)
        assertEquals(1500L, take.lengthOnReel(5000))
        assertEquals(0L, take.lengthOnReel(2000))
        assertEquals(7000L, take.lengthOnReel(20_000))
        assertEquals(9000L, take.endMs) // Retained if the user later lengthens the reel.
    }
    @Test fun invalidSelectionsAreBoundedAndStylesDropPrivateAudio() {
        val take = AudioTrack("a", "/private/take", "Voice", 3000, -200, 90_000, -1, Float.NaN).bounded()
        assertEquals(0L, take.startMs)
        assertEquals(3000L, take.endMs)
        assertEquals(0L, take.offsetMs)
        assertEquals(1f, take.volume, 0f)
        assertTrue(EditRecipe(audioTracks = listOf(take)).style().audioTracks.isEmpty())
        assertEquals(8, EditRecipe(audioTracks = List(8) { take }).bounded().audioTracks.size)
    }
}
