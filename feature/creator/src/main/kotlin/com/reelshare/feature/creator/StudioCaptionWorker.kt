package com.reelshare.feature.creator

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.util.UUID

/** One persistent, cancellable job. Only pinned language models ever cross the network. */
class StudioCaptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object { const val QUEUE = "studio_captions"; private const val CHANNEL = "studio_captions" }
    private val dao get() = EntryPointAccessors.fromApplication(applicationContext, StudioDependencies::class.java).studioDao()
    private var lastReport = 0L
    private var downloading = true
    override suspend fun doWork(): Result {
        val draftId = inputData.getString("draftId") ?: return Result.failure()
        var error = ""
        return try {
            withTimeout(15 * 60 * 1000L) { withContext(Dispatchers.IO) {
                val draft = requireNotNull(dao.draft(draftId)) { "This draft was removed." }
                if (draft.captionWorkId != id.toString()) return@withContext Result.success()
                val r = draft.recipe()
                val clips = r.collage?.let { collage ->
                    val source = requireNotNull(collage.assets.getOrNull(collage.audioIndex)) { "Choose a video's original sound in Collage before generating captions, or import an SRT file." }
                    listOf(VideoClip(source.id, source.path, source.name, source.durationMs, draft.trimStartMs, draft.trimEndMs, source.width, source.height))
                } ?: draft.timeline(r)
                val memory = ActivityManager.MemoryInfo()
                (applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
                require(!memory.lowMemory && memory.availMem >= 450L * 1024 * 1024) { "Close other apps before generating captions. You can also import an SRT file." }
                val language = inputData.getString("language") ?: "English"
                downloading = !CaptionModels.installed(applicationContext, language)
                setForeground(foreground(0, "Preparing captions"))
                val folder = CaptionModels.prepare(applicationContext, language, ::report)
                downloading = false
                report(30, "Loading $language model", true)
                val total = clips.sumOf { it.lengthMs }
                val captions = mutableListOf<TimedText>()
                Model(folder.absolutePath).use { model ->
                    var elapsed = 0L
                    var offset = 0L
                    for ((index, clip) in clips.withIndex()) {
                        ensureActive()
                        val plan = clip.speedPlan(r)
                        Recognizer(model, 16000f).use { recognizer ->
                            recognizer.setWords(true)
                            fun collect(json: String) {
                                val words = JSONObject(json).optJSONArray("result") ?: return
                                var group = mutableListOf<SpeechWord>()
                                var start = 0L
                                var end = 0L
                                fun flush() {
                                    if (group.isEmpty()) return
                                    CaptionWords.fromSpeech(group, plan, offset, UUID.randomUUID().toString())?.let(captions::add)
                                    require(captions.size <= 192) { "Too many captions for one reel. Use shorter clips." }
                                    group = mutableListOf()
                                }
                                for (i in 0 until words.length()) {
                                    val word = words.getJSONObject(i)
                                    val text = word.optString("word").trim()
                                    if (text.isBlank()) continue
                                    val nextStart = (word.getDouble("start") * 1000).toLong().coerceIn(0, clip.lengthMs)
                                    val nextEnd = (word.getDouble("end") * 1000).toLong().coerceIn(0, clip.lengthMs)
                                    if (group.isNotEmpty() && (group.size >= 6 || group.joinToString(" ") { it.text }.length + text.length > 38 || nextEnd - start > 2500 || nextStart - end > 500)) flush()
                                    if (group.isEmpty()) start = nextStart
                                    group += SpeechWord(text, nextStart, nextEnd); end = nextEnd
                                }
                                flush()
                            }
                            CaptionAudioDecoder.read(clip, { pcm ->
                                if (pcm.isNotEmpty() && recognizer.acceptWaveForm(pcm, pcm.size)) collect(recognizer.result)
                            }, { position -> report(30 + ((elapsed + position) * 68 / total).toInt(), "Listening to clip ${index + 1} of ${clips.size}") })
                            collect(recognizer.finalResult)
                        }
                        elapsed += clip.lengthMs; offset += plan.outputMs
                    }
                }
                require(captions.isNotEmpty()) { "No speech was detected. Check the language, or add captions manually." }
                ensureActive()
                val updated = withContext(Dispatchers.Default) {
                    DraftHistory.record(draft, draft.copy(editRecipe = RecipeCodec.encode(r.copy(texts = r.texts.filterNot { it.caption } + captions))), System.currentTimeMillis()).editRecipe
                }
                withContext(NonCancellable) {
                    check(dao.finishCaptions(draftId, id.toString(), draft.revision, updated, System.currentTimeMillis()) == 1) { "The draft changed. Generate captions again." }
                }
                report(100, "Captions ready · review the wording", true)
                Result.success()
            } }
        } catch (e: TimeoutCancellationException) {
            error = "Captions timed out. Use shorter clips and try again."; Result.failure(workDataOf("error" to error))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not generate captions."; Result.failure(workDataOf("error" to error)) }
        catch (e: LinkageError) { error = "Offline speech isn't supported on this device. Import SRT captions instead."; Result.failure(workDataOf("error" to error)) }
        finally { withContext(NonCancellable + Dispatchers.IO) { dao.clearCaptionTask(draftId, id.toString(), error) } }
    }
    private suspend fun report(percent: Int, label: String, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastReport < 750) return
        lastReport = now
        setProgress(workDataOf("progress" to percent, "stage" to label))
        setForeground(foreground(percent, label))
    }
    private fun foreground(percent: Int, label: String): ForegroundInfo {
        val notifications = applicationContext.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Caption generation", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit).setContentTitle("ReelShare captions").setContentText(label)
            .setProgress(100, percent, percent == 0).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        val type = if (Build.VERSION.SDK_INT >= 35 && !downloading) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        return ForegroundInfo(4202, notification, type)
    }
}
