package com.reelshare.feature.creator

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reelshare.core.database.entity.DraftEntity
import java.util.UUID

@Composable
internal fun AudioSplitControls(draft: DraftEntity, position: Long, enabled: Boolean, vm: StudioViewModel,
    selectedId: String?, select: (String) -> Unit) {
    val recipe = draft.recipe()
    val music = AudioSplit.music(draft)
    val tracks = music + recipe.audioTracks
    if (tracks.isEmpty()) return
    val selected = tracks.firstOrNull { it.id == selectedId } ?: tracks.first()
    Text("Audio at playhead · ${audioTime(position)}", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tracks.forEach { track ->
            val label = music.indexOfFirst { it.id == track.id }.takeIf { it >= 0 }?.let { "Music ${it + 1}" }
                ?: "Take ${recipe.audioTracks.indexOf(track) + 1}"
            FilterChip(selected.id == track.id, { select(track.id) }, enabled = enabled, label = { Text(label) })
        }
    }
    val canSplit = tracks.size < 8 && AudioSplit.canSplit(selected, position, draft.outputDuration())
    OutlinedButton({
        val newId = UUID.randomUUID().toString()
        vm.edit(draft.id) { current -> AudioSplit.at(current, selected.id, position, newId) }
    }, enabled = enabled && canSplit) { Text("Split audio at playhead") }
    Text(if (tracks.size >= 8) "Up to 8 audio pieces. Remove a piece before splitting again."
        else "Select audio, then scrub inside it. Leave at least one second on each side of the cut.",
        color = Muted, style = MaterialTheme.typography.bodySmall)
    val isLegacyMusic = recipe.musicTracks.isEmpty() && music.any { it.id == selected.id }
    if (isLegacyMusic) Text("Adjust this soundtrack in Audio. Split it to edit each piece separately.",
        color = Muted, style = MaterialTheme.typography.bodySmall)
    else {
        fun change(update: (AudioTrack) -> AudioTrack) {
            vm.editRecipe(draft.id) { current -> AudioSplit.update(current, selected.id, update) }
        }
        var volume by remember(selected.id, selected.volume) { mutableFloatStateOf(selected.volume) }
        Text("${if (music.any { it.id == selected.id }) "Music" else "Take"} piece · ${audioTime(selected.lengthOnReel(draft.outputDuration()))} · volume ${(volume * 100).toInt()}%",
            style = MaterialTheme.typography.titleSmall)
        Slider(volume, { volume = it }, modifier = Modifier.systemGestureExclusion().semantics { contentDescription = "Selected audio piece volume" },
            enabled = enabled, onValueChangeFinished = { val value = volume; change { it.copy(volume = value) } })
        if (selected.envelopeLengthMs > 0) {
            Text("The original fade continues across this cut. Set separate fades to shape this piece on its own.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ change { it.copy(fadeInMs = 0, fadeOutMs = 0, envelopeOffsetMs = 0, envelopeLengthMs = 0) } },
                enabled = enabled) { Text("Set separate fades") }
        } else key(selected.id) {
            AudioFadeControls("Selected piece", selected.lengthOnReel(draft.outputDuration()), selected.fadeInMs, selected.fadeOutMs, enabled) { start, end ->
                change { it.copy(fadeInMs = start, fadeOutMs = end) }
            }
        }
    }
    if (recipe.musicTracks.isNotEmpty() && music.any { it.id == selected.id }) {
        TextButton({ vm.editRecipe(draft.id) { current -> current.copy(musicTracks = current.musicTracks.filterNot { it.id == selected.id }) } },
            enabled = enabled && music.size > 1) { Text("Remove selected music piece") }
    }
}
