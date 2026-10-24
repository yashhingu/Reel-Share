package com.reelshare.feature.creator

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.reelshare.core.database.entity.DraftEntity
import java.io.File
import java.util.Locale

@Composable
internal fun MusicControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel, choose: () -> Unit) {
    val importing by vm.musicImporting.collectAsStateWithLifecycle()
    if (importing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Text("Adding your sound…", Modifier.weight(1f).padding(horizontal = 12.dp))
            TextButton(vm::cancelMusicImport) { Text("Cancel") }
        }
    }
    if (draft.audioPath.isBlank()) {
        OutlinedButton(choose, Modifier.fillMaxWidth().height(52.dp), enabled = enabled && !importing, shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.LibraryMusic, null); Spacer(Modifier.width(10.dp)); Text("Add music")
        }
        Text("Choose audio or use sound from a video on your device.", color = Muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    Surface(color = Card, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MusicArtwork(draft.audioArtwork)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(draft.audioName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(draft.audioArtist, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton({ vm.removeMusic(draft.id) }, enabled = enabled && !importing) { Icon(Icons.Default.Close, "Remove music") }
        }
    }
    if (draft.recipe().musicTracks.isNotEmpty()) {
        Text("Music is split into ${draft.recipe().musicTracks.size} pieces. Move, trim, set volume or shape fades for each piece in Clips.", color = Muted, style = MaterialTheme.typography.bodySmall)
        MusicDuckingControls(draft, enabled && !importing, vm)
        TextButton(choose, enabled = enabled && !importing) { Text("Change music") }
        return
    }
    val window = AudioWindow.bounded(draft.audioStartMs, draft.audioEndMs, draft.audioDurationMs, draft.outputDuration())
    val mix = draft.recipe().audioMix
    var range by remember(draft.id, window) { mutableStateOf(window.startMs.toFloat()..window.endMs.toFloat()) }
    var volume by remember(draft.id, draft.audioVolume) { mutableFloatStateOf(draft.audioVolume) }
    var offset by remember(draft.id, mix.musicOffsetMs) { mutableFloatStateOf(mix.musicOffsetMs.toFloat()) }
    Text("Choose your audio section", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(audioTime(range.start.toLong()), color = Accent, style = MaterialTheme.typography.labelLarge)
        Text("${audioTime((range.endInclusive - range.start).toLong())} selected", color = Muted, style = MaterialTheme.typography.labelMedium)
        Text(audioTime(range.endInclusive.toLong()), color = Accent, style = MaterialTheme.typography.labelLarge)
    }
    val maxOffset = AudioLanePlacement.maxOffset(draft.outputDuration(), window.endMs - window.startMs)
    Text("Starts in reel · ${audioTime(offset.toLong())}", style = MaterialTheme.typography.titleSmall)
    Slider(offset.coerceIn(0f, maxOffset.coerceAtLeast(1).toFloat()), { offset = it },
        valueRange = 0f..maxOffset.coerceAtLeast(1).toFloat(), enabled = enabled && !importing && maxOffset > 0,
        modifier = Modifier.systemGestureExclusion().semantics { contentDescription = "Music position in reel" },
        onValueChangeFinished = { vm.editRecipe(draft.id) { it.copy(audioMix = it.audioMix.copy(musicOffsetMs = offset.toLong())) } })
    Text("Move music along the reel here or hold its lane in Clips.", color = Muted, style = MaterialTheme.typography.bodySmall)
    fun saveRange(start: Long, end: Long) {
        vm.edit(draft.id) { it.copy(audioStartMs = start, audioEndMs = end) }
    }
    AudioWaveform(draft.audioPath, draft.audioDurationMs, range)
    RangeSlider(range, { proposed ->
        val bounded = AudioWindow.bounded(proposed.start.toLong(), proposed.endInclusive.toLong(), draft.audioDurationMs, draft.outputDuration())
        range = bounded.startMs.toFloat()..bounded.endMs.toFloat()
    }, modifier = Modifier.systemGestureExclusion(), onValueChangeFinished = { saveRange(range.start.toLong(), range.endInclusive.toLong()) },
        valueRange = 0f..draft.audioDurationMs.toFloat(), enabled = enabled && !importing)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ val delta = minOf(1000L, window.startMs); saveRange(window.startMs - delta, window.endMs - delta) }, enabled = enabled && !importing && window.startMs > 0) {
            Icon(Icons.Default.Replay, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Earlier 1s")
        }
        TextButton({ val delta = minOf(1000L, draft.audioDurationMs - window.endMs); saveRange(window.startMs + delta, window.endMs + delta) }, enabled = enabled && !importing && window.endMs < draft.audioDurationMs) {
            Text("Later 1s"); Spacer(Modifier.width(6.dp)); Icon(Icons.Default.FastForward, null, Modifier.size(18.dp))
        }
    }
    Text("Music volume · ${(volume * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
    Slider(volume, { volume = it }, modifier = Modifier.systemGestureExclusion(), onValueChangeFinished = { val value = volume; vm.edit(draft.id) { it.copy(audioVolume = value) } }, enabled = enabled && !importing)
    Text("Audio tracks are mixed with headroom to reduce clipping.", color = Muted, style = MaterialTheme.typography.bodySmall)
    AudioFadeControls("Music", window.endMs - window.startMs, mix.musicFadeInMs, mix.musicFadeOutMs, enabled && !importing) { start, end ->
        vm.editRecipe(draft.id) { it.copy(audioMix = it.audioMix.copy(musicFadeInMs = start, musicFadeOutMs = end)) }
    }
    MusicDuckingControls(draft, enabled && !importing, vm)
    Text("Music starts with the reel and stops at the selected end. It won’t repeat or extend your video.", color = Muted, style = MaterialTheme.typography.bodySmall)
    TextButton(choose, enabled = enabled && !importing) { Text("Change music") }
}

@Composable
private fun MusicDuckingControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel) {
    val mix = draft.recipe().audioMix
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("Lower music for voiceover", style = MaterialTheme.typography.titleSmall)
            Text("Music lowers during voiceover takes, including pauses, and returns afterward. Takes at 0% volume don’t lower music.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(mix.duckMusic, { value -> vm.editRecipe(draft.id) { it.copy(audioMix = it.audioMix.copy(duckMusic = value)) } },
            enabled = enabled, modifier = Modifier.semantics { contentDescription = "Lower music for voiceover" })
    }
    if (mix.duckMusic) {
        var amount by remember(mix.duckAmount) { mutableFloatStateOf(mix.duckAmount) }
        Text("Music reduction · ${(amount * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
        Slider(amount, { amount = it }, Modifier.systemGestureExclusion().semantics { contentDescription = "Music ducking amount" }, enabled = enabled,
            onValueChangeFinished = { vm.editRecipe(draft.id) { it.copy(audioMix = it.audioMix.copy(duckAmount = amount)) } })
        if (draft.recipe().audioTracks.none { it.volume > 0 && it.lengthOnReel(draft.outputDuration()) > 0 })
            Text("Add a voiceover take above 0% volume to hear ducking. Original video sound is unchanged.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MusicSheet(draft: DraftEntity, vm: StudioViewModel, dismiss: () -> Unit) {
    val entries by vm.drafts.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.importMusic(draft.id, uri); dismiss() }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.importMusic(draft.id, uri); dismiss() }
    }
    val recent = entries.filter { it.audioPath.isNotBlank() && it.id != draft.id }
        .distinctBy { it.audioName to it.audioArtist }
        .filter { "${it.audioName} ${it.audioArtist}".contains(query, true) }
    ModalBottomSheet(dismiss, containerColor = Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Find your sound", style = MaterialTheme.typography.headlineSmall)
            Text("A soundtrack for your next small moment.", color = Muted, style = MaterialTheme.typography.bodyMedium)
            SheetAction(Icons.Default.AudioFile, "Choose audio", "MP3, M4A, WAV and other supported device formats") { pickAudio.launch(arrayOf("audio/*")) }
            SheetAction(Icons.Default.VideoLibrary, "Use sound from video", "Select an MP4 saved on your device") { pickVideo.launch(arrayOf("video/mp4")) }
            if (entries.any { it.id != draft.id && it.audioPath.isNotBlank() }) {
                OutlinedTextField(query, { query = it.take(100) }, label = { Text("Search your recent music") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), singleLine = true)
                Text("Recently used", style = MaterialTheme.typography.titleSmall)
                recent.take(30).forEach { item ->
                    Surface(onClick = { vm.importMusic(draft.id, Uri.fromFile(File(item.audioPath)), item.audioName, item.audioArtist); dismiss() }, color = Bg, shape = RoundedCornerShape(18.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            MusicArtwork(item.audioArtwork)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(item.audioName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(item.audioArtist, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Default.Add, "Use this audio", tint = Accent)
                        }
                    }
                }
                if (recent.isEmpty()) Text("No matching music yet.", color = Muted)
            }
            Text("Use recordings you own or have permission to include in a video. Everything you import stays on this device.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun MusicArtwork(path: String) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (path.isNotBlank()) AsyncImage(path, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

internal fun audioTime(ms: Long): String = String.format(Locale.ROOT, "%d:%04.1f", ms / 60_000, ms % 60_000 / 1000f)
