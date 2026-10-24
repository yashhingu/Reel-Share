package com.reelshare.feature.creator

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineWaveformTest {
    private val peaks = floatArrayOf(0f, .2f, .8f, 0f)
    private val axis = TimelineAxis(listOf(250, 7750))

    private fun peak(start: Long, offset: Long, length: Long, a: Long, b: Long) =
        TimelineWaveform.peak(peaks, 4000, start, offset, length, axis, axis.xAt(a), axis.xAt(b))

    @Test fun trimmedAudioStartsFromTheSelectedSourceAndMovesOnOutputClock() {
        assertEquals(.8f, peak(2000, 3000, 2000, 3100, 3900), 0f)
        assertEquals(.8f, peak(2000, 5000, 2000, 5100, 5900), 0f)
        assertEquals(0f, peak(2000, 3000, 2000, 4100, 4900), 0f)
    }

    @Test fun barsAggregatePeaksAndExcludeAudioOutsideTheLane() {
        assertEquals(.8f, peak(0, 2000, 4000, 3100, 4900), 0f)
        assertEquals(0f, peak(0, 2000, 4000, 0, 1900), 0f)
        assertEquals(0f, peak(0, 2000, 4000, 6100, 7000), 0f)
        assertEquals(0f, TimelineWaveform.peak(floatArrayOf(), 4000, 0, 0, 4000, axis, 0f, axis.width), 0f)
    }

    @Test fun zoomAndShortClipWidthsMapToTheSameSource() {
        for (zoom in listOf(8f, 32f, 64f)) {
            val zoomed = TimelineAxis(listOf(250, 7750), zoom)
            assertEquals(.2f, TimelineWaveform.peak(peaks, 4000, 1000, 0, 3000, zoomed,
                zoomed.xAt(50), zoomed.xAt(200)), 0f)
        }
    }
}
