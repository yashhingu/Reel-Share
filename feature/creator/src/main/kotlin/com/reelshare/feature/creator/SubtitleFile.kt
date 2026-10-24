package com.reelshare.feature.creator

import java.util.Locale

internal object SubtitleFile {
    private val timing = Regex("(\\d{1,3}):(\\d{2}):(\\d{2})[,.](\\d{3})")
    fun parse(input: String, durationMs: Long): List<TimedText> {
        require(input.length <= 1_000_000) { "Choose an SRT file smaller than 1 MB." }
        val result = input.removePrefix("\uFEFF").replace("\r\n", "\n").trim().split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.lines()
            val index = lines.indexOfFirst { "-->" in it }
            if (index < 0) return@mapNotNull null
            val matches = timing.findAll(lines[index]).toList()
            if (matches.size != 2) return@mapNotNull null
            fun ms(m: MatchResult) = m.groupValues.drop(1).map(String::toLong).let { (h, m, s, milli) -> (h * 3600 + m * 60 + s) * 1000 + milli }
            val start = ms(matches[0]).coerceAtLeast(0)
            val end = ms(matches[1]).coerceAtMost(durationMs)
            val text = lines.drop(index + 1).joinToString("\n").replace(Regex("<[^>]*>"), "").trim()
            if (start >= end || text.isBlank()) null else TimedText("srt_${start}_${index}_${block.hashCode()}", text, start, end, animated = false, caption = true).bounded()
        }.sortedBy { it.startMs }
        require(result.size <= 192) { "Import at most 192 captions in one reel." }
        require(result.isNotEmpty()) { "No valid captions within this reel's duration were found." }
        require(result.zipWithNext().none { (a, b) -> a.endMs > b.startMs }) { "Caption times overlap. Fix the SRT file and import it again." }
        return result
    }
    fun write(entries: List<TimedText>): String = entries.sortedBy { it.startMs }.mapIndexed { index, t ->
        "${index + 1}\n${stamp(t.startMs)} --> ${stamp(t.endMs)}\n${t.text}\n"
    }.joinToString("\n")
    private fun stamp(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
}

/** Streaming mono resampling; chunk boundaries do not reset the audio clock. */
internal class MonoResampler(private val sourceRate: Int, private val targetRate: Int = 16000) {
    init { require(sourceRate in 8000..192000 && targetRate > 0) }
    private var index = 0L
    private var next = 0.0
    private var previous = 0f
    fun process(samples: FloatArray): ShortArray {
        val output = ShortArray((samples.size.toDouble() * targetRate / sourceRate).toInt() + 4)
        var count = 0
        for (value in samples) {
            while (next <= index) {
                val sample = if (index == 0L) value else previous + (value - previous) * (next - (index - 1)).toFloat()
                output[count++] = (sample.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                next += sourceRate.toDouble() / targetRate
            }
            previous = value; index++
        }
        return output.copyOf(count)
    }
}
