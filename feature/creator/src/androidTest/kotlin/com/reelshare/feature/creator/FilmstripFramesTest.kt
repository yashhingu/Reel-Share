package com.reelshare.feature.creator

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FilmstripFramesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.createDeviceProtectedStorageContext()

    @Test fun framesAreSmallAndReusedAndBadMediaKeepsAPlaceholder() = runBlocking {
        val source = File(context.cacheDir, "filmstrip-fixture.mp4")
        val bad = File(context.cacheDir, "filmstrip-corrupt.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("media-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        try {
            val times = listOf(0L, 500L, 1500L)
            val frames = FilmstripFrames.load(source.path, times)
            assertEquals(3, frames.size)
            frames.forEach { frame ->
                assertNotNull(frame)
                assertTrue(frame!!.width <= 96 && frame.height <= 128)
            }
            val again = FilmstripFrames.load(source.path, times)
            frames.indices.forEach { assertSame(frames[it], again[it]) }
            bad.writeText("Not a video")
            assertTrue(FilmstripFrames.load(bad.path, times).all { it == null })
            assertTrue(FilmstripFrames.load(File(context.cacheDir, "absent-filmstrip.mp4").path, times).all { it == null })
        } finally { source.delete(); bad.delete() }
    }
}
