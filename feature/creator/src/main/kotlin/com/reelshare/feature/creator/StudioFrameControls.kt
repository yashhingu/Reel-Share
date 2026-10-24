package com.reelshare.feature.creator

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun StudioFrameDrag(focus: VideoReframe.Focus, sourceWidth: Int, sourceHeight: Int,
    outputWidth: Int, outputHeight: Int, onStart: () -> Unit, onDrag: (VideoReframe.Focus?) -> Unit,
    onCommit: (VideoReframe.Focus) -> Unit, onTap: () -> Unit) {
    val latest by rememberUpdatedState(focus)
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val commit by rememberUpdatedState(onCommit)
    val tap by rememberUpdatedState(onTap)
    Box(Modifier.fillMaxSize().semantics { contentDescription = "Drag video to reframe" }
        .pointerInput(Unit) { detectTapGestures(onTap = { tap() }) }
        .pointerInput(sourceWidth, sourceHeight, outputWidth, outputHeight) {
            var moving: VideoReframe.Focus? = null
            detectDragGestures(onDragStart = { start(); moving = latest },
                onDragCancel = { moving = null; drag(null) },
                onDragEnd = { moving?.let(commit); moving = null; drag(null) }) { change, amount ->
                change.consume()
                moving = VideoReframe.move(moving ?: latest, amount.x / size.width.coerceAtLeast(1),
                    amount.y / size.height.coerceAtLeast(1), sourceWidth, sourceHeight, outputWidth, outputHeight)
                drag(moving)
            }
        })
}

@Composable
internal fun StudioFrameControls(focus: VideoReframe.Focus, enabled: Boolean,
    onDrag: (VideoReframe.Focus?) -> Unit, onCommit: (VideoReframe.Focus) -> Unit) {
    ToolHeading("Find your frame", "Drag the video to choose what stays in view. Use Zoom for a closer crop.") {
        IconButton({ onCommit(VideoReframe.Focus()) }, enabled = enabled && focus != VideoReframe.Focus()) {
            Icon(Icons.Default.RestartAlt, "Reset frame")
        }
    }
    var sliding by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(focus.zoom) }
    LaunchedEffect(focus.zoom, sliding) { if (!sliding) zoom = focus.zoom }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Zoom", style = MaterialTheme.typography.labelLarge)
        Text(String.format(Locale.ROOT, "%.1f×", zoom), color = Accent, style = MaterialTheme.typography.labelLarge)
    }
    Slider(zoom, { zoom = it; sliding = true; onDrag(focus.copy(zoom = it)) }, enabled = enabled,
        valueRange = 1f..2f, onValueChangeFinished = {
            sliding = false; onCommit(focus.copy(zoom = zoom)); onDrag(null)
        })
    Text("Drag in the preview to position the source. The crop stays inside the video at every zoom level.",
        style = MaterialTheme.typography.bodySmall, color = Muted)
}
