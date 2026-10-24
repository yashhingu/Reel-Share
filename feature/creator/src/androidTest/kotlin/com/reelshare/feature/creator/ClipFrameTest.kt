package com.reelshare.feature.creator

import android.graphics.Color
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ClipFrameTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
    private fun clip(id: String, path: String, y: Float? = null) = VideoClip(id, path, id, 4000, 0, 1500, 360, 640,
        frameZoom = y?.let { 1f }, frameY = y ?: 0f)

    @Test fun inheritedAndOverriddenFramesSurviveRoomSplitDuplicateAndUndo() = runBlocking {
        val base = DraftEntity("clip-frame", "/private/source", durationMs = 4000, width = 360, height = 640,
            trimEndMs = 4000, aspect = "1:1", editRecipe = RecipeCodec.encode(EditRecipe(frameZoom = 1.3f,
                frameY = .4f, clips = listOf(clip("one", "/private/source").copy(endMs = 2500), clip("two", "/private/source", -1f)))))
        assertEquals(.4f, base.timeline()[0].frame(base.recipe()).y, .001f)
        assertEquals(-1f, base.timeline()[1].frame(base.recipe()).y, .001f)
        val split = TimelineEdits.split(base, "one", 1200)
        assertNull(split.timeline()[1].frameZoom)
        val duplicate = TimelineEdits.duplicate(base, "two")
        assertEquals(-1f, duplicate.timeline()[2].frameY, .001f)
        val edited = DraftHistory.record(base, base.copy(editRecipe = RecipeCodec.encode(base.recipe().copy(
            clips = base.timeline().map { if (it.id == "one") it.copy(frameZoom = 1.6f, frameY = 1f) else it }))), 1000)
        val name = "clip-frame-test.db"; context.deleteDatabase(name)
        try {
            Room.databaseBuilder(context, ReelDatabase::class.java, name).build().also { db ->
                try { db.studioDao().putDraft(edited) } finally { db.close() }
            }
            Room.databaseBuilder(context, ReelDatabase::class.java, name).build().also { db ->
                try {
                    val stored = db.studioDao().draft(base.id)!!
                    assertEquals(1f, stored.timeline()[0].frameY, .001f)
                    val undone = DraftHistory.restore(stored, false)!!
                    assertNull(undone.timeline()[0].frameZoom)
                    assertEquals(edited.recipe(), DraftHistory.restore(undone, true)!!.recipe())
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
        val legacy = RecipeCodec.decode("""{"clips":[{"id":"x","path":"p","name":"X","duration":4000,"start":0,"end":1500,"width":360,"height":640}]}""")
        assertNull(legacy.clips.single().frameZoom)
        val invalid = RecipeCodec.decode(RecipeCodec.encode(EditRecipe(clips = listOf(clip("x", "p", 9f).copy(frameZoom = 99f, frameX = -9f)))))
        assertEquals(VideoReframe.Focus(2f, -1f, 1f), invalid.clips.single().frame(invalid))
        Unit
    }

    @Test fun adjacentClipsExportOppositeSquareCropsWithoutEmptyEdges() = runBlocking {
        val source = File(context.cacheDir, "clip-frame-source.mp4")
        val output = File(context.cacheDir, "clip-frame-output.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        try {
            val recipe = EditRecipe(clips = listOf(clip("top", source.path, -1f), clip("bottom", source.path, 1f)))
            val draft = DraftEntity("clip-frame-export", source.path, durationMs = 4000, width = 360, height = 640,
                trimEndMs = 4000, aspect = "1:1", mute = true, editRecipe = RecipeCodec.encode(recipe))
            withTimeout(90_000) { withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            done.completeExceptionally(exportException)
                        }
                    }).build()
                try { transformer.start(StudioComposition.create(context, draft, ExportSettings(360, 360, 2_000_000)), output.path); done.await() }
                finally { transformer.cancel() }
            } }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 2850..3150)
                fun topCount(time: Long): Int {
                    val bitmap = reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST)!!
                    try {
                        assertEquals(360, bitmap.width); assertEquals(360, bitmap.height)
                        var dark = 0
                        for (y in 5 until 35) for (x in 5 until 100) {
                            val p = bitmap.getPixel(x, y)
                            if (Color.red(p) + Color.green(p) + Color.blue(p) < 90) dark++
                        }
                        var border = 0
                        for (y in 4 until 356 step 9) for (x in listOf(2, 357)) {
                            val p = bitmap.getPixel(x, y)
                            if (Color.red(p) + Color.green(p) + Color.blue(p) < 40) border++
                        }
                        assertTrue("Unexpected black edge: $border", border < 8)
                        return dark
                    } finally { bitmap.recycle() }
                }
                val first = topCount(750_000); val second = topCount(2_250_000)
                assertTrue("Expected separate clip framing: $first vs $second", kotlin.math.abs(first - second) > 700)
            } finally { reader.release() }
        } finally { source.delete(); output.delete() }
    }
}
