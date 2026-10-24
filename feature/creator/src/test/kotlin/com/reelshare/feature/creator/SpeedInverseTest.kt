package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class SpeedInverseTest {
    @Test fun inverseMappingKeepsTheCutOnTheSameSourceFrame() {
        for (r in listOf(EditRecipe(speed = .25f), EditRecipe(speed = 4f), EditRecipe(speed = .5f, ramp = true, rampEnd = 3f))) {
            val plan = SpeedPlan(9000, r)
            for (ms in 0L..9000L step 137) assertTrue(kotlin.math.abs(ms - plan.outputToSourceMs(plan.sourceToOutputMs(ms))) <= 4)
            assertEquals(0L, plan.outputToSourceMs(-10))
            assertTrue(plan.outputToSourceMs(Long.MAX_VALUE) in 8996..9000)
        }
    }
}
