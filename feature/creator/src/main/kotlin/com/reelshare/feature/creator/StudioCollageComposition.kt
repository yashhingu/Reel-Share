package com.reelshare.feature.creator

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.util.Size
import androidx.media3.effect.*
import androidx.media3.transformer.*
import com.reelshare.core.database.entity.DraftEntity
import java.io.File
import kotlin.math.roundToInt

/** Bounded moving sources; photos and empty cells do not allocate video decoders. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StudioCollageComposition {
    fun create(context: Context, draft: DraftEntity, settings: ExportSettings, compare: Boolean,
        titles: PreviewTitles?): Composition {
        val r = draft.recipe()
        val collage = requireNotNull(r.collage?.bounded()) { "This collage is incomplete." }
        val duration = draft.outputDuration(r)
        val audioLayers = StudioAudioLayers(draft, !draft.mute && collage.audioIndex >= 0)
        val cells = CollageLayout.cells(collage.assets.size, collage.layout, collage.gap)
        val sizes = cells.map { cell -> Size((settings.width * cell.width).roundToInt().coerceAtLeast(2),
            (settings.height * cell.height).roundToInt().coerceAtLeast(2)) }
        val sequences = collage.assets.mapIndexed { index, asset ->
            require(File(asset.path).isFile) { "A collage item is missing. Choose it again." }
            val size = sizes[index]
            val uri = MediaItem.Builder().setUri(Uri.fromFile(File(asset.path)))
            if (asset.image) uri.setImageDurationMs(duration) else {
                require(draft.trimStartMs >= 0 && draft.trimEndMs <= asset.durationMs) { "A collage video has an invalid trim." }
                uri.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(draft.trimStartMs).setEndPositionMs(draft.trimEndMs).build())
            }
            val sound = !asset.image && !draft.mute && collage.audioIndex == index
            val visual = mutableListOf<Effect>(Presentation.createForWidthAndHeight(size.width, size.height,
                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
            if (asset.zoom != 1f || asset.panX != 0f || asset.panY != 0f) visual += object : MatrixTransformation {
                override fun getMatrix(presentationTimeUs: Long) = Matrix().apply {
                    setScale(asset.zoom, asset.zoom)
                    postTranslate(asset.panX * (asset.zoom - 1), -asset.panY * (asset.zoom - 1))
                }
            }
            val item = EditedMediaItem.Builder(uri.build()).setFrameRate(30).setRemoveAudio(!sound)
                .setDurationUs((if (asset.image) duration else asset.durationMs) * 1000)
                .setEffects(Effects(if (sound) listOf(StudioComposition.gain(audioLayers.headroom)) else emptyList(), visual))
            if (!asset.image) {
                val plan = SpeedPlan(draft.trimEndMs - draft.trimStartMs, r)
                item.setSpeed(SpeedParameters(object : androidx.media3.common.audio.SpeedProvider {
                    override fun getSpeed(timeUs: Long) = plan.speedAt(timeUs)
                    override fun getNextSpeedChangeTimeUs(timeUs: Long) = plan.nextChangeUs(timeUs) ?: C.TIME_UNSET
                }, true))
            }
            if (sound) EditedMediaItemSequence.withAudioAndVideoFrom(listOf(item.build()))
            else EditedMediaItemSequence.withVideoFrom(listOf(item.build()))
        }.toMutableList()
        sequences += audioLayers.sequences()
        val compositor = object : VideoCompositorSettings {
            override fun getOutputSize(inputSizes: List<Size>) = Size(settings.width, settings.height)
            override fun getOverlaySettings(inputId: Int, presentationTimeUs: Long): OverlaySettings {
                val cell = cells[inputId]
                return StaticOverlaySettings.Builder().setBackgroundFrameAnchor(cell.centerX * 2 - 1, 1 - cell.centerY * 2).build()
            }
        }
        return Composition.Builder(sequences).setVideoCompositorSettings(compositor)
            .setEffects(Effects(emptyList(), if (compare) emptyList() else StudioVisualEffects.create(context, r, duration, emptyList(), titles)))
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build()
    }
}
