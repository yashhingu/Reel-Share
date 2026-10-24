package com.reelshare.feature.creator

import kotlin.math.roundToInt

data class ExportSettings(val width: Int, val height: Int, val bitrate: Int) {
    companion object {
        fun forVideo(width: Int, height: Int, aspect: String, quality: String, lowRam: Boolean): ExportSettings {
            val ratio = when (aspect) { "9:16" -> 9f / 16; "1:1" -> 1f; "16:9" -> 16f / 9; "4:5" -> 4f / 5
                else -> width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1) }
            val requested = when (quality) { "Small" -> 480; "High" -> 1080; else -> 720 }
            val sourceRatio = width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1)
            val croppedWidth = if (sourceRatio > ratio) height * ratio else width.toFloat()
            val croppedHeight = if (sourceRatio > ratio) height.toFloat() else width / ratio
            val longEdgeLimit = if (ratio <= 1) 1920 * ratio else 1920 / ratio
            val edge = minOf(requested.toFloat(), if (lowRam) 480f else 1080f,
                minOf(croppedWidth, croppedHeight), longEdgeLimit).toInt().coerceAtLeast(2)
            fun even(value: Float) = ((value / 2).roundToInt() * 2).coerceAtLeast(2)
            val w = if (ratio <= 1) edge else even(edge * ratio)
            val h = if (ratio <= 1) even(edge / ratio) else edge
            val bitrate = when { edge <= 480 -> 1_500_000; edge <= 720 -> 3_000_000; else -> 6_000_000 }
            return ExportSettings(even(w.toFloat()), even(h.toFloat()), bitrate)
        }
    }
}
