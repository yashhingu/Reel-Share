package com.reelshare.feature.creator

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.delay
import kotlin.math.*

@Composable
private fun frames(clip: VideoClip, whole: Boolean = false, window: LongRange? = null, visible: Boolean = true): List<Bitmap?> {
    val start = window?.first ?: if (whole) 0 else clip.startMs
    val end = window?.last ?: if (whole) clip.durationMs else clip.endMs
    val times = remember(clip.path, start, end) { List(8) { start + (end - start - 1).coerceAtLeast(0) * it / 7 } }
    val result by produceState<List<Bitmap?>>(emptyList(), clip.path, times, visible) {
        value = if (visible) FilmstripFrames.load(clip.path, times) else emptyList()
    }
    return result
}

@Composable
private fun Filmstrip(clip: VideoClip, modifier: Modifier, whole: Boolean = false, visibleLeft: Float = 0f, visibleWidth: Float = Float.MAX_VALUE, window: LongRange? = null, visible: Boolean = true) {
    val images = frames(clip, whole, window, visible)
    val background = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(modifier.background(background)) {
        val tile = 44.dp.toPx()
        val first = floor(visibleLeft.coerceAtLeast(0f) / tile).toInt()
        val last = min(ceil(size.width / tile).toInt(), ceil((visibleLeft + visibleWidth).coerceAtMost(size.width) / tile).toInt())
        for (i in first until last) {
            val frame = images.getOrNull(((i * tile / size.width) * images.size).toInt().coerceIn(0, (images.size - 1).coerceAtLeast(0))) ?: continue
            val cropWidth = min(frame.width, (frame.height * tile / size.height).roundToInt()).coerceAtLeast(1)
            val cropHeight = min(frame.height, (frame.width * size.height / tile).roundToInt()).coerceAtLeast(1)
            drawImage(frame.asImageBitmap(), srcOffset = IntOffset((frame.width - cropWidth) / 2, (frame.height - cropHeight) / 2),
                srcSize = IntSize(cropWidth, cropHeight), dstOffset = IntOffset((i * tile).roundToInt(), 0), dstSize = IntSize(ceil(tile).toInt(), size.height.roundToInt()))
        }
    }
}

@Composable
private fun AudioLane(id: String, label: String, axis: TimelineAxis, offsetMs: Long, sourceLengthMs: Long,
    path: String, durationMs: Long, sourceStartMs: Long, viewportLeft: Float, viewportWidth: Float,
    color: Color, selected: Boolean, enabled: Boolean, select: () -> Unit, pause: () -> Unit,
    trim: (AudioLaneRange) -> Unit, move: (Long) -> Unit) {
    val original = AudioLaneRange(sourceStartMs, sourceStartMs + sourceLengthMs, offsetMs)
    var range by remember(id, original, axis) { mutableStateOf(original) }
    val visualOffset = range.offsetMs
    var edge by remember { mutableIntStateOf(-1) }
    var editing by remember { mutableStateOf(false) }
    val latestTrim by rememberUpdatedState(trim)
    var dragDp by remember(id, offsetMs, axis) { mutableFloatStateOf(0f) }
    val latestMove by rememberUpdatedState(move)
    val latestPause by rememberUpdatedState(pause)
    val latestSelect by rememberUpdatedState(select)
    val density = LocalDensity.current
    val maxOffset = AudioLanePlacement.maxOffset(axis.durationMs, sourceLengthMs)
    val left = axis.xAt(visualOffset)
    val length = minOf(range.endMs - range.startMs, axis.durationMs - visualOffset).coerceAtLeast(0)
    val waveform = rememberAudioWaveform(path, durationMs)
    val ink = MaterialTheme.colorScheme.onTertiaryContainer
    Box(Modifier.fillMaxWidth().height(44.dp)) {
        Surface(Modifier.offset(x = left.dp)
            .width((if (length > 0) axis.xAt(visualOffset + length) - left else 60f).coerceAtLeast(1f).dp)
            .fillMaxHeight()
            .border(if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                RoundedCornerShape(5.dp))
            .clickable(enabled = enabled, onClickLabel = "Edit $label") { latestSelect() }
            .semantics {
                contentDescription = "$label${if (selected) ", selected" else ""} position in reel"
                stateDescription = "${audioTime(visualOffset)}; ${if (waveform.peaks != null) "waveform ready" else waveform.status}"
                progressBarRangeInfo = ProgressBarRangeInfo(visualOffset.coerceIn(0, maxOffset).toFloat(), 0f..maxOffset.toFloat())
                if (enabled) setProgress { value ->
                    latestMove(value.toLong().coerceIn(0, maxOffset)); true
                }
                if (enabled && length > 0) customActions = listOf(true, false).flatMap { first ->
                    listOf(-500L, 500L).map { delta ->
                        CustomAccessibilityAction("${if (first) "Start" else "End"} ${if (delta < 0) "earlier" else "later"}") {
                            latestPause()
                            val anchor = if (first) original.offsetMs else minOf(axis.durationMs, original.offsetMs + sourceLengthMs)
                            latestTrim(AudioLaneTrim.drag(original, durationMs, axis, first, axis.xAt(anchor + delta) - axis.xAt(anchor)))
                            true
                        }
                    }
                }
            }
            .pointerInput(id, axis, original, enabled) {
                if (enabled) detectDragGesturesAfterLongPress(
                    onDragStart = { p ->
                        latestSelect(); latestPause(); dragDp = 0f; editing = true
                        val width = axis.xAt(offsetMs + length) - axis.xAt(offsetMs)
                        val zone = minOf(12f, width / 3)
                        val x = p.x / density.density
                        edge = when { length <= 0 -> -1; x < zone -> 0; x > width - zone -> 1; else -> -1 }
                    },
                    onDragCancel = { range = original; dragDp = 0f; editing = false },
                    onDragEnd = {
                        if (range != original) {
                            if (edge < 0) latestMove(range.offsetMs) else latestTrim(range)
                        }
                        dragDp = 0f; editing = false
                    }
                ) { change, amount ->
                    change.consume()
                    dragDp += amount.x / density.density
                    range = if (edge < 0) original.copy(offsetMs = AudioLanePlacement.shifted(axis, offsetMs, dragDp, sourceLengthMs))
                        else AudioLaneTrim.drag(original, durationMs, axis, edge == 0, dragDp)
                }
            }, color = color, shape = RoundedCornerShape(5.dp)) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxWidth().height(22.dp).align(Alignment.BottomStart)) {
                    val visibleStart = maxOf(left, viewportLeft)
                    val visibleEnd = minOf(axis.xAt(visualOffset + length), viewportLeft + viewportWidth)
                    val step = 3f
                    val first = floor((visibleStart - left).coerceAtLeast(0f) / step).toInt()
                    val last = ceil((visibleEnd - left).coerceAtLeast(0f) / step).toInt()
                    val middle = size.height / 2
                    if (visibleEnd > visibleStart) drawLine(ink.copy(alpha = .3f),
                        Offset((visibleStart - left).dp.toPx(), middle), Offset((visibleEnd - left).dp.toPx(), middle), 1.dp.toPx())
                    waveform.peaks?.let { data ->
                        for (i in first until last) {
                            val x = left + i * step
                            val peak = TimelineWaveform.peak(data, durationMs, range.startMs, visualOffset, length, axis, x, x + step)
                            val height = sqrt(peak) * size.height * .44f
                            val center = (i * step + step / 2).dp.toPx()
                            if (height > 0) drawLine(ink, Offset(center, middle - height), Offset(center, middle + height), 2.dp.toPx())
                        }
                    }
                }
                if (length > 0) Canvas(Modifier.fillMaxSize()) {
                    val w = minOf(3.dp.toPx(), size.width / 4)
                    drawRect(ink.copy(alpha = .6f), Offset(0f, 5.dp.toPx()), Size(w, size.height - 10.dp.toPx()))
                    drawRect(ink.copy(alpha = .6f), Offset(size.width - w, 5.dp.toPx()), Size(w, size.height - 10.dp.toPx()))
                }
                Text(if (editing) "${audioTime(range.startMs)} to ${audioTime(range.endMs)} · at ${audioTime(visualOffset)}"
                    else if (length > 0) "$label · ${audioTime(visualOffset)}${if (waveform.peaks == null) " · ${if (waveform.status.startsWith("Reading")) "Loading" else "Waveform unavailable"}" else ""}" else "Move $label in",
                    Modifier.offset(x = (viewportLeft - left).coerceAtLeast(0f).dp).padding(horizontal = 6.dp, vertical = 2.dp),
                    color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** All lanes share the final output clock. Scrolling seeks once after the gesture/fling settles. */
@Composable
internal fun StudioTimeline(draft: DraftEntity, clips: List<VideoClip>, recipe: EditRecipe, selected: String,
    selectedAudioId: String?, position: Long, enabled: Boolean, select: (String, Long) -> Unit,
    selectAudio: (String) -> Unit, pause: () -> Unit, seek: (Long) -> Unit,
    move: (String, Int) -> Unit, moveMusic: (Long) -> Unit, moveVoice: (String, Long) -> Unit,
    trimMusic: (AudioLaneRange) -> Unit, trimVoice: (String, AudioLaneRange) -> Unit,
    selectText: (TimedText) -> Unit, retimeText: (TimedText) -> Unit) {
    var zoom by remember(draft.id) { mutableFloatStateOf(32f) }
    val durations = remember(clips, recipe.speed, recipe.ramp, recipe.rampEnd) { clips.map { it.speedPlan(recipe).outputMs } }
    val axis = remember(durations, zoom) { TimelineAxis(durations, zoom) }
    val scroll = rememberScrollState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var scrubbing by remember { mutableStateOf(false) }
    var cursor by remember { mutableLongStateOf(0) }
    val density = LocalDensity.current
    val seekNow by rememberUpdatedState(seek)
    val pauseNow by rememberUpdatedState(pause)
    LaunchedEffect(scroll, axis) {
        snapshotFlow { Triple(scroll.value, dragged, scroll.isScrollInProgress) }.collect { (value, user, moving) ->
            if (user && !scrubbing) { scrubbing = true; pauseNow() }
            if (scrubbing) {
                cursor = axis.timeAt(value / density.density)
                if (!moving) { seekNow(cursor); scrubbing = false }
            }
        }
    }
    LaunchedEffect(position, axis) {
        if (!scrubbing && !scroll.isScrollInProgress) scroll.scrollTo((axis.xAt(position) * density.density).roundToInt())
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(audioTime(if (scrubbing) cursor else position), color = Accent, style = MaterialTheme.typography.labelLarge)
            Text("${clips.size} of 12 clips · ${audioTime(axis.durationMs)}", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        TextButton({ zoom = (zoom / 2).coerceAtLeast(8f) }, Modifier.semantics { contentDescription = "Zoom out timeline" }, enabled = enabled && zoom > 8) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("Zoom", color = Muted, style = MaterialTheme.typography.labelSmall)
        TextButton({ zoom = (zoom * 2).coerceAtMost(64f) }, Modifier.semantics { contentDescription = "Zoom in timeline" }, enabled = enabled && zoom < 64) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val musicColor = MaterialTheme.colorScheme.tertiaryContainer
    val textColor = MaterialTheme.colorScheme.secondaryContainer
    var timingPreview by remember(recipe.texts, axis) { mutableStateOf<TimedText?>(null) }
    var timingPart by remember { mutableStateOf(TextLaneTiming.Part.Move) }
    var timingDrag by remember { mutableFloatStateOf(0f) }
    var timingOriginal by remember { mutableStateOf<TimedText?>(null) }
    val retimeNow by rememberUpdatedState(retimeText)
    val selectTextNow by rememberUpdatedState(selectText)
    val hasCaptions = recipe.texts.any { it.caption }
    val laneHeight = if (hasCaptions) 60.dp else 28.dp
    val timelineHeight = 116.dp + laneHeight + (if (draft.audioPath.isNotBlank()) 16.dp else 0.dp) + 48.dp * (recipe.audioTracks.size + (recipe.musicTracks.size - 1).coerceAtLeast(0))
    val labelPaint = remember(muted) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = muted.toArgb(); textSize = with(density) { 10.dp.toPx() } } }
    var reorderingClip by remember { mutableStateOf<String?>(null) }
    var reorderPointerX by remember { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).semantics {
        contentDescription = "Reel timeline"
        progressBarRangeInfo = ProgressBarRangeInfo(position.toFloat(), 0f..axis.durationMs.toFloat())
        if (enabled) setProgress { seek(it.toLong().coerceIn(0, axis.durationMs)); true }
    }) {
        val viewport = maxWidth
        val half = viewport / 2
        LaunchedEffect(reorderingClip, viewport, scroll) {
            while (reorderingClip != null) {
                val viewportPx = with(density) { viewport.toPx() }
                val edge = with(density) { 48.dp.toPx() }
                val strength = when {
                    reorderPointerX < edge -> -((edge - reorderPointerX) / edge).coerceIn(0f, 1f)
                    reorderPointerX > viewportPx - edge -> ((reorderPointerX - (viewportPx - edge)) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
                if (strength != 0f) scroll.scrollBy(strength * with(density) { 12.dp.toPx() })
                delay(16)
            }
        }
        Box(Modifier.horizontalScroll(scroll, enabled = enabled).width(axis.width.dp + viewport).height(timelineHeight)) {
            Column(Modifier.padding(start = half).width(axis.width.dp)) {
                Canvas(Modifier.fillMaxWidth().height(24.dp)) {
                    val left = axis.timeAt((scroll.value / density.density - half.value).coerceAtLeast(0f))
                    val right = axis.timeAt((scroll.value / density.density + half.value).coerceAtMost(axis.width))
                    val step = when { axis.durationMs / axis.width > 100 -> 10_000L; axis.durationMs / axis.width > 40 -> 5_000L; axis.durationMs / axis.width > 20 -> 2000L; else -> 1000L }
                    var t = left / step * step
                    while (t <= right) {
                        val x = axis.xAt(t).dp.toPx()
                        drawLine(muted.copy(alpha = .4f), Offset(x, size.height - 5.dp.toPx()), Offset(x, size.height))
                        drawContext.canvas.nativeCanvas.drawText(audioTime(t).substringBefore('.'), x + 3.dp.toPx(), 14.dp.toPx(), labelPaint)
                        t += step
                    }
                }
                Row(Modifier.height(56.dp)) {
                    clips.forEachIndexed { i, clip ->
                        val segment = axis.segments[i]
                        var dx by remember(clip.id) { mutableFloatStateOf(0f) }
                        var startScroll by remember(clip.id) { mutableIntStateOf(0) }
                        val currentMove by rememberUpdatedState(move)
                        Box(Modifier.width(segment.width.dp).fillMaxHeight().padding(horizontal = 1.dp)
                            .border(if (clip.id == selected) 2.dp else 1.dp, if (clip.id == selected) primary else muted.copy(alpha = .3f), RoundedCornerShape(6.dp))
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled) { select(clip.id, segment.startMs + segment.durationMs / 2) }
                            .semantics { contentDescription = "Timeline clip ${i + 1}: ${clip.name}" }
                            .pointerInput(clip.id, axis, viewport, enabled) {
                                if (enabled) detectDragGesturesAfterLongPress(onDragStart = { point ->
                                    pause(); dx = 0f; startScroll = scroll.value
                                    reorderingClip = clip.id
                                    reorderPointerX = (half.value + segment.left) * density.density + point.x - scroll.value
                                }, onDragCancel = {
                                    reorderingClip = null; dx = 0f
                                }, onDragEnd = {
                                    reorderingClip = null
                                    val target = TimelineReorder.target(axis, i, dx, scroll.value - startScroll, density.density)
                                    currentMove(clip.id, target); dx = 0f
                                }) { change, amount ->
                                    change.consume(); dx += amount.x
                                    reorderPointerX = (half.value + segment.left) * density.density + change.position.x - scroll.value
                                }
                            }) {
                            val filmstripLeft = scroll.value - (half.value + segment.left) * density.density
                            val filmstripWidth = viewport.value * density.density
                            Filmstrip(clip, Modifier.fillMaxSize(), visibleLeft = filmstripLeft,
                                visibleWidth = filmstripWidth,
                                visible = filmstripLeft < segment.width * density.density + filmstripWidth / 2 &&
                                    filmstripLeft + filmstripWidth * 1.5f > 0f)
                            Text("${i + 1}", Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = .65f)).padding(horizontal = 5.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
                            if (dx != 0f) Text("Move ${if (dx < 0) "earlier" else "later"}", Modifier.align(Alignment.Center).background(primary).padding(4.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                val music = AudioSplit.music(draft)
                music.forEachIndexed { index, track ->
                    if (index > 0) Spacer(Modifier.height(4.dp))
                    AudioLane(track.id, "Music ${index + 1}: ${track.name}", axis, track.offsetMs,
                        track.endMs - track.startMs, track.path, track.durationMs, track.startMs,
                        scroll.value / density.density - half.value, viewport.value, musicColor,
                        track.id == selectedAudioId, enabled, { selectAudio(track.id) }, pause,
                        { if (recipe.musicTracks.isEmpty()) trimMusic(it) else trimVoice(track.id, it) }) {
                        if (recipe.musicTracks.isEmpty()) moveMusic(it) else moveVoice(track.id, it)
                    }
                }
                if (music.isEmpty()) Text("No added audio", Modifier.height(28.dp).padding(start = 6.dp, top = 5.dp), color = muted, style = MaterialTheme.typography.labelSmall)
                recipe.audioTracks.forEachIndexed { index, track ->
                    Spacer(Modifier.height(4.dp))
                    AudioLane(track.id, "Mic · Take ${index + 1}", axis, track.offsetMs,
                        track.endMs - track.startMs, track.path, track.durationMs, track.startMs,
                        scroll.value / density.density - half.value, viewport.value, musicColor,
                        track.id == selectedAudioId, enabled, { selectAudio(track.id) }, pause,
                        { trimVoice(track.id, it) }) { moveVoice(track.id, it) }
                }
                Spacer(Modifier.height(4.dp))
                Canvas(Modifier.fillMaxWidth().height(laneHeight).pointerInput(recipe.texts, axis, enabled) {
                    if (enabled) detectTapGestures { p ->
                        val t = axis.timeAt(p.x / density.density)
                        val captionLane = hasCaptions && p.y >= with(density) { 32.dp.toPx() }
                        recipe.texts.lastOrNull { it.caption == captionLane && t >= it.startMs && t < it.endMs }?.let(selectTextNow)
                    }
                }.pointerInput(recipe.texts, axis, enabled) {
                    if (enabled) detectDragGesturesAfterLongPress(
                        onDragStart = { p ->
                            val x = p.x / density.density
                            val captionLane = hasCaptions && p.y >= with(density) { 32.dp.toPx() }
                            val entry = recipe.texts.lastOrNull { it.caption == captionLane && x >= axis.xAt(it.startMs) && x <= axis.xAt(it.endMs) }
                            timingOriginal = entry; timingDrag = 0f
                            if (entry != null) {
                                pauseNow()
                                val left = axis.xAt(entry.startMs); val right = axis.xAt(entry.endMs)
                                val edge = minOf(12f, (right - left) / 3)
                                timingPart = when { x - left < edge -> TextLaneTiming.Part.Start
                                    right - x < edge -> TextLaneTiming.Part.End
                                    else -> TextLaneTiming.Part.Move }
                                timingPreview = entry
                            }
                        },
                        onDragCancel = { timingPreview = null; timingOriginal = null },
                        onDragEnd = {
                            val changed = timingPreview
                            if (changed != null && changed != timingOriginal) retimeNow(changed)
                            timingPreview = null; timingOriginal = null
                        }
                    ) { change, amount ->
                        timingOriginal?.let { original ->
                            change.consume(); timingDrag += amount.x / density.density
                            timingPreview = TextLaneTiming.drag(original, axis, timingPart, timingDrag)
                        }
                    }
                }.semantics {
                    contentDescription = "Titles and captions timeline"
                    if (enabled) customActions = recipe.texts.take(20).flatMap { entry ->
                        val name = "${if (entry.caption) "caption" else "title"}: ${entry.text}"
                        listOf(CustomAccessibilityAction("Edit $name") { selectTextNow(entry); true }) +
                            listOf("Move earlier" to (TextLaneTiming.Part.Move to -500L),
                                "Move later" to (TextLaneTiming.Part.Move to 500L),
                                "Start earlier" to (TextLaneTiming.Part.Start to -500L),
                                "Start later" to (TextLaneTiming.Part.Start to 500L),
                                "End earlier" to (TextLaneTiming.Part.End to -500L),
                                "End later" to (TextLaneTiming.Part.End to 500L)).map { (label, action) ->
                                CustomAccessibilityAction("$label $name") {
                                    val anchor = if (action.first == TextLaneTiming.Part.End) entry.endMs else entry.startMs
                                    val dx = axis.xAt(anchor + action.second) - axis.xAt(anchor)
                                    retimeNow(TextLaneTiming.drag(entry, axis, action.first, dx)); true
                                }
                            }
                    }
                }) {
                    val viewportLeft = scroll.value / density.density - half.value
                    val viewportRight = viewportLeft + viewport.value
                    recipe.texts.forEach { saved ->
                        val entry = timingPreview?.takeIf { it.id == saved.id } ?: saved
                        val left = axis.xAt(entry.startMs)
                        val right = axis.xAt(entry.endMs)
                        if (right > left && right >= viewportLeft && left <= viewportRight) {
                            val x = left.dp.toPx(); val w = (right - left).dp.toPx()
                            val y = if (entry.caption) 32.dp.toPx() else 0f
                            val h = 28.dp.toPx()
                            drawRect(textColor, Offset(x, y), Size(w, h))
                            val handle = minOf(3.dp.toPx(), w / 4)
                            drawRect(primary, Offset(x, y + 4.dp.toPx()), Size(handle, h - 8.dp.toPx()))
                            drawRect(primary, Offset(x + w - handle, y + 4.dp.toPx()), Size(handle, h - 8.dp.toPx()))
                            drawContext.canvas.nativeCanvas.apply {
                                save(); clipRect(x, y, x + w - 2, y + h)
                                drawText(entry.text, max(x + 5.dp.toPx(), viewportLeft.dp.toPx() + 5.dp.toPx()), y + 18.dp.toPx(), labelPaint); restore()
                            }
                        }
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.TopCenter).width(2.dp).height(timelineHeight).background(primary))
    }
    timingPreview?.let { Text("${audioTime(it.startMs)} to ${audioTime(it.endMs)}", color = Accent, style = MaterialTheme.typography.labelLarge) }
    Text("Drag to scrub · tap audio to edit · hold a clip to reorder · hold audio/text to move · hold audio/text edges to trim", color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun FilmstripTrim(clip: VideoClip, enabled: Boolean, commit: (Long, Long) -> Unit) {
    var start by remember(clip) { mutableFloatStateOf(clip.startMs.toFloat()) }
    var end by remember(clip) { mutableFloatStateOf(clip.endMs.toFloat()) }
    var fineStart by remember(clip.id) { mutableStateOf(true) }
    var fineSpan by remember(clip.id) { mutableLongStateOf(15_000L) }
    var fineCenter by remember(clip.id) { mutableLongStateOf(clip.startMs) }
    val latestCommit by rememberUpdatedState(commit)
    val primary = MaterialTheme.colorScheme.primary
    Text("Trim clip · ${audioTime(start.toLong())} — ${audioTime(end.toLong())}", style = MaterialTheme.typography.labelLarge)
    BoxWithConstraints(Modifier.fillMaxWidth().height(64.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        Filmstrip(clip, Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)), whole = true)
        Canvas(Modifier.fillMaxSize()) {
            val left = size.width * start / clip.durationMs
            val right = size.width * end / clip.durationMs
            drawRect(Color.Black.copy(alpha = .6f), size = Size(left, size.height))
            drawRect(Color.Black.copy(alpha = .6f), Offset(right, 0f), Size(size.width - right, size.height))
            drawLine(primary, Offset(left, 1.dp.toPx()), Offset(right, 1.dp.toPx()), 2.dp.toPx())
            drawLine(primary, Offset(left, size.height - 1.dp.toPx()), Offset(right, size.height - 1.dp.toPx()), 2.dp.toPx())
        }
        for (first in listOf(true, false)) {
            val value = if (first) start else end
            Box(Modifier.offset { IntOffset((widthPx * value / clip.durationMs - 14.dp.toPx()).roundToInt().coerceIn(0, (widthPx - 28.dp.toPx()).roundToInt()), 0) }
                .width(28.dp).fillMaxHeight().background(primary, RoundedCornerShape(5.dp))
                .semantics {
                    contentDescription = if (first) "Clip trim start" else "Clip trim end"
                    progressBarRangeInfo = ProgressBarRangeInfo(value, if (first) 0f..(end - 1000) else (start + 1000)..clip.durationMs.toFloat())
                    if (enabled) setProgress { v ->
                        if (first) start = v.coerceIn(0f, end - 1000) else end = v.coerceIn(start + 1000, clip.durationMs.toFloat())
                        latestCommit(start.toLong(), end.toLong()); true
                    }
                }
                .pointerInput(clip.id, clip.startMs, clip.endMs, enabled) {
                    if (enabled) detectHorizontalDragGestures(onDragStart = {
                        fineStart = first
                    }, onDragCancel = { start = clip.startMs.toFloat(); end = clip.endMs.toFloat() },
                        onDragEnd = {
                            fineCenter = (if (first) start else end).toLong()
                            latestCommit(start.toLong(), end.toLong())
                        }) { change, amount ->
                        change.consume()
                        val ms = amount * clip.durationMs / widthPx
                        if (first) start = (start + ms).coerceIn(0f, end - 1000) else end = (end + ms).coerceIn(start + 1000, clip.durationMs.toFloat())
                    }
                }, contentAlignment = Alignment.Center) {
                Box(Modifier.width(3.dp).height(22.dp).background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp)))
            }
        }
    }
    if (clip.durationMs > 30_000) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Fine trim", style = MaterialTheme.typography.labelLarge)
            FilterChip(fineStart, { fineStart = true; fineCenter = start.toLong() }, label = { Text("Start") }, enabled = enabled)
            FilterChip(!fineStart, { fineStart = false; fineCenter = end.toLong() }, label = { Text("End") }, enabled = enabled)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("View", style = MaterialTheme.typography.labelMedium)
            listOf(5_000L, 15_000L, 60_000L).forEach { span ->
                FilterChip(fineSpan == span, { fineSpan = span; fineCenter = (if (fineStart) start else end).toLong() },
                    label = { Text("${span / 1000}s") }, enabled = enabled)
            }
        }
        val window = PrecisionTrim.window(clip.durationMs, fineCenter, fineSpan)
        val value = if (fineStart) start else end
        val low = if (fineStart) window.first.toFloat() else maxOf(window.first.toFloat(), start + 1000f)
        val high = if (fineStart) minOf(window.last.toFloat(), end - 1000f) else window.last.toFloat()
        Text("${if (fineStart) "Start" else "End"} · ${audioTime(value.toLong())} · ${audioTime(window.first)}–${audioTime(window.last)} of source",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        BoxWithConstraints(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp))) {
            Filmstrip(clip, Modifier.fillMaxSize(), window = window)
            Canvas(Modifier.fillMaxSize()) {
                val marker = size.width * ((value - window.first) / (window.last - window.first).toFloat()).coerceIn(0f, 1f)
                drawLine(primary, Offset(marker, 0f), Offset(marker, size.height), 3.dp.toPx())
            }
        }
        if (high > low) {
            Slider(value.coerceIn(low, high), { v ->
                if (fineStart) start = v.coerceIn(low, high) else end = v.coerceIn(low, high)
            }, Modifier.fillMaxWidth().padding(horizontal = 24.dp), enabled = enabled, valueRange = low..high,
                onValueChangeFinished = {
                    fineCenter = (if (fineStart) start else end).toLong()
                    latestCommit(start.toLong(), end.toLong())
                })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(-100L, 100L).forEach { delta ->
                val next = (value.toLong() + delta).coerceIn(0, clip.durationMs)
                val allowed = if (fineStart) next < end.toLong() - 999 else next > start.toLong() + 999
                TextButton({
                    if (fineStart) start = next.toFloat() else end = next.toFloat()
                    fineCenter = next
                    latestCommit(start.toLong(), end.toLong())
                }, enabled = enabled && allowed && next != value.toLong()) {
                    Text(if (delta < 0) "−0.1s" else "+0.1s")
                }
            }
        }
    }
}
