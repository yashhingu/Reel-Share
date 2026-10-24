package com.reelshare.feature.creator

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.reelshare.core.database.entity.DraftEntity

@Composable
internal fun CaptionControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel, restoreKey: String, selectedId: String?, placeText: (TimedText) -> Unit, previewText: (TimedText) -> Unit) {
    val context = LocalContext.current
    var language by rememberSaveable { mutableStateOf("English") }
    var confirm by rememberSaveable { mutableStateOf(false) }
    var pendingLanguage by rememberSaveable { mutableStateOf<String?>(null) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingLanguage?.let { vm.generateCaptions(draft.id, it) }; pendingLanguage = null
    }
    val captions = draft.recipe().texts.filter { it.caption }.sortedBy { it.startMs }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importCaptions(draft.id, it) } }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri -> uri?.let { vm.saveCaptions(draft.id, it) } }
    ToolHeading("Let your words be seen", "Generate from the original clips' speech. Review every caption before sharing.")
    ChoiceChips(listOf("English", "Hindi"), language, enabled) { language = it }
    val installed = remember(language, draft.captionWorkId) { CaptionModels.installed(context, language) }
    Text(if (installed) "$language model ready · recognition works offline" else "First use downloads the $language model (about 40–45 MB). After that, recognition works offline. Your video and audio stay on this device.", color = Muted, style = MaterialTheme.typography.bodySmall)
    Button({ confirm = true }, Modifier.fillMaxWidth(), enabled = enabled) {
        Icon(Icons.Default.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text(if (captions.isEmpty()) "Generate captions" else "Regenerate captions")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ pick.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream")) }, enabled = enabled) { Text("Import SRT") }
        OutlinedButton({ save.launch("${draft.title.take(60)}.srt") }, enabled = enabled && captions.isNotEmpty()) { Text("Export SRT") }
    }
    if (captions.isNotEmpty()) {
        val index = captions.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
        Text("Caption ${index + 1} of ${captions.size}", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ placeText(captions[index - 1]) }, enabled = enabled && index > 0) { Text("Previous") }
            TextButton({ placeText(captions[index + 1]) }, enabled = enabled && index < captions.lastIndex) { Text("Next") }
        }
        val entry = captions[index]
        TimedTextControls(entry, draft.outputDuration(), enabled, restoreKey, { placeText(entry) }, { previewText(entry) },
            textChange = { value -> vm.editRecipe(draft.id, "caption:${entry.id}") { it.copy(texts = it.texts.map { t -> if (t.id == entry.id) value else t }) } }) { value -> vm.editRecipe(draft.id) { it.copy(texts = it.texts.map { t -> if (t.id == entry.id) value else t }) } }
        TextButton({ vm.editRecipe(draft.id) { it.copy(texts = it.texts.map { t -> if (t.caption) t.copy(position = entry.position, x = entry.x, y = entry.y, size = entry.size, color = entry.color, background = entry.background, animated = entry.animated, rotation = entry.rotation, font = entry.font, style = entry.style,
            animation = entry.animation, animationMs = entry.animationMs, wordHighlight = entry.wordHighlight, highlightColor = entry.highlightColor) else t }) } }, enabled = enabled) { Text("Apply style to all captions") }
        TextButton({ vm.editRecipe(draft.id) { it.copy(texts = it.texts.filterNot { t -> t.id == entry.id }) } }, enabled = enabled) { Text("Delete this caption") }
        TextButton({ vm.editRecipe(draft.id) { it.copy(texts = it.texts.filterNot { t -> t.caption }) } }, enabled = enabled) { Text("Clear captions") }
    }
    Text("Set clip order, trims and speed first. Caption times use the final timeline; regenerate after changing its pace.", color = Muted, style = MaterialTheme.typography.bodySmall)
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Generate $language captions?") },
        text = { Text((if (installed) "Uses the language model on this device." else "Downloads about 40–45 MB over your current connection, then recognizes speech on this device.") +
            " Existing captions will be replaced only after generation succeeds. You can leave this screen and cancel from the notification.") },
        confirmButton = { TextButton({
            confirm = false
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                pendingLanguage = language; notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else vm.generateCaptions(draft.id, language)
        }) { Text("Generate") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
