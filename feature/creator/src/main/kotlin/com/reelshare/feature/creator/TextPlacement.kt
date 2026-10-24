package com.reelshare.feature.creator

import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Preview hit boxes and exported overlays use the same font measurement and frame coordinates. */
internal object TextPlacement {
    data class Raster(val layout: StaticLayout, val padding: Int, val width: Int, val height: Int,
        val baseWidth: Int, val baseHeight: Int, val rotation: Float, val text: SpannableString)
    data class Placement(val rect: RectF, val scale: Float)

    fun raster(t: TimedText, font: Typeface, frameWidth: Int): Raster {
        val padding = (frameWidth * .035f).toInt().coerceAtLeast(2)
        val maxWidth = (frameWidth * .82f).toInt().coerceAtLeast(padding * 2 + 1)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font; textSize = frameWidth * t.size
            color = when (t.color) { "Yellow" -> android.graphics.Color.rgb(255, 222, 89); "Blue" -> android.graphics.Color.rgb(167, 190, 255); else -> android.graphics.Color.WHITE }
            if (t.style == "Label") color = Color.rgb(22, 25, 33)
            if (t.style == "Shadow" || t.style == "Classic" && !t.background)
                setShadowLayer((frameWidth * .006f).coerceAtLeast(1f), 0f, (frameWidth * .003f).coerceAtLeast(1f), Color.BLACK)
        }
        val textWidth = ceil(Layout.getDesiredWidth(t.text, paint)).toInt().coerceIn(1, maxWidth - padding * 2)
        val text = SpannableString(t.text)
        val layout = StaticLayout.Builder.obtain(text, 0, t.text.length, paint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setMaxLines(6)
            .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        val baseWidth = textWidth + padding * 2
        val baseHeight = layout.height + padding * 2
        val rotation = if (t.rotation.isFinite()) t.rotation.coerceIn(-180f, 180f) else 0f
        val radians = Math.toRadians(rotation.toDouble())
        val c = abs(cos(radians)); val s = abs(sin(radians))
        return Raster(layout, padding, ceil(baseWidth * c + baseHeight * s - .00001).toInt(),
            ceil(baseWidth * s + baseHeight * c - .00001).toInt(), baseWidth, baseHeight, rotation, text)
    }

    /** Rotation is baked into a snug transparent bitmap, shared by preview, gallery and export. */
    fun draw(t: TimedText, raster: Raster, word: CaptionWord? = null): Bitmap = Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888).also { image ->
        val text = raster.text
        text.getSpans(0, text.length, BackgroundColorSpan::class.java).forEach(text::removeSpan)
        text.getSpans(0, text.length, ForegroundColorSpan::class.java).forEach(text::removeSpan)
        if (word != null && word.from >= 0 && word.until <= text.length && word.from < word.until) {
            val fill = when (t.highlightColor) {
                "Blue" -> Color.rgb(167, 190, 255); "White" -> Color.WHITE; else -> Color.rgb(255, 222, 89)
            }
            text.setSpan(BackgroundColorSpan(fill), word.from, word.until, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.setSpan(ForegroundColorSpan(Color.rgb(22, 25, 33)), word.from, word.until, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val canvas = Canvas(image)
        canvas.translate(image.width / 2f, image.height / 2f)
        canvas.rotate(raster.rotation)
        canvas.translate(-raster.baseWidth / 2f, -raster.baseHeight / 2f)
        if (t.style == "Label" || t.style == "Classic" && t.background) {
            val color = if (t.style == "Label") when (t.color) {
                "Yellow" -> Color.rgb(255, 222, 89); "Blue" -> Color.rgb(167, 190, 255); else -> Color.WHITE
            } else 0xB8000000.toInt()
            canvas.drawRoundRect(0f, 0f, raster.baseWidth.toFloat(), raster.baseHeight.toFloat(),
                raster.padding.toFloat(), raster.padding.toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        }
        canvas.translate(raster.padding.toFloat(), raster.padding.toFloat())
        val paint = raster.layout.paint
        if (t.style == "Outline") {
            val original = paint.color
            paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = (paint.textSize * .10f).coerceAtLeast(1f); paint.color = Color.BLACK
            raster.layout.draw(canvas)
            paint.style = Paint.Style.FILL; paint.color = original
        }
        raster.layout.draw(canvas)
    }

    fun place(t: TimedText, width: Int, height: Int, raster: Raster): Placement {
        val scale = minOf(1f, width * .9f / raster.width, height * .9f / raster.height)
        val w = raster.width * scale / width
        val h = raster.height * scale / height
        val legacyY = when (t.position) { "Top" -> .11f + h / 2; "Center" -> .5f; else -> .85f - h / 2 }
        val x = (t.x ?: .5f).coerceIn(w / 2, 1 - w / 2)
        val y = (t.y ?: legacyY).coerceIn(h / 2, 1 - h / 2)
        return Placement(RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), scale)
    }

    fun move(t: TimedText, dx: Float, dy: Float, width: Int, height: Int, raster: Raster): TimedText {
        val current = place(t, width, height, raster).rect
        val next = t.copy(x = current.centerX() + dx, y = current.centerY() + dy)
        val clamped = place(next, width, height, raster).rect
        return next.copy(x = clamped.centerX(), y = clamped.centerY())
    }
}
