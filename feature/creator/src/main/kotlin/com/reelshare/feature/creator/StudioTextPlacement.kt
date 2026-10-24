package com.reelshare.feature.creator

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/** Only gesture guides are drawn here. Media3 draws the actual text in preview and export. */
@Composable
internal fun StudioTextPlacement(texts: List<TimedText>, position: Long, settings: ExportSettings,
    selectedId: String?, onSelect: (String) -> Unit, onDrag: (TimedText?) -> Unit, onCommit: (TimedText) -> Unit) {
    val context = LocalContext.current
    val fonts = remember(context) { StudioTextFonts(context) }
    val select by rememberUpdatedState(onSelect)
    val drag by rememberUpdatedState(onDrag)
    val commit by rememberUpdatedState(onCommit)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val frameWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val frameHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        texts.filter { it.text.isNotBlank() && position >= it.startMs && position < it.endMs }.forEach { entry ->
            key(entry.id) {
                val rasterKey = entry.copy(x = null, y = null, position = "Bottom", startMs = 0, endMs = 100, animated = false)
                val raster = remember(rasterKey, settings.width, fonts) { TextPlacement.raster(entry, fonts.get(entry.font), settings.width) }
                val rect = TextPlacement.place(entry, settings.width, settings.height, raster).rect
                val latest by rememberUpdatedState(entry)
                val latestRaster by rememberUpdatedState(raster)
                fun nudge(dx: Float, dy: Float): Boolean {
                    select(entry.id)
                    commit(TextPlacement.move(latest, dx, dy, settings.width, settings.height, latestRaster))
                    return true
                }
                Box(Modifier.zIndex(if (entry.id == selectedId) 1f else 0f)
                    .offset { IntOffset((rect.left * frameWidth).roundToInt(), (rect.top * frameHeight).roundToInt()) }
                    .size(maxWidth * rect.width(), maxHeight * rect.height())
                    .border(if (entry.id == selectedId) 2.dp else 1.dp,
                        if (entry.id == selectedId) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White.copy(alpha = .65f), RoundedCornerShape(6.dp))
                    .semantics {
                        contentDescription = "Move ${if (entry.caption) "caption" else "title"}: ${entry.text}"
                        customActions = listOf(CustomAccessibilityAction("Move left") { nudge(-.03f, 0f) },
                            CustomAccessibilityAction("Move right") { nudge(.03f, 0f) },
                            CustomAccessibilityAction("Move up") { nudge(0f, -.03f) },
                            CustomAccessibilityAction("Move down") { nudge(0f, .03f) })
                    }
                    .pointerInput(entry.id, frameWidth, frameHeight) {
                        var moving: TimedText? = null
                        detectDragGestures(onDragStart = { select(entry.id); moving = latest },
                            onDragCancel = { moving = null; drag(null) },
                            onDragEnd = { moving?.let(commit); moving = null; drag(null) }) { change, amount ->
                            change.consume()
                            moving = TextPlacement.move(moving ?: latest, amount.x / frameWidth, amount.y / frameHeight,
                                settings.width, settings.height, latestRaster)
                            drag(moving)
                        }
                    })
            }
        }
    }
}
