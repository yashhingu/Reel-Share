package com.reelshare.feature.creator

/** Gives decoder preparation longer than a playing frame that has stopped advancing. */
internal class PreviewStallTracker {
    enum class Timeout { PREPARING, PLAYING }

    private var position = Long.MIN_VALUE
    private var ready = false
    private var sinceMs = Long.MIN_VALUE

    fun tick(nowMs: Long, positionMs: Long, playingRequested: Boolean, prepared: Boolean,
        playerReady: Boolean): Timeout? {
        if (!playingRequested || !prepared) {
            sinceMs = nowMs
            position = positionMs
            ready = playerReady
            return null
        }
        if (positionMs != position || playerReady != ready || sinceMs == Long.MIN_VALUE) {
            sinceMs = nowMs
            position = positionMs
            ready = playerReady
            return null
        }
        val timeoutMs = if (playerReady) 15_000L else 45_000L
        return if (nowMs - sinceMs > timeoutMs) {
            if (playerReady) Timeout.PLAYING else Timeout.PREPARING
        } else null
    }
}
