package com.reelshare.feature.creator

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.*
import androidx.test.platform.app.InstrumentationRegistry
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CollageMediaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()

    @Test fun threeVideoRowsSurviveRecipeRestoreAndExport() = runBlocking {
        val folder = File(context.cacheDir, "collage-three").apply { mkdirs() }
        try {
            val video = File(folder, "video.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
            val assets = List(3) { CollageAsset("$it", video.path, "Video", false, 4000, 360, 640) }
            val recipe = EditRecipe(collage = Collage(assets, layout = "Rows", gap = .02f, audioIndex = 1))
            val restored = RecipeCodec.decode(RecipeCodec.encode(recipe))
            assertEquals(recipe, restored)
            val draft = DraftEntity("three", video.path, durationMs = 2000, width = 360, height = 640, trimEndMs = 2000,
                editRecipe = RecipeCodec.encode(restored))
            val output = File(folder, "out.mp4")
            render(draft, output)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                val frame = reader.getScaledFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST, 180, 320)!!
                try {
                    for (y in listOf(53, 160, 267)) {
                        val p = frame.getPixel(90, y)
                        assertTrue("Row $y is empty", Color.red(p) + Color.green(p) + Color.blue(p) > 120)
                    }
                    val gutter = frame.getPixel(90, 107)
                    assertTrue(Color.red(gutter) + Color.green(gutter) + Color.blue(gutter) < 100)
                } finally { frame.recycle() }
            } finally { reader.release() }
        } finally { folder.deleteRecursively() }
    }

    @Test fun twoVideosAndPhotosExportIntoTheirOwnCells() = runBlocking {
        val folder = File(context.cacheDir, "collage-mixed").apply { mkdirs() }
        try {
            val video = File(folder, "video.mp4")
            InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
            val red = photo(folder, "red", Color.RED)
            val blue = photo(folder, "blue", Color.BLUE)
            val asset = CollageAsset("v", video.path, "Video", false, 4000, 360, 640)
            val collage = Collage(listOf(asset, red, asset.copy(id = "v2"), blue), gap = .02f, audioIndex = 0)
            val recipe = EditRecipe(collage = collage, texts = listOf(TimedText("t", "Together", 0, 1500, position = "Top")))
            assertEquals(recipe, RecipeCodec.decode(RecipeCodec.encode(recipe)))
            val draft = DraftEntity("mixed", video.path, durationMs = 2000, width = 360, height = 640, trimEndMs = 2000,
                editRecipe = RecipeCodec.encode(recipe))
            val out = File(folder, "out.mp4")
            render(draft, out)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(out.path)
                assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 1900..2150)
                val frame = reader.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST, 180, 320)!!
                try {
                    assertTrue(Color.red(frame.getPixel(135, 100)) > 180)
                    assertTrue(Color.blue(frame.getPixel(135, 240)) > 180)
                    assertTrue(Color.red(frame.getPixel(90, 200)) < 60) // Central gutter.
                } finally { frame.recycle() }
            } finally { reader.release() }
        } finally { folder.deleteRecursively() }
    }

    @Test fun photoOnlyCollageExportsAtEditedSpeedWithoutAudio() = runBlocking {
        val folder = File(context.cacheDir, "collage-photos").apply { mkdirs() }
        try {
            val red = photo(folder, "red", Color.RED)
            val blue = photo(folder, "blue", Color.BLUE)
            val recipe = EditRecipe(speed = 2f, collage = Collage(listOf(red, blue), layout = "Rows", gap = 0f))
            val draft = DraftEntity("photos", red.path, durationMs = 2000, width = 360, height = 640, trimEndMs = 2000,
                editRecipe = RecipeCodec.encode(recipe))
            val output = File(folder, "out.mp4")
            render(draft, output)
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                assertTrue(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() in 900..1150)
                assertNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                val frame = reader.getScaledFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST, 90, 160)!!
                try {
                    assertTrue(Color.red(frame.getPixel(45, 40)) > 180)
                    assertTrue(Color.blue(frame.getPixel(45, 120)) > 180)
                } finally { frame.recycle() }
            } finally { reader.release() }
        } finally { folder.deleteRecursively() }
    }

    private fun photo(folder: File, name: String, color: Int): CollageAsset {
        val file = File(folder, "$name.png")
        val bitmap = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
        return CollageAsset(name, file.path, name, true, 0, 160, 160)
    }

    private suspend fun render(draft: DraftEntity, output: File) = withTimeout(90_000) { withContext(Dispatchers.Main) {
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
            }).build()
        try { transformer.start(StudioComposition.create(context, draft, ExportSettings(360, 640, 1_000_000)), output.path); done.await() }
        finally { transformer.cancel() }
    } }
}
