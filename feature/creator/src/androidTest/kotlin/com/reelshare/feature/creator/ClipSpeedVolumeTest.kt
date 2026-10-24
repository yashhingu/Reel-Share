package com.reelshare.feature.creator

import android.media.MediaMetadataRetriever
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.reelshare.core.database.ReelDatabase
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ClipSpeedVolumeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
    private fun fixture() = File(context.cacheDir, "clip-speed-volume-source.mp4").also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
    private fun draft(path: String, first: VideoClip, second: VideoClip) =
        DraftEntity("clip-controls", path, durationMs = 4000, width = 360, height = 640, trimEndMs = 4000,
            editRecipe = RecipeCodec.encode(EditRecipe(clips = listOf(first, second))))
    private fun clip(id: String, path: String) = VideoClip(id, path, id, 4000, 0, 1500, 360, 640)

    @Test fun decisionsSurviveRoomUndoRedoSplitDuplicateAndLegacy() = runBlocking {
        val base = draft("/private/source", clip("a", "/private/source").copy(endMs = 3000), clip("b", "/private/source"))
        val changed = base.copy(editRecipe = RecipeCodec.encode(base.recipe().copy(clips = base.timeline().map {
            if (it.id == "a") it.copy(speed = .5f, volume = .25f) else it.copy(speed = 2f, volume = 0f)
        })))
        assertEquals(6750L, changed.outputDuration())
        assertEquals(3000L, TimelineEdits.center(changed, "a"))
        assertEquals(6375L, TimelineEdits.center(changed, "b"))
        assertEquals(1500L, TimelineEdits.sourceAtPlayhead(changed, "a", 3000L))
        val split = TimelineEdits.split(changed, "a", 3000L)
        assertEquals(listOf(.5f, .5f, 2f), split.timeline().map { it.speed })
        assertEquals(listOf(.25f, .25f, 0f), split.timeline().map { it.volume })
        assertEquals(6750L, split.outputDuration())
        val duplicated = TimelineEdits.duplicate(split, split.timeline()[1].id)
        assertEquals(.5f, duplicated.timeline()[2].speed!!, .001f)
        assertEquals(.25f, duplicated.timeline()[2].volume, .001f)
        val stored = DraftHistory.record(base, changed, 1000)
        val name = "clip-speed-volume-test.db"; context.deleteDatabase(name)
        try {
            Room.databaseBuilder(context, ReelDatabase::class.java, name).build().also { db ->
                try { db.studioDao().putDraft(stored) } finally { db.close() }
            }
            Room.databaseBuilder(context, ReelDatabase::class.java, name).build().also { db ->
                try {
                    val recovered = db.studioDao().draft(base.id)!!
                    assertEquals(changed.recipe(), recovered.recipe())
                    val undo = DraftHistory.restore(recovered, false)!!
                    assertEquals(4500L, undo.outputDuration())
                    assertEquals(changed.recipe(), DraftHistory.restore(undo, true)!!.recipe())
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
        val legacy = RecipeCodec.decode("""{"clips":[{"id":"a","path":"p","name":"A","duration":4000,"start":0,"end":1500,"width":360,"height":640}]}""")
        assertNull(legacy.clips.single().speed)
        assertEquals(1f, legacy.clips.single().volume, .001f)
        val malformed = RecipeCodec.decode(RecipeCodec.encode(EditRecipe(clips = listOf(clip("x", "p").copy(speed = 99f, volume = -1f)))))
        assertEquals(4f, malformed.clips.single().speed!!, .001f)
        assertEquals(0f, malformed.clips.single().volume, .001f)
        Unit
    }

    @Test fun mixedClipSpeedsSetActualExportDuration() = runBlocking {
        val source = fixture(); val output = File(context.cacheDir, "clip-speed-output.mp4")
        try {
            val first = clip("slow", source.path).copy(speed = .5f)
            val second = clip("fast", source.path).copy(speed = 2f)
            val edited = draft(source.path, first, second)
            assertEquals(3750L, edited.outputDuration())
            export(edited, output)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                val actual = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Expected 3750 ms, got $actual", actual in 3650..3850)
                assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                for (time in listOf(500_000L, 2_500_000L, 3_450_000L)) {
                    val frame = reader.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 90, 160)
                    assertNotNull("Missing frame at $time", frame); frame?.recycle()
                }
            } finally { reader.release() }
        } finally { source.delete(); output.delete() }
    }

    @Test fun clipVolumeChangesOnlyItsOriginalAudio() = runBlocking {
        val source = fixture(); val output = File(context.cacheDir, "clip-volume-output.mp4")
        try {
            val first = clip("quiet", source.path).copy(volume = .25f)
            val second = clip("loud", source.path)
            export(draft(source.path, first, second), output)
            val samples = ArrayList<Short>()
            CaptionAudioDecoder.read(VideoClip("pcm", output.path, "Export", 3000, 0, 3000, 360, 640),
                { block -> block.forEach(samples::add) }, {})
            fun rms(startMs: Int, endMs: Int): Double {
                val from = startMs * 16; val until = minOf(endMs * 16, samples.size)
                assertTrue("Missing PCM $startMs–$endMs", until > from)
                var sum = 0.0
                for (i in from until until) sum += samples[i].toDouble() * samples[i]
                return sqrt(sum / (until - from))
            }
            val quiet = rms(400, 1100); val loud = rms(1900, 2600)
            assertTrue("Expected audible reference sound", loud > 50)
            assertEquals("Clip sound ratio $quiet / $loud", .25, quiet / loud, .07)
        } finally { source.delete(); output.delete() }
    }

    private suspend fun export(draft: DraftEntity, output: File) = withTimeout(90_000) { withContext(Dispatchers.Main) {
        output.delete()
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    done.completeExceptionally(exportException)
                }
            }).build()
        try { transformer.start(StudioComposition.create(context, draft, ExportSettings(360, 640, 1_000_000)), output.path); done.await() }
        finally { transformer.cancel() }
    } }
}
