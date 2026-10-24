package com.reelshare.feature.creator

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.reelshare.core.database.entity.DraftEntity
import java.io.File
import java.util.Locale
import java.util.UUID

@Composable
internal fun CollageControls(draft: DraftEntity, enabled: Boolean, vm: StudioViewModel,
    selectedId: String?, select: (String) -> Unit, create: () -> Unit) {
    val collage = draft.recipe().collage
    ToolHeading("Bring it together", if (collage == null) "Photos and videos, arranged in one frame." else "Tap a cell, then add media or record.")
    if (collage == null) {
        Button(create, enabled = enabled) { Icon(Icons.Default.Dashboard, null); Spacer(Modifier.width(8.dp)); Text("Create a collage") }
        Text("Starts a new draft with 2–4 photos or MP4 videos. Each cell has its own crop and position.", style = MaterialTheme.typography.bodySmall, color = Muted)
        return
    }
    fun update(change: (Collage) -> Collage) = vm.editRecipe(draft.id) { r -> r.copy(collage = r.collage?.let(change)) }
    val context = LocalContext.current
    val index = collage.assets.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val asset = collage.assets[index]
    var target by rememberSaveable(draft.id) { mutableStateOf(asset.id) }
    var capture by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.replaceCollageMedia(draft.id, target, it) } }
    val camera = rememberLauncherForActivityResult(object : ActivityResultContracts.CaptureVideo() {
        override fun createIntent(context: android.content.Context, input: Uri): Intent = super.createIntent(context, input)
            .putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, 60)
            .putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, 1)
    }) { success ->
        val uri = capture?.let(Uri::parse)
        if (success && uri != null) vm.replaceCollageMedia(draft.id, target, uri)
        else uri?.lastPathSegment?.let { File(context.filesDir, "captures/$it").delete() }
        capture = null
    }
    Text("${collage.assets.count { !it.placeholder }} of ${collage.assets.size} cells filled · draft saved", color = Accent, style = MaterialTheme.typography.labelLarge)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(collage.assets, key = { it.id }) { item ->
            val i = collage.assets.indexOf(item)
            Surface(onClick = { select(item.id) }, enabled = enabled, shape = RoundedCornerShape(14.dp),
                border = if (asset.id == item.id) BorderStroke(2.dp, Accent) else null, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.width(104.dp)) {
                    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                        if (item.placeholder) Icon(Icons.Default.Add, "Empty cell ${i + 1}", tint = Accent)
                        else AsyncImage(if (item.image) File(item.path) else File(File(item.path).parentFile, "cover_${File(item.path).nameWithoutExtension}.jpg"),
                            "Collage item ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Text("${i + 1} · ${if (item.placeholder) "Add" else if (item.image) "Photo" else "Video"}", Modifier.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ target = asset.id; pick.launch(arrayOf("image/*", "video/mp4")) }, enabled = enabled) {
            Icon(Icons.Default.AddPhotoAlternate, null); Spacer(Modifier.width(6.dp)); Text(if (asset.placeholder) "Add media" else "Replace")
        }
        OutlinedButton({
            try {
                val folder = File(context.filesDir, "captures").apply { mkdirs() }
                val file = File(folder, "${UUID.randomUUID()}.mp4")
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                target = asset.id; capture = uri.toString(); camera.launch(uri)
            } catch (e: Exception) { vm.error("No compatible camera is available. Add an MP4 from your device.") }
        }, enabled = enabled && (collage.assets.count { !it.image && it.id != asset.id } < CollageImporter.maxVideos(context))) {
            Icon(Icons.Default.Videocam, null); Spacer(Modifier.width(6.dp)); Text("Record")
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(Collage.LAYOUTS) { layout ->
            Surface(onClick = { update { it.copy(layout = layout) } }, enabled = enabled,
                shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (collage.layout == layout) BorderStroke(2.dp, Accent) else null) {
                Column(Modifier.width(92.dp).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BoxWithConstraints(Modifier.fillMaxWidth().height(62.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                        CollageLayout.cells(collage.assets.size, layout, .04f).forEachIndexed { i, cell ->
                            Box(Modifier.offset(maxWidth * cell.left, maxHeight * cell.top)
                                .size(maxWidth * cell.width, maxHeight * cell.height)
                                .background(if (i == 0) Accent else MaterialTheme.colorScheme.secondaryContainer))
                        }
                    }
                    Text(layout, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    EditSlider("Spacing", collage.gap, 0f.. .04f, enabled, { "${(it * 1000).toInt()}" }) { value -> update { it.copy(gap = value) } }
    if (asset.placeholder) {
        Text("Select an empty cell above, then record or add media. Fill every cell before exporting. This device supports up to ${CollageImporter.maxVideos(context)} moving videos per collage.", color = Muted, style = MaterialTheme.typography.bodySmall)
        return
    }
    ToolHeading("Item ${index + 1} crop", "Zoom in, then move the picture within its cell.") {
        IconButton({ update { c -> c.copy(assets = c.assets.map { if (it.id == asset.id) it.copy(zoom = 1f, panX = 0f, panY = 0f) else it }) } }, enabled = enabled) {
            Icon(Icons.Default.RestartAlt, "Reset item crop")
        }
    }
    fun crop(change: (CollageAsset) -> CollageAsset) = update { c -> c.copy(assets = c.assets.map { if (it.id == asset.id) change(it) else it }) }
    EditSlider("Zoom", asset.zoom, 1f..2f, enabled, { String.format(Locale.ROOT, "%.1f×", it) }) { value -> crop { it.copy(zoom = value) } }
    EditSlider("Horizontal position", asset.panX, -1f..1f, enabled && asset.zoom > 1f) { value -> crop { it.copy(panX = value) } }
    EditSlider("Vertical position", asset.panY, -1f..1f, enabled && asset.zoom > 1f) { value -> crop { it.copy(panY = value) } }
    Row {
        fun move(delta: Int) = update { c ->
            val soundId = c.assets.getOrNull(c.audioIndex)?.id
            val media = c.assets.toMutableList().apply { java.util.Collections.swap(this, index, index + delta) }
            c.copy(assets = media, audioIndex = media.indexOfFirst { it.id == soundId })
        }
        TextButton({ move(-1) }, enabled = enabled && index > 0) { Icon(Icons.Default.ChevronLeft, null); Text("Move earlier") }
        TextButton({ move(1) }, enabled = enabled && index < collage.assets.lastIndex) { Text("Move later"); Icon(Icons.Default.ChevronRight, null) }
    }
    ToolHeading("Original sound", "Choose one video's sound. Add a soundtrack in Audio.")
    val options = listOf("Silent") + collage.assets.mapIndexedNotNull { i, a -> if (a.image) null else "Video ${i + 1}" }
    ChoiceChips(options, if (collage.audioIndex < 0) "Silent" else "Video ${collage.audioIndex + 1}", enabled) { value ->
        update { it.copy(audioIndex = if (value == "Silent") -1 else value.substringAfter(' ').toInt() - 1) }
    }
    if (collage.assets.all { it.image }) {
        EditSlider("Collage duration", draft.durationMs.toFloat(), 1000f..60_000f, enabled, { audioTime(it.toLong()) }) { value ->
            vm.edit(draft.id) { it.copy(durationMs = value.toLong(), trimStartMs = 0, trimEndMs = value.toLong()) }
        }
    } else Text("Duration follows the shortest video, up to 60 seconds. Use Trim to shorten the whole collage.", color = Muted, style = MaterialTheme.typography.bodySmall)
}
