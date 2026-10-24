package com.reelshare.feature.creator

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Explicit bounded dimensions keep the native surface and overlay hit boxes on the same frame. */
@Composable
internal fun StudioPreviewFrame(ratio: Float, content: @Composable BoxScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val safeRatio = ratio.takeIf { it.isFinite() && it > 0 } ?: 1f
        val width = minOf(maxWidth, maxHeight * safeRatio)
        Box(Modifier.size(width, width / safeRatio).clipToBounds().testTag("video-frame").semantics { contentDescription = "Video frame" }, content = content)
    }
}
