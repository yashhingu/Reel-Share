package com.reelshare.feature.creator

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.Presentation
import androidx.media3.transformer.*
import com.reelshare.core.database.entity.DraftEntity
import java.io.File

/** Preview and export get identical decisions and fresh processors. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StudioComposition {
    fun create(context: Context, draft: DraftEntity, settings: ExportSettings, compare: Boolean = false,
               previewTitles: PreviewTitles? = null, previewReframe: PreviewReframe? = null): Composition {
        val recipe = draft.recipe()
        if (recipe.collage != null) return StudioCollageComposition.create(context, draft, settings, compare, previewTitles)
        val clips = draft.timeline(recipe)
        val duration = draft.outputDuration(recipe)
        val audioLayers = StudioAudioLayers(draft, !draft.mute && clips.any { it.volume > 0f })
        val videos = clips.map { source ->
            require(source.startMs >= 0 && source.endMs <= source.durationMs && source.lengthMs >= 1000) { "A timeline clip has an invalid trim." }
            require(File(source.path).isFile) { "A timeline video is missing. Remove it or import it again." }
            val plan = source.speedPlan(recipe)
            val speed = object : SpeedProvider {
                override fun getSpeed(timeUs: Long) = plan.speedAt(timeUs)
                override fun getNextSpeedChangeTimeUs(timeUs: Long) = plan.nextChangeUs(timeUs) ?: C.TIME_UNSET
            }
            val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(source.startMs).setEndPositionMs(source.endMs).build()
            EditedMediaItem.Builder(MediaItem.Builder().setUri(Uri.fromFile(File(source.path))).setClippingConfiguration(clip).build())
                .setDurationUs(source.durationMs * 1000).setRemoveAudio(draft.mute).setFrameRate(30)
                .setSpeed(SpeedParameters(speed, true))
                .setEffects(Effects(if (!draft.mute) listOf(gain(audioLayers.headroom * source.volume)) else emptyList(), buildList {
                    if (!compare && (previewReframe != null || source.frame(recipe) != VideoReframe.Focus()))
                        add(VideoReframe.transform(source.width, source.height, settings.width, settings.height) {
                            previewReframe?.forClip(source.id) ?: source.frame(recipe)
                        })
                    add(Presentation.createForWidthAndHeight(settings.width, settings.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
                })).build()
        }
        val sequence = if (draft.mute) EditedMediaItemSequence.withVideoFrom(videos) else EditedMediaItemSequence.withAudioAndVideoFrom(videos)
        val sequences = mutableListOf(sequence)
        sequences += audioLayers.sequences()
        val boundaries = clips.runningFold(0L) { t, c -> t + c.speedPlan(recipe).outputMs }.drop(1).dropLast(1)
        return Composition.Builder(sequences)
            .setEffects(Effects(emptyList(), if (compare) emptyList() else StudioVisualEffects.create(context, recipe, duration, boundaries, previewTitles)))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
    }
    internal fun gain(volume: Float) = ChannelMixingAudioProcessor().apply {
        for (channels in 1..8) putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(channels, channels).scaleBy(volume))
    }
}
