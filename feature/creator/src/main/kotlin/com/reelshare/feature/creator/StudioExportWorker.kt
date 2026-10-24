package com.reelshare.feature.creator

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.*
import androidx.work.*
import com.reelshare.core.database.dao.StudioDao
import com.reelshare.core.database.entity.DraftEntity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import java.io.File

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StudioDependencies { fun studioDao(): StudioDao }

/** One serial export queue prevents competing hardware codec allocations. */
@androidx.annotation.OptIn(UnstableApi::class)
class StudioExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { const val QUEUE = "studio_exports"; const val CHANNEL = "studio_exports" }
    private val dao get() = EntryPointAccessors.fromApplication(applicationContext, StudioDependencies::class.java).studioDao()
    private var pendingDownload: Uri? = null
    private var pendingLegacy: File? = null

    override suspend fun doWork(): Result {
        val draftId = inputData.getString("draftId") ?: return Result.failure()
        var output: File? = null
        var completed = false
        return try {
            setForeground(foreground(0, "Preparing"))
            withTimeout(15 * 60 * 1000L) {
                var draft = dao.draft(draftId) ?: error("This draft was removed.")
                if (draft.workId != id.toString()) return@withTimeout Result.success()
                require(draft.recipe().collage?.assets?.any { it.placeholder } != true) { "Fill each collage cell before exporting." }
                if (RecipeCodec.hasRetiredFilter(draft.editRecipe)) {
                    draft = draft.copy(editRecipe = RecipeCodec.encode(draft.recipe()), revision = draft.revision + 1)
                }
                dao.putDraft(draft.copy(status = "EXPORTING", error = ""))
                val source = File(draft.sourcePath)
                require(source.isFile) { "The original video is missing. Import it again." }
                require(draft.audioPath.isBlank() || File(draft.audioPath).isFile) { "The selected music is missing. Choose it again or remove it." }
                if (draft.width == 0 || draft.height == 0) {
                    draft = withContext(Dispatchers.IO) {
                        val reader = android.media.MediaMetadataRetriever()
                        try {
                            reader.setDataSource(source.absolutePath)
                            var width = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            var height = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            val rotation = reader.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                            if (rotation % 180 != 0) { val previous = width; width = height; height = previous }
                            require(width > 0 && height > 0) { "Could not read video dimensions." }
                            draft.copy(width = width, height = height)
                        } finally { reader.release() }
                    }
                }
                val cache = File(applicationContext.cacheDir, "renders")
                withContext(Dispatchers.IO) { cache.mkdirs() }
                val rendered = File(draft.renderedPath)
                val target = if (draft.renderedRevision == draft.revision && rendered.isFile) rendered else {
                    require(cache.usableSpace > source.length() + 64L * 1024 * 1024) { "Free some storage before exporting." }
                    File(cache, "${draft.id}_${draft.revision}.mp4").also { file ->
                        output = file
                        withContext(Dispatchers.IO) { file.delete() }
                        val lowRam = (applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
                        val settings = ExportSettings.forVideo(draft.width, draft.height, draft.aspect, draft.quality, lowRam)
                        try { render(draft, file, settings) }
                        catch (e: CancellationException) { throw e }
                        catch (e: ExportException) {
                            withContext(Dispatchers.IO) { file.delete() }
                            report(0, "Retrying with compatibility settings")
                            render(draft, file, ExportSettings.forVideo(draft.width, draft.height, draft.aspect, "Small", true))
                        }
                    }
                }
                ensureActive()
                val publicUri = if (inputData.getBoolean("download", false)) download(target, draft.title) else draft.exportUri
                ensureActive()
                withContext(NonCancellable + Dispatchers.IO) {
                    val current = dao.draft(draftId)
                    check(current?.workId == id.toString()) { "This export was replaced." }
                    dao.putDraft(current.copy(renderedPath = target.absolutePath, renderedRevision = draft.revision, width = draft.width, height = draft.height,
                        exportUri = publicUri, status = "READY", workId = "", error = ""))
                    completed = true
                    pendingDownload = null
                    pendingLegacy = null
                    cache.listFiles()?.filter { it.name.startsWith("${draft.id}_") && it != target }?.forEach { it.delete() }
                }
                notifyReady(inputData.getBoolean("download", false))
                Result.success()
            }
        } catch (e: TimeoutCancellationException) {
            failure(draftId, "Export timed out. Try Small quality or a shorter clip.")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure(draftId, e.message ?: "Export failed. Try Small quality.") }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!completed) {
                    output?.delete()
                    pendingDownload?.let { runCatching { applicationContext.contentResolver.delete(it, null, null) } }
                    pendingLegacy?.delete()
                    dao.draft(draftId)?.takeIf { it.workId == id.toString() }?.let {
                        dao.putDraft(it.copy(status = "DRAFT", workId = ""))
                    }
                }
            }
        }
    }

    private suspend fun failure(draftId: String, message: String): Result {
        withContext(NonCancellable + Dispatchers.IO) {
            dao.draft(draftId)?.takeIf { it.workId == id.toString() }?.let {
                dao.putDraft(it.copy(status = "DRAFT", error = message, workId = ""))
            }
        }
        return Result.failure(workDataOf("error" to message))
    }

    private suspend fun render(draft: DraftEntity, output: File, settings: ExportSettings) = withContext(Dispatchers.Main.immediate) {
        val done = CompletableDeferred<Unit>()
        val encoder = DefaultEncoderFactory.Builder(applicationContext).setEnableFallback(true)
            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(settings.bitrate).build()).build()
        val transformer = Transformer.Builder(applicationContext).setEncoderFactory(encoder)
            .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { done.complete(Unit) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { done.completeExceptionally(exportException) }
            }).build()
        try {
            transformer.start(StudioComposition.create(applicationContext, draft, settings), output.absolutePath)
            val progress = ProgressHolder()
            var last = -1
            while (!done.isCompleted) {
                delay(500)
                if (transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE && progress.progress >= last + 3) {
                    last = progress.progress
                    report((last * .9f).toInt(), "Rendering ${settings.width} × ${settings.height}")
                }
            }
            done.await()
        } finally { transformer.cancel() }
    }

    @Suppress("DEPRECATION")
    private suspend fun download(source: File, title: String): String = withContext(Dispatchers.IO) {
        val safe = title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").take(60).ifBlank { "Reel" }
        val filename = "${safe}_${id.toString().take(8)}.mp4"
        val resolver = applicationContext.contentResolver
        val uri = if (Build.VERSION.SDK_INT >= 29) {
            // Recover a copy interrupted by process death using this job's stable filename.
            var finished: Uri? = null
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_PENDING),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(filename, "Download/ReelShare/"), null)?.use { rows ->
                while (rows.moveToNext()) {
                    val existing = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, rows.getLong(0))
                    if (rows.getInt(1) == 0) finished = existing
                    else resolver.delete(existing, null, null)
                }
            }
            finished?.let { return@withContext it.toString() }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/ReelShare")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            })?.also { pendingDownload = it } ?: error("Could not create the download.")
        } else {
            val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ReelShare")
            check(directory.isDirectory || directory.mkdirs()) { "Allow storage access to save your video." }
            Uri.fromFile(File(directory, filename).also { pendingLegacy = it })
        }
        val stream = if (Build.VERSION.SDK_INT >= 29) resolver.openOutputStream(uri) else pendingLegacy!!.outputStream()
        stream?.use { target -> source.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            var copied = 0L
            var last = -1
            while (true) {
                ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                target.write(buffer, 0, count)
                copied += count
                val progress = 90 + (copied * 9 / source.length().coerceAtLeast(1)).toInt()
                if (progress > last) { report(progress, "Saving to Download/ReelShare"); last = progress }
            }
        } } ?: error("Could not open the download.")
        ensureActive()
        if (Build.VERSION.SDK_INT >= 29) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        else android.media.MediaScannerConnection.scanFile(applicationContext, arrayOf(pendingLegacy!!.absolutePath), arrayOf("video/mp4"), null)
        uri.toString()
    }

    private suspend fun report(progress: Int, stage: String) {
        setProgress(workDataOf("progress" to progress, "stage" to stage))
        setForeground(foreground(progress, stage))
    }
    private fun foreground(progress: Int, stage: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Video exports", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("ReelShare · Exporting video")
            .setContentText("$stage · $progress%").setOngoing(true).setOnlyAlertOnce(true).setProgress(100, progress, progress == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 35) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode(), notification)
    }

    private fun notifyReady(downloaded: Boolean) {
        runCatching {
            val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("Your reel is ready")
                .setContentText(if (downloaded) "Saved in Download/ReelShare" else "Your preview is ready in ReelShare")
                .setAutoCancel(true)
            if (intent != null) notification.setContentIntent(PendingIntent.getActivity(applicationContext, id.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(id.hashCode() xor 0x40000000, notification.build())
        }
    }
}
