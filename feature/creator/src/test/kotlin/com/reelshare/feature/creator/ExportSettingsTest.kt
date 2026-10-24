package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class ExportSettingsTest {
    @Test fun portraitBalancedTargets720WithoutUpscaling() {
        assertEquals(ExportSettings(720, 1280, 3_000_000), ExportSettings.forVideo(1080, 1920, "9:16", "Balanced", false))
        assertEquals(360, ExportSettings.forVideo(360, 640, "9:16", "High", false).width)
    }
    @Test fun lowRamLimitsHighQuality() {
        assertEquals(ExportSettings(480, 480, 1_500_000), ExportSettings.forVideo(3840, 2160, "1:1", "High", true))
    }
    @Test fun dimensionsStayEvenForCodecCompatibility() {
        val output = ExportSettings.forVideo(1920, 1080, "16:9", "Small", false)
        assertEquals(0, output.width % 2)
        assertEquals(0, output.height % 2)
        assertEquals(480, output.height)
    }
    @Test fun originalAspectIsPreserved() {
        val output = ExportSettings.forVideo(1920, 1080, "Original", "Balanced", false)
        assertEquals(1280, output.width)
        assertEquals(720, output.height)
    }
    @Test fun oppositeAspectCropDoesNotUpscale() {
        val output = ExportSettings.forVideo(1080, 1920, "16:9", "High", false)
        assertTrue(output.width <= 1080)
        assertTrue(output.height <= 1920)
    }
    @Test fun extremeAspectIsBounded() {
        val output = ExportSettings.forVideo(256, 4096, "Original", "High", false)
        assertTrue(output.height <= 1920)
    }
}
