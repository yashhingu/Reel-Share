package com.reelshare.feature.creator

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.reelshare.core.database.dao.StudioDao
import com.reelshare.core.database.entity.DraftEntity
import com.reelshare.core.database.entity.StudioProfile
import com.reelshare.core.database.entity.StudioSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class StudioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: StudioDao,
    private val savedState: SavedStateHandle,
    private val musicImporter: MusicImporter
) : ViewModel() {
    val drafts = dao.observeDrafts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val profile = dao.observeProfile().map { it ?: StudioProfile() }.stateIn(viewModelScope, SharingStarted.Eagerly, StudioProfile())
    val appearance = dao.observeSettings().map { it?.appearance ?: "System" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "System")
    val musicImporting = MutableStateFlow(false)
    val styles = dao.observeStyles().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var musicJob: Job? = null
    val selectedId = savedState.getStateFlow<String?>("draft", null)
    val importing = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private val manager = WorkManager.getInstance(context)
    val work = manager.getWorkInfosByTagFlow(StudioExportWorker.QUEUE)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val captionWork = manager.getWorkInfosByTagFlow(StudioCaptionWorker.QUEUE)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val edits = Mutex()
    internal val voiceover = VoiceoverRecorder(context) { id, file, offset ->
        val imported = musicImporter.copy(Uri.fromFile(file), id, layer = true)
        var committed = false
        try {
            edits.withLock {
                val draft = requireNotNull(dao.draft(id)) { "This draft was removed." }
                require(draft.workId.isEmpty() && draft.captionWorkId.isEmpty()) { "Finish the current task first." }
                val r = draft.recipe()
                require(r.audioTracks.size < 4) { "A reel supports up to four voiceover takes. Remove a take first." }
                require(r.audioTracks.size + AudioSplit.music(draft).size < 8) { "Remove an audio piece before recording another take." }
                val take = AudioTrack(UUID.randomUUID().toString(), imported.file.path, "Voiceover ${r.audioTracks.size + 1}",
                    imported.durationMs, offsetMs = offset.coerceIn(0, (draft.outputDuration() - 1000).coerceAtLeast(0)))
                commitEdit(draft, draft.copy(editRecipe = RecipeCodec.encode(r.copy(audioTracks = r.audioTracks + take))))
                committed = true
            }
        } finally {
            if (!committed) imported.file.parentFile?.deleteRecursively()
        }
    }

    internal fun startVoiceover(id: String, offset: Long) { task { edits.withLock {
        val draft = requireNotNull(dao.draft(id)) { "This draft was removed." }
        require(!musicImporting.value && !importing.value && draft.workId.isEmpty() && draft.captionWorkId.isEmpty()) { "Finish the current task first." }
        require(draft.recipe().audioTracks.size < 4) { "Remove a voiceover take before recording another." }
        require(draft.recipe().audioTracks.size + AudioSplit.music(draft).size < 8) { "Remove an audio piece before recording another take." }
        val start = offset.coerceIn(0, (draft.outputDuration() - 1000).coerceAtLeast(0))
        voiceover.start(id, start, draft.outputDuration() - start)
    } } }

    override fun onCleared() { voiceover.close(); super.onCleared() }
    // A process can die during a picker copy. Only unreferenced music folders are reclaimed.
    private val musicRecovery = viewModelScope.async(Dispatchers.IO) {
        runCatching {
            val retained = dao.observeDrafts().first().mapNotNull {
                it.audioPath.takeIf(String::isNotBlank)?.let { path -> File(path).canonicalFile.parentFile }
            }.toSet()
            val root = File(context.filesDir, "studio").canonicalFile
            root.listFiles()?.forEach { draftFolder ->
                if (draftFolder.isDirectory && draftFolder.canonicalFile.parentFile == root) {
                    draftFolder.listFiles()?.forEach { folder ->
                        if (folder.name.startsWith("music_") && folder.isDirectory &&
                            folder.canonicalFile.parentFile == draftFolder.canonicalFile && folder.canonicalFile !in retained) folder.deleteRecursively()
                    }
                }
            }
        }
    }

    init {
        task {
            edits.withLock {
                val known = withContext(Dispatchers.IO) { manager.getWorkInfosByTag(StudioExportWorker.QUEUE).get() }
                val captions = withContext(Dispatchers.IO) { manager.getWorkInfosByTag(StudioCaptionWorker.QUEUE).get() }
                dao.observeDrafts().first().filter { it.captionWorkId.isNotEmpty() && captions.none { job -> job.id.toString() == it.captionWorkId && !job.state.isFinished } }.forEach {
                    dao.putDraft(it.copy(captionWorkId = ""))
                }
                dao.observeDrafts().first().filter { it.workId.isNotEmpty() && known.none { job -> job.id.toString() == it.workId && !job.state.isFinished } }.forEach {
                    dao.putDraft(it.copy(workId = "", status = "DRAFT"))
                }
                // Keep all other edits, but invalidate a cached render of the retired look.
                dao.observeDrafts().first().filter {
                    it.workId.isEmpty() && it.captionWorkId.isEmpty() && RecipeCodec.hasRetiredFilter(it.editRecipe)
                }.forEach {
                    dao.putDraft(it.copy(editRecipe = RecipeCodec.encode(it.recipe()), revision = it.revision + 1, status = "DRAFT"))
                }
            }
        }
        viewModelScope.launch {
            combine(captionWork, drafts) { jobs, entries -> jobs to entries }.collect { (jobs, entries) ->
                jobs.filter { it.state.isFinished }.forEach { job ->
                    entries.filter { it.captionWorkId == job.id.toString() }.forEach { draft ->
                        dao.clearCaptionTask(draft.id, job.id.toString(), job.outputData.getString("error").orEmpty())
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(work, drafts) { jobs, entries -> jobs to entries }.collect { (jobs, entries) ->
                // Queued work can be cancelled before doWork ever starts. Repair its Room state too.
                jobs.filter { it.state.isFinished }.forEach { job ->
                    entries.filter { it.workId == job.id.toString() }.forEach { draft ->
                        edits.withLock {
                            dao.draft(draft.id)?.takeIf { it.workId == job.id.toString() }?.let {
                                dao.putDraft(it.copy(workId = "", status = "DRAFT",
                                    error = if (job.state == WorkInfo.State.FAILED) job.outputData.getString("error").orEmpty() else ""))
                            }
                        }
                    }
                }
            }
        }
    }

    fun open(id: String?) { savedState["draft"] = id }
    fun appearance(value: String) {
        if (value in listOf("System", "Light", "Dark")) task { dao.putSettings(StudioSettings(appearance = value)) }
    }
    fun clearMessage() { message.value = null }
    fun error(value: String) { message.value = value }
    private fun task(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { message.value = e.message ?: "Something went wrong. Please try again." }
    }

    fun cancelMusicImport() { musicJob?.cancel() }

    fun createVideoLayout(count: Int, layout: String) {
        if (importing.value) return
        importing.value = true
        task {
            val id = UUID.randomUUID().toString()
            var committed = false
            try {
                val draft = withContext(Dispatchers.IO) { CollageImporter.empty(context, id, count, layout) }
                withContext(NonCancellable) { edits.withLock { dao.putDraft(draft); committed = true } }
                open(id)
            } finally {
                if (!committed) withContext(NonCancellable + Dispatchers.IO) { File(context.filesDir, "studio/$id").deleteRecursively() }
                importing.value = false
            }
        }
    }

    internal fun replaceCollageMedia(id: String, assetId: String, uri: Uri) {
        if (importing.value) return
        importing.value = true
        task {
            val key = UUID.randomUUID().toString()
            val folder = File(context.filesDir, "studio/$id")
            var committed = false
            try {
                val original = requireNotNull(dao.draft(id)) { "This draft was removed." }
                val collage = requireNotNull(original.recipe().collage)
                require(original.workId.isEmpty() && original.captionWorkId.isEmpty()) { "Finish the current task first." }
                require(folder.isDirectory && collage.assets.any { it.id == assetId }) { "This collage item was removed." }
                val image = context.contentResolver.getType(uri)?.startsWith("image/") == true
                require(image || collage.assets.count { !it.image && it.id != assetId } < CollageImporter.maxVideos(context)) {
                    "This device supports ${CollageImporter.maxVideos(context)} videos per collage. Choose a photo for this cell."
                }
                val asset = withContext(Dispatchers.IO) { CollageImporter.copyAsset(context, uri, folder, key).copy(id = assetId) }
                edits.withLock {
                    val current = requireNotNull(dao.draft(id)) { "This draft was removed." }
                    require(current.workId.isEmpty() && current.captionWorkId.isEmpty()) { "Finish the current task first." }
                    val r = current.recipe()
                    val c = requireNotNull(r.collage)
                    val media = c.assets.map { if (it.id == assetId) asset else it }
                    val duration = media.filterNot { it.image }.minOfOrNull { it.durationMs }?.coerceAtMost(60_000) ?: current.durationMs
                    val sound = if (c.assets.none { !it.image } || c.audioIndex >= 0 && media[c.audioIndex].image)
                        media.indexOfFirst { !it.image } else c.audioIndex
                    val updated = c.copy(assets = media, audioIndex = sound)
                    val cover = withContext(Dispatchers.IO) { CollageImporter.cover(folder, updated) }
                    withContext(NonCancellable) {
                        dao.putDraft(current.copy(sourcePath = media.first().path, editRecipe = RecipeCodec.encode(r.copy(collage = updated)), durationMs = duration,
                            trimStartMs = current.trimStartMs.coerceAtMost(duration - 1000), trimEndMs = current.trimEndMs.coerceAtMost(duration),
                            thumbnailPath = cover.path, revision = current.revision + 1, status = "DRAFT", error = "", updatedAt = System.currentTimeMillis()))
                        committed = true
                    }
                    val previous = c.assets.first { it.id == assetId }.path
                    if (media.none { it.path == previous }) withContext(Dispatchers.IO) {
                        File(previous).canonicalFile.takeIf { it.parentFile == folder.canonicalFile }?.delete()
                    }
                    if (current.thumbnailPath != cover.path) withContext(Dispatchers.IO) {
                        File(current.thumbnailPath).canonicalFile.takeIf {
                            it.parentFile == folder.canonicalFile && it.name.startsWith("collage_cover_")
                        }?.delete()
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!committed) listOf("$key.jpg", "$key.mp4", "cover_$key.jpg").forEach { File(folder, it).delete() }
                    if (uri.authority == "${context.packageName}.fileprovider" && uri.pathSegments.firstOrNull() == "captures") {
                        val root = File(context.filesDir, "captures").canonicalFile
                        File(root, uri.lastPathSegment.orEmpty()).canonicalFile.takeIf { it.parentFile == root }?.delete()
                    }
                    importing.value = false
                }
            }
        }
    }

    fun importCollage(uris: List<Uri>) {
        if (importing.value || uris.isEmpty()) return
        importing.value = true
        task {
            val id = UUID.randomUUID().toString()
            var committed = false
            try {
                val draft = withContext(Dispatchers.IO) { CollageImporter.create(context, uris, id) }
                withContext(NonCancellable) { edits.withLock { dao.putDraft(draft); committed = true } }
                open(id)
            } finally {
                if (!committed) withContext(NonCancellable + Dispatchers.IO) { File(context.filesDir, "studio/$id").deleteRecursively() }
                importing.value = false
            }
        }
    }

    fun importMusic(id: String, uri: Uri, name: String? = null, artist: String? = null) {
        if (voiceover.state.value.busy) { error("Finish recording before adding music."); return }
        if (musicImporting.value) return
        musicImporting.value = true
        musicJob = task {
            var imported: ImportedMusic? = null
            var committed = false
            try {
                musicRecovery.await()
                withContext(Dispatchers.IO) { imported = musicImporter.copy(uri, id) }
                val music = requireNotNull(imported)
                var previous = ""
                edits.withLock {
                    val draft = dao.draft(id) ?: kotlin.error("This draft was removed.")
                    require(draft.workId.isEmpty() && draft.captionWorkId.isEmpty()) { "Wait for the current task before changing music." }
                    require(draft.recipe().audioTracks.size < 8) { "Remove an audio piece before adding music." }
                    previous = draft.audioPath
                    withContext(NonCancellable) {
                        dao.putDraft(DraftHistory.clear(draft).copy(audioPath = music.file.absolutePath, audioName = name ?: music.name,
                            audioArtist = artist ?: music.artist, audioArtwork = music.artwork,
                            audioDurationMs = music.durationMs, audioStartMs = 0,
                            audioEndMs = minOf(music.durationMs, draft.outputDuration()), audioVolume = .5f,
                            editRecipe = RecipeCodec.encode(draft.recipe().copy(musicTracks = emptyList(), audioMix = draft.recipe().audioMix.copy(musicOffsetMs = 0))),
                            revision = draft.revision + 1, status = "DRAFT", error = "", updatedAt = System.currentTimeMillis()))
                        committed = true
                    }
                }
                withContext(Dispatchers.IO) { deletePrivateMusic(id, previous) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (!committed) imported?.let { deletePrivateMusic(id, it.file.absolutePath) }
                }
                musicImporting.value = false
            }
        }
    }

    fun removeMusic(id: String) { task { edits.withLock {
        require(!musicImporting.value && !voiceover.state.value.busy) { "Finish recording or adding music first." }
        dao.draft(id)?.takeIf { it.workId.isEmpty() && it.captionWorkId.isEmpty() }?.let { draft ->
            dao.putDraft(DraftHistory.clear(draft).copy(audioPath = "", audioName = "", audioArtist = "", audioArtwork = "", audioDurationMs = 0,
                audioStartMs = 0, audioEndMs = 0,
                editRecipe = RecipeCodec.encode(draft.recipe().copy(musicTracks = emptyList(), audioMix = draft.recipe().audioMix.copy(musicOffsetMs = 0))),
                revision = draft.revision + 1, status = "DRAFT", error = "", updatedAt = System.currentTimeMillis()))
            withContext(Dispatchers.IO) { deletePrivateMusic(id, draft.audioPath) }
        }
    } } }

    private fun deletePrivateMusic(id: String, path: String) {
        if (path.isBlank()) return
        val root = File(context.filesDir, "studio/$id").canonicalFile
        val folder = File(path).canonicalFile.parentFile ?: return
        if (folder.parentFile == root && folder.name.startsWith("music_")) folder.deleteRecursively()
    }

    fun importVideo(uri: Uri, appendTo: String? = null) {
        if (voiceover.state.value.busy) { error("Finish recording before importing video."); return }
        if (importing.value) return
        importing.value = true
        task {
            val id = UUID.randomUUID().toString()
            val folder = File(context.filesDir, if (appendTo == null) "studio/$id" else "studio/$appendTo/clip_$id")
            var committed = false
            try {
                val draft = withContext(Dispatchers.IO) {
                    Mp4Input.requireValid(context, uri)
                    require(folder.mkdirs()) { "Could not create a draft." }
                    val size = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
                    require(size <= Mp4Input.MAX_BYTES) { "Choose an MP4 smaller than 512 MB." }
                    require(folder.usableSpace > (if (size > 0) size else Mp4Input.MAX_BYTES) + 64L * 1024 * 1024) { "Free some storage before importing." }
                    val source = File(folder, "original.mp4")
                    context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        var total = 0L
                        while (true) {
                            ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= Mp4Input.MAX_BYTES) { "Choose an MP4 smaller than 512 MB." }
                            output.write(buffer, 0, count)
                        }
                    } } ?: error("This video is no longer accessible.")
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(source.absolutePath)
                        fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0
                        val duration = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        require(duration in 1000..600_000) { "Choose a video between 1 second and 10 minutes." }
                        var width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                        var height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                        require(width > 0 && height > 0 && width <= 4096 && height <= 4096 && width.toLong() * height <= 9_000_000) {
                            "Choose a video up to 4K resolution for reliable editing."
                        }
                        if (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0L) { val old = width; width = height; height = old }
                        val cover = File(folder, "cover.jpg")
                        runCatching { retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)?.let {
                            try { cover.outputStream().use { out -> it.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out) } }
                            finally { it.recycle() }
                        } }
                        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }?.substringBeforeLast('.')?.take(120)?.ifBlank { null } ?: "New reel"
                        DraftEntity(id, source.absolutePath, cover.absolutePath, title = name, durationMs = duration,
                            width = width, height = height, trimEndMs = duration)
                    } finally { retriever.release() }
                }
                withContext(NonCancellable) { edits.withLock {
                    if (appendTo == null) dao.putDraft(draft) else {
                        val existing = requireNotNull(dao.draft(appendTo)) { "This draft was removed." }
                        require(existing.workId.isEmpty() && existing.captionWorkId.isEmpty()) { "Wait for the current task to finish." }
                        val r = existing.recipe()
                        val clips = existing.timeline(r)
                        require(clips.size < 12) { "A reel supports up to 12 clips." }
                        require(clips.sumOf { it.lengthMs } + draft.durationMs <= 600_000) { "Keep the timeline under 10 minutes." }
                        val next = VideoClip(id, draft.sourcePath, draft.title, draft.durationMs, 0, draft.durationMs, draft.width, draft.height)
                        commitEdit(existing, existing.copy(editRecipe = RecipeCodec.encode(r.copy(clips = clips + next))))
                    }
                    committed = true
                } }
                open(appendTo ?: id)
                if (uri.authority == "${context.packageName}.fileprovider" && uri.pathSegments.firstOrNull() == "captures") {
                    withContext(Dispatchers.IO) {
                        val root = File(context.filesDir, "captures").canonicalFile
                        File(root, uri.lastPathSegment.orEmpty()).canonicalFile.takeIf { it.parentFile == root }?.delete()
                    }
                }
            } finally {
                if (!committed) withContext(NonCancellable + Dispatchers.IO) { folder.deleteRecursively() }
                importing.value = false
            }
        }
    }

    // Call under edits: current decisions and history are committed in one Room row.
    private suspend fun commitEdit(before: DraftEntity, next: DraftEntity, group: String? = null) {
        val updated = withContext(Dispatchers.Default) { DraftHistory.record(before, next, System.currentTimeMillis(), group) }
        if (updated == before) return
        dao.putDraft(updated.copy(revision = before.revision + 1, status = "DRAFT", error = "", updatedAt = System.currentTimeMillis()))
    }

    fun edit(id: String, group: String? = null, change: (DraftEntity) -> DraftEntity) { task { edits.withLock {
        require(!importing.value && !musicImporting.value && !voiceover.state.value.busy) { "Finish recording or importing media first." }
        dao.draft(id)?.takeIf { it.workId.isEmpty() && it.captionWorkId.isEmpty() }?.let { commitEdit(it, change(it), group) }
    } } }

    internal fun editRecipe(id: String, group: String? = null, change: (EditRecipe) -> EditRecipe) = edit(id, group) {
        it.copy(editRecipe = RecipeCodec.encode(change(it.recipe())))
    }

    internal fun restoreEdit(id: String, redo: Boolean) { task { edits.withLock {
        require(!importing.value && !musicImporting.value && !voiceover.state.value.busy) { "Finish recording or importing media first." }
        val draft = dao.draft(id) ?: return@withLock
        require(draft.workId.isEmpty() && draft.captionWorkId.isEmpty()) { "Finish the current task first." }
        val restored = withContext(Dispatchers.Default) { DraftHistory.restore(draft, redo) } ?: return@withLock
        dao.putDraft(restored.copy(revision = draft.revision + 1, status = "DRAFT", error = "", updatedAt = System.currentTimeMillis()))
    } } }

    internal fun splitClip(id: String, clipId: String, outputMs: Long) = edit(id) { TimelineEdits.split(it, clipId, outputMs) }
    internal fun duplicateClip(id: String, clipId: String) = edit(id) { TimelineEdits.duplicate(it, clipId) }
    internal fun editClip(id: String, clipId: String, change: (VideoClip) -> VideoClip) = edit(id) { draft ->
        val recipe = draft.recipe()
        draft.copy(editRecipe = RecipeCodec.encode(recipe.copy(clips = draft.timeline(recipe).map { clip ->
            if (clip.id == clipId) change(clip) else clip
        })))
    }

    internal fun moveClip(id: String, clipId: String, delta: Int) = edit(id) { draft ->
        val r = draft.recipe()
        val clips = draft.timeline(r).toMutableList()
        val index = clips.indexOfFirst { it.id == clipId }
        if (index >= 0) java.util.Collections.swap(clips, index, (index + delta).coerceIn(0, clips.lastIndex))
        draft.copy(editRecipe = RecipeCodec.encode(r.copy(clips = clips)))
    }

    internal fun removeClip(id: String, clipId: String) = edit(id) { draft ->
        val r = draft.recipe()
        val clips = draft.timeline(r)
        if (clips.size <= 1 || clips.none { it.id == clipId }) draft else
            draft.copy(editRecipe = RecipeCodec.encode(r.copy(clips = clips.filterNot { it.id == clipId })))
        // Keep originals until draft deletion: undo and duplicate may reference them.
    }

    internal fun saveStyle(id: String, name: String) { task {
        require(dao.observeStyles().first().size < 30) { "You can save up to 30 styles. Remove an old style first." }
        val draft = dao.draft(id) ?: return@task
        dao.putStyle(com.reelshare.core.database.entity.StudioStyle(UUID.randomUUID().toString(),
            name.trim().take(40).ifBlank { "My style" }, RecipeCodec.encode(draft.recipe().style()), System.currentTimeMillis()))
        message.value = "Style saved. Apply it to any draft."
    } }
    internal fun deleteStyle(id: String) { task { dao.deleteStyle(id) } }
    internal fun applyStyle(id: String, json: String) = editRecipe(id) { current ->
        RecipeCodec.decode(json).copy(texts = current.texts, clips = current.clips, collage = current.collage, audioTracks = current.audioTracks, musicTracks = current.musicTracks, audioMix = current.audioMix, speed = current.speed, ramp = current.ramp, rampEnd = current.rampEnd)
    }

    internal fun generateCaptions(id: String, language: String) { task { edits.withLock {
        val draft = dao.draft(id) ?: return@withLock
        require(draft.workId.isEmpty() && draft.captionWorkId.isEmpty() && !importing.value && !musicImporting.value && !voiceover.state.value.busy) { "Finish the current task first." }
        val jobs = withContext(Dispatchers.IO) { manager.getWorkInfosByTag(StudioExportWorker.QUEUE).get() + manager.getWorkInfosByTag(StudioCaptionWorker.QUEUE).get() }
        require(jobs.none { !it.state.isFinished }) { "Finish or cancel the current media task first." }
        val request = OneTimeWorkRequestBuilder<StudioCaptionWorker>().setInputData(workDataOf("draftId" to id, "language" to language))
            .addTag(StudioCaptionWorker.QUEUE).build()
        dao.putDraft(draft.copy(captionWorkId = request.id.toString(), error = ""))
        try { withContext(Dispatchers.IO) { manager.enqueueUniqueWork(StudioCaptionWorker.QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get() } }
        catch (e: Exception) { withContext(NonCancellable) { dao.putDraft(draft) }; throw e }
    } } }
    internal fun cancelCaptions(draft: DraftEntity) { if (draft.captionWorkId.isNotEmpty()) manager.cancelWorkById(UUID.fromString(draft.captionWorkId)) }

    internal fun importCaptions(id: String, uri: Uri) { task {
        val input = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    ensureActive()
                    val count = stream.read(buffer); if (count < 0) break
                    require(out.size() + count <= 1_000_000) { "Choose an SRT file smaller than 1 MB." }
                    out.write(buffer, 0, count)
                }
                val bytes = out.toByteArray()
                require(bytes.size <= 1_000_000) { "Choose an SRT file smaller than 1 MB." }
                bytes.toString(Charsets.UTF_8)
            } ?: kotlin.error("Could not open this subtitle file.")
        }
        edits.withLock {
            val draft = dao.draft(id) ?: return@withLock
            require(draft.workId.isEmpty() && draft.captionWorkId.isEmpty()) { "Finish the current task first." }
            val r = draft.recipe()
            val captions = SubtitleFile.parse(input, draft.outputDuration(r))
            commitEdit(draft, draft.copy(editRecipe = RecipeCodec.encode(r.copy(texts = r.texts.filterNot { it.caption } + captions))))
            message.value = "${captions.size} captions imported."
        }
    } }
    internal fun saveCaptions(id: String, uri: Uri) { task {
        val draft = dao.draft(id) ?: return@task
        val captions = draft.recipe().texts.filter { it.caption && it.startMs < draft.outputDuration() }.map { it.copy(endMs = it.endMs.coerceAtMost(draft.outputDuration())) }
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(SubtitleFile.write(captions)) }
                ?: kotlin.error("Could not write this subtitle file.")
        }
        message.value = "Captions saved."
    } }

    fun export(id: String, download: Boolean) { task { edits.withLock {
        val draft = dao.draft(id) ?: return@withLock
        require(draft.recipe().collage?.assets?.any { it.placeholder } != true) { "Fill each collage cell before exporting." }
        require(!musicImporting.value && !importing.value && !voiceover.state.value.busy) { "Wait for recording or importing to finish." }
        val captions = withContext(Dispatchers.IO) { manager.getWorkInfosByTag(StudioCaptionWorker.QUEUE).get() }
        require(draft.captionWorkId.isEmpty() && captions.none { !it.state.isFinished }) { "Wait for captions or cancel them first." }
        if (draft.workId.isNotEmpty()) return@withLock
        require(draft.trimEndMs - draft.trimStartMs >= 1000) { "Keep at least one second of video." }
        val request = OneTimeWorkRequestBuilder<StudioExportWorker>()
            .setInputData(workDataOf("draftId" to id, "download" to download))
            .addTag(StudioExportWorker.QUEUE).build()
        dao.putDraft(draft.copy(status = "QUEUED", workId = request.id.toString(), error = ""))
        try {
            withContext(Dispatchers.IO) { manager.enqueueUniqueWork(StudioExportWorker.QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request).result.get() }
        } catch (e: Exception) {
            withContext(NonCancellable) { dao.putDraft(draft.copy(error = "Could not start export. Please try again.")) }
            throw e
        }
    } } }

    fun cancel(draft: DraftEntity) { task {
        if (draft.workId.isNotEmpty()) manager.cancelWorkById(UUID.fromString(draft.workId))
    } }

    fun delete(draft: DraftEntity) { task { edits.withLock {
        val current = dao.draft(draft.id) ?: return@withLock
        require(!musicImporting.value && !importing.value && !voiceover.state.value.busy) { "Finish recording or importing before deleting a draft." }
        require(current.captionWorkId.isEmpty()) { "Cancel caption generation before deleting this draft." }
        require(current.workId.isEmpty()) { "Cancel the export before deleting this draft." }
        dao.deleteDraft(draft.id)
        if (selectedId.value == draft.id) open(null)
        withContext(Dispatchers.IO) {
            val root = File(context.filesDir, "studio").canonicalFile
            val folder = File(draft.sourcePath).canonicalFile.parentFile
            if (folder?.parentFile == root) folder.deleteRecursively()
            val cache = File(context.cacheDir, "renders").canonicalFile
            cache.listFiles()?.filter { it.name.startsWith("${draft.id}_") }?.forEach { it.delete() }
        }
    } } }

    fun updateProfile(name: String, bio: String) { task { edits.withLock {
        dao.putProfile((dao.profile() ?: StudioProfile()).copy(name = name.trim().take(60).ifBlank { "Your studio" }, bio = bio.take(160)))
    } } }
    fun removePhoto() { task { edits.withLock {
        val current = dao.profile() ?: return@withLock
        dao.putProfile(current.copy(photoPath = ""))
        withContext(Dispatchers.IO) {
            val root = File(context.filesDir, "profile").canonicalFile
            File(current.photoPath).canonicalFile.takeIf { it.parentFile == root }?.delete()
        }
    } } }
    fun photo(uri: Uri) { task { edits.withLock {
        val photo = withContext(Dispatchers.IO) {
            require(context.contentResolver.getType(uri)?.startsWith("image/") == true) { "Choose an image." }
            val folder = File(context.filesDir, "profile").apply { mkdirs() }
            val target = File(folder, "${UUID.randomUUID()}.jpg")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0
                    while (true) { ensureActive(); val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= 10 * 1024 * 1024) { "Choose a photo smaller than 10 MB." }; out.write(buffer, 0, count) }
                } } ?: error("Could not open this image.")
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(target.absolutePath, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "This image could not be read. Choose another photo." }
                target
            } catch (e: Exception) { target.delete(); throw e }
        }
        val current = dao.profile() ?: StudioProfile()
        val previous = current.photoPath
        dao.putProfile(current.copy(photoPath = photo.absolutePath))
        withContext(Dispatchers.IO) { File(previous).takeIf { it.parentFile?.canonicalPath == photo.parentFile?.canonicalPath }?.delete() }
    } } }
}
