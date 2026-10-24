package com.reelshare.feature.creator

/** Limits automatic graph rebuilds when Media3's GL frame queue fails during a seek. */
internal class PreviewFrameRecovery(private val windowMs: Long = 30_000, private val maxRetries: Int = 2) {
    private var windowStartMs = -1L
    private var retries = 0

    fun allow(nowMs: Long): Boolean {
        if (windowStartMs < 0 || nowMs - windowStartMs >= windowMs) {
            windowStartMs = nowMs
            retries = 0
        }
        if (retries >= maxRetries) return false
        retries++
        return true
    }
}
