package com.reelshare.feature.creator

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Copies only explicitly selected files, with bounded decoding on the IO dispatcher. */
internal object CollageImporter {
    fun maxVideos(context: Context) = if ((context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice) 2 else 3

    fun empty(context: Context, id: String, count: Int, layout: String): DraftEntity {
        require(count in 2..4)
        val folder = File(context.filesDir, "studio/$id").apply { require(mkdirs()) }
        val file = File(folder, "blank.jpg")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) } } finally { bitmap.recycle() }
        val collage = Collage(List(count) { CollageAsset(UUID.randomUUID().toString(), file.path, "Empty cell", true, 0, 32, 32, placeholder = true) }, layout, gap = 0f)
        return DraftEntity(id, file.path, file.path, title = "My video layout", durationMs = 8000, width = 1080,
            height = 1920, trimEndMs = 8000, aspect = "9:16", editRecipe = RecipeCodec.encode(EditRecipe(collage = collage)))
    }

    suspend fun create(context: Context, uris: List<Uri>, id: String): DraftEntity {
        require(uris.size in 2..4) { "Choose 2–4 photos or MP4 videos." }
        require(uris.count { context.contentResolver.getType(it)?.startsWith("image/") != true } <= maxVideos(context)) {
            "This device supports ${maxVideos(context)} videos per collage; the other items can be photos."
        }
        val folder = File(context.filesDir, "studio/$id")
        require(folder.mkdirs()) { "Could not create your collage." }
        val assets = uris.mapIndexed { index, uri -> copyAsset(context, uri, folder, index.toString()) }
        val duration = assets.filterNot { it.image }.minOfOrNull { it.durationMs }?.coerceAtMost(60_000) ?: 8000
        val collage = Collage(assets, audioIndex = assets.indexOfFirst { !it.image })
        return DraftEntity(id, assets.first().path, cover(folder, collage).path, title = "My collage",
            durationMs = duration, width = 1080, height = 1920, trimEndMs = duration, aspect = "9:16",
            editRecipe = RecipeCodec.encode(EditRecipe(collage = collage)))
    }

    fun cover(folder: File, collage: Collage): File {
        val result = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(result); canvas.drawColor(Color.BLACK)
            val cells = CollageLayout.cells(collage.assets.size, collage.layout, collage.gap)
            collage.assets.forEachIndexed { i, asset ->
                val path = if (asset.image) asset.path else File(folder, "cover_${File(asset.path).nameWithoutExtension}.jpg").path
                val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: return@forEachIndexed
                try {
                    val c = cells[i]
                    val dest = RectF(c.left * 360, c.top * 640, (c.left + c.width) * 360, (c.top + c.height) * 640)
                    val scale = maxOf(dest.width() / bitmap.width, dest.height() / bitmap.height)
                    val dw = bitmap.width * scale; val dh = bitmap.height * scale
                    canvas.save(); canvas.clipRect(dest)
                    canvas.drawBitmap(bitmap, null, RectF(dest.centerX() - dw / 2, dest.centerY() - dh / 2,
                        dest.centerX() + dw / 2, dest.centerY() + dh / 2), Paint(Paint.FILTER_BITMAP_FLAG)); canvas.restore()
                } finally { bitmap.recycle() }
            }
            return File(folder, "collage_cover_${UUID.randomUUID()}.jpg").also { file -> file.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        } finally { result.recycle() }
    }

    suspend fun copyAsset(context: Context, uri: Uri, folder: File, index: String): CollageAsset {
        coroutineContext.ensureActive()
        val image = context.contentResolver.getType(uri)?.startsWith("image/") == true
        if (!image) Mp4Input.requireValid(context, uri)
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0).take(120) else null
        } ?: "Collage item"
        val file = File(folder, "$index.${if (image) "jpg" else "mp4"}")
        val limit = if (image) 20L * 1024 * 1024 else Mp4Input.MAX_BYTES
        val declared = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1
        require(declared <= limit) { "Choose photos under 20 MB and MP4 videos under 512 MB." }
        require(folder.usableSpace > (if (declared > 0) declared else limit) + 64L * 1024 * 1024) { "Free some storage before importing." }
        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { out ->
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                coroutineContext.ensureActive()
                val size = input.read(buffer); if (size < 0) break
                total += size; require(total <= limit) { "This file is too large for a collage." }
                out.write(buffer, 0, size)
            }
        } } ?: error("A selected file is no longer accessible.")
        return if (image) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Choose a supported JPEG, PNG, WebP or HEIF photo." }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })) { "Could not decode this photo." }
            val orientation = runCatching { ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
            val matrix = Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
                    5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
                    7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
                }
            }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            try {
                file.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                if (index == "0") File(folder, "cover.jpg").outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                CollageAsset(UUID.randomUUID().toString(), file.path, name, true, 0, upright.width, upright.height)
            } finally { if (upright !== bitmap) upright.recycle(); bitmap.recycle() }
        } else {
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(file.path)
                fun number(key: Int) = reader.extractMetadata(key)?.toLongOrNull() ?: 0
                val duration = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
                var width = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                var height = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                require(duration in 1000..600_000) { "Choose videos between 1 second and 10 minutes." }
                require(width > 0 && height > 0 && width.toLong() * height <= 1920L * 1080) { "Use videos up to 1080p for smooth collage editing. Compress larger videos first." }
                if (number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0L) { val swap = width; width = height; height = swap }
                reader.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)?.let { cover ->
                    try { File(folder, "cover_$index.jpg").outputStream().use { cover.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                        if (index == "0") File(folder, "cover_$index.jpg").copyTo(File(folder, "cover.jpg"), overwrite = true)
                    } finally { cover.recycle() }
                }
                CollageAsset(UUID.randomUUID().toString(), file.path, name, false, duration, width, height)
            } finally { reader.release() }
        }
    }
}
