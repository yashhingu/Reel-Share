package com.reelshare.feature.creator

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.test.platform.app.InstrumentationRegistry
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class StudioPreviewTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun mixedCollageCanPlayAgainAfterReachingTheEnd() = runBlocking {
        val folder = File(context.createDeviceProtectedStorageContext().cacheDir, "preview-loop-fixture").apply { mkdirs() }
        val source = File(folder, "video.mp4")
        val photo = File(folder, "photo.jpg")
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        try {
            withTimeout(30_000) { withContext(Dispatchers.Main) {
                val frames = AtomicInteger()
                val reader = ImageReader.newInstance(360, 640, PixelFormat.RGBA_8888, 3)
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.use { frames.incrementAndGet() } }, Handler(Looper.getMainLooper()))
                var player = studioPreviewPlayer(context, collage = true)
                var failure: PlaybackException? = null
                val listener = object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure = error } }
                player.addListener(listener)
                try {
                    val assets = listOf(CollageAsset("photo", photo.path, "Photo", true, 0, 32, 32),
                        CollageAsset("video", source.path, "Video", false, 4000, 360, 640))
                    val draft = DraftEntity("mixed-loop", photo.path, durationMs = 4000, width = 360, height = 640, trimEndMs = 2000,
                        editRecipe = RecipeCodec.encode(EditRecipe(collage = Collage(assets, "Rows", audioIndex = 1))))
                    player.setVideoSurface(reader.surface, Size(360, 640))
                    player.setComposition(StudioComposition.create(context, draft, ExportSettings(360, 640, 1_000_000)))
                    player.prepare(); player.play()
                    withTimeout(12_000) { while (player.playbackState != Player.STATE_ENDED) { failure?.let { throw it }; delay(50) } }
                    val before = frames.get()
                    // The editor recreates an ended multi-input graph before replay.
                    player.release()
                    player = studioPreviewPlayer(context, collage = true)
                    player.addListener(listener)
                    player.setVideoSurface(reader.surface, Size(360, 640))
                    player.setComposition(StudioComposition.create(context, draft, ExportSettings(360, 640, 1_000_000)))
                    player.prepare(); player.play()
                    withTimeout(8_000) { while (player.currentPosition < 500 || frames.get() < before + 3) { failure?.let { throw it }; delay(50) } }
                    assertNull(failure)
                } finally { player.release(); reader.close() }
            } }
        } finally { source.delete(); photo.delete(); folder.delete() }
    }

    @Test fun titleTextureChangesEvenWhenTheTitleIdStaysTheSame() {
        val titles = PreviewTitles(context)
        val overlay = titles.overlays[0] as BitmapOverlay
        overlay.configure(Size(360, 640))
        titles.update(listOf(TimedText("same", "Short", 0, 3000)))
        val initialHeight = overlay.getBitmap(500_000).height
        titles.update(listOf(TimedText("same", "First\nSecond\nThird", 0, 3000)))
        assertTrue(overlay.getBitmap(500_000).height > initialHeight)
        titles.update(emptyList())
        assertEquals(1, overlay.getBitmap(500_000).width)
    }

    @Test fun threeVideoCollagePreviewProducesFramesAndAdvancesClock() = runBlocking {
        val source = File(context.createDeviceProtectedStorageContext().cacheDir, "preview-collage-fixture.mp4")
        source.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        try {
            withTimeout(30_000) { withContext(Dispatchers.Main) {
                val frames = AtomicInteger()
                val reader = ImageReader.newInstance(360, 640, PixelFormat.RGBA_8888, 3)
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.use { frames.incrementAndGet() } }, Handler(Looper.getMainLooper()))
                val player = studioPreviewPlayer(context, collage = true)
                var failure: PlaybackException? = null
                player.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure = error } })
                try {
                    val assets = List(3) { CollageAsset("$it", source.path, "Video", false, 4000, 360, 640) }
                    val draft = DraftEntity("collage-preview", source.path, durationMs = 4000, width = 360, height = 640, trimEndMs = 4000,
                        editRecipe = RecipeCodec.encode(EditRecipe(collage = Collage(assets, "Rows", audioIndex = 1))))
                    player.setVideoSurface(reader.surface, Size(360, 640))
                    player.setComposition(StudioComposition.create(context, draft, ExportSettings(360, 640, 1_000_000)))
                    player.prepare(); player.play()
                    withTimeout(20_000) { while (player.currentPosition < 500 || frames.get() < 3) { failure?.let { throw it }; delay(50) } }
                    assertNull(failure)
                } finally { player.release(); reader.close() }
            } }
        } finally { source.delete() }
    }

    @Test fun addingEditingAndRemovingTitlesKeepsThePreviewMoving() = runBlocking {
        val source = File(context.createDeviceProtectedStorageContext().cacheDir, "preview-title-fixture.mp4")
        source.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        try {
            withTimeout(30_000) { withContext(Dispatchers.Main) {
                val frames = AtomicInteger()
                val reader = ImageReader.newInstance(360, 640, PixelFormat.RGBA_8888, 3)
                reader.setOnImageAvailableListener({ it.acquireLatestImage()?.use { frames.incrementAndGet() } }, Handler(Looper.getMainLooper()))
                val titles = PreviewTitles(context)
                val player = studioPreviewPlayer(context, collage = false)
                var failure: PlaybackException? = null
                player.addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure = error } })
                try {
                    val draft = DraftEntity("preview", source.path, durationMs = 4000, width = 360, height = 640, trimEndMs = 4000)
                    player.setVideoSurface(reader.surface, Size(360, 640))
                    player.setComposition(StudioComposition.create(context, draft, ExportSettings.forVideo(360, 640, "Original", "Small", true), previewTitles = titles))
                    player.prepare(); player.play()
                    withTimeout(15_000) { while (player.currentPosition < 250 || frames.get() < 2) { failure?.let { throw it }; delay(50) } }
                    val start = player.currentPosition
                    titles.update(listOf(TimedText("one", "Your story", 0, 4000)))
                    player.experimentalRedrawLastFrame()
                    delay(450)
                    assertTrue("Clock stalled after adding title", player.currentPosition >= start + 200)
                    player.pause(); delay(150)
                    val paused = player.currentPosition
                    val before = frames.get()
                    titles.update(listOf(TimedText("one", "Changed title", 0, 4000, position = "Top",
                        rotation = 37f, font = "Serif", style = "Label")))
                    player.experimentalRedrawLastFrame(); delay(350)
                    assertTrue("Paused title edit moved the clock", kotlin.math.abs(player.currentPosition - paused) < 80)
                    assertTrue("Paused title edit did not redraw", frames.get() > before)
                    player.play()
                    titles.update(listOf(TimedText("one", "Keep moving", 0, 4000, rotation = -60f, font = "Typewriter", style = "Outline")))
                    player.experimentalRedrawLastFrame(); delay(400)
                    assertTrue("Clock stalled after text style edit", player.currentPosition >= paused + 200)
                    titles.update(emptyList()); player.experimentalRedrawLastFrame(); player.play()
                    delay(500)
                    assertTrue("Clock stalled after title removal", player.currentPosition >= paused + 200)
                    assertNull(failure)
                } finally {
                    player.release(); reader.close()
                }
            } }
        } finally { source.delete() }
    }
}
