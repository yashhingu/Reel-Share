package com.reelshare.feature.creator

import android.graphics.*
import android.media.ImageReader
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TextMotionMediaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
    private fun caption() = TimedText("caption", "one two", 0, 2000, background = false, animated = false, caption = true,
        x = .5f, y = .7f, size = .10f, wordHighlight = true,
        wordTimings = listOf(CaptionWord(0, 3, 0, 600), CaptionWord(4, 7, 1000, 2000)), wordTimingText = "one two")
    private fun recipe() = EditRecipe(brightness = -1f, texts = listOf(
        TimedText("title", "Create", 0, 2000, x = .5f, y = .2f, animation = "Pop", animationMs = 300),
        caption().copy(animation = "Slide", animationMs = 300)))
    private fun fixture() = File(context.cacheDir, "text-motion-source.mp4").also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
    }
    private fun draft(source: File) = DraftEntity("text-motion", source.path, durationMs = 4000, width = 360, height = 640,
        trimEndMs = 2000, mute = true, editRecipe = RecipeCodec.encode(recipe()))

    @Test fun wordsAndAnimationsSurviveRoomHistoryAndMalformedOptionalTimings() = runBlocking {
        val name = "text-motion-test.db"; context.deleteDatabase(name)
        val original = DraftEntity("room-motion", "/private/source", durationMs = 2000, width = 360, height = 640, trimEndMs = 2000)
        val changed = DraftHistory.record(original, original.copy(editRecipe = RecipeCodec.encode(recipe())), 1000)
        try {
            val db = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try { db.studioDao().putDraft(changed) } finally { db.close() }
            val reopened = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try {
                val saved = reopened.studioDao().draft(original.id)!!
                assertEquals(recipe(), saved.recipe())
                val undo = DraftHistory.restore(saved, false)!!
                assertTrue(undo.recipe().texts.isEmpty())
                assertEquals(recipe(), DraftHistory.restore(undo, true)!!.recipe())
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
        val malformed = RecipeCodec.decode("""{"brightness":0.3,"texts":[{"id":"c","text":"one two","start":0,"end":2000,"caption":true,"wordHighlight":true,"wordTimings":["invalid"],"wordTimingText":"one two"}]}""")
        assertEquals(.3f, malformed.brightness, .001f)
        assertEquals("one two", malformed.texts.single().text)
        assertTrue(malformed.texts.single().wordTimings.isEmpty())
        assertTrue(malformed.texts.single().wordHighlight)
        assertEquals("Fade", RecipeCodec.decode("""{"texts":[{"id":"t","text":"Old","start":0,"end":1000}]}""").texts.single().animationName)
    }

    @Test fun wordTexturesChangeAtBoundariesAndSeekBackWithoutRemeasuring() {
        val titles = PreviewTitles(context); val c = caption()
        titles.update(listOf(c))
        val overlay = titles.overlays.last() as BitmapOverlay; overlay.configure(Size(360, 640))
        val first = overlay.getBitmap(200_000)
        val firstCue = yellow(first)
        assertTrue(firstCue.count > 100); assertTrue(firstCue.x < first.width / 2)
        assertSame(first, overlay.getBitmap(400_000))
        val gap = overlay.getBitmap(800_000); assertEquals(0, yellow(gap).count)
        val second = overlay.getBitmap(1_200_000)
        assertTrue(yellow(second).x > second.width / 2)
        assertEquals(first.width, second.width); assertEquals(first.height, second.height)
        val rewound = overlay.getBitmap(200_000)
        assertTrue(first.sameAs(rewound))
        titles.update(listOf(c.copy(x = .8f, animation = "Pop", animationMs = 500)))
        assertSame(rewound, overlay.getBitmap(200_000))
        val early = overlay.getOverlaySettings(100_000); val steady = overlay.getOverlaySettings(500_000)
        assertTrue(early.scale.first < steady.scale.first && early.alphaScale < steady.alphaScale)
        assertSame(rewound, overlay.getBitmap(400_000))
        // Switching off must remove the old word spans, including after an edited caption.
        titles.update(listOf(c.copy(wordHighlight = false))); assertEquals(0, yellow(overlay.getBitmap(200_000)).count)
        titles.update(listOf(CaptionWords.editText(c, "new line"))); assertTrue(yellow(overlay.getBitmap(200_000)).count > 100)
    }

    @Test fun draggedTimingSurvivesRoomHistoryAndPreviewBoundaries() = runBlocking {
        val name = "text-lane-test.db"; context.deleteDatabase(name)
        val axis = TimelineAxis(listOf(4000))
        val moved = TextLaneTiming.drag(caption(), axis, TextLaneTiming.Part.Move, axis.xAt(1000))
        val resized = TextLaneTiming.drag(moved, axis, TextLaneTiming.Part.End, axis.xAt(3500) - axis.xAt(3000))
        val original = DraftEntity("text-lane", "/private/source", durationMs = 4000, width = 360, height = 640,
            trimEndMs = 4000, editRecipe = RecipeCodec.encode(EditRecipe(texts = listOf(caption()))))
        val edited = DraftHistory.record(original, original.copy(editRecipe = RecipeCodec.encode(EditRecipe(texts = listOf(resized)))), 1000)
        try {
            val db = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try { db.studioDao().putDraft(edited) } finally { db.close() }
            val reopened = Room.databaseBuilder(context, ReelDatabase::class.java, name).build()
            try {
                val saved = reopened.studioDao().draft(original.id)!!
                assertEquals(resized, saved.recipe().texts.single())
                val undone = DraftHistory.restore(saved, false)!!
                assertEquals(caption(), undone.recipe().texts.single())
                assertEquals(resized, DraftHistory.restore(undone, true)!!.recipe().texts.single())
                val titles = PreviewTitles(context); titles.update(saved.recipe().texts)
                val overlay = titles.overlays.last() as BitmapOverlay; overlay.configure(Size(360, 640))
                assertEquals(0f, overlay.getOverlaySettings(500_000).alphaScale, 0f)
                assertTrue(overlay.getOverlaySettings(1_500_000).alphaScale > .9f)
                assertEquals(0f, overlay.getOverlaySettings(3_700_000).alphaScale, 0f)
                assertTrue(CaptionWords.hasSpeechTiming(saved.recipe().texts.single()))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun draggedTitleAppearsOnlyInsideItsExportedRange() = runBlocking {
        val source = fixture(); val output = File(context.cacheDir, "text-lane-export.mp4").apply { delete() }
        val axis = TimelineAxis(listOf(4000))
        val title = TimedText("dragged", "Your story", 0, 2000, animated = false, x = .5f, y = .2f)
        val moved = TextLaneTiming.drag(title, axis, TextLaneTiming.Part.Move, axis.xAt(1000))
        val resized = TextLaneTiming.drag(moved, axis, TextLaneTiming.Part.End, axis.xAt(2500) - axis.xAt(3000))
        val edited = draft(source).copy(trimEndMs = 4000, editRecipe = RecipeCodec.encode(EditRecipe(brightness = -1f, texts = listOf(resized))))
        try {
            withTimeout(90_000) { withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
                    }).build()
                try { transformer.start(StudioComposition.create(context, edited, ExportSettings(360, 640, 2_000_000)), output.path); done.await() }
                finally { transformer.cancel() }
            } }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                fun count(ms: Long): Int {
                    val frame = requireNotNull(reader.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 360, 640))
                    try { return whiteBounds(frame).width() } finally { frame.recycle() }
                }
                assertEquals(0, count(500))
                assertTrue(count(1500) > 20)
                assertEquals(0, count(3000))
                assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 3900..4100)
            } finally { reader.release() }
        } finally { source.delete(); output.delete() }
    }

    @Test fun animatedNativeExportKeepsWordTimingAndMotion() = runBlocking {
        val source = fixture(); val output = File(context.cacheDir, "text-motion-export.mp4")
        output.delete()
        try {
            withTimeout(90_000) { withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
                    }).build()
                try { transformer.start(StudioComposition.create(context, draft(source), ExportSettings(360, 640, 2_000_000)), output.path); done.await() }
                finally { transformer.cancel() }
            } }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 1900..2100)
                fun frame(ms: Long) = requireNotNull(reader.getScaledFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 360, 640))
                val early = frame(100); val first = frame(400); val gap = frame(800); val second = frame(1200)
                try {
                    val a = yellow(early, .5f); val b = yellow(first, .5f); val c = yellow(second, .5f)
                    assertTrue(a.count > 100 && b.count > 100 && c.count > 100)
                    assertTrue("Slide did not move: $a vs $b", a.y > b.y + 5)
                    assertTrue("First/second words did not change", b.x < 180 && c.x > 180)
                    assertEquals(0, yellow(gap, .5f).count)
                    assertTrue("Pop did not scale", whiteBounds(early).width() < whiteBounds(first).width())
                } finally { early.recycle(); first.recycle(); gap.recycle(); second.recycle() }
            } finally { reader.release() }
        } finally { source.delete(); output.delete() }
    }

    @Test fun nativePausedPreviewRestoresHighlightsAfterForwardAndBackwardSeeks() = runBlocking {
        val source = fixture()
        try {
            withTimeout(40_000) { withContext(Dispatchers.Main) {
                val frames = AtomicInteger(); val cue = AtomicReference(Cue(0, 0f, 0f))
                val reader = ImageReader.newInstance(360, 640, PixelFormat.RGBA_8888, 3)
                reader.setOnImageAvailableListener({ images -> images.acquireLatestImage()?.use { image ->
                    val p = image.planes[0]; val bytes = p.buffer
                    var count = 0; var sumX = 0L; var sumY = 0L
                    for (y in 320 until 640 step 2) for (x in 0 until 360 step 2) {
                        val i = y * p.rowStride + x * p.pixelStride
                        val r = bytes.get(i).toInt() and 255; val g = bytes.get(i + 1).toInt() and 255; val b = bytes.get(i + 2).toInt() and 255
                        if (isYellow(r, g, b)) { count++; sumX += x; sumY += y }
                    }
                    cue.set(Cue(count, if (count > 0) sumX.toFloat() / count else 0f, if (count > 0) sumY.toFloat() / count else 0f)); frames.incrementAndGet()
                } }, Handler(Looper.getMainLooper()))
                val player = studioPreviewPlayer(context, collage = false)
                var failure: PlaybackException? = null
                player.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure = error } })
                try {
                    player.setVideoSurface(reader.surface, Size(360, 640))
                    player.setComposition(StudioComposition.create(context, draft(source), ExportSettings(360, 640, 1_500_000)))
                    player.prepare(); player.play()
                    withTimeout(15_000) { while (player.currentPosition < 250 || frames.get() < 2) { failure?.let { throw it }; delay(30) } }
                    player.pause(); delay(100)
                    for ((ms, side) in listOf(400L to -1, 1200L to 1, 800L to 0, 400L to -1)) {
                        val before = frames.get()
                        player.seekTo(ms); player.experimentalRedrawLastFrame()
                        withTimeout(5000) { while (true) {
                            failure?.let { throw it }
                            val active = cue.get()
                            val match = if (side == 0) active.count == 0 else active.count > 50 && if (side < 0) active.x < 180 else active.x > 180
                            if (frames.get() > before && abs(player.currentPosition - ms) < 80 && match) break
                            delay(30)
                        } }
                        assertFalse(player.isPlaying)
                    }
                    assertNull(failure)
                } finally { player.release(); reader.close() }
            } }
        } finally { source.delete() }
    }

    private data class Cue(val count: Int, val x: Float, val y: Float)
    // Include dimmed entrance frames; chroma separation still excludes white glyphs and the black source.
    private fun isYellow(r: Int, g: Int, b: Int) = r > 40 && g > 35 && r - b > 25 && g - b > 20
    private fun yellow(bitmap: Bitmap, fromY: Float = 0f): Cue {
        var count = 0; var sumX = 0L; var sumY = 0L
        for (y in (bitmap.height * fromY).toInt() until bitmap.height) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            if (isYellow(Color.red(p), Color.green(p), Color.blue(p))) { count++; sumX += x; sumY += y }
        }
        return Cue(count, if (count > 0) sumX.toFloat() / count else 0f, if (count > 0) sumY.toFloat() / count else 0f)
    }
    private fun whiteBounds(bitmap: Bitmap): Rect {
        val bounds = Rect()
        for (y in 0 until 250) for (x in 0 until bitmap.width) {
            val p = bitmap.getPixel(x, y)
            if (Color.red(p) > 35 && abs(Color.red(p) - Color.blue(p)) < 10) bounds.union(x, y, x + 1, y + 1)
        }
        return bounds
    }
}
