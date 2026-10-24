package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class SubtitleFileTest {
    @Test fun subtitlesRoundTripUnicodeAndMilliseconds() {
        val entries = listOf(TimedText("a", "Hello\nनमस्ते", 1234, 5678, caption = true))
        val read = SubtitleFile.parse(SubtitleFile.write(entries), 10_000).single()
        assertEquals(entries.single().text, read.text)
        assertEquals(1234L, read.startMs); assertEquals(5678L, read.endMs)
    }
    @Test fun subtitlesClampToOutputDurationAndSkipOutsideTimes() {
        val read = SubtitleFile.parse("1\n00:00:01,000 --> 00:00:08,000\nHello\n\n2\n00:00:10,000 --> 00:00:11,000\nOutside", 5000)
        assertEquals(1, read.size); assertEquals(5000L, read.single().endMs)
    }
    @Test(expected = IllegalArgumentException::class) fun overlappingSubtitlesAreRejected() {
        SubtitleFile.parse("1\n00:00:00,000 --> 00:00:02,000\nOne\n\n2\n00:00:01,000 --> 00:00:03,000\nTwo", 4000)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidSubtitleFileDoesNotEraseCaptions() { SubtitleFile.parse("not subtitles", 4000) }
    @Test fun resamplingIsIndependentOfDecoderBufferBoundaries() {
        val samples = FloatArray(4410) { kotlin.math.sin(it * .1).toFloat() }
        val single = MonoResampler(44100).process(samples)
        val streaming = MonoResampler(44100)
        val chunks = samples.toList().chunked(137).flatMap { streaming.process(it.toFloatArray()).toList() }.toShortArray()
        assertArrayEquals(single, chunks); assertTrue(single.size in 1599..1601)
    }
    @Test fun resamplingClampsSamplesToPcmRange() {
        val samples = MonoResampler(16000).process(floatArrayOf(-2f, 0f, 2f))
        assertArrayEquals(shortArrayOf(-32767, 0, 32767), samples)
    }
}
