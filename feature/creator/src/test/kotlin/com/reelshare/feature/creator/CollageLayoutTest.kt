package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class CollageLayoutTest {
    @Test fun everyLayoutKeepsItemsInsideTheCanvasWithoutOverlap() {
        for (count in 2..4) for (layout in Collage.LAYOUTS) {
            val cells = CollageLayout.cells(count, layout, .04f)
            assertEquals(count, cells.size)
            cells.forEach { assertTrue(it.left >= 0 && it.top >= 0 && it.width > 0 && it.height > 0 && it.left + it.width <= 1.001f && it.top + it.height <= 1.001f) }
            cells.forEachIndexed { i, a -> cells.drop(i + 1).forEach { b ->
                assertTrue(a.left + a.width <= b.left + .001f || b.left + b.width <= a.left + .001f ||
                    a.top + a.height <= b.top + .001f || b.top + b.height <= a.top + .001f)
            } }
        }
    }
    @Test fun invalidRecipeCannotAllocateUnboundedVideoDecoders() {
        val video = CollageAsset("v", "/video", "Video", false, 4000, 360, 640)
        assertNull(Collage(List(4) { video.copy(id = "$it") }).bounded())
        val bounded = Collage(listOf(video, video.copy(id = "two")), gap = Float.NaN, audioIndex = 8).bounded()!!
        assertEquals(0f, bounded.gap, 0f); assertEquals(-1, bounded.audioIndex)
    }
    @Test fun reusableStylesDoNotContainCollageMedia() {
        val image = CollageAsset("p", "/private/photo", "Private", true, 0, 100, 100)
        assertNull(EditRecipe(collage = Collage(listOf(image, image.copy(id = "two")))).style().collage)
    }
}
