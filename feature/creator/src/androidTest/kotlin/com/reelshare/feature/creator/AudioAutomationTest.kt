package com.reelshare.feature.creator

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.util.Size
import androidx.media3.exoplayer.audio.*
import androidx.media3.transformer.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.reelshare.core.database.ReelDatabase
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioAutomationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
    private fun fixture(name: String) = File(context.cacheDir, "automation-$name").also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun draft(video: File, audio: File): DraftEntity = DraftEntity("automation", video.path,
        durationMs = 4000, width = 360, height = 640, trimEndMs = 4000, mute = true,
        audioPath = audio.path, audioDurationMs = 4000, audioStartMs = 1000, audioEndMs = 3000, audioVolume = 1f,
        editRecipe = RecipeCodec.encode(EditRecipe(audioTracks = listOf(
            AudioTrack("voice", audio.path, "Silent take", 4000, 3000, 4000, 750)))))

    @Test fun gainProcessorRestoresPositionForBothPcmEncodings() {
        val envelope = AudioEnvelope(4000, 1000, 1000, listOf(VoiceWindow(1500, 2500)), .75f)
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            val processor = GainProcessor(AudioGainProvider(envelope, 1f))
            processor.configure(AudioProcessor.AudioFormat(16_000, 1, encoding))
            try {
                for (offset in listOf(0L, 500_000L, 1_700_000L, 3_000_000L, 3_500_000L, 0L)) {
                    processor.flush(AudioProcessor.StreamMetadata.Builder().setPositionOffsetUs(offset).build())
                    val pcm = ByteBuffer.allocateDirect(if (encoding == C.ENCODING_PCM_FLOAT) 256 else 128).order(ByteOrder.nativeOrder())
                    repeat(64) { if (encoding == C.ENCODING_PCM_FLOAT) pcm.putFloat(.5f) else pcm.putShort(16_384) }
                    pcm.flip(); processor.queueInput(pcm)
                    val output = processor.output.order(ByteOrder.nativeOrder())
                    repeat(64) { index ->
                        val sample = if (encoding == C.ENCODING_PCM_FLOAT) output.float else output.short / 32768f
                        assertEquals("Encoding $encoding, seek $offset, sample $index",
                            .5f * envelope.gainAtUs(offset + index * 1_000_000L / 16_000), sample, .0001f)
                    }
                }
            } finally { processor.reset() }
        }
    }

    @Test fun audioMixAndTakeFadesSurviveRoomUndoAndLegacyRecipes() = runBlocking {
        val name = "audio-automation-test.db"; context.deleteDatabase(name)
        val original = DraftEntity("automation-room", "/private/video", durationMs = 4000, width = 360, height = 640, trimEndMs = 4000)
        val recipe = EditRecipe(audioMix = AudioMix(500, 1000, true, .8f, 1200),
            audioTracks = listOf(AudioTrack("v", "/private/take", "Voice", 4000, fadeInMs = 300, fadeOutMs = 700)))
        val edited = DraftHistory.record(original, original.copy(editRecipe = RecipeCodec.encode(recipe)), 1000)
        try {
            val first = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try { first.studioDao().putDraft(edited) } finally { first.close() }
            val reopened = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try {
                val saved = reopened.studioDao().draft(original.id)!!
                assertEquals(recipe, saved.recipe())
                val undo = DraftHistory.restore(saved, false)!!
                assertEquals(AudioMix(), undo.recipe().audioMix)
                assertEquals(recipe, DraftHistory.restore(undo, true)!!.recipe())
                assertEquals(AudioMix(), RecipeCodec.decode("{}").audioMix)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun movingMusicLaneDelaysTheExportedAudioWithoutExtendingVideo() = runBlocking {
        val video = fixture("media-fixture.mp4"); val audio = fixture("audio-waveform-fixture.m4a")
        val output = File(context.cacheDir, "automation-delayed-music.mp4")
        try {
            val base = draft(video, audio).copy(editRecipe = RecipeCodec.encode(EditRecipe(audioMix = AudioMix(musicOffsetMs = 1500))))
            export(base, output)
            val samples = pcm(output, 4000)
            assertTrue("Moving music must leave silence before its lane", rms(samples, 200, 1200) < 10)
            assertTrue("Moved music should be audible", rms(samples, 1800, 2500) > 100)
            assertTrue("Music should stop at the reel end", samples.size <= 4000 * 16 + 1024)
        } finally { video.delete(); audio.delete(); output.delete() }
    }

    @Test fun laneEdgeTrimsPersistAndExportMusicAndVoiceoverAtTheSameClock() = runBlocking {
        val video = fixture("media-fixture.mp4"); val audio = fixture("audio-waveform-fixture.m4a")
        val axis = TimelineAxis(listOf(4000))
        val left = AudioLaneTrim.drag(AudioLaneRange(0, 4000, 0), 4000, axis, true, axis.xAt(1500))
        val range = AudioLaneTrim.drag(left, 4000, axis, false, axis.xAt(2500) - axis.xAt(4000))
        val original = draft(video, audio)
        val trimmed = original.copy(audioStartMs = range.startMs, audioEndMs = range.endMs,
            editRecipe = RecipeCodec.encode(EditRecipe(audioMix = AudioMix(musicOffsetMs = range.offsetMs),
                audioTracks = listOf(AudioTrack("v", audio.path, "Voice", 4000, range.startMs, range.endMs, range.offsetMs)))))
        val name = "lane-trim-test.db"; context.deleteDatabase(name)
        try {
            val db = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try { db.studioDao().putDraft(DraftHistory.record(original, trimmed, 1000)) } finally { db.close() }
            val reopened = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try {
                val saved = reopened.studioDao().draft(original.id)!!
                assertEquals(range.startMs, saved.audioStartMs); assertEquals(range.endMs, saved.audioEndMs)
                assertEquals(range.offsetMs, saved.recipe().audioMix.musicOffsetMs)
                val undo = DraftHistory.restore(saved, false)!!
                assertEquals(original.audioStartMs, undo.audioStartMs)
                val redone = DraftHistory.restore(undo, true)!!
                assertEquals(saved.recipe(), redone.recipe())
                assertEquals(saved.audioStartMs, redone.audioStartMs); assertEquals(saved.audioEndMs, redone.audioEndMs)
                for (voice in listOf(false, true)) {
                    val output = File(context.cacheDir, "lane-trim-$voice.mp4")
                    val recipe = saved.recipe()
                    val base = if (voice) saved.copy(audioPath = "", editRecipe = RecipeCodec.encode(recipe.copy(audioMix = AudioMix())))
                        else saved.copy(editRecipe = RecipeCodec.encode(recipe.copy(audioTracks = emptyList())))
                    try {
                        export(base, output)
                        val samples = pcm(output, 4000)
                        assertTrue("Trim leaked before placement", rms(samples, 200, 1200) < 10)
                        assertTrue("Trim removed the selected tone", rms(samples, 1700, 2200) > 100)
                        assertTrue("Trim retained removed tail", samples.size <= 2600 * 16)
                    } finally { output.delete() }
                }
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name); video.delete(); audio.delete() }
    }

    @Test fun audioSplitsSurviveHistoryAndKeepExportGainAcrossTheCut() = runBlocking {
        val video = fixture("media-fixture.mp4"); val audio = fixture("audio-waveform-fixture.m4a")
        val base = draft(video, audio).copy(audioStartMs = 0, audioEndMs = 4000,
            editRecipe = RecipeCodec.encode(EditRecipe(audioMix = AudioMix(1000, 1000))))
        val split = AudioSplit.at(base, "music", 2000, "right")
        val pieces = split.recipe().musicTracks
        val name = "audio-split-test.db"; context.deleteDatabase(name)
        val output = File(context.cacheDir, "audio-split-reference.mp4")
        try {
            val db = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try { db.studioDao().putDraft(DraftHistory.record(base, split, 1000)) } finally { db.close() }
            val reopened = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try {
                val saved = reopened.studioDao().draft(base.id)!!
                assertEquals(pieces, saved.recipe().musicTracks)
                val undo = DraftHistory.restore(saved, false)!!
                assertTrue(undo.recipe().musicTracks.isEmpty())
                assertEquals(pieces, DraftHistory.restore(undo, true)!!.recipe().musicTracks)
                assertTrue(saved.recipe().style().musicTracks.isEmpty())
                export(base, output)
                val reference = pcm(output, 4000)
                for (voice in listOf(false, true)) {
                    val edited = if (voice) saved.copy(audioPath = "", editRecipe = RecipeCodec.encode(EditRecipe(audioTracks = pieces))) else saved
                    assertEquals(1f, StudioAudioLayers(edited, false).headroom, 0f)
                    export(edited, output)
                    val actual = pcm(output, 4000)
                    for ((start, end) in listOf(1200L to 1600L, 1900L to 2100L, 2400L to 2800L)) {
                        val expected = rms(reference, start, end)
                        assertTrue("Missing tone at split", expected > 100)
                        assertEquals("Split changed gain, voice=$voice at $start", 1.0, rms(actual, start, end) / expected, .10)
                    }
                    assertTrue(actual.size in reference.size - 1500..reference.size + 1500)
                }
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name); output.delete(); video.delete(); audio.delete() }
    }

    @Test fun selectedPieceVolumeAndFadesShapeOnlyThatExportSection() = runBlocking {
        val video = fixture("media-fixture.mp4"); val audio = fixture("audio-waveform-fixture.m4a")
        val referenceFile = File(context.cacheDir, "piece-reference.mp4")
        val changedFile = File(context.cacheDir, "piece-changed.mp4")
        try {
            val base = draft(video, audio).copy(audioStartMs = 0, audioEndMs = 4000,
                editRecipe = RecipeCodec.encode(EditRecipe()))
            val split = AudioSplit.at(base, "music", 2000, "right")
            val adjusted = AudioSplit.update(split.recipe(), "music") { it.copy(volume = .4f, fadeOutMs = 500,
                envelopeOffsetMs = 0, envelopeLengthMs = 0) }
            val edited = split.copy(editRecipe = RecipeCodec.encode(AudioSplit.update(adjusted, "right") {
                it.copy(volume = .8f, fadeInMs = 500, envelopeOffsetMs = 0, envelopeLengthMs = 0)
            }))
            assertEquals(2, edited.recipe().musicTracks.size)
            export(base, referenceFile); export(edited, changedFile)
            val reference = pcm(referenceFile, 4000); val changed = pcm(changedFile, 4000)
            for ((start, end, ratio) in listOf(Triple(1100L, 1400L, .4), Triple(2600L, 2900L, .8))) {
                val expected = rms(reference, start, end)
                assertTrue("Reference section must have sound", expected > 100)
                assertEquals("Piece gain at $start", ratio, rms(changed, start, end) / expected, .10)
            }
            assertTrue("First piece should fade before the cut", rms(changed, 1800, 1900) < rms(changed, 1100, 1400) * .8)
            assertTrue("Second piece should fade in after the cut", rms(changed, 2100, 2200) < rms(changed, 2600, 2900) * .7)
        } finally { referenceFile.delete(); changedFile.delete(); video.delete(); audio.delete() }
    }

    @Test fun musicFadeAndDuckingAreAudibleInTheExportedPcm() = runBlocking {
        val video = fixture("media-fixture.mp4"); val audio = fixture("audio-waveform-fixture.m4a")
        val baseOutput = File(context.cacheDir, "automation-base.mp4"); val output = File(context.cacheDir, "automation-mix.mp4")
        val collageOutput = File(context.cacheDir, "automation-collage.mp4"); val mutedOutput = File(context.cacheDir, "automation-muted-voice.mp4")
        try {
            val base = draft(video, audio)
            export(base, baseOutput)
            val mix = AudioMix(500, 500, true, .75f)
            val edited = base.copy(editRecipe = RecipeCodec.encode(base.recipe().copy(audioMix = mix)))
            export(edited, output)
            val plain = pcm(baseOutput, 4000); val effected = pcm(output, 4000)
            val envelope = AudioEnvelope(2000, 500, 500, listOf(VoiceWindow(750, 1750)), .75f)
            for (ms in listOf(200L, 550L, 1000L, 1400L, 1800L)) {
                val ratio = rms(effected, ms - 40, ms + 40) / rms(plain, ms - 40, ms + 40)
                assertEquals("Export gain at $ms ms", envelope.gainAtUs(ms * 1000).toDouble(), ratio, .065)
            }
            // MP4 may end its audio track early instead of writing silent AAC to the video's end.
            assertTrue("Music must stop with its selection", effected.size <= 2400 * 16 || rms(effected, 2400, 2900) < 10)
            val collage = Collage(List(2) { CollageAsset("$it", video.path, "Video", false, 4000, 360, 640) })
            export(edited.copy(editRecipe = RecipeCodec.encode(edited.recipe().copy(collage = collage))), collageOutput)
            val combined = pcm(collageOutput, 4000)
            assertEquals("Collage must use identical ducking", rms(effected, 900, 1300), rms(combined, 900, 1300), 20.0)
            val muted = edited.recipe().let { it.copy(audioTracks = it.audioTracks.map { take -> take.copy(volume = 0f) }) }
            export(edited.copy(editRecipe = RecipeCodec.encode(muted)), mutedOutput)
            assertEquals("Muted takes must not duck music", rms(plain, 900, 1300), rms(pcm(mutedOutput, 4000), 900, 1300), 20.0)
        } finally { video.delete(); audio.delete(); baseOutput.delete(); output.delete(); collageOutput.delete(); mutedOutput.delete() }
    }

    @Test fun delayedTakeFadesRespectTheReelEndAfterSpeedChange() = runBlocking {
        val video = fixture("media-fixture.mp4"); val audio = fixture("audio-waveform-fixture.m4a")
        val plainOutput = File(context.cacheDir, "automation-voice-base.mp4"); val output = File(context.cacheDir, "automation-voice-fade.mp4")
        try {
            val track = AudioTrack("v", audio.path, "Voice", 4000, 1000, 3000, 1500)
            val recipe = EditRecipe(speed = 2f, audioTracks = listOf(track))
            val base = draft(video, audio).copy(audioPath = "", editRecipe = RecipeCodec.encode(recipe))
            export(base, plainOutput)
            val changed = recipe.copy(audioTracks = listOf(track.copy(fadeInMs = 1000, fadeOutMs = 1000)))
            export(base.copy(editRecipe = RecipeCodec.encode(changed)), output)
            val plain = pcm(plainOutput, 2000); val faded = pcm(output, 2000)
            assertTrue("Output audio extended the two-second reel", faded.size in 30_000..33_000)
            assertTrue("Audio before delayed take", rms(faded, 300, 1200) < 10)
            for (ms in listOf(1600L, 1750L, 1900L)) {
                val ratio = rms(faded, ms - 25, ms + 25) / rms(plain, ms - 25, ms + 25)
                assertEquals("Clipped take fade at $ms", AudioEnvelope(500, 1000, 1000).gainAtUs((ms - 1500) * 1000).toDouble(), ratio, .1)
            }
        } finally { video.delete(); audio.delete(); plainOutput.delete(); output.delete() }
    }

    @Test fun previewPcmAfterSeekingMatchesTheExportEnvelope() = runBlocking {
        val video = fixture("media-fixture.mp4"); val audio = fixture("audio-waveform-fixture.m4a")
        try { withTimeout(30_000) { withContext(Dispatchers.Main) {
            val sink = CapturingSink(DefaultAudioSink.Builder(context).build())
            val reader = ImageReader.newInstance(360, 640, PixelFormat.RGBA_8888, 3)
            reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
            val player = CompositionPlayer.Builder(context).setPerStreamMediaProgressionEnabled(true)
                .experimentalSetEnableReplayableCache(true).setAudioSink(sink).build()
            var error: PlaybackException? = null
            player.addListener(object : Player.Listener { override fun onPlayerError(e: PlaybackException) { error = e } })
            try {
                val base = draft(video, audio)
                val mix = AudioMix(500, 500, true, .75f)
                val edited = base.copy(editRecipe = RecipeCodec.encode(base.recipe().copy(audioMix = mix)))
                player.setVideoSurface(reader.surface, Size(360, 640))
                player.setComposition(StudioComposition.create(context, edited, ExportSettings(360, 640, 1_000_000)))
                player.prepare(); player.play()
                while (player.currentPosition < 1100) { error?.let { throw it }; delay(25) }
                player.pause(); delay(100)
                val original = sink.blocks.toList()
                val reference = sink.rms(original, 510, 590)
                assertTrue("Missing preview reference audio", reference > .02)
                val previousGeneration = sink.generation.get()
                player.seekTo(1000); player.play()
                while (player.currentPosition < 1400 || sink.generation.get() == previousGeneration) { error?.let { throw it }; delay(25) }
                player.pause(); delay(100)
                val afterSeek = sink.blocks.filter { it.generation > previousGeneration }
                assertEquals("Seek must not restart fade/duck clocks", .25, sink.rms(afterSeek, 1050, 1200) / reference, .07)
                val beforeReplay = sink.generation.get()
                player.seekTo(0); player.play()
                while (player.currentPosition < 400 || sink.generation.get() == beforeReplay) { error?.let { throw it }; delay(25) }
                player.pause(); delay(100)
                val replay = sink.blocks.filter { it.generation > beforeReplay }
                assertEquals("Replay must restart the fade", .5, sink.rms(replay, 225, 275) / reference, .08)
            } finally { player.release(); reader.close() }
        } } } finally { video.delete(); audio.delete() }
    }

    private data class Block(val generation: Int, val timeUs: Long, val rate: Int, val samples: FloatArray)
    private class CapturingSink(sink: AudioSink) : ForwardingAudioSink(sink) {
        var format: Format = Format.Builder().build()
        val generation = AtomicInteger()
        val blocks = ConcurrentLinkedQueue<Block>()
        override fun configure(config: AudioSink.AudioSinkConfig) { format = config.format; super.configure(config) }
        override fun flush() { generation.incrementAndGet(); super.flush() }
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            val copy = buffer.duplicate().order(ByteOrder.nativeOrder()); val start = buffer.position()
            val result = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            val end = buffer.position(); val bytes = if (format.pcmEncoding == C.ENCODING_PCM_FLOAT) 4 else 2
            val frameBytes = bytes * format.channelCount.coerceAtLeast(1)
            if (end > start && blocks.size < 512) {
                copy.position(start); copy.limit(end)
                val samples = FloatArray((end - start) / frameBytes) {
                    var sum = 0f
                    repeat(format.channelCount) { sum += if (bytes == 4) copy.float else copy.short / 32768f }
                    sum / format.channelCount
                }
                blocks += Block(generation.get(), presentationTimeUs + start / frameBytes * 1_000_000L / format.sampleRate, format.sampleRate, samples)
            }
            return result
        }
        fun rms(data: List<Block>, startMs: Long, endMs: Long): Double {
            var sum = 0.0; var count = 0
            data.forEach { b -> b.samples.forEachIndexed { i, v ->
                val time = b.timeUs + i * 1_000_000L / b.rate
                if (time >= startMs * 1000 && time < endMs * 1000) { sum += v * v; count++ }
            } }
            assertTrue("No preview PCM in $startMs–$endMs ms; blocks ${data.map { it.timeUs / 1000 }}", count > 0)
            return sqrt(sum / count)
        }
    }

    private suspend fun pcm(file: File, duration: Long): ShortArray {
        val samples = ArrayList<Short>()
        CaptionAudioDecoder.read(VideoClip("pcm", file.path, "Output", duration, 0, duration, 360, 640), { block -> block.forEach { samples += it } }, {})
        return samples.toShortArray()
    }
    private fun rms(pcm: ShortArray, startMs: Long, endMs: Long): Double {
        val from = (startMs * 16).toInt().coerceIn(0, pcm.size)
        val until = (endMs * 16).toInt().coerceIn(from, pcm.size)
        assertTrue("Missing exported PCM", until > from)
        var sum = 0.0; for (i in from until until) sum += pcm[i].toDouble() * pcm[i]
        return sqrt(sum / (until - from))
    }
    private suspend fun export(draft: DraftEntity, output: File) = withTimeout(90_000) { withContext(Dispatchers.Main) {
        output.delete(); val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, result: ExportResult) { done.complete(Unit) }
                override fun onError(composition: Composition, result: ExportResult, error: ExportException) { done.completeExceptionally(error) }
            }).build()
        try { transformer.start(StudioComposition.create(context, draft, ExportSettings(360, 640, 1_000_000)), output.path); done.await() }
        finally { transformer.cancel() }
    } }
}
