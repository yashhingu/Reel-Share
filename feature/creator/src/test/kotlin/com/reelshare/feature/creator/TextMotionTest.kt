package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class TextMotionTest {
    private fun speech(plan: SpeedPlan = SpeedPlan(2000, EditRecipe())) = CaptionWords.fromSpeech(listOf(
        SpeechWord("Hello", 200, 500), SpeechWord("नमस्ते", 800, 1200)), plan, 4000, "speech")!!

    @Test fun speechWordsFollowSpeedOffsetAndKeepSilentGaps() {
        val text = speech(SpeedPlan(2000, EditRecipe(speed = 2f)))
        assertEquals(4100L, text.startMs); assertEquals(4600L, text.endMs)
        assertEquals(listOf(CaptionWord(0, 5, 0, 150), CaptionWord(6, 12, 300, 500)), text.wordTimings)
        assertTrue(CaptionWords.hasSpeechTiming(text))
        val words = CaptionWords.resolved(text)
        assertEquals(0, CaptionWords.active(words, 149)); assertEquals(-1, CaptionWords.active(words, 150))
        assertEquals(-1, CaptionWords.active(words, 299)); assertEquals(1, CaptionWords.active(words, 300))
        assertEquals(-1, CaptionWords.active(words, 500)); assertEquals(-1, CaptionWords.active(words, -1))
        assertEquals(0, CaptionWords.active(words, 0))
    }

    @Test fun speechTimestampsMapThroughEachRampSegment() {
        val plan = SpeedPlan(2000, EditRecipe(speed = .5f, ramp = true, rampEnd = 2f))
        val text = speech(plan)
        assertEquals(4000 + plan.sourceToOutputMs(200), text.startMs)
        assertEquals(plan.sourceToOutputMs(800) - plan.sourceToOutputMs(200), text.wordTimings[1].startMs)
        assertEquals(plan.sourceToOutputMs(1200) - plan.sourceToOutputMs(200), text.wordTimings[1].endMs)
        assertTrue(CaptionWords.hasSpeechTiming(text))
    }

    @Test fun pacedWordsHandleWhitespaceEmojiAndTimingEdits() {
        val text = TimedText("paced", "Hi\nनमस्ते  🎬!", 1000, 3000, caption = true)
        val words = CaptionWords.resolved(text)
        assertEquals(listOf("Hi", "नमस्ते", "🎬!"), words.map { text.text.substring(it.from, it.until) })
        assertEquals(0L, words.first().startMs); assertEquals(2000L, words.last().endMs)
        assertFalse(CaptionWords.hasSpeechTiming(text))
        val moved = CaptionWords.retime(speech(), 1000, 3000)
        assertTrue(CaptionWords.hasSpeechTiming(moved))
        assertEquals(1200L, moved.wordTimings[1].startMs)
        assertEquals("Hello नमस्ते", moved.wordTimingText)
        assertTrue(CaptionWords.editText(moved, "New words").wordTimings.isEmpty())
        assertFalse(CaptionWords.hasSpeechTiming(moved.copy(text = "Hello friend").bounded()))
    }

    @Test fun damagedSpansAndOverlappingTimesUsePacedFallback() {
        val text = speech()
        for (invalid in listOf(text.copy(wordTimingText = "different"),
            text.copy(wordTimings = listOf(CaptionWord(-1, 99, 0, 900))),
            text.copy(wordTimings = text.wordTimings.map { it.copy(startMs = 0) }),
            text.copy(wordTimings = text.wordTimings.map { it.copy(endMs = 9999) }))) {
            val bounded = invalid.bounded()
            assertFalse(CaptionWords.hasSpeechTiming(bounded)); assertTrue(bounded.wordTimings.isEmpty())
            assertEquals(2, CaptionWords.resolved(bounded).size)
        }
        assertFalse(TimedText("t", "Title", 0, 1000, wordHighlight = true).bounded().wordHighlight)
    }

    @Test fun animationStaysBoundedAndRestoresAtArbitrarySeeks() {
        for (name in TimedText.ANIMATIONS) for (length in listOf(100L, 800L)) {
            val t = TimedText("t", "Motion", 1000, 1200, animation = name, animationMs = length)
            assertEquals(0f, TextAnimation.at(t, 999).alpha, 0f)
            assertEquals(0f, TextAnimation.at(t, 1200).alpha, 0f)
            assertEquals(1f, TextAnimation.at(t, 1100).alpha, 0f)
            assertEquals(1f, TextAnimation.at(t, 1100).scale, .0001f)
            for (ms in 1000L..1199L) {
                val f = TextAnimation.at(t, ms)
                assertTrue(f.alpha in 0f..1f && f.scale in .82f..1f && f.offsetY in 0f.. .06f)
            }
            assertEquals(TextAnimation.at(t, 1050), TextAnimation.at(t, 1150))
        }
        assertEquals("Fade", TimedText("t", "Legacy", 0, 1000).animationName)
        assertEquals("None", TimedText("t", "Legacy", 0, 1000, animated = false).animationName)
        assertEquals("None", TimedText("t", "Explicit", 0, 1000, animation = "None").animationName)
        assertTrue(TextAnimation.at(TimedText("t", "Slide", 0, 1000, animation = "Slide", animationMs = 400), 100).offsetY > 0)
        assertTrue(TextAnimation.at(TimedText("t", "Pop", 0, 1000, animation = "Pop", animationMs = 400), 100).scale < 1)
    }
}
