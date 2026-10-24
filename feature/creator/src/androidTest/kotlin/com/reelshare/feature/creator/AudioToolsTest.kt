package com.reelshare.feature.creator

import android.media.MediaMetadataRetriever
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.reelshare.core.database.ReelDatabase
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioToolsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
    private fun fixture(name: String) = File(context.cacheDir, name).also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @Test fun waveformShowsActualSilenceAndToneAndCorruptionIsRecoverable() = runBlocking {
        val audio = fixture("audio-waveform-fixture.m4a")
        val bad = File(context.cacheDir, "invalid-waveform").apply { writeText("not audio") }
        try {
            val peaks = AudioWaveforms.read(audio.path, 4000)
            assertEquals(256, peaks.size)
            assertTrue("Leading silence", peaks.take(55).max() < .02f)
            assertTrue("Tone must be visible", peaks.slice(90..170).min() > .08f)
            assertTrue("Trailing silence", peaks.takeLast(45).max() < .02f)
            assertSame(peaks, AudioWaveforms.read(audio.path, 4000))
            assertTrue(runCatching { AudioWaveforms.read(bad.path, 4000) }.isFailure)
            assertSame(peaks, AudioWaveforms.read(audio.path, 4000))
        } finally { audio.delete(); bad.delete() }
    }

    @Test fun voiceoverDecisionsAndUndoSurviveRoomReopen() = runBlocking {
        val name = "audio-tools-test.db"
        context.deleteDatabase(name)
        val original = DraftEntity("audio", "/private/source", durationMs = 4000, width = 360, height = 640, trimEndMs = 4000)
        val track = AudioTrack("a", "/private/take", "Voiceover 1", 9000, 1000, 8000, 2000, .6f)
        val edited = DraftHistory.record(original, original.copy(editRecipe = RecipeCodec.encode(EditRecipe(audioTracks = listOf(track)))), 1000)
        try {
            val first = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try { first.studioDao().putDraft(edited) } finally { first.close() }
            val second = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try {
                val saved = second.studioDao().draft("audio")!!
                assertEquals(track, saved.recipe().audioTracks.single())
                val undo = DraftHistory.restore(saved, false)!!
                assertTrue(undo.recipe().audioTracks.isEmpty())
                assertEquals(track, DraftHistory.restore(undo, true)!!.recipe().audioTracks.single())
                assertTrue(RecipeCodec.decode("{}").audioTracks.isEmpty())
            } finally { second.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun timelineWaveformMapsRealTrimmedAudioAtDifferentOffsetsAndZooms() = runBlocking {
        val audio = fixture("audio-waveform-fixture.m4a")
        try {
            val peaks = AudioWaveforms.read(audio.path, 4000)
            for (zoom in listOf(8f, 32f, 64f)) for (offset in listOf(0L, 2500L)) {
                val axis = TimelineAxis(listOf(250, 7750), zoom)
                fun peak(a: Long, b: Long) = TimelineWaveform.peak(peaks, 4000, 500, offset, 3500,
                    axis, axis.xAt(offset + a), axis.xAt(offset + b))
                assertTrue("Trimmed leading silence", peak(0, 250) < .02f)
                assertTrue("Trimmed tone", peak(1000, 1500) > .08f)
                assertTrue("Trimmed trailing silence", peak(3000, 3400) < .02f)
            }
            assertSame("Placement/zoom must reuse decoded data", peaks, AudioWaveforms.read(audio.path, 4000))
        } finally { audio.delete() }
    }

    @Test fun delayedVoiceoverExportsSilenceBeforeOffsetAndStopsWithVideo() = runBlocking {
        val video = fixture("media-fixture.mp4")
        val audio = fixture("audio-waveform-fixture.m4a")
        val output = File(context.cacheDir, "delayed-voiceover-export.mp4").apply { delete() }
        val track = AudioTrack("a", audio.path, "Voice", 4000, 1000, 4000, 1500)
        val draft = DraftEntity("audio-export", video.path, durationMs = 4000, width = 360, height = 640,
            trimEndMs = 4000, mute = true, editRecipe = RecipeCodec.encode(EditRecipe(audioTracks = listOf(track))))
        try {
            export(draft, output)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                assertTrue(abs(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() - 4000) < 150)
            } finally { reader.release() }
            var index = 0L; var quiet = 0L; var loud = 0L; var loudCount = 0L
            CaptionAudioDecoder.read(VideoClip("out", output.path, "Export", 4000, 0, 4000, 360, 640), { samples ->
                samples.forEach { sample ->
                    val ms = index++ / 16
                    if (ms < 1200) quiet = maxOf(quiet, abs(sample.toLong()))
                    if (ms in 1800..3000) { loud += abs(sample.toLong()); loudCount++ }
                }
            }, {})
            assertTrue("Audio leaked before offset: $quiet", quiet < 200)
            assertTrue("Delayed take was silent", loud / loudCount.coerceAtLeast(1) > 1500)
        } finally { video.delete(); audio.delete(); output.delete() }
    }

    @Test fun musicAndMultipleVoiceoverTracksMixWithoutClipping() = runBlocking {
        val video = fixture("media-fixture.mp4")
        val audio = fixture("audio-waveform-fixture.m4a")
        val output = File(context.cacheDir, "mixed-voiceover-export.mp4").apply { delete() }
        val tracks = List(4) { AudioTrack("$it", audio.path, "Take", 4000, 1000, 3000, it * 100L) }
        val draft = DraftEntity("audio-mix", video.path, durationMs = 4000, width = 360, height = 640, trimEndMs = 4000,
            audioPath = audio.path, audioDurationMs = 4000, audioStartMs = 1000, audioEndMs = 3000, audioVolume = 1f,
            editRecipe = RecipeCodec.encode(EditRecipe(audioTracks = tracks)))
        try {
            assertEquals(1f / 6, StudioAudioLayers(draft, true).headroom, .0001f)
            export(draft, output)
            var peak = 0; var count = 0L
            CaptionAudioDecoder.read(VideoClip("out", output.path, "Export", 4000, 0, 4000, 360, 640), { samples ->
                count += samples.size; samples.forEach { peak = maxOf(peak, abs(it.toInt())) }
            }, {})
            assertTrue("No mixed audio", peak > 300)
            assertTrue("Clipped mix: $peak", peak < 32_000)
            assertTrue("Audio unexpectedly extended video", count in 62_000..66_000)
        } finally { video.delete(); audio.delete(); output.delete() }
    }

    @Test fun collageExportsItsOriginalSoundMusicAndDelayedVoiceover() = runBlocking {
        val video = fixture("media-fixture.mp4")
        val audio = fixture("audio-waveform-fixture.m4a")
        val output = File(context.cacheDir, "collage-voiceover-export.mp4").apply { delete() }
        val collage = Collage(List(2) { CollageAsset("$it", video.path, "Video", false, 4000, 360, 640) }, audioIndex = 0)
        val draft = DraftEntity("collage-audio", video.path, durationMs = 4000, width = 360, height = 640, trimEndMs = 4000,
            audioPath = audio.path, audioDurationMs = 4000, audioEndMs = 2000,
            editRecipe = RecipeCodec.encode(EditRecipe(collage = collage,
                audioTracks = listOf(AudioTrack("voice", audio.path, "Voice", 4000, 1000, 3000, 1500)))))
        try {
            export(draft, output)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                assertTrue(abs(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() - 4000) < 150)
                assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertNotNull(reader.getScaledFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST, 36, 64)?.also { it.recycle() })
            } finally { reader.release() }
        } finally { video.delete(); audio.delete(); output.delete() }
    }

    @Test fun recorderSavesAValidTakeAndDiscardDoesNotCommit() = runBlocking {
        // Use synthetic/silent emulator input; a normal test run must not record a phone's room.
        assumeTrue("Recorder automation uses an emulator; microphone quality needs a manual phone check.",
            android.os.Build.HARDWARE in listOf("ranchu", "goldfish"))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.RECORD_AUDIO")).use { it.readBytes() }
        assertEquals(android.content.pm.PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
        val committed = mutableListOf<Pair<Long, Long>>()
        val recorder = VoiceoverRecorder(context) { _, file, offset ->
            val reader = MediaMetadataRetriever()
            try { reader.setDataSource(file.path); committed += offset to reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() }
            finally { reader.release() }
        }
        try {
            recorder.start("test", 1500, 8000)
            val started = withTimeout(10_000) { recorder.state.first { it.phase == "Recording" || !it.busy } }
            assertEquals("Microphone: ${started.error}", "Recording", started.phase)
            delay(3000); recorder.stop()
            val saved = withTimeout(10_000) { recorder.state.first { it.phase == "Saved" || it.error.isNotEmpty() } }
            assertEquals(saved.error, "Saved", saved.phase)
            assertEquals(1500L, committed.single().first); assertTrue(committed.single().second >= 1000)
            recorder.start("test", 0, 8000)
            withTimeout(10_000) { recorder.state.first { it.phase == "Recording" } }
            delay(1200); recorder.stop(discard = true)
            withTimeout(10_000) { recorder.state.first { !it.busy } }
            assertEquals(1, committed.size)
            recorder.start("test", 0, 8000)
            withTimeout(10_000) { recorder.state.first { it.phase == "Recording" } }
            delay(150); recorder.stop()
            val short = withTimeout(10_000) { recorder.state.first { !it.busy } }
            assertTrue("Short recording needs a clear message", short.error.isNotEmpty())
            assertEquals(1, committed.size)
            recorder.start("test", 250, 1000)
            val automatic = withTimeout(10_000) { recorder.state.first { it.phase == "Saved" || it.error.isNotEmpty() } }
            assertEquals(automatic.error, "Saved", automatic.phase)
            assertEquals(2, committed.size)
            assertTrue("One-second reel needs a valid source take", committed.last().second >= 1000)
        } finally { recorder.close() }
    }

    private suspend fun export(draft: DraftEntity, output: File) = withTimeout(90_000) { withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
            }).build()
        try { transformer.start(StudioComposition.create(context, draft, ExportSettings.forVideo(360, 640, "Original", "Small", true)), output.path); done.await() }
        finally { transformer.cancel() }
    } }
}
