package com.reelshare.feature.creator

import kotlin.math.roundToLong

/** UTF-16 offsets match Android spans; times are relative to the caption on the output clock. */
internal data class CaptionWord(val from: Int, val until: Int, val startMs: Long, val endMs: Long)
internal data class SpeechWord(val text: String, val startMs: Long, val endMs: Long)

internal object CaptionWords {
    private val token = Regex("\\S+")
    fun tokens(text: String): List<IntRange> = token.findAll(text.take(180)).map { it.range }.toList()

    fun hasSpeechTiming(t: TimedText): Boolean {
        if (!t.caption || t.wordTimings.isEmpty() || t.wordTimings.size > 32 || t.wordTimingText != t.text || t.text.length > 180) return false
        val tokens = tokens(t.text)
        if (tokens.size != t.wordTimings.size) return false
        var end = 0L
        for ((i, word) in t.wordTimings.withIndex()) {
            if (word.from != tokens[i].first || word.until != tokens[i].last + 1 || word.startMs < end ||
                word.endMs <= word.startMs || word.endMs > t.endMs - t.startMs) return false
            end = word.endMs
        }
        return true
    }

    /** SRT/edited captions have no speech alignment. Weight longer words for an estimated reading pace. */
    fun paced(t: TimedText): List<CaptionWord> {
        val tokens = tokens(t.text)
        val total = tokens.sumOf { t.text.codePointCount(it.first, it.last + 1) }.coerceAtLeast(1)
        val duration = (t.endMs - t.startMs).coerceAtLeast(1)
        var used = 0
        return tokens.map { range ->
            val start = duration * used / total
            used += t.text.codePointCount(range.first, range.last + 1)
            CaptionWord(range.first, range.last + 1, start, duration * used / total)
        }
    }

    fun resolved(t: TimedText) = if (hasSpeechTiming(t)) t.wordTimings else paced(t)

    fun fromSpeech(group: List<SpeechWord>, plan: SpeedPlan, offset: Long, id: String): TimedText? {
        if (group.isEmpty()) return null
        val start = plan.sourceToOutputMs(group.first().startMs)
        val end = plan.sourceToOutputMs(group.last().endMs)
        if (end <= start) return null
        val text = group.joinToString(" ") { it.text }
        var character = 0
        val words = group.map { word ->
            CaptionWord(character, character + word.text.length, plan.sourceToOutputMs(word.startMs) - start,
                plan.sourceToOutputMs(word.endMs) - start).also { character += word.text.length + 1 }
        }
        return TimedText(id, text, offset + start, offset + end, animated = false, caption = true,
            wordTimings = words, wordTimingText = text).bounded()
    }

    fun active(words: List<CaptionWord>, ms: Long): Int {
        var low = 0; var high = words.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (words[mid].startMs <= ms) low = mid + 1 else high = mid - 1
        }
        return high.takeIf { it >= 0 && ms < words[it].endMs } ?: -1
    }

    fun editText(t: TimedText, text: String): TimedText = if (text == t.text) t else
        t.copy(text = text.take(180), wordTimings = emptyList(), wordTimingText = "")

    fun retime(t: TimedText, startMs: Long, endMs: Long): TimedText {
        val oldDuration = (t.endMs - t.startMs).coerceAtLeast(1)
        val newDuration = (endMs - startMs).coerceAtLeast(1)
        val updated = t.copy(startMs = startMs, endMs = endMs, wordTimings = t.wordTimings.map { word ->
            word.copy(startMs = (word.startMs.toDouble() * newDuration / oldDuration).roundToLong(),
                endMs = (word.endMs.toDouble() * newDuration / oldDuration).roundToLong())
        })
        return updated.bounded()
    }
}
