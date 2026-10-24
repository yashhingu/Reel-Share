package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class AudioEnvelopeTest {
    @Test fun fadesUseThePlayedLengthAndNeverOverlap() {
        val e = AudioEnvelope(2000, 5000, 5000)
        assertEquals(1000L, e.fadeIn); assertEquals(1000L, e.fadeOut)
        assertEquals(0f, e.gainAtUs(0), 0f)
        assertEquals(.5f, e.gainAtUs(500_000), .001f)
        assertEquals(1f, e.gainAtUs(1_000_000), 0f)
        assertEquals(.5f, e.gainAtUs(1_500_000), .001f)
        assertEquals(0f, e.gainAtUs(2_000_000), 0f)
    }
    @Test fun overlappingTakesUseOneReductionWithSmoothAttackAndRelease() {
        val e = AudioEnvelope(6000, voices = listOf(VoiceWindow(1000, 3000), VoiceWindow(2000, 4000)), duckAmount = .75f)
        assertEquals(1f, e.gainAtUs(800_000), 0f)
        assertEquals(.625f, e.gainAtUs(925_000), .001f)
        assertEquals(.25f, e.gainAtUs(2_500_000), .001f)
        assertEquals(.25f, e.gainAtUs(3_300_000), .001f)
        assertEquals(.625f, e.gainAtUs(4_175_000), .001f)
        assertEquals(1f, e.gainAtUs(4_350_000), 0f)
    }
    @Test fun fadesAndDuckingMultiplyAndRandomAccessHasNoHistory() {
        val e = AudioEnvelope(4000, 1000, 1000, listOf(VoiceWindow(0, 2000)), .5f)
        assertEquals(.25f, e.gainAtUs(500_000), .001f)
        assertEquals(.5f, e.gainAtUs(3_500_000), .001f)
        assertEquals(.25f, e.gainAtUs(500_000), .001f)
        assertEquals(1f, AudioEnvelope(4000).gainAtUs(100_000), 0f)
        assertEquals(AudioMix(), EditRecipe(audioMix = AudioMix(1000, 1000, true)).style().audioMix)
    }
}
