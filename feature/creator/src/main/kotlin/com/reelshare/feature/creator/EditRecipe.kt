package com.reelshare.feature.creator

import kotlin.math.roundToLong

/** Versioned edit decisions. Times are milliseconds on the final, speed-adjusted timeline. */
internal data class EditRecipe(
    val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f,
    val warmth: Float = 0f, val filter: String = "None", val strength: Float = 1f,
    val speed: Float = 1f, val ramp: Boolean = false, val rampEnd: Float = 1f,
    val fadeInMs: Long = 0, val fadeOutMs: Long = 0,
    val motion: Boolean = false, val zoomStart: Float = 1f, val zoomEnd: Float = 1.2f,
    val panStart: Float = 0f, val panEnd: Float = 0f, val smooth: Boolean = true,
    val transition: String = "Cut", val transitionMs: Long = 500,
    val texts: List<TimedText> = emptyList(), val clips: List<VideoClip> = emptyList(),
    val collage: Collage? = null, val audioTracks: List<AudioTrack> = emptyList(), val audioMix: AudioMix = AudioMix(),
    val frameZoom: Float = 1f, val frameX: Float = 0f, val frameY: Float = 0f,
    val musicTracks: List<AudioTrack> = emptyList()
) {
    fun bounded() = copy(brightness = brightness.safe(-1f, 1f), contrast = contrast.safe(-.8f, .8f),
        saturation = saturation.safe(-1f, 1f), warmth = warmth.safe(-1f, 1f),
        filter = filter.takeIf { it in FILTERS } ?: "None", strength = strength.safe(0f, 1f),
        speed = speed.safe(.25f, 4f, 1f), rampEnd = rampEnd.safe(.25f, 4f, 1f),
        fadeInMs = fadeInMs.coerceIn(0, 2000), fadeOutMs = fadeOutMs.coerceIn(0, 2000),
        zoomStart = zoomStart.safe(1f, 2f, 1f), zoomEnd = zoomEnd.safe(1f, 2f, 1f),
        panStart = panStart.safe(-1f, 1f), panEnd = panEnd.safe(-1f, 1f),
        frameZoom = frameZoom.safe(1f, 2f, 1f), frameX = frameX.safe(-1f, 1f), frameY = frameY.safe(-1f, 1f),
        transition = transition.takeIf { it in TRANSITIONS } ?: "Cut", transitionMs = transitionMs.coerceIn(100, 1500),
        texts = texts.take(200).map { it.bounded() }, clips = clips.take(12).map { it.bounded() }, collage = collage?.bounded(),
        audioTracks = audioTracks.take(8).filter { it.path.isNotBlank() && it.durationMs >= 1000 }.map { it.bounded() }, audioMix = audioMix.bounded(),
        musicTracks = musicTracks.take(8).filter { it.path.isNotBlank() && it.durationMs >= 1000 }.map { it.bounded() })

    // Styles carry a visual treatment, never someone's footage or private caption text.
    fun style() = copy(texts = emptyList(), clips = emptyList(), collage = null, audioTracks = emptyList(), musicTracks = emptyList(), audioMix = AudioMix(),
        speed = 1f, ramp = false, rampEnd = 1f, frameZoom = 1f, frameX = 0f, frameY = 0f)

    companion object {
        val FILTERS = listOf("None", "Amber", "Arctic", "Soft", "Punch")
        val TRANSITIONS = listOf("Cut", "Fade black", "Fade white")
    }
}

internal data class TimedText(val id: String, val text: String, val startMs: Long, val endMs: Long,
    val position: String = "Bottom", val size: Float = .065f, val color: String = "White",
    val background: Boolean = true, val animated: Boolean = true, val caption: Boolean = false,
    val x: Float? = null, val y: Float? = null, val rotation: Float = 0f,
    val font: String = "Bold", val style: String = "Classic", val animation: String = "", val animationMs: Long = 150,
    val wordHighlight: Boolean = false, val highlightColor: String = "Yellow",
    val wordTimings: List<CaptionWord> = emptyList(), val wordTimingText: String = "") {
    fun bounded(): TimedText {
        val speech = CaptionWords.hasSpeechTiming(this)
        return copy(text = text.take(180), startMs = startMs.coerceAtLeast(0),
        endMs = endMs.coerceAtLeast(startMs.coerceAtLeast(0) + 100),
        position = position.takeIf { it in listOf("Top", "Center", "Bottom") } ?: "Bottom",
        x = x?.takeIf { it.isFinite() }?.coerceIn(0f, 1f), y = y?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        size = size.safe(.035f, .12f, .065f), color = color.takeIf { it in listOf("White", "Yellow", "Blue") } ?: "White",
        rotation = rotation.safe(-180f, 180f), font = font.takeIf { it in FONTS } ?: "Bold",
        style = style.takeIf { it in STYLES } ?: "Classic", animation = animation.takeIf { it.isEmpty() || it in ANIMATIONS } ?: "",
        animationMs = animationMs.coerceIn(100, 800), wordHighlight = wordHighlight && caption,
        highlightColor = highlightColor.takeIf { it in listOf("Yellow", "Blue", "White") } ?: "Yellow",
        wordTimings = if (speech) wordTimings else emptyList(), wordTimingText = if (speech) wordTimingText else "")
    }

    val animationName: String get() = animation.ifEmpty { if (animated) "Fade" else "None" }

    companion object {
        val FONTS = listOf("Bold", "Simple", "Serif", "Typewriter")
        val STYLES = listOf("Classic", "Shadow", "Outline", "Label")
        val ANIMATIONS = listOf("None", "Fade", "Pop", "Slide")
    }
}

internal data class VideoClip(val id: String, val path: String, val name: String, val durationMs: Long,
    val startMs: Long, val endMs: Long, val width: Int, val height: Int,
    val speed: Float? = null, val volume: Float = 1f,
    val frameZoom: Float? = null, val frameX: Float = 0f, val frameY: Float = 0f,
    val rampStartMs: Long? = null, val rampEndMs: Long? = null) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(1)
    fun bounded() = copy(speed = speed?.safe(.25f, 4f, 1f), volume = volume.safe(0f, 1f, 1f),
        frameZoom = frameZoom?.safe(1f, 2f, 1f), frameX = frameX.safe(-1f, 1f), frameY = frameY.safe(-1f, 1f))
    fun speedPlan(recipe: EditRecipe): SpeedPlan {
        val pace = speed?.let { recipe.copy(speed = it, ramp = false) } ?: recipe
        val start = rampStartMs
        val end = rampEndMs
        return if (pace.ramp && start != null && end != null && start >= 0 &&
            start <= startMs && endMs <= end && end <= durationMs && end > start)
            SpeedPlan(lengthMs, pace, end - start, startMs - start)
        else SpeedPlan(lengthMs, pace)
    }
    fun frame(recipe: EditRecipe) = if (frameZoom == null) VideoReframe.Focus(recipe.frameZoom, recipe.frameX, recipe.frameY)
        else VideoReframe.Focus(frameZoom, frameX, frameY)
}

/** A bounded, piecewise speed ramp; shared with caption timing and soundtrack duration. */
internal class SpeedPlan(val sourceMs: Long, recipe: EditRecipe, originMs: Long = sourceMs, offsetMs: Long = 0) {
    private val r = recipe.copy(speed = recipe.speed.safe(.25f, 4f, 1f), rampEnd = recipe.rampEnd.safe(.25f, 4f, 1f))
    private val originCount = if (r.ramp) minOf(24, (originMs / 100).toInt().coerceAtLeast(1)) else 1
    private val originBoundaries = LongArray(originCount + 1) { originMs * 1000 * it / originCount }
    val boundaries: LongArray = if (r.ramp && (originMs != sourceMs || offsetMs != 0L)) {
        val from = offsetMs * 1000
        val to = from + sourceMs * 1000
        (listOf(0L) + originBoundaries.filter { it > from && it < to }.map { it - from } + listOf(sourceMs * 1000)).toLongArray()
    } else LongArray(originCount + 1) { sourceMs * 1000 * it / originCount }
    val count = boundaries.size - 1
    val speeds = FloatArray(count) { i ->
        if (!r.ramp) r.speed else {
            val midpoint = offsetMs * 1000 + (boundaries[i] + boundaries[i + 1]) / 2
            val originalIndex = (originBoundaries.binarySearch(midpoint).let { if (it >= 0) it else -it - 2 }).coerceIn(0, originCount - 1)
            r.speed + (r.rampEnd - r.speed) * (originalIndex + .5f) / originCount
        }
    }
    val outputMs: Long get() = sourceToOutputMs(sourceMs)
    fun sourceToOutputMs(ms: Long): Long {
        val until = ms.coerceIn(0, sourceMs) * 1000
        var out = 0.0
        for (i in 0 until count) {
            out += ((until.coerceAtMost(boundaries[i + 1]) - boundaries[i]).coerceAtLeast(0)) / speeds[i].toDouble()
        }
        return (out / 1000).roundToLong()
    }
    fun speedAt(us: Long): Float = speeds[index(us)]
    fun outputToSourceMs(ms: Long): Long {
        if (ms <= 0) return 0
        if (ms >= outputMs) return sourceMs
        var remainingUs = ms * 1000.0
        for (i in 0 until count) {
            val outputUs = (boundaries[i + 1] - boundaries[i]) / speeds[i].toDouble()
            if (remainingUs <= outputUs) return ((boundaries[i] + remainingUs * speeds[i]) / 1000).roundToLong().coerceIn(0, sourceMs)
            remainingUs -= outputUs
        }
        return sourceMs
    }
    fun nextChangeUs(us: Long): Long? = (index(us) + 1).takeIf { it < count }?.let { boundaries[it] }
    private fun index(us: Long): Int {
        val found = boundaries.binarySearch(us.coerceAtLeast(0))
        return (if (found >= 0) found else -found - 2).coerceIn(0, count - 1)
    }
}

internal object MotionTiming {
    fun progress(ms: Long, duration: Long, smooth: Boolean): Float {
        val t = (ms.toFloat() / duration.coerceAtLeast(1)).coerceIn(0f, 1f)
        return if (smooth) t * t * (3 - 2 * t) else t
    }
    fun envelope(ms: Long, duration: Long, fadeIn: Long, fadeOut: Long): Float {
        val i = fadeIn.coerceAtMost(duration / 2)
        val o = fadeOut.coerceAtMost(duration / 2)
        return minOf(if (i > 0) ms.toFloat() / i else 1f,
            if (o > 0) (duration - ms).toFloat() / o else 1f).coerceIn(0f, 1f)
    }
}

private fun Float.safe(min: Float, max: Float, fallback: Float = 0f) = if (isFinite()) coerceIn(min, max) else fallback
