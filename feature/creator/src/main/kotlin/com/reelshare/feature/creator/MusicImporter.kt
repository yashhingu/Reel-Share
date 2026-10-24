package com.reelshare.feature.creator

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

internal data class ImportedMusic(val file: File, val name: String, val artist: String, val artwork: String, val durationMs: Long)

/** SAF selection is copied once; no broad media permission or fragile external file reference. */
class MusicImporter @Inject constructor(@ApplicationContext private val context: Context) {
    internal suspend fun copy(uri: Uri, draftId: String, layer: Boolean = false): ImportedMusic {
        val root = File(context.filesDir, "studio").canonicalFile
        val draftFolder = File(root, draftId).canonicalFile
        require(draftFolder.parentFile == root && draftFolder.isDirectory) { "This draft is no longer available." }
        val folder = File(draftFolder, "${if (layer) "layer" else "music"}_${UUID.randomUUID()}")
        val isVideo = context.contentResolver.getType(uri)?.startsWith("video/") == true || uri.lastPathSegment?.endsWith(".mp4", true) == true
        val limit = if (isVideo) MAX_VIDEO_BYTES else MAX_BYTES
        val sizeMessage = if (isVideo) "Choose a video smaller than 512 MB." else "Choose audio smaller than 64 MB."
        try {
            require(folder.mkdirs()) { "Could not prepare music storage." }
            val declaredSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
            require(declaredSize <= limit) { sizeMessage }
            require(folder.usableSpace > (if (declaredSize > 0) declaredSize else limit) + MAX_BYTES) { "Free some storage before adding music." }
            val file = File(folder, if (isVideo) "audio.mp4" else "audio")
            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    require(count <= limit) { sizeMessage }
                    output.write(buffer, 0, size)
                }
            } } ?: error("This audio file is no longer accessible.")
            val extractor = MediaExtractor()
            val duration = try {
                extractor.setDataSource(file.absolutePath)
                val tracks = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                val audio = tracks.firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    ?: error("Choose a supported audio file such as MP3, M4A, WAV or AAC.")
                if (audio.containsKey(MediaFormat.KEY_DURATION)) audio.getLong(MediaFormat.KEY_DURATION) / 1000 else 0
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                val length = duration.takeIf { it > 0 } ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                require(length in 1000..1_800_000) { "Choose audio between 1 second and 30 minutes." }
                val displayName = (if (uri.scheme == "content") context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } else File(uri.path.orEmpty()).name).orEmpty().substringBeforeLast('.').ifBlank { "Your audio" }
                val name = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: displayName
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: "From your device"
                var art = ""
                runCatching {
                    retriever.embeddedPicture?.takeIf { it.size <= 2 * 1024 * 1024 }?.let { bytes ->
                        val cover = File(folder, "cover"); cover.writeBytes(bytes); art = cover.absolutePath
                    }
                }
                return ImportedMusic(file, name.take(160), artist.take(160), art, length)
            } finally { retriever.release() }
        } catch (e: Exception) {
            folder.deleteRecursively()
            if (e is kotlinx.coroutines.CancellationException || e is IllegalArgumentException) throw e
            throw IllegalArgumentException("Could not read this sound. Try an MP3, M4A, WAV or an MP4 with audio.", e)
        }
    }

    companion object {
        internal const val MAX_BYTES = 64L * 1024 * 1024
        internal const val MAX_VIDEO_BYTES = 512L * 1024 * 1024
    }
}
