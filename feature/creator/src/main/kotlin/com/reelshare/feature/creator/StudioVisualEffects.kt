package com.reelshare.feature.creator

import android.content.Context
import android.graphics.*
import androidx.media3.common.Effect
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.effect.*
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object StudioVisualEffects {
    fun create(context: Context, r: EditRecipe, duration: Long, boundaries: List<Long>, previewTitles: PreviewTitles? = null): List<Effect> = buildList {
        val (b, c, sat, w) = ColorTreatment.from(r)
        if (b != 0f) add(Brightness(b))
        if (c != 0f) add(Contrast(c))
        if (sat != 0f) add(HslAdjustment.Builder().adjustSaturation(sat * 100).build())
        if (w != 0f) add(RgbAdjustment.Builder().setRedScale(1 + w * .2f).setBlueScale(1 - w * .2f).build())
        if (r.motion) add(object : MatrixTransformation {
            private val matrix = Matrix()
            override fun getMatrix(presentationTimeUs: Long): Matrix {
                val t = MotionTiming.progress(presentationTimeUs / 1000, duration, r.smooth)
                val zoom = r.zoomStart + (r.zoomEnd - r.zoomStart) * t
                val pan = r.panStart + (r.panEnd - r.panStart) * t
                matrix.setScale(zoom, zoom); matrix.postTranslate(pan * (zoom - 1), 0f)
                return matrix
            }
        })
        val fonts = StudioTextFonts(context)
        val overlays = previewTitles?.overlays ?: buildList {
            r.texts.filter { !it.caption && it.text.isNotBlank() }.take(8).forEach { text -> add(StudioTextOverlay({ listOf(text) }, fonts)) }
            val captions = r.texts.filter { it.caption && it.text.isNotBlank() }.sortedBy { it.startMs }
            if (captions.isNotEmpty()) add(StudioTextOverlay({ captions }, fonts))
        }
        if (overlays.isNotEmpty()) add(OverlayEffect(overlays))
        if (r.fadeInMs > 0 || r.fadeOutMs > 0 || r.transition != "Cut" && boundaries.isNotEmpty()) add(object : RgbMatrix {
            private val matrix = FloatArray(16).apply { this[15] = 1f }
            override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
                val ms = presentationTimeUs / 1000
                var scale = MotionTiming.envelope(ms, duration, r.fadeInMs, r.fadeOutMs)
                var white = 0f
                if (r.transition != "Cut") for (boundary in boundaries) {
                    val transition = (abs(ms - boundary).toFloat() / (r.transitionMs / 2f)).coerceIn(0f, 1f)
                    scale *= transition
                    if (r.transition == "Fade white") white = maxOf(white, 1 - transition)
                }
                matrix[0] = scale; matrix[5] = scale; matrix[10] = scale
                matrix[12] = white; matrix[13] = white; matrix[14] = white
                return matrix
            }
        })
    }
}

/** Shared color decisions for GPU playback/export and the small filter swatches. */
internal data class ColorTreatment(val brightness: Float, val contrast: Float, val saturation: Float, val warmth: Float) {
    companion object {
        fun from(r: EditRecipe): ColorTreatment {
            val s = r.strength
            return ColorTreatment(
                (r.brightness + if (r.filter == "Soft") .06f * s else 0f).coerceIn(-1f, 1f),
                (r.contrast + when (r.filter) { "Soft" -> -.2f * s; "Punch" -> .25f * s; else -> 0f }).coerceIn(-.95f, .95f),
                (r.saturation + when (r.filter) { "Punch" -> .3f * s; "Soft" -> -.15f * s; else -> 0f }).coerceIn(-1f, 1f),
                (r.warmth + when (r.filter) { "Amber" -> .5f * s; "Arctic" -> -.5f * s; "Soft" -> .12f * s; else -> 0f }).coerceIn(-1f, 1f)
            )
        }
    }
}

/** Stable overlay slots allow typing/timing changes without stopping the decoder or audio clock. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PreviewTitles(context: Context) {
    private data class Snapshot(val titles: List<List<TimedText>>, val captions: List<TimedText>)
    @Volatile private var snapshot = Snapshot(List(8) { emptyList() }, emptyList())
    private val fonts = StudioTextFonts(context)
    val overlays: List<TextureOverlay> = List(8) { i -> StudioTextOverlay({ snapshot.titles[i] }, fonts) } +
        StudioTextOverlay({ snapshot.captions }, fonts)
    fun update(texts: List<TimedText>) {
        val titles = texts.filter { !it.caption && it.text.isNotBlank() }.take(8)
        snapshot = Snapshot(List(8) { i -> titles.getOrNull(i)?.let(::listOf) ?: emptyList() },
            texts.filter { it.caption && it.text.isNotBlank() }.sortedBy { it.startMs })
    }
}

/** One cached caption texture, rather than a bitmap for every subtitle at once. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class StudioTextOverlay(private val entries: () -> List<TimedText>, private val fonts: StudioTextFonts) : BitmapOverlay() {
    private var frameWidth = 480
    private var frameHeight = 854
    private var lastEntry: TimedText? = null
    private var timingEntry: TimedText? = null
    private var words: List<CaptionWord> = emptyList()
    private var lastWord = -1
    private var bitmap: Bitmap? = null
    private var rasterKey: TimedText? = null
    private var raster: TextPlacement.Raster? = null
    private val empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    override fun configure(videoSize: Size) {
        if (frameWidth != videoSize.width) { lastEntry = null; bitmap = null; rasterKey = null; lastWord = -1 }
        frameWidth = videoSize.width
        frameHeight = videoSize.height
    }
    private fun at(us: Long) = entries().firstOrNull { us / 1000 >= it.startMs && us / 1000 < it.endMs }
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val entry = at(presentationTimeUs) ?: return empty
        val timingKey = entry.copy(x = null, y = null)
        if (timingEntry != timingKey) {
            words = if (entry.caption && entry.wordHighlight) CaptionWords.resolved(entry) else emptyList()
            timingEntry = timingKey
        }
        val active = CaptionWords.active(words, presentationTimeUs / 1000 - entry.startMs)
        val texture = textureKey(entry)
        if (lastEntry != texture || lastWord != active) {
            bitmap = TextPlacement.draw(entry, measure(entry), words.getOrNull(active)); lastEntry = texture; lastWord = active
        }
        return requireNotNull(bitmap)
    }
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val t = at(presentationTimeUs) ?: return StaticOverlaySettings.Builder().setAlphaScale(0f).build()
        val placement = TextPlacement.place(t, frameWidth, frameHeight, measure(t))
        val motion = TextAnimation.at(t, presentationTimeUs / 1000)
        val halfHeight = placement.rect.height() * motion.scale / 2
        val centerY = (placement.rect.centerY() + motion.offsetY).coerceIn(halfHeight, 1 - halfHeight)
        return StaticOverlaySettings.Builder().setBackgroundFrameAnchor(placement.rect.centerX() * 2 - 1, 1 - centerY * 2)
            .setOverlayFrameAnchor(0f, 0f).setScale(placement.scale * motion.scale, placement.scale * motion.scale).setAlphaScale(motion.alpha).build()
    }
    private fun measure(t: TimedText): TextPlacement.Raster {
        val key = textureKey(t)
        if (rasterKey != key) { raster = TextPlacement.raster(t, fonts.get(t.font), frameWidth); rasterKey = key }
        return requireNotNull(raster)
    }
    private fun textureKey(t: TimedText) = t.copy(startMs = 0, endMs = 100, position = "Bottom", x = null, y = null,
        animated = false, animation = "None", animationMs = 150, wordTimings = emptyList(), wordTimingText = "")
    // Media3 may release/recreate a shader at a clip boundary with the same effect.
    // Keep its cached bitmaps valid; BitmapOverlay releases GL textures, GC owns bitmaps.
}
