package com.reelshare.feature.creator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.work.WorkInfo
import coil.compose.AsyncImage
import com.reelshare.core.database.entity.DraftEntity
import com.reelshare.core.database.entity.StudioProfile
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import com.reelshare.core.designsystem.theme.ReelShareTheme
import com.reelshare.core.designsystem.R as DesignR

internal val Bg: Color @Composable get() = MaterialTheme.colorScheme.background
internal val Card: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
internal val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioScreen(vm: StudioViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val drafts by vm.drafts.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val jobs by vm.work.collectAsStateWithLifecycle()
    val appearance by vm.appearance.collectAsStateWithLifecycle()
    var menu by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    var collageStart by rememberSaveable { mutableStateOf(false) }
    var playId by rememberSaveable { mutableStateOf<String?>(null) }
    var capture by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<DraftEntity?>(null) }
    var pending by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.let { (id, download) -> vm.export(id, download) }; pending = null
    }
    fun beginExport() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else { pending?.let { (id, download) -> vm.export(id, download) }; pending = null }
    }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginExport() else { pending = null; vm.error("Allow storage access to save to Download/ReelShare.") }
    }
    fun export(id: String, download: Boolean) {
        pending = id to download
        if (download && Build.VERSION.SDK_INT <= 28 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else beginExport()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importVideo) }
    val collagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), vm::importCollage)
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::photo) }
    val camera = rememberLauncherForActivityResult(object : ActivityResultContracts.CaptureVideo() {
        override fun createIntent(context: android.content.Context, input: Uri): Intent = super.createIntent(context, input).putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, 90)
    }) { success ->
        val uri = capture?.let(Uri::parse)
        if (success && uri != null) vm.importVideo(uri)
        else uri?.lastPathSegment?.let { File(context.filesDir, "captures/$it").delete() }
        capture = null
    }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.clearMessage() } }
    BackHandler(menu || sheet || selected != null || playId != null || tab != 0) {
        when { menu -> menu = false; sheet -> sheet = false; selected != null -> vm.open(null); playId != null -> playId = null; else -> tab = 0 }
    }
    ReelShareTheme(appearance) {
        Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.TopCenter) {
            // Keep a comfortable portrait workspace on tablets and desktop windows too.
            Scaffold(modifier = Modifier.widthIn(max = 600.dp).fillMaxSize(), containerColor = Bg,
                snackbarHost = { SnackbarHost(snackbar) }, floatingActionButton = {
                    if (tab == 0 && selected == null && playId == null && drafts.any { it.renderedRevision == it.revision && it.renderedPath.isNotBlank() })
                        ExtendedFloatingActionButton(onClick = { sheet = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New reel") },
                            containerColor = Accent, contentColor = MaterialTheme.colorScheme.onPrimary)
                }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (importing) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Importing your video…", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    val draft = drafts.find { it.id == selected }
                    when {
                        draft != null -> Editor(draft, jobs.find { it.id.toString() == draft.workId }, vm, { vm.open(null) }) { export(draft.id, it) }
                        playId != null -> {
                            PageHeader("Your reel", "Made by you", { playId = null })
                            drafts.find { it.id == playId }?.let { video ->
                                ReelPage(video, !sheet && !menu, Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp), usePublic = true) { vm.open(video.id); playId = null }
                            }
                        }
                        tab == 0 -> {
                            StudioHeader(profile, { tab = 3 }, { menu = true })
                            val reels = drafts.filter { it.renderedRevision == it.revision && it.renderedPath.isNotBlank() }
                            if (reels.isEmpty()) EmptyStudio(drafts.size, { sheet = true }, { tab = 1 })
                            else {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Made by you", style = MaterialTheme.typography.headlineSmall)
                                        Text("${reels.size} ${if (reels.size == 1) "reel" else "reels"} · swipe to explore", color = Muted, style = MaterialTheme.typography.bodySmall)
                                    }
                                    Icon(Icons.Default.AutoAwesome, null, tint = Accent)
                                }
                                val pager = rememberPagerState { reels.size }
                                VerticalPager(pager, Modifier.weight(1f).padding(horizontal = 16.dp).padding(bottom = 12.dp), pageSpacing = 12.dp, key = { reels[it].id }) { index ->
                                    ReelPage(reels[index], index == pager.settledPage && !sheet && !menu && !importing, Modifier.fillMaxSize()) { vm.open(reels[index].id) }
                                }
                            }
                        }
                        tab == 1 || tab == 2 -> {
                            val saved = tab == 2
                            PageHeader(if (saved) "Saved videos" else "Drafts", if (saved) "Your finished takes" else "Good ideas take a few takes", { tab = 0 }, { sheet = true })
                            val entries = if (saved) drafts.filter { it.exportUri.isNotEmpty() } else drafts
                            if (entries.isEmpty()) LibraryEmpty(saved) { sheet = true }
                            else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                item { Text(if (saved) "Saved to Download/ReelShare" else "Every edit is saved automatically", color = Muted, style = MaterialTheme.typography.bodySmall) }
                                items(entries, key = { it.id }) { item -> DraftCard(item, jobs.find { it.id.toString() == item.workId }, saved,
                                    { if (saved) playId = item.id else vm.open(item.id) }, { export(item.id, true) }, { vm.cancel(item) }, { deleting = item }) }
                            }
                        }
                        tab == 3 -> Profile(profile, { tab = 0 }, { photo.launch(arrayOf("image/*")) }, vm::removePhoto, vm::updateProfile)
                        else -> Appearance(appearance, vm::appearance) { tab = 0 }
                    }
                }
            }
        }
        if (menu) ModalBottomSheet({ menu = false }, containerColor = Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your studio", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
                SheetAction(Icons.Default.EditNote, "Drafts", "${drafts.size} ${if (drafts.size == 1) "project" else "projects"} · automatically saved") { menu = false; tab = 1 }
                SheetAction(Icons.Default.DownloadDone, "Saved videos", "${drafts.count { it.exportUri.isNotBlank() }} videos on your device") { menu = false; tab = 2 }
                SheetAction(Icons.Default.Palette, "Appearance", "$appearance theme") { menu = false; tab = 4 }
                Text("Offline. Personal. Yours.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp, start = 4.dp))
            }
        }
        if (sheet) ModalBottomSheet({ sheet = false }, containerColor = Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Start your reel", style = MaterialTheme.typography.headlineSmall)
                Text("Record, import a video or bring a few moments together.", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                SheetAction(Icons.Default.Videocam, "Create reel", "Open camera · up to 90 seconds", !importing) {
                    sheet = false
                    try {
                        val folder = File(context.filesDir, "captures").apply { mkdirs() }
                        val file = File(folder, "capture_${UUID.randomUUID()}.mp4")
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        capture = uri.toString(); camera.launch(uri)
                    } catch (e: Exception) { vm.error("No compatible camera is available. Import an MP4 instead.") }
                }
                SheetAction(Icons.Default.VideoLibrary, "Import MP4", "From your device · up to 512 MB / 10 min", !importing) { sheet = false; picker.launch(arrayOf("video/mp4")) }
                SheetAction(Icons.Default.Dashboard, "Video layouts / collage", "Record or import into a split frame", !importing) {
                    sheet = false; collageStart = true
                }
            }
        }
        if (collageStart) CollageStartSheet({ collageStart = false }, {
            collageStart = false; collagePicker.launch(arrayOf("image/*", "video/mp4"))
        }) { count, layout -> collageStart = false; vm.createVideoLayout(count, layout) }
        deleting?.let { item -> AlertDialog({ deleting = null }, title = { Text("Delete this draft?") },
            text = { Text("This removes the private original and preview. Videos saved in Downloads stay on your device.") },
            confirmButton = { TextButton({ vm.delete(item); deleting = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Keep draft") } }) }
    }
}

@Composable
private fun StudioHeader(profile: StudioProfile, openProfile: () -> Unit, menu: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(DesignR.drawable.studio_mark), null, tint = Accent, modifier = Modifier.size(32.dp))
        Text("ReelShare", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 10.dp))
        IconButton(openProfile) {
            if (profile.photoPath.isNotBlank()) AsyncImage(File(profile.photoPath), "Profile", Modifier.size(32.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.AccountCircle, "Profile", tint = Muted, modifier = Modifier.size(28.dp))
        }
        IconButton(menu) { Icon(Icons.Default.Menu, "Studio menu") }
    }
}

@Composable
internal fun PageHeader(title: String, subtitle: String, back: () -> Unit, action: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (action != null) IconButton(action) { Icon(Icons.Default.Add, "New reel") }
    }
}

@Composable
private fun EmptyStudio(draftCount: Int, create: () -> Unit, drafts: () -> Unit) {
    val hasDrafts = draftCount > 0
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 20.dp, bottom = 24.dp)) {
        Text(if (hasDrafts) "Keep your\nideas moving." else "Make your\nnext reel.", style = MaterialTheme.typography.headlineLarge)
        Text(if (hasDrafts) "$draftCount ${if (draftCount == 1) "draft is" else "drafts are"} saved on this device. Pick up where you left off."
            else "Record a moment or import a video. Shape it with music, motion and captions.",
            color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        Button(if (hasDrafts) drafts else create, Modifier.fillMaxWidth().heightIn(min = 56.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), shape = RoundedCornerShape(20.dp)) {
            Icon(if (hasDrafts) Icons.Default.EditNote else Icons.Default.Add, null, Modifier.size(22.dp))
            Text(if (hasDrafts) "Continue a draft" else "Create a reel", Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(20.dp))
        }
        if (hasDrafts) OutlinedButton(create, Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp),
            contentPadding = PaddingValues(16.dp), shape = RoundedCornerShape(18.dp)) {
            Icon(Icons.Default.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Create a reel")
        }
        Text("In your editor", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 28.dp, bottom = 12.dp))
        Surface(color = Card, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(18.dp)) {
                StudioFeature(Icons.Default.ContentCut, "Trim & arrange", "Crop, combine and reorder clips.")
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                StudioFeature(Icons.Default.AutoAwesome, "Find your style", "Color, text and motion controls.")
                HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                StudioFeature(Icons.Default.GraphicEq, "Sound & captions", "Add your music. Caption your words.")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Lock, null, Modifier.size(16.dp), tint = Muted)
            Text("No account. Your videos stay on this device.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StudioFeature(icon: ImageVector, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp)) {
            Icon(icon, null, Modifier.padding(10.dp).size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun LibraryEmpty(saved: Boolean, create: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(24.dp)) {
            Icon(if (saved) Icons.Default.DownloadDone else Icons.Default.EditNote, null, Modifier.padding(24.dp).size(36.dp), tint = Accent)
        }
        Text(if (saved) "Keep your best takes" else "Your ideas live here", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
        Text(if (saved) "Export a reel to save it in Download/ReelShare." else "Import a video and your edits will be saved as a draft.", color = Muted, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
        Button(create, Modifier.height(52.dp)) { Text("Create a reel") }
    }
}

@Composable
internal fun SheetAction(icon: ImageVector, title: String, detail: String, enabled: Boolean = true, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Bg).clickable(enabled = enabled, onClick = click).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, null, Modifier.padding(12.dp).size(24.dp), tint = Accent)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DraftCard(draft: DraftEntity, work: WorkInfo?, saved: Boolean, open: () -> Unit, download: () -> Unit, cancel: () -> Unit, delete: () -> Unit) {
    var actions by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(24.dp), color = Card) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = open).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(File(draft.thumbnailPath), null, Modifier.size(68.dp, 88.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(draft.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${audioTime(remember(draft.editRecipe, draft.trimStartMs, draft.trimEndMs) { draft.outputDuration() })} · ${draft.aspect}", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        Text(if (saved) "Ready to watch" else if (draft.workId.isNotEmpty()) "Exporting…" else "Continue editing", color = Accent, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                Box {
                    IconButton({ actions = true }) { Icon(Icons.Default.MoreVert, "Options for ${draft.title}") }
                    DropdownMenu(actions, { actions = false }) {
                        DropdownMenuItem({ Text(if (saved) "Play video" else "Edit draft") }, { actions = false; open() })
                        if (draft.workId.isEmpty()) {
                            if (!saved) DropdownMenuItem({ Text("Save video") }, { actions = false; download() })
                            DropdownMenuItem({ Text("Delete draft", color = MaterialTheme.colorScheme.error) }, { actions = false; delete() })
                        }
                    }
                }
            }
            if (draft.workId.isNotEmpty()) WorkProgress(work, cancel)
            if (draft.error.isNotBlank()) Text(draft.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
        }
    }
}

@Composable
internal fun WorkProgress(work: WorkInfo?, cancel: () -> Unit) {
    val progress = work?.progress?.getInt("progress", 0) ?: 0
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${work?.progress?.getString("stage") ?: "Queued"} · $progress%", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            TextButton(cancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun Appearance(current: String, change: (String) -> Unit, back: () -> Unit) {
    PageHeader("Appearance", "A studio that feels like you", back)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose your canvas", style = MaterialTheme.typography.headlineMedium)
        Text("Comfortable in the sunshine. Focused after dark.", color = Muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
        listOf("System", "Light", "Dark").forEach { value ->
            Surface(color = if (current == value) MaterialTheme.colorScheme.primaryContainer else Card, shape = RoundedCornerShape(22.dp), onClick = { change(value) }) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (value) { "Light" -> Icons.Default.LightMode; "Dark" -> Icons.Default.DarkMode; else -> Icons.Default.BrightnessAuto }, null, tint = Accent)
                    Column(Modifier.weight(1f).padding(start = 16.dp)) {
                        Text(value, style = MaterialTheme.typography.titleMedium)
                        Text(when (value) { "Light" -> "Fresh, clear, and open"; "Dark" -> "Let your footage shine"; else -> "Follow your device settings" }, color = Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    RadioButton(current == value, onClick = null)
                }
            }
        }
        Text("Your choice is saved on this device.", color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun Profile(profile: StudioProfile, back: () -> Unit, photo: () -> Unit, removePhoto: () -> Unit, save: (String, String) -> Unit) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var name by remember(profile.name) { mutableStateOf(profile.name) }
    var bio by remember(profile.bio) { mutableStateOf(profile.bio) }
    PageHeader("Profile", "Your own creative corner", back)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = Card) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = photo), contentAlignment = Alignment.Center) {
                    if (profile.photoPath.isNotBlank()) AsyncImage(File(profile.photoPath), "Change profile photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Icon(Icons.Default.AddAPhoto, "Add profile photo", tint = Accent, modifier = Modifier.size(32.dp))
                }
                TextButton(photo) { Text("Change photo") }
                if (profile.photoPath.isNotBlank()) TextButton(removePhoto) { Text("Remove photo", color = Muted) }
                if (!editing) {
                    Text(profile.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    if (profile.bio.isNotBlank()) Text(profile.bio, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = Muted, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        if (editing) {
            OutlinedTextField(name, { name = it.take(60) }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp))
            OutlinedTextField(bio, { bio = it.take(160) }, label = { Text("A little about you") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), minLines = 2, maxLines = 4)
            Button({ save(name, bio); editing = false }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Text("Save profile") }
        } else FilledTonalButton({ editing = true }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Edit profile") }
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Lock, null, tint = Muted, modifier = Modifier.size(18.dp))
            Text("Just for you. Your profile and videos stay on this device, with no account needed.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ReelPage(draft: DraftEntity, active: Boolean, modifier: Modifier, usePublic: Boolean = false, edit: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black)) {
        if (active) StudioPlayer(if (usePublic) draft.exportUri else draft.renderedPath, 0, 0, false, false, Modifier.fillMaxSize())
        else AsyncImage(File(draft.thumbnailPath), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(.85f)))).padding(24.dp).padding(bottom = 56.dp)) {
            Text("YOUR ORIGINAL", color = Color(0xFFA7BEFF), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp)
            Text(draft.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            if (draft.caption.isNotBlank()) Text(draft.caption, color = Color.White.copy(.8f), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            TextButton(edit, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 12.dp)) { Icon(Icons.Default.Tune, null, Modifier.size(16.dp), tint = Color(0xFFA7BEFF)); Spacer(Modifier.width(8.dp)); Text("Edit reel", color = Color(0xFFA7BEFF)) }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun StudioPlayer(path: String, start: Long, end: Long, mute: Boolean, crop: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var error by remember(path) { mutableStateOf(false) }
    var boost by remember { mutableStateOf(false) }
    val player = remember(path) { ExoPlayer.Builder(context).build().apply {
        repeatMode = Player.REPEAT_MODE_ONE; setHandleAudioBecomingNoisy(true)
        setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, true)
    } }
    LaunchedEffect(player, start, end) {
        delay(250)
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
        if (end > start) clipping.setEndPositionMs(end)
        player.setMediaItem(MediaItem.Builder().setUri(if (path.startsWith("content:") || path.startsWith("file:")) Uri.parse(path) else Uri.fromFile(File(path))).setClippingConfiguration(clipping.build()).build())
        player.prepare(); player.playWhenReady = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    LaunchedEffect(mute) { player.volume = if (mute) 0f else 1f }
    DisposableEffect(player, owner) {
        val listener = object : Player.Listener { override fun onPlayerError(e: PlaybackException) { error = true } }
        val observer = LifecycleEventObserver { _, event -> when (event) {
            Lifecycle.Event.ON_PAUSE -> { player.setPlaybackSpeed(1f); boost = false; player.pause() }
            Lifecycle.Event.ON_RESUME -> player.play()
            else -> Unit
        } }
        player.addListener(listener); owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); player.removeListener(listener); player.release() }
    }
    Box(modifier.pointerInput(player, owner) {
        detectTapGestures(onTap = { if (player.isPlaying) player.pause() else player.play() }, onLongPress = { position ->
            if (position.x >= size.width / 2 && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) { boost = true; player.setPlaybackSpeed(2f); player.play() }
        }, onPress = { try { tryAwaitRelease() } finally { boost = false; if (owner.lifecycle.currentState != Lifecycle.State.DESTROYED) player.setPlaybackSpeed(1f) } })
    }) {
        AndroidView(factory = { PlayerView(it).apply { useController = false; this.player = player; resizeMode = if (crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT } },
            update = { it.player = player }, modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
        if (boost) Text("2×", color = Color(0xFF102D75), modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).clip(CircleShape).background(Color(0xFFA7BEFF)).padding(horizontal = 18.dp, vertical = 6.dp))
        if (error) Column(Modifier.align(Alignment.Center).background(Card).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Preview unavailable"); Text("Open the draft to create a fresh preview.", fontSize = 12.sp, color = Muted) }
    }
}

internal fun time(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)
