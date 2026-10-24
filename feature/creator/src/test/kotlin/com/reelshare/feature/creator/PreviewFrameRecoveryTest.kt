package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class PreviewFrameRecoveryTest {
    @Test fun limitsRetriesDuringRepeatedSeekFailuresAndReopensAfterQuietPeriod() {
        val recovery = PreviewFrameRecovery(windowMs = 30_000, maxRetries = 2)
        assertTrue(recovery.allow(100))
        assertTrue(recovery.allow(200))
        assertFalse(recovery.allow(300))
        assertTrue(recovery.allow(30_100))
        assertTrue(recovery.allow(30_200))
        assertFalse(recovery.allow(30_300))
    }
}
