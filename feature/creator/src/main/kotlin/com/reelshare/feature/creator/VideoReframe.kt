package com.reelshare.feature.creator

import android.graphics.Matrix
import androidx.media3.effect.MatrixTransformation

/** Pan is normalized against the available source area, so the crop never exposes an empty edge. */
internal object VideoReframe {
    data class Focus(val zoom: Float = 1f, val x: Float = 0f, val y: Float = 0f)
    fun visible(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int): Pair<Float, Float> {
        val source = sourceWidth.coerceAtLeast(1).toFloat() / sourceHeight.coerceAtLeast(1)
        val output = outputWidth.coerceAtLeast(1).toFloat() / outputHeight.coerceAtLeast(1)
        return minOf(1f, output / source) to minOf(1f, source / output)
    }
    fun move(focus: Focus, dx: Float, dy: Float, sourceWidth: Int, sourceHeight: Int,
             outputWidth: Int, outputHeight: Int): Focus {
        val (visibleX, visibleY) = visible(sourceWidth, sourceHeight, outputWidth, outputHeight)
        val roomX = focus.zoom - visibleX
        val roomY = focus.zoom - visibleY
        return focus.copy(
            x = if (roomX < .0001f) 0f else (focus.x + 2f * dx * visibleX / roomX).coerceIn(-1f, 1f),
            y = if (roomY < .0001f) 0f else (focus.y + 2f * dy * visibleY / roomY).coerceIn(-1f, 1f))
    }
    fun transform(sourceWidth: Int, sourceHeight: Int, outputWidth: Int, outputHeight: Int,
                  current: () -> Focus): MatrixTransformation {
        val (visibleX, visibleY) = visible(sourceWidth, sourceHeight, outputWidth, outputHeight)
        return object : MatrixTransformation {
            private val matrix = Matrix()
            override fun getMatrix(presentationTimeUs: Long): Matrix {
                val focus = current()
                matrix.setScale(focus.zoom, focus.zoom)
                matrix.postTranslate(focus.x * (focus.zoom - visibleX), -focus.y * (focus.zoom - visibleY))
                return matrix
            }
        }
    }
}

internal class PreviewReframe(initial: VideoReframe.Focus) {
    @Volatile var focus: VideoReframe.Focus = initial
    @Volatile var clips: Map<String, VideoReframe.Focus> = emptyMap()
    fun forClip(id: String) = clips[id] ?: focus
}
