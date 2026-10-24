package com.reelshare.feature.creator

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteOrder

/** Decode in small buffers on an IO worker, with precise source trim and prompt cancellation. */
internal object CaptionAudioDecoder {
    suspend fun read(clip: VideoClip, accept: (ShortArray) -> Unit, progress: suspend (Long) -> Unit) = decode(clip, false, accept, progress)
    // Waveforms use the strongest channel so opposite-phase stereo does not appear silent.
    suspend fun readPeaks(clip: VideoClip, accept: (ShortArray) -> Unit) = decode(clip, true, accept, {})
    private suspend fun decode(clip: VideoClip, peakChannels: Boolean, accept: (ShortArray) -> Unit, progress: suspend (Long) -> Unit) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(clip.path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return // Silent clips do not need fabricated captions.
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            // Codec delay applies again after seeking. Decode a short preroll, then
            // discard by timestamp so the requested start does not lose speech.
            extractor.seekTo((clip.startMs - 250).coerceAtLeast(0) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler = MonoResampler(sampleRate)
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            while (!outputEnded) {
                currentCoroutineContext().ensureActive()
                if (!inputEnded) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(i))
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0 || extractor.sampleTime >= clip.endMs * 1000) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true
                        } else { decoder.queueInputBuffer(i, 0, count, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                when (val i = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(channels in 1..8) { "This recording's audio channel layout isn't supported for captions." }
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) { "This audio format isn't supported for captions." }
                        resampler = MonoResampler(sampleRate)
                    }
                    else -> if (i >= 0) {
                        try {
                            val bytes = requireNotNull(decoder.getOutputBuffer(i)).order(ByteOrder.LITTLE_ENDIAN)
                            bytes.position(info.offset); bytes.limit(info.offset + info.size)
                            val frameBytes = channels * if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frames = info.size / frameBytes
                            val mono = FloatArray(frames)
                            var count = 0
                            for (frame in 0 until frames) {
                                var sample = 0f
                                repeat(channels) {
                                    val channel = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) bytes.float else bytes.short / 32768f
                                    sample = if (peakChannels) maxOf(sample, kotlin.math.abs(channel)) else sample + channel
                                }
                                val timeUs = info.presentationTimeUs + frame * 1_000_000L / sampleRate
                                if (timeUs >= clip.startMs * 1000 && timeUs < clip.endMs * 1000) mono[count++] = if (peakChannels) sample else sample / channels
                            }
                            if (count > 0) accept(resampler.process(mono.copyOf(count)))
                            progress((info.presentationTimeUs / 1000 - clip.startMs).coerceIn(0, clip.lengthMs))
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally { decoder.releaseOutputBuffer(i, false) }
                    }
                }
            }
        } finally {
            codec?.let { runCatching { it.stop() }; it.release() }; extractor.release()
        }
    }
}
