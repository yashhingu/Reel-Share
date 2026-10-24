package com.reelshare.feature.creator

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelshare.core.database.entity.DraftEntity
import java.util.Locale
import java.util.UUID

@Composable
internal fun EditSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean,
    format: (Float) -> String = { "${(it * 100).toInt()}" }, commit: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(format(current), color = Accent, style = MaterialTheme.typography.labelLarge)
        }
        Slider(current, { current = it }, valueRange = range, enabled = enabled, onValueChangeFinished = { commit(current) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChoiceChips(values: List<String>, selected: String, enabled: Boolean, choose: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value -> FilterChip(selected == value, { choose(value) }, { Text(value) }, enabled = enabled) }
    }
}

@Composable
internal fun AdjustControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel) {
    val r = draft.recipe()
    ToolHeading("Your light, your color", "Adjustments apply to the complete reel.") {
        IconButton({ vm.editRecipe(draft.id) { it.copy(brightness = 0f, contrast = 0f, saturation = 0f, warmth = 0f) } }, enabled = enabled) {
            Icon(Icons.Default.RestartAlt, "Reset adjustments")
        }
    }
    EditSlider("Brightness", r.brightness, -.5f.. .5f, enabled) { v -> vm.editRecipe(draft.id) { it.copy(brightness = v) } }
    EditSlider("Contrast", r.contrast, -.8f.. .8f, enabled) { v -> vm.editRecipe(draft.id) { it.copy(contrast = v) } }
    EditSlider("Saturation", r.saturation, -1f..1f, enabled) { v -> vm.editRecipe(draft.id) { it.copy(saturation = v) } }
    EditSlider("Warmth", r.warmth, -1f..1f, enabled) { v -> vm.editRecipe(draft.id) { it.copy(warmth = v) } }
}

@Composable
internal fun FilterControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel) {
    val r = draft.recipe()
    val styles by vm.styles.collectAsStateWithLifecycle()
    var name by rememberSaveable(draft.id) { mutableStateOf("") }
    ToolHeading("Choose your look", "Preview each treatment on a frame from your video.")
    FilterThumbnails(draft, r, enabled) { value -> vm.editRecipe(draft.id) { it.copy(filter = value) } }
    Text("Strength controls how much of the selected look is applied. 0% keeps your color; 100% applies the full look.", color = Muted, style = MaterialTheme.typography.bodySmall)
    EditSlider("Strength", r.strength, 0f..1f, enabled && r.filter != "None", format = { "${(it * 100).toInt()}%" }) { v -> vm.editRecipe(draft.id) { it.copy(strength = v) } }
    HorizontalDivider()
    ToolHeading("Your saved styles", "Save color, motion, fades and transitions for your next reel.")
    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Style name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled, shape = RoundedCornerShape(16.dp))
    OutlinedButton({ vm.saveStyle(draft.id, name); name = "" }, enabled = enabled && name.isNotBlank()) { Icon(Icons.Default.Add, null); Text("Save this style") }
    styles.take(30).forEach { style ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ vm.applyStyle(draft.id, style.recipe) }, Modifier.weight(1f), enabled = enabled) { Text(style.name) }
            IconButton({ vm.deleteStyle(style.id) }, enabled = enabled) { Icon(Icons.Default.DeleteOutline, "Delete ${style.name}") }
        }
    }
}

@Composable
internal fun SpeedControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel) {
    val r = draft.recipe()
    ToolHeading("Change the pace", "Pitch stays natural. Slow motion uses your video's existing frames.") {
        IconButton({ vm.editRecipe(draft.id) { it.copy(speed = 1f, rampEnd = 1f) } }, enabled = enabled) {
            Icon(Icons.Default.RestartAlt, if (r.ramp) "Reset ramp speeds" else "Reset speed")
        }
    }
    ChoiceChips(listOf("Constant", "Ramp"), if (r.ramp) "Ramp" else "Constant", enabled) { value -> vm.editRecipe(draft.id) { it.copy(ramp = value == "Ramp") } }
    if (r.ramp) Text("A ramp gradually changes speed across each imported clip. Splitting keeps that clip's progression. 1× is normal, 0.5× is half speed and 2× is double speed.", color = Muted, style = MaterialTheme.typography.bodySmall)
    val speedLabel: (Float) -> String = { String.format(Locale.ROOT, "%.2f×", it) }
    EditSlider(if (r.ramp) "Start speed" else "Speed", r.speed, .25f..4f, enabled, speedLabel) { v -> vm.editRecipe(draft.id) { it.copy(speed = v) } }
    if (r.ramp) EditSlider("End speed", r.rampEnd, .25f..4f, enabled, speedLabel) { v -> vm.editRecipe(draft.id) { it.copy(rampEnd = v) } }
    if (!r.ramp) ChoiceChips(listOf("0.5×", "1×", "2×"), speedLabel(r.speed).replace(".00", "").replace("0.50", "0.5"), enabled) { value -> vm.editRecipe(draft.id) { it.copy(speed = when (value) { "0.5×" -> .5f; "2×" -> 2f; else -> 1f }) } }
    Text("Output duration · ${audioTime(draft.outputDuration())}", color = Accent, style = MaterialTheme.typography.labelLarge)
    Text("Clips with their own speed keep it. Set the pace before adding timed titles or captions.",
        color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun MotionControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel, showGuide: () -> Unit) {
    val r = draft.recipe()
    ToolHeading("Make the frame move", "Set a starting frame and an ending frame. The video moves between them.") {
        IconButton(showGuide) { Icon(Icons.Default.Info, "How keyframe motion works") }
    }
    SettingSwitch("Keyframe motion", r.motion, enabled) { v -> vm.editRecipe(draft.id) { it.copy(motion = v) } }
    if (r.motion) {
        Text("Pan is visible when zoom is above 1×. Negative moves left; positive moves right.", color = Muted, style = MaterialTheme.typography.bodySmall)
        EditSlider("Start zoom", r.zoomStart, 1f..2f, enabled, { String.format(Locale.ROOT, "%.2f×", it) }) { v -> vm.editRecipe(draft.id) { it.copy(zoomStart = v) } }
        EditSlider("End zoom", r.zoomEnd, 1f..2f, enabled, { String.format(Locale.ROOT, "%.2f×", it) }) { v -> vm.editRecipe(draft.id) { it.copy(zoomEnd = v) } }
        EditSlider("Start pan", r.panStart, -1f..1f, enabled) { v -> vm.editRecipe(draft.id) { it.copy(panStart = v) } }
        EditSlider("End pan", r.panEnd, -1f..1f, enabled) { v -> vm.editRecipe(draft.id) { it.copy(panEnd = v) } }
        SettingSwitch("Ease in and out", r.smooth, enabled) { v -> vm.editRecipe(draft.id) { it.copy(smooth = v) } }
    }
    ToolHeading("Picture fades", "Bring the complete picture in and out gently.")
    EditSlider("Fade in", r.fadeInMs.toFloat(), 0f..2000f, enabled, { "${it.toInt()} ms" }) { v -> vm.editRecipe(draft.id) { it.copy(fadeInMs = v.toLong()) } }
    EditSlider("Fade out", r.fadeOutMs.toFloat(), 0f..2000f, enabled, { "${it.toInt()} ms" }) { v -> vm.editRecipe(draft.id) { it.copy(fadeOutMs = v.toLong()) } }
}

@Composable
internal fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, change, enabled = enabled)
    }
}

@Composable
internal fun TimelineControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel, position: Long,
    selectedClipId: String?, selectClip: (String) -> Unit,
    frameFocus: VideoReframe.Focus, frameOverride: Boolean,
    frameDrag: (VideoReframe.Focus?) -> Unit, frameCommit: (VideoReframe.Focus) -> Unit, frameReset: () -> Unit,
    selectText: (TimedText) -> Unit, seek: (Long) -> Unit) {
    val importing by vm.importing.collectAsStateWithLifecycle()
    val r = remember(draft.editRecipe) { draft.recipe() }
    val clips = remember(draft.sourcePath, draft.durationMs, draft.trimStartMs, draft.trimEndMs, r) { draft.timeline(r) }
    val audioTracks = AudioSplit.music(draft) + r.audioTracks
    var selectedAudioId by remember(draft.id) { mutableStateOf<String?>(null) }
    val activeAudioId = selectedAudioId?.takeIf { id -> audioTracks.any { it.id == id } } ?: audioTracks.firstOrNull()?.id
    val index = clips.indexOfFirst { it.id == selectedClipId }.coerceAtLeast(0)
    val clip = clips[index]
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importVideo(it, draft.id) } }
    StudioTimeline(draft, clips, r, clip.id, activeAudioId, position, enabled,
        select = { id, ms -> selectClip(id); seek(ms) }, pause = { seek(position) }, seek = seek,
        selectAudio = { selectedAudioId = it },
        move = { id, target -> vm.edit(draft.id) { current ->
            val recipe = current.recipe()
            val list = current.timeline(recipe).toMutableList()
            val from = list.indexOfFirst { it.id == id }
            if (from >= 0 && target in list.indices && from != target) list.add(target, list.removeAt(from))
            current.copy(editRecipe = RecipeCodec.encode(recipe.copy(clips = list)))
        } }, moveMusic = { offset -> vm.editRecipe(draft.id) { r ->
            r.copy(audioMix = r.audioMix.copy(musicOffsetMs = offset))
        } }, moveVoice = { trackId, offset -> vm.editRecipe(draft.id) { r ->
            r.copy(audioTracks = r.audioTracks.map { if (it.id == trackId) it.copy(offsetMs = offset) else it },
                musicTracks = r.musicTracks.map { if (it.id == trackId) it.copy(offsetMs = offset) else it })
        } }, trimMusic = { range -> vm.edit(draft.id) { current ->
            val recipe = current.recipe()
            current.copy(audioStartMs = range.startMs, audioEndMs = range.endMs,
                editRecipe = RecipeCodec.encode(recipe.copy(audioMix = recipe.audioMix.copy(musicOffsetMs = range.offsetMs))))
        } }, trimVoice = { id, range -> vm.editRecipe(draft.id) { current ->
            current.copy(audioTracks = current.audioTracks.map { if (it.id == id)
                it.copy(startMs = range.startMs, endMs = range.endMs, offsetMs = range.offsetMs, envelopeOffsetMs = 0, envelopeLengthMs = 0) else it },
                musicTracks = current.musicTracks.map { if (it.id == id)
                    it.copy(startMs = range.startMs, endMs = range.endMs, offsetMs = range.offsetMs, envelopeOffsetMs = 0, envelopeLengthMs = 0) else it })
        } }, selectText = selectText, retimeText = { changed -> vm.editRecipe(draft.id) { current ->
            current.copy(texts = current.texts.map { if (it.id == changed.id) CaptionWords.retime(it, changed.startMs, changed.endMs) else it })
        } })
    AudioSplitControls(draft, position, enabled, vm, activeAudioId) { selectedAudioId = it }
    ToolHeading("Clip ${index + 1}: ${clip.name}", "These controls change only the selected timeline clip.") {
        IconButton({ vm.editClip(draft.id, clip.id) { it.copy(speed = null, volume = 1f) } },
            enabled = enabled && (clip.speed != null || clip.volume != 1f)) {
            Icon(Icons.Default.RestartAlt, "Reset selected clip speed and sound")
        }
    }
    Text(if (clip.speed == null) "Following the reel pace${if (r.ramp) " (ramp)" else ""}. Set a speed to override it."
        else "This clip has its own constant speed. Other clips keep their pace.", color = Muted,
        style = MaterialTheme.typography.bodySmall)
    EditSlider("Clip speed", clip.speed ?: r.speed, .25f..4f, enabled,
        { String.format(Locale.ROOT, "%.2f×", it) }) { value -> vm.editClip(draft.id, clip.id) { it.copy(speed = value) } }
    ChoiceChips(listOf("Reel pace", "0.5×", "1×", "2×"),
        when (clip.speed) { null -> "Reel pace"; .5f -> "0.5×"; 1f -> "1×"; 2f -> "2×"; else -> "" }, enabled) { value ->
        vm.editClip(draft.id, clip.id) { it.copy(speed = when (value) { "Reel pace" -> null; "0.5×" -> .5f; "2×" -> 2f; else -> 1f }) }
    }
    EditSlider("Clip sound", clip.volume, 0f..1f, enabled, { "${(it * 100).toInt()}%" }) { value ->
        vm.editClip(draft.id, clip.id) { it.copy(volume = value) }
    }
    Text(if (draft.mute) "Original sound is muted for the reel. Turn it on in Audio to hear clip sound levels."
        else "0% silences this clip's original sound; added music and voiceover keep their levels.",
        color = Muted, style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    ToolHeading("Frame this clip", "Drag the preview to position this clip. Set a zoom to override the reel frame.") {
        IconButton(frameReset, enabled = enabled && frameOverride) {
            Icon(Icons.Default.RestartAlt, "Use reel frame for selected clip")
        }
    }
    Text(if (frameOverride) "This clip has its own crop. Other clips keep their frame."
        else "Following the reel frame. Drag or zoom to give this clip its own crop.",
        color = Muted, style = MaterialTheme.typography.bodySmall)
    var frameSliding by remember(clip.id) { mutableStateOf(false) }
    var frameZoom by remember(clip.id) { mutableFloatStateOf(frameFocus.zoom) }
    LaunchedEffect(clip.id, frameFocus.zoom, frameSliding) { if (!frameSliding) frameZoom = frameFocus.zoom }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Clip zoom", style = MaterialTheme.typography.labelLarge)
        Text(String.format(Locale.ROOT, "%.1f×", frameZoom), color = Accent, style = MaterialTheme.typography.labelLarge)
    }
    Slider(frameZoom, { frameZoom = it; frameSliding = true; frameDrag(frameFocus.copy(zoom = it)) },
        enabled = enabled, valueRange = 1f..2f, onValueChangeFinished = {
            frameSliding = false; frameCommit(frameFocus.copy(zoom = frameZoom)); frameDrag(null)
        })
    val cut = TimelineEdits.sourceAtPlayhead(draft, clip.id, position, r)
    val canSplit = cut != null && cut - clip.startMs >= 1000 && clip.endMs - cut >= 1000
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ vm.splitClip(draft.id, clip.id, position) }, enabled = enabled && clips.size < 12 && canSplit) {
            Icon(Icons.Default.ContentCut, null); Spacer(Modifier.width(6.dp)); Text("Split")
        }
        OutlinedButton({ vm.duplicateClip(draft.id, clip.id) }, enabled = enabled && clips.size < 12 && clips.sumOf { it.lengthMs } + clip.lengthMs <= 600_000) {
            Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Duplicate")
        }
    }
    Text("Pause and move the playhead to your cut. Keep at least one second of source on each side. A speed ramp continues across the split.",
        color = Muted, style = MaterialTheme.typography.bodySmall)
    FilmstripTrim(clip, enabled) { start, end -> vm.edit(draft.id) { current ->
            val r = current.recipe()
            current.copy(editRecipe = RecipeCodec.encode(r.copy(clips = current.timeline(r).map { if (it.id == clip.id) it.copy(startMs = start, endMs = end) else it })))
        } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton({ vm.moveClip(draft.id, clip.id, -1) }, enabled = enabled && index > 0) { Icon(Icons.Default.ArrowUpward, null); Text("Earlier") }
        TextButton({ vm.moveClip(draft.id, clip.id, 1) }, enabled = enabled && index < clips.lastIndex) { Icon(Icons.Default.ArrowDownward, null); Text("Later") }
    }
    TextButton({ vm.removeClip(draft.id, clip.id) }, enabled = enabled && clips.size > 1) { Icon(Icons.Default.RemoveCircleOutline, null); Text("Remove selected clip") }
    OutlinedButton({ pick.launch(arrayOf("video/mp4")) }, Modifier.fillMaxWidth(), enabled = enabled && !importing && clips.size < 12) { Icon(Icons.Default.Add, null); Text(if (importing) "Importing clip…" else "Add MP4 clip") }
    ToolHeading("Between clips", "A cut or a fade through a solid color. Sound stays continuous.")
    ChoiceChips(EditRecipe.TRANSITIONS, r.transition, enabled && clips.size > 1) { v -> vm.editRecipe(draft.id) { it.copy(transition = v) } }
    if (r.transition != "Cut") EditSlider("Transition length", r.transitionMs.toFloat(), 100f..1500f, enabled, { "${it.toInt()} ms" }) { v -> vm.editRecipe(draft.id) { it.copy(transitionMs = v.toLong()) } }
}

@Composable
internal fun TextControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel, restoreKey: String, selectedId: String?, placeText: (TimedText) -> Unit, previewText: (TimedText) -> Unit) {
    val r = draft.recipe()
    val titles = r.texts.filterNot { it.caption }
    val entry = titles.firstOrNull { it.id == selectedId } ?: titles.firstOrNull()
    ToolHeading("Words in the frame", "Up to 8 titles, each with its own timing and style.")
    OutlinedButton({
        val entry = TimedText(UUID.randomUUID().toString(), "Your story", 0, minOf(3000, draft.outputDuration()))
        vm.editRecipe(draft.id) { it.copy(texts = it.texts + entry) }
        placeText(entry)
    }, enabled = enabled && titles.size < 8) { Icon(Icons.Default.Add, null); Text("Add title") }
    if (entry == null) return
    ChoiceChips(titles.map { "Title ${titles.indexOf(it) + 1}" }, "Title ${titles.indexOf(entry) + 1}", enabled) { label -> placeText(titles[label.substringAfter(' ').toInt() - 1]) }
    TimedTextControls(entry, draft.outputDuration(), enabled, restoreKey, { placeText(entry) }, { previewText(entry) },
        textChange = { updated -> vm.editRecipe(draft.id, "text:${entry.id}") { it.copy(texts = it.texts.map { t -> if (t.id == entry.id) updated else t }) } }) { updated -> vm.editRecipe(draft.id) { it.copy(texts = it.texts.map { t -> if (t.id == entry.id) updated else t }) } }
    TextButton({ vm.editRecipe(draft.id) { it.copy(texts = it.texts.filterNot { t -> t.id == entry.id }) } }, enabled = enabled) { Icon(Icons.Default.DeleteOutline, null); Text("Remove title") }
}

@Composable
internal fun TimedTextControls(entry: TimedText, duration: Long, enabled: Boolean, restoreKey: String, placeText: () -> Unit, previewText: () -> Unit,
    textChange: ((TimedText) -> Unit)? = null, change: (TimedText) -> Unit) {
    // Room can emit an older autosave while the next keystroke is being entered.
    var text by remember(entry.id, restoreKey) { mutableStateOf(entry.text) }
    OutlinedTextField(text, { text = it.take(180); (textChange ?: change)(CaptionWords.editText(entry, text)) }, label = { Text(if (entry.caption) "Caption text" else "Title text") }, enabled = enabled, modifier = Modifier.fillMaxWidth(), maxLines = 4, shape = RoundedCornerShape(16.dp))
    val end = entry.endMs.coerceIn(100, duration.coerceAtLeast(100))
    val start = entry.startMs.coerceIn(0, end - 100)
    var range by remember(entry.id, start, end) { mutableStateOf(start.toFloat()..end.toFloat()) }
    Text("${audioTime(range.start.toLong())} — ${audioTime(range.endInclusive.toLong())}", color = Accent, style = MaterialTheme.typography.labelLarge)
    RangeSlider(range, { if (it.endInclusive - it.start >= 100) range = it }, valueRange = 0f..duration.toFloat(), enabled = enabled,
        onValueChangeFinished = { change(CaptionWords.retime(entry, range.start.toLong(), range.endInclusive.toLong())) })
    OutlinedButton(placeText, enabled = enabled) { Icon(Icons.Default.OpenWith, null); Spacer(Modifier.width(8.dp)); Text("Position on video") }
    Text("Drag the outlined text in the preview. Its position saves when you release.", color = Muted, style = MaterialTheme.typography.bodySmall)
    var panel by rememberSaveable(entry.caption) { mutableStateOf("Style") }
    ChoiceChips(listOf("Style", "Animate"), panel, enabled) { panel = it }
    if (panel == "Style") {
        ChoiceChips(listOf("White", "Yellow", "Blue"), entry.color, enabled) { change(entry.copy(color = it)) }
        EditSlider("Text size", entry.size, .035f.. .12f, enabled, { "${(it * 100).toInt()}%" }) { change(entry.copy(size = it)) }
        StudioTextStyleControls(entry, enabled, change)
    } else StudioTextMotionControls(entry, enabled, previewText, change)
}
