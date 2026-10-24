package com.reelshare.feature.creator

import android.media.MediaMetadataRetriever
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.reelshare.core.database.ReelDatabase
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TimelineHistoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
    private fun draft() = DraftEntity("history", "/private/source.mp4", durationMs = 4000, width = 360, height = 640,
        trimStartMs = 500, trimEndMs = 3500, title = "Original", renderedPath = "/cache/old.mp4", renderedRevision = 7, revision = 7)

    @Test fun undoAndRedoSurviveRoomReopenAndPreserveExportMetadata() = runBlocking {
        val name = "timeline-history-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
        val original = draft()
        val edited = DraftHistory.record(original, original.copy(title = "Changed", aspect = "1:1",
            editRecipe = RecipeCodec.encode(EditRecipe(brightness = .3f))), 1000).copy(revision = 8)
        try {
            val first = open()
            try { first.studioDao().putDraft(edited) } finally { first.close() }
            val second = open()
            try {
                val restored = DraftHistory.restore(second.studioDao().draft(original.id)!!, false)!!
                assertEquals("Original", restored.title); assertEquals("Original", restored.aspect)
                assertEquals(0f, restored.recipe().brightness, 0f)
                assertEquals(8L, restored.revision); assertEquals(7L, restored.renderedRevision)
                assertEquals(original.renderedPath, restored.renderedPath)
                second.studioDao().putDraft(restored)
            } finally { second.close() }
            val third = open()
            try {
                val current = third.studioDao().draft(original.id)!!
                assertTrue(DraftHistory.meta(current).redo)
                val redone = DraftHistory.restore(current, true)!!
                assertEquals("Changed", redone.title); assertEquals("1:1", redone.aspect)
                assertEquals(.3f, redone.recipe().brightness, 0f)
                assertNotEquals(DraftHistory.meta(current).token, DraftHistory.meta(redone).token)
            } finally { third.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun typingCoalescesAndNewEditClearsRedoWithoutNestingHistory() {
        val original = draft()
        val one = DraftHistory.record(original, original.copy(title = "A"), 1000, "name")
        val two = DraftHistory.record(one, one.copy(title = "AB"), 1500, "name")
        assertSame(two, DraftHistory.record(two, two.copy(), 1600))
        val undo = DraftHistory.restore(two, false)!!
        assertEquals(original.title, undo.title)
        assertFalse(DraftHistory.meta(undo).undo); assertTrue(DraftHistory.meta(undo).redo)
        val branch = DraftHistory.record(undo, undo.copy(mute = true), 1700)
        assertFalse(DraftHistory.meta(branch).redo)
        assertFalse(DraftHistory.meta(DraftHistory.clear(branch)).undo)
        var many = original
        repeat(60) { many = DraftHistory.record(many, many.copy(title = "Edit $it"), 5000L + it * 2000) }
        val h = JSONObject(many.editRecipe).getJSONObject("_history")
        assertEquals(20, h.getJSONArray("undo").length())
        assertFalse(h.getJSONArray("undo").getJSONObject(0).getJSONObject("recipe").has("_history"))
        assertTrue(many.editRecipe.toByteArray().size <= 256_000)
        var large = original
        repeat(30) { n -> large = DraftHistory.record(large, large.copy(editRecipe = RecipeCodec.encode(EditRecipe(
            texts = List(192) { TimedText("$it", "$n " + "字".repeat(178), 0, 1000, caption = true) }))), 200_000L + n * 2000) }
        assertTrue("History exceeded byte budget", large.editRecipe.toByteArray().size <= 256_000)
    }

    @Test fun splitAndDuplicateRespectSourceRangesSpeedAndLimits() {
        for (speed in listOf(.25f, .5f, 1f, 2f, 4f)) {
            val original = draft().copy(editRecipe = RecipeCodec.encode(EditRecipe(speed = speed)))
            val split = TimelineEdits.split(original, original.id, original.outputDuration() / 2)
            val clips = split.timeline()
            assertEquals(2, clips.size); assertEquals(500L, clips[0].startMs); assertEquals(3500L, clips[1].endMs)
            assertEquals(clips[0].endMs, clips[1].startMs)
            assertEquals(3000L, clips.sumOf { it.lengthMs })
            assertTrue(kotlin.math.abs(original.outputDuration() - split.outputDuration()) <= 2)
            val copied = TimelineEdits.duplicate(split, clips[1].id).timeline()
            assertEquals(3, copied.size); assertEquals(copied[1].path, copied[2].path)
            assertEquals(copied[1].startMs, copied[2].startMs); assertNotEquals(copied[1].id, copied[2].id)
        }
        assertThrows(IllegalArgumentException::class.java) { TimelineEdits.split(draft(), "history", 100) }
        val ramp = draft().copy(editRecipe = RecipeCodec.encode(EditRecipe(ramp = true, rampEnd = 2f)))
        val rampSplit = TimelineEdits.split(ramp, "history", ramp.outputDuration() / 2)
        assertEquals(2, rampSplit.timeline().size)
        assertTrue(kotlin.math.abs(ramp.outputDuration() - rampSplit.outputDuration()) <= 2)
        assertEquals(rampSplit.timeline(), RecipeCodec.decode(RecipeCodec.encode(rampSplit.recipe())).clips)
        val rampUndone = DraftHistory.restore(DraftHistory.record(ramp, rampSplit, 1000), false)!!
        assertEquals(1, rampUndone.timeline().size)
        val clip = draft().timeline().first()
        val full = draft().copy(editRecipe = RecipeCodec.encode(EditRecipe(clips = List(12) { clip.copy(id = "$it") })))
        assertThrows(IllegalArgumentException::class.java) { TimelineEdits.duplicate(full, "0") }
    }

    @Test fun editedTimelineExportsAfterHistoryRoundTrip() = runBlocking {
        val source = File(context.cacheDir, "timeline-history-fixture.mp4")
        val output = File(context.cacheDir, "timeline-history-output.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        try {
            val original = draft().copy(sourcePath = source.path, editRecipe = RecipeCodec.encode(EditRecipe(speed = 2f)))
            val split = DraftHistory.record(original, TimelineEdits.split(original, original.id, 750), 1000)
            val copied = DraftHistory.record(split, TimelineEdits.duplicate(split, split.timeline()[0].id), 2000)
            val recovered = DraftHistory.restore(DraftHistory.restore(copied, false)!!, true)!!
            assertEquals(2250L, recovered.outputDuration())
            withTimeout(60_000) { withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
                    }).build()
                try { transformer.start(StudioComposition.create(context, recovered, ExportSettings(360, 640, 1_000_000)), output.path); done.await() }
                finally { transformer.cancel() }
            } }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                assertTrue(kotlin.math.abs(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() - 2250) < 120)
                assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                for (time in listOf(200_000L, 1_000_000L, 2_000_000L)) {
                    val frame = reader.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST, 90, 160)
                    assertNotNull("No frame at $time", frame); frame?.recycle()
                }
            } finally { reader.release() }
        } finally { source.delete(); output.delete() }
    }
}
