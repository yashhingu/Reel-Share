package com.reelshare.feature.creator

internal data class AudioMix(val musicFadeInMs: Long = 0, val musicFadeOutMs: Long = 0,
    val duckMusic: Boolean = false, val duckAmount: Float = .7f, val musicOffsetMs: Long = 0) {
    fun bounded() = copy(musicFadeInMs = musicFadeInMs.coerceIn(0, 5000), musicFadeOutMs = musicFadeOutMs.coerceIn(0, 5000),
        duckAmount = if (duckAmount.isFinite()) duckAmount.coerceIn(0f, 1f) else .7f,
        musicOffsetMs = musicOffsetMs.coerceIn(0, 2_400_000))
}

internal data class VoiceWindow(val startMs: Long, val endMs: Long)

/** Pure output-clock gain: seeks, exports and repeated playback evaluate the same envelope. */
internal class AudioEnvelope(val lengthMs: Long, fadeInMs: Long = 0, fadeOutMs: Long = 0,
    voices: List<VoiceWindow> = emptyList(), private val duckAmount: Float = 0f) {
    // Snapshot decisions and use array iteration: no iterator allocation per PCM frame.
    private val voices = voices.toTypedArray()
    val fadeIn = fadeInMs.coerceIn(0, lengthMs.coerceAtLeast(0) / 2)
    val fadeOut = fadeOutMs.coerceIn(0, lengthMs.coerceAtLeast(0) / 2)
    fun gainAtUs(timeUs: Long): Float {
        val t = timeUs / 1000.0
        if (t < 0 || t > lengthMs) return 0f
        val fade = minOf(if (fadeIn > 0) t / fadeIn else 1.0,
            if (fadeOut > 0) (lengthMs - t) / fadeOut else 1.0).coerceIn(0.0, 1.0)
        var duck = 1.0
        for (voice in voices) {
            val activity = when {
                t < voice.startMs - ATTACK_MS || t >= voice.endMs + RELEASE_MS -> 0.0
                t < voice.startMs -> smooth((t - voice.startMs + ATTACK_MS) / ATTACK_MS)
                t <= voice.endMs -> 1.0
                else -> 1.0 - smooth((t - voice.endMs) / RELEASE_MS)
            }
            duck = minOf(duck, 1.0 - duckAmount * activity)
        }
        return (fade * duck).toFloat().coerceIn(0f, 1f)
    }

    /** First possible departure from unity, used to copy constant-gain PCM in whole buffers. */
    fun nextBoundaryUs(timeUs: Long): Long? {
        val boundaries = buildList {
            if (fadeIn > 0) add(fadeIn * 1000)
            if (fadeOut > 0) add((lengthMs - fadeOut) * 1000)
            if (duckAmount > 0) voices.forEach { add((it.startMs - ATTACK_MS) * 1000); add((it.endMs + RELEASE_MS) * 1000) }
            add(lengthMs * 1000)
        }
        // At the exact start of fade-out/duck attack, gain is still 1 for this sample
        // only. Report that boundary too; the provider advances at least one frame.
        return boundaries.filter { it >= timeUs }.minOrNull()
    }

    companion object {
        const val ATTACK_MS = 150L
        const val RELEASE_MS = 350L
        private fun smooth(t: Double): Double { val x = t.coerceIn(0.0, 1.0); return x * x * (3 - 2 * x) }
    }
}
