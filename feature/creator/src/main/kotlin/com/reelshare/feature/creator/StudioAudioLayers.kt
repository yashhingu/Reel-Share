package com.reelshare.feature.creator

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.GainProcessor
import androidx.media3.transformer.*
import com.reelshare.core.database.entity.DraftEntity
import java.io.File

/** Shared by both composition types, so delayed audio has the same clock in preview and export. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class StudioAudioLayers(draft: DraftEntity, originalSound: Boolean) {
    private val duration = draft.outputDuration()
    private val recipe = draft.recipe()
    private val mix = recipe.audioMix
    private val music = AudioSplit.music(draft)
    private val tracks = (music + recipe.audioTracks).filter { it.lengthOnReel(duration) > 0 }
    val headroom = 1f / (AudioSplit.peakConcurrency(tracks, duration) + if (originalSound) 1 else 0).coerceAtLeast(1)
    fun sequences(): List<EditedMediaItemSequence> = tracks.map { track ->
        require(File(track.path).isFile) { "An audio track is missing. Remove it or record it again." }
        val length = track.lengthOnReel(duration)
        val envelopeOffset = track.envelopeOffsetMs
        val envelopeLength = track.envelopeLengthMs.takeIf { it > 0 } ?: length
        val duck = if (mix.duckMusic && music.any { it.id == track.id }) recipe.audioTracks
            .filter { it.volume > 0 && it.lengthOnReel(duration) > 0 }
            .map { VoiceWindow(it.offsetMs - track.offsetMs + envelopeOffset,
                it.offsetMs + it.lengthOnReel(duration) - track.offsetMs + envelopeOffset) } else emptyList()
        val volume = track.volume.coerceIn(0f, 1f) * headroom
        val processors = if (track.fadeInMs > 0 || track.fadeOutMs > 0 || duck.isNotEmpty())
            listOf(GainProcessor(AudioGainProvider(AudioEnvelope(envelopeLength, track.fadeInMs, track.fadeOutMs, duck, mix.duckAmount), volume, envelopeOffset * 1000)))
            else listOf(StudioComposition.gain(volume))
        val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(File(track.path)))
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(track.startMs)
                .setEndPositionMs(track.startMs + length).build()).build())
            .setDurationUs(track.durationMs * 1000).setRemoveVideo(true)
            .setEffects(Effects(processors, emptyList())).build()
        EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO)).apply {
            if (track.offsetMs > 0) addGap(track.offsetMs * 1000)
            addItem(item)
        }.build()
    }
}
