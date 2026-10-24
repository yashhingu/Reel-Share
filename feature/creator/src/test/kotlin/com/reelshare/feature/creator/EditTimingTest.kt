package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class EditTimingTest {
    @Test fun speedChangesOutputAndCaptionTimesTogether() {
        val plan = SpeedPlan(10_000, EditRecipe(speed = 2f))
        assertEquals(5000, plan.outputMs)
        assertEquals(1500, plan.sourceToOutputMs(3000))
        assertNull(plan.nextChangeUs(0))
    }
    @Test fun rampUsesIntegratedDurationNotAverageSpeed() {
        val plan = SpeedPlan(10_000, EditRecipe(speed = 1f, ramp = true, rampEnd = 2f))
        assertTrue(plan.outputMs in 6925..6935)
        assertTrue(plan.sourceToOutputMs(5000) > plan.outputMs / 2)
    }
    @Test fun nextSpeedChangeAlwaysAdvancesIncludingUnevenBoundaries() {
        val plan = SpeedPlan(1237, EditRecipe(speed = .5f, ramp = true, rampEnd = 2f))
        var current = 0L
        var changes = 0
        while (true) {
            val next = plan.nextChangeUs(current) ?: break
            assertTrue(next > current); current = next; changes++
            assertTrue(changes <= 24)
        }
        assertEquals(plan.count - 1, changes)
    }
    @Test fun splitRampKeepsEveryOriginalSpeedStepAndDuration() {
        val recipe = EditRecipe(speed = .5f, ramp = true, rampEnd = 3f)
        val original = SpeedPlan(12_000, recipe)
        val cut = 4_130L
        val first = SpeedPlan(cut, recipe, 12_000, 0)
        val second = SpeedPlan(12_000 - cut, recipe, 12_000, cut)
        assertTrue(kotlin.math.abs(original.outputMs - first.outputMs - second.outputMs) <= 1)
        for (source in listOf(0L, 200L, 1_500L, cut - 1, cut, cut + 100, 7_500L, 11_999L)) {
            val part = if (source < cut) first else second
            val local = if (source < cut) source else source - cut
            assertEquals(original.speedAt(source * 1000), part.speedAt(local * 1000), .0001f)
            val output = if (source < cut) first.sourceToOutputMs(local) else first.outputMs + second.sourceToOutputMs(local)
            assertTrue(kotlin.math.abs(original.sourceToOutputMs(source) - output) <= 1)
        }
        val trimmedSecond = SpeedPlan(4_000, recipe, 12_000, cut + 1_000)
        assertEquals(original.speedAt((cut + 1_000) * 1000), trimmedSecond.speedAt(0), .0001f)
    }
    @Test fun invalidSpeedsCannotCreateUnboundedExports() {
        assertEquals(1000L, SpeedPlan(1000, EditRecipe(speed = Float.NaN)).outputMs)
        assertEquals(4000L, SpeedPlan(1000, EditRecipe(speed = 0f)).outputMs)
        assertEquals(1000L, SpeedPlan(1000, EditRecipe(speed = Float.POSITIVE_INFINITY)).outputMs)
        assertEquals(250L, SpeedPlan(1000, EditRecipe(speed = 100f)).outputMs)
    }
    @Test fun fadesStayBoundedForShortReels() {
        assertEquals(0f, MotionTiming.envelope(0, 1000, 2000, 2000), .001f)
        assertEquals(1f, MotionTiming.envelope(500, 1000, 2000, 2000), .001f)
        assertEquals(0f, MotionTiming.envelope(1000, 1000, 2000, 2000), .001f)
    }
    @Test fun motionNeverExtrapolatesBeyondKeyframes() {
        assertEquals(0f, MotionTiming.progress(-100, 1000, true), .001f)
        assertEquals(.5f, MotionTiming.progress(500, 1000, true), .001f)
        assertEquals(1f, MotionTiming.progress(2000, 1000, true), .001f)
    }
    @Test fun savedStyleExcludesMediaAndPersonalText() {
        val r = EditRecipe(warmth = .5f, speed = 2f, texts = listOf(TimedText("a", "Private", 0, 1000)),
            clips = listOf(VideoClip("a", "/private/clip", "Private", 1000, 0, 1000, 720, 1280)))
        assertTrue(r.style().texts.isEmpty()); assertTrue(r.style().clips.isEmpty())
        assertEquals(1f, r.style().speed, .001f); assertEquals(.5f, r.style().warmth, .001f)
    }
}
