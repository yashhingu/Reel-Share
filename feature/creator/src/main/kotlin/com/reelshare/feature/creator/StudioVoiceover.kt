package com.reelshare.feature.creator

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.reelshare.core.database.entity.DraftEntity

@Composable
internal fun VoiceoverControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel, record: () -> Unit) {
    val tracks = draft.recipe().audioTracks
    var selectedId by remember(draft.id) { mutableStateOf<String?>(null) }
    val selected = tracks.firstOrNull { it.id == selectedId } ?: tracks.lastOrNull()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Voiceover", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        TextButton(record, enabled = enabled && tracks.size < 4) { Icon(Icons.Default.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Record voiceover") }
    }
    if (tracks.isEmpty()) {
        Text("Add your voice alongside music. Each take is saved in this draft.", color = Muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tracks.forEachIndexed { i, a -> FilterChip(selected?.id == a.id, { selectedId = a.id }, enabled = enabled, label = { Text("Take ${i + 1}") }) }
    }
    if (selected == null) return
    val track = selected
    fun change(update: (AudioTrack) -> AudioTrack) { vm.editRecipe(draft.id) { r -> r.copy(audioTracks = r.audioTracks.map { if (it.id == track.id) update(it).bounded() else it }) } }
    var range by remember(track) { mutableStateOf(track.startMs.toFloat()..track.endMs.toFloat()) }
    var offset by remember(track) { mutableFloatStateOf(track.offsetMs.toFloat()) }
    var volume by remember(track) { mutableFloatStateOf(track.volume) }
    Text("${audioTime(range.start.toLong())} – ${audioTime(range.endInclusive.toLong())} of take", style = MaterialTheme.typography.labelLarge)
    AudioWaveform(track.path, track.durationMs, range)
    RangeSlider(range, { if (it.endInclusive - it.start >= 1000) range = it }, valueRange = 0f..track.durationMs.toFloat(), enabled = enabled,
        modifier = Modifier.systemGestureExclusion().semantics { contentDescription = "Voiceover source selection" },
        onValueChangeFinished = { change { it.copy(startMs = range.start.toLong(), endMs = range.endInclusive.toLong(), envelopeOffsetMs = 0, envelopeLengthMs = 0) } })
    val maxOffset = (draft.outputDuration() - 1000).coerceAtLeast(0)
    Text("Starts in reel · ${audioTime(offset.toLong())}", style = MaterialTheme.typography.titleSmall)
    Slider(offset.coerceIn(0f, maxOffset.coerceAtLeast(1).toFloat()), { offset = it }, valueRange = 0f..maxOffset.coerceAtLeast(1).toFloat(), enabled = enabled && maxOffset > 0,
        modifier = Modifier.systemGestureExclusion().semantics { contentDescription = "Voiceover position in reel" },
        onValueChangeFinished = { change { it.copy(offsetMs = offset.toLong()) } })
    Text("Voiceover volume · ${(volume * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
    Slider(volume, { volume = it }, enabled = enabled, modifier = Modifier.systemGestureExclusion().semantics { contentDescription = "Voiceover volume" },
        onValueChangeFinished = { change { it.copy(volume = volume) } })
    Text(if (track.lengthOnReel(draft.outputDuration()) == 0L) "This take is outside the current reel. Move it earlier to hear it."
        else "${audioTime(track.lengthOnReel(draft.outputDuration()))} plays. Audio stops at the reel’s end without extending your video.", color = Muted, style = MaterialTheme.typography.bodySmall)
    AudioFadeControls("Voiceover", track.lengthOnReel(draft.outputDuration()), track.fadeInMs, track.fadeOutMs, enabled) { start, end ->
        change { it.copy(fadeInMs = start, fadeOutMs = end, envelopeOffsetMs = 0, envelopeLengthMs = 0) }
    }
    TextButton({ vm.editRecipe(draft.id) { r -> r.copy(audioTracks = r.audioTracks.filterNot { it.id == track.id }) } }, enabled = enabled) {
        Icon(Icons.Default.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remove take")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VoiceoverSheet(draft: DraftEntity, offsetMs: Long, vm: StudioViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val state by vm.voiceover.state.collectAsStateWithLifecycle()
    var permissionDenied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) vm.startVoiceover(draft.id, offsetMs)
    }
    DisposableEffect(owner, vm) {
        vm.voiceover.reset()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) vm.voiceover.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); vm.voiceover.stop() }
    }
    fun close() { vm.voiceover.stop(); dismiss() }
    ModalBottomSheet(::close, containerColor = Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Mic, null, Modifier.size(36.dp), tint = Accent)
            Text("Your voice, your story", style = MaterialTheme.typography.headlineSmall)
            Text("Preview is paused to avoid speaker feedback. Record a take, then adjust its start, section and volume in Audio.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            Text("Starts at ${audioTime(offsetMs.coerceAtMost((draft.outputDuration() - 1000).coerceAtLeast(0)))}", style = MaterialTheme.typography.labelLarge)
            Text(audioTime(state.elapsedMs), style = MaterialTheme.typography.displaySmall)
            if (state.phase == "Recording") LinearProgressIndicator(progress = { state.level }, Modifier.fillMaxWidth().semantics { contentDescription = "Microphone level" })
            if (state.phase == "Preparing" || state.phase == "Saving") CircularProgressIndicator()
            if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error)
            if (permissionDenied) {
                Text("Microphone access is needed to record a take. Try again or allow Microphone in Android Settings.", color = MaterialTheme.colorScheme.error)
                TextButton({ context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null))) }) { Text("Open app settings") }
            }
            if (state.phase == "Saved") Text("Take saved to this draft.", color = Accent)
            if (state.phase == "Recording") {
                Button({ vm.voiceover.stop() }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop and save take") }
                TextButton({ vm.voiceover.stop(discard = true) }) { Text("Discard take") }
            } else if (!state.busy) Button({
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVoiceover(draft.id, offsetMs)
                else permission.launch(Manifest.permission.RECORD_AUDIO)
            }, Modifier.fillMaxWidth(), enabled = draft.recipe().audioTracks.size < 4) { Text(if (state.phase == "Saved") "Record another take" else "Start recording") }
            Text("Closing this sheet or leaving the app stops and saves a valid take. At least one second of encoded audio is needed; short reels get extra recording time to finish encoding. Up to four takes per reel.", color = Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(::close) { Text("Done") }
        }
    }
}
