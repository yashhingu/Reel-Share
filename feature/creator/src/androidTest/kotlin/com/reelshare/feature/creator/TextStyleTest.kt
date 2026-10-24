package com.reelshare.feature.creator

import android.graphics.*
import android.media.MediaMetadataRetriever
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.transformer.*
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.reelshare.core.database.ReelDatabase
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TextStyleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
    private val fonts = StudioTextFonts(context)

    @Test fun rotatedLongTitlesStayInEveryCanvasAndDragToTheEdges() {
        for ((w, h) in listOf(360 to 640, 640 to 360, 480 to 480)) {
            for (font in TimedText.FONTS) for (angle in listOf(-180f, -90f, -37f, 0f, 45f, 90f, 180f)) {
                val entry = TimedText("title", "Your story नमस्ते\n".repeat(6), 0, 2000, size = .12f, font = font, rotation = angle)
                val raster = TextPlacement.raster(entry, fonts.get(font), w)
                for (direction in listOf(-10f, 10f)) {
                    val moved = TextPlacement.move(entry, direction, direction, w, h, raster)
                    val rect = TextPlacement.place(moved, w, h, raster).rect
                    assertTrue("$font $angle $w×$h: $rect", rect.left >= -.0001f && rect.top >= -.0001f && rect.right <= 1.0001f && rect.bottom <= 1.0001f)
                }
            }
        }
        val flat = TimedText("t", "Rotate this", 0, 2000)
        val a = TextPlacement.raster(flat, fonts.get(flat.font), 360)
        val b = TextPlacement.raster(flat.copy(rotation = 90f), fonts.get(flat.font), 360)
        assertEquals(a.width, b.height); assertEquals(a.height, b.width)
    }

    @Test fun fontsAndStylesHaveDistinctPixelsAndMovingRotatedTextReusesTexture() {
        fun fingerprint(t: TimedText): Int {
            val bitmap = TextPlacement.draw(t, TextPlacement.raster(t, fonts.get(t.font), 360))
            return try { IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }.contentHashCode() }
            finally { bitmap.recycle() }
        }
        val entry = TimedText("t", "Wide iii नमस्ते", 0, 2000, animated = false, rotation = 32f)
        assertEquals(4, TimedText.FONTS.map { fingerprint(entry.copy(font = it)) }.toSet().size)
        assertEquals(4, TimedText.STYLES.map { fingerprint(entry.copy(style = it)) }.toSet().size)
        val titles = PreviewTitles(context)
        val overlay = titles.overlays.first() as BitmapOverlay
        overlay.configure(Size(360, 640)); titles.update(listOf(entry))
        val first = overlay.getBitmap(500_000)
        titles.update(listOf(entry.copy(x = .2f, y = .3f)))
        assertSame(first, overlay.getBitmap(500_000))
        titles.update(listOf(entry.copy(rotation = -32f, font = "Serif", style = "Outline")))
        assertNotSame(first, overlay.getBitmap(500_000))
    }

    @Test fun textStyleSurvivesRoomReopenUndoAndLegacyDefaults() = runBlocking {
        val name = "text-style-test.db"; context.deleteDatabase(name)
        val legacy = RecipeCodec.decode("""{"texts":[{"id":"t","text":"Old title","start":0,"end":2000}]}""")
        val old = legacy.texts.single()
        assertEquals("Bold", old.font); assertEquals("Classic", old.style); assertEquals(0f, old.rotation, 0f)
        val original = DraftEntity("text-style", "/private/video", durationMs = 2000, width = 360, height = 640, trimEndMs = 2000,
            editRecipe = RecipeCodec.encode(legacy.copy(audioMix = AudioMix(500, 500, true))))
        val updated = old.copy(font = "Typewriter", style = "Label", rotation = -42f, x = .4f, y = .6f)
        val edited = DraftHistory.record(original, original.copy(editRecipe = RecipeCodec.encode(original.recipe().copy(texts = listOf(updated)))), 1000)
        try {
            val db = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try { db.studioDao().putDraft(edited) } finally { db.close() }
            val reopened = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try {
                val saved = reopened.studioDao().draft(original.id)!!
                assertEquals(updated, saved.recipe().texts.single())
                val undo = DraftHistory.restore(saved, false)!!
                assertEquals(old, undo.recipe().texts.single())
                assertEquals(updated, DraftHistory.restore(undo, true)!!.recipe().texts.single())
                assertEquals(original.recipe().audioMix, saved.recipe().audioMix)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
        val bounded = updated.copy(rotation = Float.NaN, font = "missing", style = "missing").bounded()
        assertEquals(0f, bounded.rotation, 0f); assertEquals("Bold", bounded.font); assertEquals("Classic", bounded.style)
    }

    @Test fun nativeExportMatchesRotatedPreviewPixels() = runBlocking {
        val folder = File(context.cacheDir, "text-style-export-fixture").apply { mkdirs() }
        val photo = File(folder, "black.png")
        val black = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        try { photo.outputStream().use { black.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { black.recycle() }
        val texts = listOf(
            TimedText("a", "Your story", 0, 2000, size = .09f, animated = false, x = .5f, y = .2f, rotation = 27f, font = "Serif", style = "Label"),
            TimedText("b", "Made here", 0, 2000, size = .10f, animated = false, x = .5f, y = .5f, rotation = -35f, font = "Simple", style = "Outline"),
            TimedText("c", "Create", 0, 2000, size = .08f, animated = false, x = .5f, y = .8f, rotation = 90f, font = "Typewriter", style = "Shadow"))
        val recipe = EditRecipe(texts = texts, collage = Collage(List(2) { CollageAsset("$it", photo.path, "Black", true, 0, 32, 32) }, gap = 0f))
        val draft = DraftEntity("text-style-export", photo.path, durationMs = 2000, width = 360, height = 640, trimEndMs = 2000, editRecipe = RecipeCodec.encode(recipe))
        val output = File(folder, "out.mp4")
        try {
            withTimeout(90_000) { withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
                    }).build()
                try { transformer.start(StudioComposition.create(context, draft, ExportSettings(360, 640, 2_000_000)), output.path); done.await() }
                finally { transformer.cancel() }
            } }
            val reader = MediaMetadataRetriever()
            val actual = try { reader.setDataSource(output.path); requireNotNull(reader.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST, 360, 640)) }
            finally { reader.release() }
            val expected = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
            try {
                val titles = PreviewTitles(context); titles.update(texts)
                val canvas = Canvas(expected)
                texts.forEachIndexed { index, text ->
                    val overlay = titles.overlays[index] as BitmapOverlay; overlay.configure(Size(360, 640))
                    val bitmap = overlay.getBitmap(1_000_000)
                    val rect = TextPlacement.place(text, 360, 640, TextPlacement.raster(text, fonts.get(text.font), 360)).rect
                    canvas.drawBitmap(bitmap, null, RectF(rect.left * 360, rect.top * 640, rect.right * 360, rect.bottom * 640), Paint(Paint.FILTER_BITMAP_FLAG))
                }
                // Check each title region, so a missing title cannot pass on the mostly black canvas.
                for (text in texts) {
                    val r = TextPlacement.place(text, 360, 640, TextPlacement.raster(text, fonts.get(text.font), 360)).rect
                    var intersection = 0; var union = 0; var error = 0L
                    for (y in (r.top * 640).toInt() until (r.bottom * 640).toInt()) for (x in (r.left * 360).toInt() until (r.right * 360).toInt()) {
                        val a = Color.red(actual.getPixel(x, y)); val e = Color.red(expected.getPixel(x, y))
                        // Canvas and the encoder's GL sampling differ at subpixel glyph edges.
                        // Keep an unblurred overlap check and compare brightness with a 1px smoothing kernel.
                        if (a > 80 || e > 80) { union++; error += abs(smoothedRed(actual, x, y) - smoothedRed(expected, x, y)) }
                        if (a > 80 && e > 80) intersection++
                    }
                    assertTrue("${text.id}: no text", union > 100)
                    assertTrue("${text.id}: overlap ${intersection.toFloat() / union}", intersection.toFloat() / union > .80f)
                    assertTrue("${text.id}: pixel error ${error.toFloat() / union}", error.toFloat() / union < 25f)
                }
            } finally { actual.recycle(); expected.recycle() }
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }

    private fun smoothedRed(bitmap: Bitmap, x: Int, y: Int): Int {
        val kernel = intArrayOf(1, 4, 6, 4, 1)
        var sum = 0
        for (dy in -2..2) for (dx in -2..2) sum += kernel[dy + 2] * kernel[dx + 2] * Color.red(
            bitmap.getPixel((x + dx).coerceIn(0, bitmap.width - 1), (y + dy).coerceIn(0, bitmap.height - 1)))
        return sum / 256
    }
}
