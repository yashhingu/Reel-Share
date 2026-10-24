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
class VideoReframeMediaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
    private fun fixture() = File(context.cacheDir, "reframe-source.mp4").also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    @Test fun dragBoundsAndLegacyFrameDecisions() {
        assertEquals(1f, VideoReframe.visible(360, 640, 360, 640).first, .001f)
        val square = VideoReframe.visible(360, 640, 360, 360)
        assertEquals(1f, square.first, .001f)
        assertEquals(.5625f, square.second, .001f)
        val moved = VideoReframe.move(VideoReframe.Focus(), 0f, 1f, 360, 640, 360, 360)
        assertEquals(1f, moved.y, .001f)
        assertEquals(0f, VideoReframe.move(VideoReframe.Focus(), 1f, 1f, 360, 640, 360, 640).x, .001f)
        assertEquals(0f, VideoReframe.move(VideoReframe.Focus(), 1f, 1f, 360, 640, 360, 640).y, .001f)
        assertEquals(1f, VideoReframe.move(VideoReframe.Focus(zoom = 2f), 100f, 100f, 360, 640, 360, 640).x, .001f)
        val legacy = RecipeCodec.decode("""{"version":1,"brightness":0.4}""")
        assertEquals(VideoReframe.Focus(), VideoReframe.Focus(legacy.frameZoom, legacy.frameX, legacy.frameY))
        val invalid = RecipeCodec.decode("""{"frameZoom":99,"frameX":-99,"frameY":99}""")
        assertEquals(VideoReframe.Focus(2f, -1f, 1f), VideoReframe.Focus(invalid.frameZoom, invalid.frameX, invalid.frameY))
    }

    @Test fun frameSurvivesRoomReopenUndoAndReset() = runBlocking {
        val name = "reframe-test.db"; context.deleteDatabase(name)
        val original = DraftEntity("reframe-room", "/private/source", durationMs = 2000, width = 360, height = 640,
            trimEndMs = 2000, aspect = "1:1")
        val framed = DraftHistory.record(original, original.copy(editRecipe = RecipeCodec.encode(EditRecipe(
            frameZoom = 1.4f, frameX = -.5f, frameY = .75f))), 1000)
        try {
            Room.databaseBuilder(context, ReelDatabase::class.java, name).build().also { db ->
                try { db.studioDao().putDraft(framed) } finally { db.close() }
            }
            Room.databaseBuilder(context, ReelDatabase::class.java, name).build().also { db ->
                try {
                    val saved = db.studioDao().draft(original.id)!!
                    assertEquals(.75f, saved.recipe().frameY, .001f)
                    assertEquals("1:1", saved.aspect)
                    val undone = DraftHistory.restore(saved, false)!!
                    assertEquals(1f, undone.recipe().frameZoom, .001f)
                    assertEquals(framed.recipe(), DraftHistory.restore(undone, true)!!.recipe())
                    val reset = saved.recipe().copy(frameZoom = 1f, frameX = 0f, frameY = 0f)
                    assertEquals(EditRecipe(), RecipeCodec.decode(RecipeCodec.encode(reset)))
                } finally { db.close() }
            }
        } finally { context.deleteDatabase(name) }
        Unit
    }

    @Test fun nativeSquareExportsRevealOppositeEndsWithoutBlackBorders() = runBlocking {
        val source = fixture()
        val settings = ExportSettings(360, 360, 2_000_000)
        suspend fun export(y: Float): File {
            val output = File(context.cacheDir, "reframe-$y.mp4"); output.delete()
            val draft = DraftEntity("reframe", source.path, durationMs = 4000, width = 360, height = 640,
                trimEndMs = 2000, aspect = "1:1", mute = true,
                editRecipe = RecipeCodec.encode(EditRecipe(frameY = y)))
            withTimeout(90_000) { withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            done.completeExceptionally(exportException)
                        }
                    }).build()
                try { transformer.start(StudioComposition.create(context, draft, settings), output.path); done.await() }
                finally { transformer.cancel() }
            } }
            return output
        }
        val one = File(context.cacheDir, "reframe--1.0.mp4")
        val two = File(context.cacheDir, "reframe-1.0.mp4")
        try {
            export(-1f); export(1f)
            fun inspect(file: File): Pair<Int, Int> {
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(file.path)
                    assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 1900..2100)
                    val bitmap = requireNotNull(reader.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST))
                    try {
                        assertEquals(360, bitmap.width); assertEquals(360, bitmap.height)
                        var dark = 0; var border = 0
                        for (y in 5 until 35) for (x in 5 until 100) {
                            val p = bitmap.getPixel(x, y)
                            if (Color.red(p) + Color.green(p) + Color.blue(p) < 90) dark++
                        }
                        for (y in 4 until 356 step 9) for (x in listOf(2, 357)) {
                            val p = bitmap.getPixel(x, y)
                            if (Color.red(p) + Color.green(p) + Color.blue(p) < 40) border++
                        }
                        return dark to border
                    } finally { bitmap.recycle() }
                } finally { reader.release() }
            }
            val a = inspect(one); val b = inspect(two)
            assertTrue("The top timestamp must appear in only one framing: $a vs $b", kotlin.math.abs(a.first - b.first) > 700)
            assertTrue("Unexpected empty crop edge: $a vs $b", a.second < 8 && b.second < 8)
        } finally { source.delete(); one.delete(); two.delete() }
    }
}
