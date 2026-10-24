package com.reelshare.feature.creator

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
class StudioMediaTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()
    private fun fixture(): File = File(context.cacheDir, "media-fixture.mp4").also { file ->
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @Test fun recipeRoundTripPreservesTheCompleteTimeline() {
        val recipe = EditRecipe(brightness = .2f, warmth = -.5f, filter = "Amber", strength = .6f,
            speed = .5f, ramp = true, rampEnd = 2f, fadeInMs = 500, motion = true, panEnd = -.5f,
            transition = "Fade white", texts = listOf(TimedText("a", "Hello नमस्ते", 123, 1234)),
            clips = listOf(VideoClip("c", "/private/video", "First", 4000, 500, 3500, 360, 640)))
        assertEquals(recipe, RecipeCodec.decode(RecipeCodec.encode(recipe)))
    }

    @Test fun retiredMonoRecipesKeepOtherEdits() {
        val legacy = """{"version":1,"filter":"Mono","brightness":0.2,"speed":2,"motion":true}"""
        assertTrue(RecipeCodec.hasRetiredFilter(legacy))
        val recipe = RecipeCodec.decode(legacy)
        assertEquals("None", recipe.filter)
        assertEquals(.2f, recipe.brightness, .001f)
        assertEquals(2f, recipe.speed, .001f)
        assertTrue(recipe.motion)
        assertFalse(RecipeCodec.hasRetiredFilter(RecipeCodec.encode(recipe)))
    }

    @Test fun decodedTrimHasCorrectPcmDuration() = runBlocking {
        val file = fixture()
        var samples = 0L
        try {
            CaptionAudioDecoder.read(VideoClip("c", file.path, "Test", 4000, 500, 2500, 360, 640), { samples += it.size }, {})
            assertTrue("Decoded $samples samples", samples in 31_900..32_100)
        } finally { file.delete() }
    }

    @Test fun speedRampAndShortSoundtrackExportOnTheSameClock() = runBlocking {
        val file = fixture()
        val output = File(context.cacheDir, "ramp-integration.mp4")
        output.delete()
        val recipe = EditRecipe(speed = .5f, ramp = true, rampEnd = 2f)
        val draft = DraftEntity("ramp", file.path, durationMs = 4000, width = 360, height = 640, trimStartMs = 500, trimEndMs = 1500,
            audioPath = file.path, audioDurationMs = 4000, audioEndMs = 1000, editRecipe = RecipeCodec.encode(recipe))
        try {
            withTimeout(90_000) { withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
                    }).build()
                try { transformer.start(StudioComposition.create(context, draft, ExportSettings.forVideo(360, 640, "Original", "Small", true)), output.path); done.await() }
                finally { transformer.cancel() }
            } }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                val actual = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Expected ${draft.outputDuration()}, got $actual", kotlin.math.abs(actual - draft.outputDuration()) < 120)
                assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
            } finally { reader.release() }
        } finally { file.delete(); output.delete() }
    }

    @Test fun splitRampExportsWithoutRestartingItsSpeedProgression() = runBlocking {
        val file = fixture()
        val output = File(context.cacheDir, "split-ramp-integration.mp4")
        output.delete()
        val recipe = EditRecipe(speed = .5f, ramp = true, rampEnd = 2f)
        val original = DraftEntity("split-ramp", file.path, durationMs = 4000, width = 360, height = 640,
            trimStartMs = 0, trimEndMs = 4000, editRecipe = RecipeCodec.encode(recipe))
        val split = TimelineEdits.split(original, original.id, original.outputDuration() / 2)
        try {
            assertEquals(2, split.timeline().size)
            assertTrue(kotlin.math.abs(original.outputDuration() - split.outputDuration()) <= 2)
            withTimeout(90_000) { withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
                    }).build()
                try { transformer.start(StudioComposition.create(context, split, ExportSettings.forVideo(360, 640, "Original", "Small", true)), output.path); done.await() }
                finally { transformer.cancel() }
            } }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                val actual = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Expected ${original.outputDuration()}, got $actual", kotlin.math.abs(actual - original.outputDuration()) < 180)
                assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            } finally { reader.release() }
        } finally { file.delete(); output.delete() }
    }

    @Test fun exportCombinesClipsSpeedMusicTitlesMotionAndTransitions() = runBlocking {
        val file = fixture()
        val output = File(context.cacheDir, "effects-integration.mp4")
        output.delete()
        val recipe = EditRecipe(saturation = -1f, speed = 2f, motion = true, zoomEnd = 1.3f,
            fadeInMs = 200, fadeOutMs = 200, transition = "Fade black", transitionMs = 400,
            texts = listOf(TimedText("title", "Made here", 200, 800, position = "Top")),
            clips = listOf(VideoClip("one", file.path, "One", 4000, 0, 2000, 360, 640), VideoClip("two", file.path, "Two", 4000, 2000, 4000, 360, 640)))
        val draft = DraftEntity("test", file.path, durationMs = 4000, width = 360, height = 640, trimEndMs = 4000,
            audioPath = file.path, audioDurationMs = 4000, audioEndMs = 2000, editRecipe = RecipeCodec.encode(recipe))
        try {
            withTimeout(90_000) { withContext(Dispatchers.Main) {
                val done = CompletableDeferred<Unit>()
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
                    }).build()
                try { transformer.start(StudioComposition.create(context, draft, ExportSettings.forVideo(360, 640, "Original", "Small", true)), output.path); done.await() }
                finally { transformer.cancel() }
            } }
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(output.path)
                val duration = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                assertTrue("Actual duration $duration", duration in 1900..2150)
                assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertEquals("yes", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                val frame = reader.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST, 90, 160)!!
                try {
                    val pixel = frame.getPixel(45, 80)
                    assertTrue("Transition should pass through black", android.graphics.Color.red(pixel) < 40)
                } finally { frame.recycle() }
                val mono = reader.getScaledFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST, 90, 160)!!
                try {
                    val pixel = mono.getPixel(45, 80)
                    assertTrue(kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.green(pixel)) < 8)
                    assertTrue(android.graphics.Color.red(pixel) > 60)
                } finally { mono.recycle() }
            } finally { reader.release() }
        } finally { file.delete(); output.delete() }
    }
}
