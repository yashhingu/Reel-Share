package com.reelshare.feature.creator

import android.graphics.Typeface
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.test.platform.app.InstrumentationRegistry
import com.reelshare.core.database.entity.DraftEntity
import org.junit.Assert.*
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TextPlacementTest {
    @Test fun coordinatesPersistThroughRecipeAndUndoWhileLegacyPositionsStillLoad() {
        val legacy = RecipeCodec.decode("""{"texts":[{"id":"title","text":"Hello","start":0,"end":1000,"position":"Top"}]}""").texts.single()
        assertNull(legacy.x); assertNull(legacy.y)
        val raster = TextPlacement.raster(legacy, Typeface.DEFAULT_BOLD, 360)
        assertEquals(.11f, TextPlacement.place(legacy, 360, 640, raster).rect.top, .001f)
        val draft = DraftEntity("drag", "/source", durationMs = 2000, width = 360, height = 640, trimEndMs = 2000, editRecipe = RecipeCodec.encode(EditRecipe(texts = listOf(legacy))))
        val moved = legacy.copy(x = .3f, y = .6f)
        val edited = DraftHistory.record(draft, draft.copy(editRecipe = RecipeCodec.encode(EditRecipe(texts = listOf(moved)))), 1000)
        assertEquals(moved, edited.recipe().texts.single())
        val undone = DraftHistory.restore(edited, false)!!
        assertEquals(legacy, undone.recipe().texts.single())
        assertEquals(moved, DraftHistory.restore(undone, true)!!.recipe().texts.single())
        assertNull(moved.copy(x = Float.NaN).bounded().x)
    }

    @Test fun draggingAndLongTextStayInsidePortraitAndLandscapeFrames() {
        for ((width, height) in listOf(360 to 640, 640 to 360, 480 to 480)) {
            for (text in listOf("Hi", "Long multiline caption ".repeat(8))) {
                val entry = TimedText("title", text, 0, 2000, size = .12f)
                val raster = TextPlacement.raster(entry, Typeface.DEFAULT_BOLD, width)
                for ((dx, dy) in listOf(-10f to -10f, 10f to 10f, .1f to -.2f)) {
                    val moved = TextPlacement.move(entry, dx, dy, width, height, raster)
                    val rect = TextPlacement.place(moved, width, height, raster).rect
                    assertTrue(rect.left >= -.001f && rect.top >= -.001f && rect.right <= 1.001f && rect.bottom <= 1.001f)
                }
            }
        }
    }

    @Test fun movingTheTitleReusesItsTextureAndUpdatesItsRenderAnchor() {
        val titles = PreviewTitles(InstrumentationRegistry.getInstrumentation().targetContext)
        val overlay = titles.overlays.first() as BitmapOverlay
        overlay.configure(Size(360, 640))
        val entry = TimedText("title", "Drag me", 0, 2000, animated = false, x = .4f, y = .3f)
        titles.update(listOf(entry))
        val bitmap = overlay.getBitmap(500_000)
        val before = overlay.getOverlaySettings(500_000).backgroundFrameAnchor
        titles.update(listOf(entry.copy(x = .6f, y = .7f)))
        assertSame(bitmap, overlay.getBitmap(500_000))
        val after = overlay.getOverlaySettings(500_000).backgroundFrameAnchor
        assertTrue(after.first > before.first)
        assertTrue(after.second < before.second)
    }
}
