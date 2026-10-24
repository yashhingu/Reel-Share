package com.reelshare.feature.creator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Small shared cache and one retriever at a time, independent of the playback decoder. */
internal object FilmstripFrames {
    private val decoder = Mutex()
    private val cache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    suspend fun load(path: String, times: List<Long>): List<Bitmap?> = withContext(Dispatchers.IO) {
        decoder.withLock {
            coroutineContext.ensureActive()
            val file = File(path)
            if (!file.isFile) return@withLock List(times.size) { null }
            val keys = times.map { "$path:${file.lastModified()}:$it" }
            val frames = keys.map { cache.get(it) }.toMutableList()
            if (frames.all { it != null }) return@withLock frames
            if (Build.VERSION.SDK_INT < 27) {
                val cover = BitmapFactory.decodeFile(File(file.parentFile, "cover.jpg").path)
                return@withLock List(times.size) { cover }
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                for (i in times.indices) {
                    coroutineContext.ensureActive()
                    if (frames[i] != null) continue
                    frames[i] = runCatching {
                        retriever.getScaledFrameAtTime(times[i] * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 96, 128)
                    }.getOrNull()?.also { cache.put(keys[i], it) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { /* Missing/unsupported frames keep the neutral placeholder. */ }
            finally { runCatching { retriever.release() } }
            frames
        }
    }
}
