package com.reelshare.feature.creator

import android.content.Context
import android.media.MediaRecorder
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

internal data class VoiceoverState(val phase: String = "Ready", val elapsedMs: Long = 0, val level: Float = 0f,
    val error: String = "") {
    val busy get() = phase in listOf("Preparing", "Recording", "Saving")
}

/** Native recorder work is serialized off the UI thread, including stop/release and Room commit. */
internal class VoiceoverRecorder(private val context: Context,
    private val save: suspend (String, File, Long) -> Unit) {
    val state = MutableStateFlow(VoiceoverState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var recorder: MediaRecorder? = null
    private var pending: File? = null
    private var draftId = ""
    private var offset = 0L
    private var started = 0L
    private var meter: Job? = null
    private var closing = false
    private var stopDecision: Pair<Boolean, String>? = null

    fun reset() { if (!state.value.busy) state.value = VoiceoverState() }

    @Synchronized fun start(id: String, startMs: Long, maximumMs: Long) {
        if (state.value.busy || closing) return
        stopDecision = null
        state.value = VoiceoverState("Preparing")
        scope.launch { lock.withLock {
            try {
                require(maximumMs >= 1000) { "Move the playhead earlier to record at least one second." }
                val folder = File(context.cacheDir, "voiceovers").apply { mkdirs() }
                require(folder.usableSpace > 80L * 1024 * 1024) { "Free some storage before recording." }
                // These files are never referenced by Room: a committed take has a private layer copy.
                folder.listFiles()?.filter { it.name.startsWith("take_") }?.forEach { it.delete() }
                pending = File(folder, "take_${UUID.randomUUID()}.m4a")
                draftId = id; offset = startMs
                @Suppress("DEPRECATION")
                val native = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
                recorder = native
                native.setAudioSource(MediaRecorder.AudioSource.MIC)
                native.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                native.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                native.setAudioChannels(1); native.setAudioSamplingRate(44_100); native.setAudioEncodingBitRate(96_000)
                native.setOutputFile(requireNotNull(pending).absolutePath)
                // AAC startup/packet rounding needs a little margin even for a one-second reel.
                // Composition clips the take to the reel; the extra source tail remains editable.
                native.setMaxDuration((maximumMs + 500).coerceIn(2500, 600_000).toInt())
                native.setMaxFileSize(60L * 1024 * 1024)
                native.setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) stop()
                }
                native.setOnErrorListener { _, _, _ -> stop(discard = true, error = "Microphone recording was interrupted. Try another take.") }
                native.prepare(); native.start()
                started = SystemClock.elapsedRealtime()
                state.value = VoiceoverState("Recording")
                meter = scope.launch {
                    while (isActive) {
                        delay(100)
                        lock.withLock {
                            if (state.value.phase == "Recording") state.value = VoiceoverState("Recording",
                                SystemClock.elapsedRealtime() - started, (runCatching { native.maxAmplitude }.getOrDefault(0) / 32768f).coerceIn(0f, 1f))
                        }
                    }
                }
            } catch (e: Exception) {
                release(); pending?.delete(); pending = null
                state.value = VoiceoverState(error = if (e is SecurityException) "Allow microphone access to record a voiceover." else e.message ?: "Could not start the microphone.")
            }
        } }
    }

    @Synchronized fun stop(discard: Boolean = false, error: String = "") {
        if (!state.value.busy || state.value.phase == "Saving" || stopDecision != null) return
        stopDecision = discard to error
        scope.launch { lock.withLock { finish(discard, error) } }
    }

    private suspend fun finish(discard: Boolean, error: String) {
        if (recorder == null) return
        meter?.cancel(); meter = null
        val elapsed = SystemClock.elapsedRealtime() - started
        state.value = VoiceoverState("Saving", elapsed)
        val file = pending
        val stopped = runCatching { recorder?.stop() }.isSuccess
        release(); pending = null
        try {
            if (discard) state.value = VoiceoverState(error = error)
            else {
                require(stopped && elapsed >= 1100 && file != null && file.length() > 0) { "Record a little over one second before saving a take." }
                val reader = MediaMetadataRetriever()
                val duration = try {
                    reader.setDataSource(file.path)
                    reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                } finally { reader.release() }
                require(duration >= 1000) { "This take contains less than one second of audio. Record a little longer and try again." }
                save(draftId, file, offset)
                state.value = VoiceoverState("Saved", elapsed)
            }
        } catch (e: Exception) {
            state.value = VoiceoverState(error = e.message ?: "Could not save this take. Please try again.")
        } finally { file?.delete() }
    }

    private fun release() { recorder?.let { runCatching { it.reset() }; runCatching { it.release() } }; recorder = null }

    @Synchronized fun close() {
        closing = true
        val decision = stopDecision ?: (false to "")
        scope.launch {
            lock.withLock { finish(decision.first, decision.second) }
            scope.cancel()
        }
    }
}
