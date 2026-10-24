package com.reelshare.feature.creator

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.reelshare.core.database.entity.DraftEntity

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun Editor(draft: DraftEntity, work: WorkInfo?, vm: StudioViewModel, back: () -> Unit, export: (Boolean) -> Unit) {
    val captionJobs by vm.captionWork.collectAsStateWithLifecycle()
    val mediaImporting by vm.importing.collectAsStateWithLifecycle()
    val captionWork = captionJobs.firstOrNull { it.id.toString() == draft.captionWorkId }
    val busy = draft.workId.isNotEmpty() || draft.captionWorkId.isNotEmpty()
    var compare by rememberSaveable(draft.id) { mutableStateOf(false) }
    var position by remember(draft.id) { mutableLongStateOf(0L) }
    var seekMs by remember(draft.id) { mutableStateOf<Long?>(null) }
    var seekRevision by remember(draft.id) { mutableLongStateOf(0) }
    var tool by rememberSaveable(draft.id) { mutableIntStateOf(if (draft.recipe().collage != null) 11 else 0) }
    var allTools by rememberSaveable { mutableStateOf(false) }
    var expanded by rememberSaveable(draft.id) { mutableStateOf(false) }
    BackHandler(expanded) { expanded = false }
    var motionGuide by rememberSaveable(draft.id) { mutableStateOf(false) }
    var collageStart by rememberSaveable(draft.id) { mutableStateOf(false) }
    var collageItem by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var paused by rememberSaveable(draft.id) { mutableStateOf(false) }
    var playing by remember(draft.id) { mutableStateOf(false) }
    val toolList = rememberLazyListState()
    val toolScroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val itemWidth = with(LocalDensity.current) { 64.dp.roundToPx() }
    val toolbarInset = with(LocalDensity.current) { 8.dp.roundToPx() }
    val fixedToolsWidth = with(LocalDensity.current) { 52.dp.roundToPx() }
    fun selectTool(index: Int) {
        focus.clearFocus(); keyboard?.hide()
        tool = index; expanded = false
    }
    LaunchedEffect(tool) {
        toolScroll.scrollTo(0)
        withFrameNanos { }
        val viewport = toolList.layoutInfo.viewportSize.width
        // Include the fixed tool-grid button so the selected icon centers on the screen.
        val offset = toolbarInset + fixedToolsWidth / 2 - ((viewport - itemWidth) / 2).coerceAtLeast(0)
        toolList.animateScrollToItem(tool, offset)
    }
    val recipe = remember(draft.editRecipe) { draft.recipe() }
    val duration = remember(draft.editRecipe, draft.trimStartMs, draft.trimEndMs) { draft.outputDuration(recipe) }
    val tools = listOf("Trim" to Icons.Default.ContentCut, "Frame" to Icons.Default.Crop,
        "Audio" to Icons.AutoMirrored.Filled.VolumeUp, "Details" to Icons.Default.EditNote,
        "Adjust" to Icons.Default.Tune, "Looks" to Icons.Default.AutoAwesome, "Speed" to Icons.Default.Speed,
        "Text" to Icons.Default.TextFields, "Motion" to Icons.Default.ZoomIn, "Clips" to Icons.Default.ViewTimeline, "Captions" to Icons.Default.Subtitles,
        "Collage" to Icons.Default.Dashboard)
    val collagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(), vm::importCollage)
    val musicImporting by vm.musicImporting.collectAsStateWithLifecycle()
    val voiceState by vm.voiceover.state.collectAsStateWithLifecycle()
    var recordingVoice by rememberSaveable(draft.id) { mutableStateOf(false) }
    var recordingOffset by rememberSaveable(draft.id) { mutableLongStateOf(0) }
    var audioTab by rememberSaveable(draft.id) { mutableIntStateOf(0) }
    var choosingMusic by rememberSaveable(draft.id) { mutableStateOf(false) }
    var exporting by rememberSaveable(draft.id) { mutableStateOf(false) }
    val history = remember(draft.editRecipe) { DraftHistory.meta(draft) }
    var selectedText by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    var draggingText by remember(draft.id) { mutableStateOf<TimedText?>(null) }
    var pendingText by remember(draft.id, history.token) { mutableStateOf<TimedText?>(null) }
    LaunchedEffect(recipe.texts) { if (recipe.texts.any { it == pendingText }) pendingText = null }
    val previewTexts = recipe.texts.map { t -> (draggingText ?: pendingText)?.takeIf { it.id == t.id } ?: t }
    val savedFrame = VideoReframe.Focus(recipe.frameZoom, recipe.frameX, recipe.frameY)
    var draggingFrame by remember(draft.id) { mutableStateOf<VideoReframe.Focus?>(null) }
    var pendingFrame by remember(draft.id, history.token) { mutableStateOf<VideoReframe.Focus?>(null) }
    LaunchedEffect(savedFrame) { if (savedFrame == pendingFrame) pendingFrame = null }
    val previewFrame = draggingFrame ?: pendingFrame ?: savedFrame
    fun commitFrame(value: VideoReframe.Focus) {
        pendingFrame = value
        vm.editRecipe(draft.id) { it.copy(frameZoom = value.zoom, frameX = value.x, frameY = value.y) }
    }
    var selectedClipId by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    val timelineClips = remember(draft.sourcePath, draft.durationMs, draft.trimStartMs, draft.trimEndMs, recipe) { draft.timeline(recipe) }
    val selectedClip = timelineClips.firstOrNull { it.id == selectedClipId } ?: timelineClips.first()
    LaunchedEffect(tool) {
        if (tool == 9 && recipe.collage == null) {
            paused = true
            seekMs = TimelineEdits.center(draft, selectedClip.id, recipe)
            seekRevision++
        }
    }
    val savedClipFrame = selectedClip.frame(recipe)
    var draggingClipFrame by remember(draft.id) { mutableStateOf<Pair<String, VideoReframe.Focus>?>(null) }
    var pendingClipFrame by remember(draft.id, history.token) { mutableStateOf<Pair<String, VideoReframe.Focus>?>(null) }
    LaunchedEffect(recipe.clips, selectedClip.id) {
        if (pendingClipFrame?.first == selectedClip.id && savedClipFrame == pendingClipFrame?.second) pendingClipFrame = null
    }
    val previewClipFrame = draggingClipFrame?.takeIf { it.first == selectedClip.id }?.second
        ?: pendingClipFrame?.takeIf { it.first == selectedClip.id }?.second ?: savedClipFrame
    fun currentClipId() = selectedClipId?.takeIf { id -> timelineClips.any { it.id == id } } ?: timelineClips.first().id
    fun commitClipFrame(value: VideoReframe.Focus) {
        val id = currentClipId()
        pendingClipFrame = id to value
        vm.editClip(draft.id, id) { it.copy(frameZoom = value.zoom, frameX = value.x, frameY = value.y) }
    }
    fun resetClipFrame() {
        pendingClipFrame = null; draggingClipFrame = null
        vm.editClip(draft.id, currentClipId()) { it.copy(frameZoom = null, frameX = 0f, frameY = 0f) }
    }
    val previewSettings = remember(draft.width, draft.height, draft.aspect) { ExportSettings.forVideo(draft.width, draft.height, draft.aspect, "Small", true) }
    fun placeText(entry: TimedText) {
        focus.clearFocus(); keyboard?.hide(); selectedText = entry.id; compare = false; paused = true
        seekMs = (entry.startMs + minOf(200, (entry.endMs - entry.startMs) / 2)).coerceIn(0, (duration - 1).coerceAtLeast(0))
        seekRevision++
    }
    fun previewText(entry: TimedText) {
        focus.clearFocus(); keyboard?.hide(); selectedText = entry.id; compare = false; paused = false
        seekMs = entry.startMs.coerceIn(0, (duration - 1).coerceAtLeast(0)); seekRevision++
    }
    var title by remember(draft.id, history.token) { mutableStateOf(draft.title) }
    var caption by remember(draft.id, history.token) { mutableStateOf(draft.caption) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ if (expanded) expanded = false else back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.weight(1f)) {
            Text("Edit reel", style = MaterialTheme.typography.titleMedium)
            Text(if (draft.captionWorkId.isNotEmpty()) "Creating captions" else if (busy) "Export in progress" else "Saved to drafts", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        IconButton({ focus.clearFocus(); keyboard?.hide(); vm.restoreEdit(draft.id, false) },
            enabled = history.undo && !busy && !mediaImporting && !musicImporting && !voiceState.busy) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo edit") }
        IconButton({ focus.clearFocus(); keyboard?.hide(); vm.restoreEdit(draft.id, true) },
            enabled = history.redo && !busy && !mediaImporting && !musicImporting && !voiceState.busy) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo edit") }
        Button({ focus.clearFocus(); keyboard?.hide(); exporting = true }, enabled = !busy && !musicImporting && !mediaImporting && !voiceState.busy && recipe.collage?.assets?.any { it.placeholder } != true, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Text("Export", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp)); Icon(Icons.Default.IosShare, null, Modifier.size(16.dp))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val controlsHeight = (maxHeight * .38f).coerceIn(150.dp, 290.dp).coerceAtMost(maxHeight * .5f)
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f)) {
                if (!busy) {
                    val ratio = previewSettings.width.toFloat() / previewSettings.height
                    Surface(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(18.dp), color = Color.Black) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            StudioPreviewFrame(ratio) {
                                StudioCompositionPreview(draft, !recordingVoice && !voiceState.busy && !choosingMusic && !exporting && !allTools && !motionGuide && !collageStart && !musicImporting && !mediaImporting,
                                    Modifier.fillMaxSize(), compare, seekMs, paused, { paused = it }, { playing = it }, seekRevision,
                                    previewTexts, previewFrame,
                                    (draggingClipFrame ?: pendingClipFrame)?.takeIf { !compare }) { position = it }
                                if (tool == 1 && recipe.collage == null && !compare) {
                                    StudioFrameDrag(previewFrame, draft.width, draft.height, previewSettings.width, previewSettings.height,
                                        { paused = true }, { draggingFrame = it }, ::commitFrame, { paused = !paused })
                                }
                                if (tool == 9 && recipe.collage == null && !compare && !busy) {
                                    key(selectedClip.id) {
                                        StudioFrameDrag(previewClipFrame, selectedClip.width, selectedClip.height, previewSettings.width, previewSettings.height,
                                            { paused = true }, { draggingClipFrame = it?.let { frame -> currentClipId() to frame } },
                                            ::commitClipFrame, { paused = !paused })
                                    }
                                }
                                if (tool == 11 && recipe.collage != null) {
                                    val c = recipe.collage
                                    BoxWithConstraints(Modifier.fillMaxSize()) {
                                        CollageLayout.cells(c.assets.size, c.layout, c.gap).forEachIndexed { i, cell ->
                                            val item = c.assets[i]
                                            Box(Modifier.offset(maxWidth * cell.left, maxHeight * cell.top).size(maxWidth * cell.width, maxHeight * cell.height)
                                                .border(if ((collageItem ?: c.assets.first().id) == item.id) 2.dp else 1.dp, if ((collageItem ?: c.assets.first().id) == item.id) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = .35f))
                                                .clickable(enabled = !busy && !mediaImporting) { collageItem = item.id }, contentAlignment = Alignment.Center) {
                                                if (item.placeholder) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Icon(Icons.Default.AddCircleOutline, null, tint = Color.White)
                                                    Text("Cell ${i + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                                if ((tool == 7 || tool == 10) && !compare && !mediaImporting && !musicImporting) {
                                    StudioTextPlacement(previewTexts, position, previewSettings, selectedText,
                                        { selectedText = it; paused = true; focus.clearFocus(); keyboard?.hide() },
                                        { draggingText = it }, { moved ->
                                            pendingText = moved
                                            vm.editRecipe(draft.id) { r -> r.copy(texts = r.texts.map { if (it.id == moved.id) it.copy(x = moved.x, y = moved.y) else it }) }
                                        })
                                }
                            }
                            IconButton({ focus.clearFocus(); keyboard?.hide(); expanded = !expanded }, Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                                Icon(if (expanded) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull, if (expanded) "Show editing controls" else "Expand video preview", tint = Color.White)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ paused = !paused }, Modifier.semantics { contentDescription = if (paused) "Play preview" else "Pause preview" }) {
                            if (!paused && !playing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                        }
                        Text("${audioTime(position)} / ${audioTime(duration)}", color = Muted, style = MaterialTheme.typography.labelSmall)
                        var dragging by remember { mutableStateOf(false) }
                        var scrub by remember { mutableFloatStateOf(0f) }
                        Slider(if (dragging) scrub else position.toFloat().coerceIn(0f, duration.toFloat()),
                            { dragging = true; scrub = it }, Modifier.weight(1f).padding(horizontal = 8.dp),
                            valueRange = 0f..duration.coerceAtLeast(1).toFloat(), onValueChangeFinished = { seekMs = scrub.toLong(); seekRevision++; dragging = false })
                        TextButton({ compare = !compare }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (compare) "After" else "Before", style = MaterialTheme.typography.labelSmall) }
                    }
                } else Surface(Modifier.fillMaxWidth().padding(20.dp), shape = RoundedCornerShape(24.dp), color = Card) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.AutoAwesome, null, tint = Accent)
                        Text(if (draft.captionWorkId.isNotEmpty()) "Finding your words" else "Your reel is taking shape", style = MaterialTheme.typography.headlineSmall)
                        Text("You can leave this screen while the task runs. Your draft is safe.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (!expanded || busy) Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ allTools = true }, enabled = !busy) { Icon(Icons.Default.GridView, "All editing tools", tint = Accent) }
                    LazyRow(Modifier.weight(1f).clipToBounds(), state = toolList, contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(tools) { index, (label, icon) ->
                        Surface(onClick = { selectTool(index) }, enabled = !busy, shape = RoundedCornerShape(14.dp),
                            color = if (tool == index) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            contentColor = if (tool == index) MaterialTheme.colorScheme.onPrimaryContainer else Muted) {
                            Column(Modifier.width(64.dp).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(icon, null, Modifier.size(22.dp)); Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                    }
                    }
                }
                if (!expanded || busy) Surface(color = Card, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
                Column(Modifier.height(controlsHeight).fillMaxWidth().verticalScroll(toolScroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (tool) {
                        0 -> if (recipe.clips.isNotEmpty()) {
                            ToolHeading("Trim each clip", "This reel has a timeline. Open Clips to select and trim any video.")
                            OutlinedButton({ selectTool(9) }, enabled = !busy) { Text("Open clips") }
                        } else {
                            ToolHeading("Keep the good part", "Drag the handles to set your start and end. Use Fine trim on longer videos.")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TimeLabel("START", time(draft.trimStartMs))
                                TimeLabel("DURATION", time(draft.trimEndMs - draft.trimStartMs))
                                TimeLabel("END", time(draft.trimEndMs))
                            }
                            FilmstripTrim(selectedClip, !busy) { start, end ->
                                vm.edit(draft.id) { it.copy(trimStartMs = start, trimEndMs = end) }
                            }
                            Text("Keep at least 1 second. Your original stays untouched.", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        1 -> {
                            StudioFrameControls(previewFrame, !busy && recipe.collage == null,
                                { draggingFrame = it }, ::commitFrame)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("Original", "9:16", "1:1", "4:5", "16:9").forEach { value ->
                                    FilterChip(draft.aspect == value, { vm.edit(draft.id) { it.copy(aspect = value) } }, label = { Text(value) }, enabled = !busy,
                                        leadingIcon = if (draft.aspect == value) { { Icon(Icons.Default.Check, null, Modifier.size(16.dp)) } } else null)
                                }
                            }
                            Text(when (draft.aspect) { "9:16" -> "A full-height frame for short videos."; "1:1" -> "A square frame for a balanced composition."; "4:5" -> "A taller frame with room for the details."; "16:9" -> "A wide frame for the bigger picture."; else -> "Keep the proportions of your original video." }, color = Muted, style = MaterialTheme.typography.bodyMedium)
                        }
                        2 -> {
                            ToolHeading("Set the mood", "Music and voice, on your timeline.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(audioTab == 0, { audioTab = 0 }, label = { Text("Music") }, leadingIcon = { Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp)) })
                                FilterChip(audioTab == 1, { audioTab = 1 }, label = { Text("Voiceover") }, leadingIcon = { Icon(Icons.Default.Mic, null, Modifier.size(18.dp)) })
                            }
                            if (audioTab == 0) {
                            Surface(color = Card, shape = RoundedCornerShape(20.dp)) {
                                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (draft.mute) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, null, tint = Accent)
                                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                        Text("Mute original audio", style = MaterialTheme.typography.titleSmall)
                                        Text(if (draft.mute) "Original sound is muted" else "Original sound is on", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(draft.mute, { value -> vm.edit(draft.id) { it.copy(mute = value) } }, enabled = !busy && !musicImporting)
                                }
                            }
                            MusicControls(draft, !busy && !voiceState.busy && !mediaImporting, vm) { choosingMusic = true }
                            } else VoiceoverControls(draft, !busy && !musicImporting && !mediaImporting && !voiceState.busy, vm) {
                                paused = true; recordingOffset = position; recordingVoice = true
                            }
                        }
                        4 -> AdjustControls(draft, !busy && !mediaImporting, vm)
                        5 -> FilterControls(draft, !busy && !mediaImporting, vm)
                        6 -> SpeedControls(draft, !busy && !mediaImporting, vm)
                        7 -> TextControls(draft, !busy && !mediaImporting && !musicImporting, vm, history.token, selectedText, ::placeText, ::previewText)
                        8 -> MotionControls(draft, !busy && !mediaImporting, vm) { motionGuide = true }
                        9 -> if (recipe.collage == null) TimelineControls(draft, !busy && !mediaImporting && !musicImporting, vm, position,
                            selectedClip.id, { selectedClipId = it; pendingClipFrame = null; draggingClipFrame = null; paused = true },
                            previewClipFrame, selectedClip.frameZoom != null,
                            { draggingClipFrame = it?.let { frame -> currentClipId() to frame } }, ::commitClipFrame, ::resetClipFrame,
                            { placeText(it); selectTool(if (it.caption) 10 else 7) }) { seekMs = it; seekRevision++; paused = true } else {
                            ToolHeading("One frame, several moments", "Collage items play together. Open Collage to change their order and crop; use Trim for the whole frame.")
                            OutlinedButton({ selectTool(11) }) { Text("Open collage") }
                        }
                        10 -> CaptionControls(draft, !busy && !mediaImporting && !musicImporting, vm, history.token, selectedText, ::placeText, ::previewText)
                        11 -> CollageControls(draft, !busy && !mediaImporting, vm, collageItem, { collageItem = it }) { collageStart = true }
                        else -> {
                            ToolHeading("Make it yours", "Give this reel a name you'll remember.")
                            OutlinedTextField(title, { value -> title = value.take(120); val text = title; vm.edit(draft.id, "reel-name") { it.copy(title = text) } }, label = { Text("Reel name") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, shape = RoundedCornerShape(16.dp))
                            OutlinedTextField(caption, { value -> caption = value.take(2200); val text = caption; vm.edit(draft.id, "reel-notes") { it.copy(caption = text) } }, label = { Text("Notes / caption") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 2, maxLines = 5, shape = RoundedCornerShape(16.dp))
                        }
                    }
                    if (draft.error.isNotEmpty()) Text(draft.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                }
            }
            if (busy) Surface(color = Bg, shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    if (busy) {
                        WorkProgress(if (draft.captionWorkId.isNotEmpty()) captionWork else work) { if (draft.captionWorkId.isNotEmpty()) vm.cancelCaptions(draft) else vm.cancel(draft) }
                        TextButton(back, Modifier.fillMaxWidth()) { Text("Continue in background") }
                    }
                }
            }
        }
    }
    if (allTools) ModalBottomSheet({ allTools = false }, containerColor = Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Make it your own", style = MaterialTheme.typography.headlineSmall)
            Text("Choose a tool. Every edit saves to your draft.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            listOf(listOf(0, 9, 1), listOf(4, 5, 6), listOf(7, 10, 8), listOf(2, 11, 3)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { index ->
                        val (label, icon) = tools[index]
                        Surface(onClick = { selectTool(index); allTools = false }, color = if (tool == index) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.weight(1f)) {
                            Column(Modifier.padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(icon, null, tint = Accent); Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (choosingMusic) MusicSheet(draft, vm) { choosingMusic = false }
    if (recordingVoice) VoiceoverSheet(draft, recordingOffset, vm) { recordingVoice = false }
    if (collageStart) CollageStartSheet({ collageStart = false }, {
        collageStart = false; collagePicker.launch(arrayOf("image/*", "video/mp4"))
    }) { count, layout -> collageStart = false; vm.createVideoLayout(count, layout) }
    if (motionGuide) MotionGuide(draft, { motionGuide = false }) {
        vm.editRecipe(draft.id) { it.copy(motion = true, zoomStart = 1f, zoomEnd = 1.35f, panStart = 0f, panEnd = 0f, smooth = true) }
        motionGuide = false
    }
    if (exporting) ModalBottomSheet({ exporting = false }, containerColor = Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Ready for the replay?", style = MaterialTheme.typography.headlineSmall)
            Text("Choose a size that suits your reel.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Small", "Balanced", "High").forEach { value -> FilterChip(draft.quality == value, { vm.edit(draft.id) { it.copy(quality = value) } }, label = { Text(value) }) }
            }
            Text(when (draft.quality) { "Small" -> "Smaller files · up to 480p"; "High" -> "More detail · up to 1080p"; else -> "Everyday quality · up to 720p" } + "\nMP4 · resolution adapts to your source and device.", color = Muted, style = MaterialTheme.typography.bodySmall)
            SheetAction(Icons.Default.Movie, "Create preview", "Watch it in your personal reel feed") { exporting = false; export(false) }
            Button({ exporting = false; export(true) }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) {
                Icon(Icons.Default.Download, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Save to device")
            }
            Text("Saved videos go to Download/ReelShare.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
internal fun ToolHeading(title: String, detail: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        trailing?.invoke()
    }
}

@Composable
private fun TimeLabel(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
