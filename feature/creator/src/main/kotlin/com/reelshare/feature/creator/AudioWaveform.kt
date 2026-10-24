package com.reelshare.feature.creator

import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Streaming PCM analysis: at most one decoder and 8,192 retained peaks per source. */
internal object AudioWaveforms {
    private val lock = Mutex()
    private val cache = LruCache<String, FloatArray>(24)
    suspend fun read(path: String, durationMs: Long): FloatArray = withContext(Dispatchers.IO) {
        val file = File(path)
        val key = "$path:${file.length()}:${file.lastModified()}:$durationMs"
        lock.withLock {
            cache.get(key)?.let { return@withLock it }
            withTimeout(30_000) {
                val peaks = FloatArray((durationMs / 20).coerceIn(256, 8192).toInt())
                val samples = (durationMs * 16).coerceAtLeast(1)
                var index = 0L
                CaptionAudioDecoder.readPeaks(VideoClip("waveform", path, "Audio", durationMs, 0, durationMs, 1, 1), { pcm ->
                    pcm.forEach { sample ->
                        val bin = (index++ * peaks.size / samples).toInt().coerceIn(peaks.indices)
                        peaks[bin] = maxOf(peaks[bin], abs(sample.toInt()) / 32768f)
                    }
                })
                cache.put(key, peaks)
                peaks
            }
        }
    }
}

internal data class WaveformState(val peaks: FloatArray? = null, val status: String = "Reading waveform...")

@Composable
internal fun rememberAudioWaveform(path: String, durationMs: Long): WaveformState {
    val result by produceState(WaveformState(), path, durationMs) {
        value = WaveformState()
        try { value = WaveformState(AudioWaveforms.read(path, durationMs), "") }
        catch (e: CancellationException) {
            if (e is TimeoutCancellationException) value = WaveformState(status = "Waveform took too long. Editing is still available.")
            else throw e
        } catch (_: Exception) { value = WaveformState(status = "Waveform unavailable. Editing is still available.") }
    }
    return result
}

@Composable
internal fun AudioWaveform(path: String, durationMs: Long, range: ClosedFloatingPointRange<Float>) {
    val (peaks, status) = rememberAudioWaveform(path, durationMs)
    val base = MaterialTheme.colorScheme.outlineVariant
    val selected = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(52.dp).semantics { contentDescription = "Audio waveform; selected ${audioTime(range.start.toLong())} to ${audioTime(range.endInclusive.toLong())}" }) {
        val left = size.width * range.start / durationMs.coerceAtLeast(1)
        val right = size.width * range.endInclusive / durationMs.coerceAtLeast(1)
        drawRect(selected.copy(alpha = .1f), Offset(left, 0f), Size((right - left).coerceAtLeast(0f), size.height))
        drawLine(base, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        peaks?.let { data ->
            val count = minOf(data.size, (size.width / 3.dp.toPx()).toInt().coerceAtLeast(1))
            repeat(count) { i ->
                val from = i * data.size / count
                val until = ((i + 1) * data.size / count).coerceAtLeast(from + 1)
                var peak = 0f
                for (j in from until until) peak = maxOf(peak, data[j])
                val x = (i + .5f) * size.width / count
                val h = sqrt(peak) * size.height * .45f
                drawLine(if (x in left..right) selected else base, Offset(x, size.height / 2 - h), Offset(x, size.height / 2 + h), 2.dp.toPx())
            }
        }
    }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.labelSmall, color = Muted)
}
