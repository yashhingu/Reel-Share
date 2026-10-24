package com.reelshare.feature.creator

import androidx.media3.common.C
import androidx.media3.common.audio.GainProcessor

/** GainProcessor restores its sample position from Media3 StreamMetadata after a seek. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AudioGainProvider(private val envelope: AudioEnvelope, private val volume: Float, private val offsetUs: Long = 0) : GainProcessor.GainProvider {
    override fun getGainFactorAtSamplePosition(samplePosition: Long, sampleRate: Int): Float =
        volume * envelope.gainAtUs(samplePosition * 1_000_000 / sampleRate + offsetUs)

    override fun isUnityUntil(samplePosition: Long, sampleRate: Int): Long {
        if (getGainFactorAtSamplePosition(samplePosition, sampleRate) != 1f) return C.TIME_UNSET
        val boundary = envelope.nextBoundaryUs(samplePosition * 1_000_000 / sampleRate + offsetUs) ?: return C.TIME_END_OF_SOURCE
        return (((boundary - offsetUs) * sampleRate + 999_999) / 1_000_000).coerceAtLeast(samplePosition + 1)
    }
}
