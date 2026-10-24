package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class TextLaneTimingTest {
    private val axis = TimelineAxis(listOf(250, 7750))
    private val entry = TimedText("title", "hello", 250, 2250)

    @Test fun moveCrossesPiecewiseAxisAndPreservesLengthAtEdges() {
        val moved = TextLaneTiming.drag(entry, axis, TextLaneTiming.Part.Move, axis.xAt(3250) - axis.xAt(250))
        assertEquals(3250.0, moved.startMs.toDouble(), 1.0)
        assertEquals(2000, moved.endMs - moved.startMs)
        assertEquals(6000, TextLaneTiming.drag(entry, axis, TextLaneTiming.Part.Move, 10000f).startMs)
        assertEquals(0, TextLaneTiming.drag(entry, axis, TextLaneTiming.Part.Move, -10000f).startMs)
    }

    @Test fun resizeCannotCrossOtherEdgeOrLeaveReel() {
        assertEquals(2150, TextLaneTiming.drag(entry, axis, TextLaneTiming.Part.Start, 10000f).startMs)
        assertEquals(350, TextLaneTiming.drag(entry, axis, TextLaneTiming.Part.End, -10000f).endMs)
        assertEquals(8000, TextLaneTiming.drag(entry, axis, TextLaneTiming.Part.End, 10000f).endMs)
    }

    @Test fun movedCaptionKeepsSpeechAlignmentAndResizeScalesWords() {
        val caption = entry.copy(text = "one two", caption = true, wordHighlight = true,
            wordTimings = listOf(CaptionWord(0, 3, 0, 800), CaptionWord(4, 7, 1000, 2000)), wordTimingText = "one two")
        val moved = TextLaneTiming.drag(caption, axis, TextLaneTiming.Part.Move, 80f)
        assertEquals(caption.wordTimings, moved.wordTimings)
        val resized = TextLaneTiming.drag(caption, axis, TextLaneTiming.Part.End, axis.xAt(4250) - axis.xAt(2250))
        assertTrue(CaptionWords.hasSpeechTiming(resized))
        assertEquals(4000.0, resized.wordTimings.last().endMs.toDouble(), 2.0)
    }
}
