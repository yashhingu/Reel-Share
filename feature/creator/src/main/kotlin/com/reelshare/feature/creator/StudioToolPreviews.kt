package com.reelshare.feature.creator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import coil.compose.AsyncImage
import com.reelshare.core.database.entity.DraftEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun FilterThumbnails(draft: DraftEntity, recipe: EditRecipe, enabled: Boolean, choose: (String) -> Unit) {
    val path = if (recipe.collage != null) draft.thumbnailPath else draft.timeline(recipe).firstOrNull()?.let { clip ->
        if (clip.path == draft.sourcePath) draft.thumbnailPath else File(File(clip.path).parentFile, "cover.jpg").path
    }.orEmpty()
    // Small sampled bitmaps are prepared off the UI thread. Changing strength does
    // not regenerate the gallery: swatches consistently show the full treatment.
    val base = recipe.copy(filter = "None", strength = 1f, texts = emptyList(), clips = emptyList())
    val colors = ColorTreatment.from(base)
    val thumbnails by produceState<List<Bitmap>>(emptyList(), path, colors) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 288 || bounds.outHeight / sample > 384) sample *= 2
            val source = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withContext emptyList()
            try {
                withContext(Dispatchers.Default) {
                    EditRecipe.FILTERS.map { filter ->
                        ensureActive()
                        filterSwatch(source, ColorTreatment.from(base.copy(filter = filter)))
                    }
                }
            } finally { source.recycle() }
        }
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(EditRecipe.FILTERS) { i, filter ->
            val selected = recipe.filter == filter
            Surface(onClick = { choose(filter) }, enabled = enabled, shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = if (selected) BorderStroke(2.dp, Accent) else null,
                modifier = Modifier.width(82.dp).semantics { contentDescription = "${if (filter == "None") "Original" else filter} look" }) {
                Column {
                    Box(Modifier.fillMaxWidth().height(94.dp), contentAlignment = Alignment.Center) {
                        thumbnails.getOrNull(i)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                            ?: Icon(Icons.Default.Image, null, tint = Muted)
                        if (selected) Icon(Icons.Default.CheckCircle, "Selected look", Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp), tint = Accent)
                    }
                    Text(if (filter == "None") "Original" else filter, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 7.dp),
                        color = if (selected) Accent else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/** Representative SDR swatch; full playback uses the Media3 GPU color pipeline. */
private fun filterSwatch(source: Bitmap, treatment: ColorTreatment): Bitmap {
    val pixels = IntArray(source.width * source.height)
    source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
    val hsl = FloatArray(3)
    val contrast = (1 + treatment.contrast) / (1.0001f - treatment.contrast)
    fun channel(value: Int) = (((value / 255f + treatment.brightness - .5f) * contrast + .5f) * 255).roundToInt().coerceIn(0, 255)
    for (i in pixels.indices) {
        val p = pixels[i]
        ColorUtils.RGBToHSL(channel(android.graphics.Color.red(p)), channel(android.graphics.Color.green(p)), channel(android.graphics.Color.blue(p)), hsl)
        hsl[1] = (hsl[1] + treatment.saturation).coerceIn(0f, 1f)
        val saturated = ColorUtils.HSLToColor(hsl)
        pixels[i] = android.graphics.Color.rgb(
            (android.graphics.Color.red(saturated) * (1 + treatment.warmth * .2f)).roundToInt().coerceIn(0, 255),
            android.graphics.Color.green(saturated),
            (android.graphics.Color.blue(saturated) * (1 - treatment.warmth * .2f)).roundToInt().coerceIn(0, 255))
    }
    return Bitmap.createBitmap(pixels, source.width, source.height, Bitmap.Config.ARGB_8888)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MotionGuide(draft: DraftEntity, dismiss: () -> Unit, applyExample: () -> Unit) {
    ModalBottomSheet(dismiss, containerColor = Card, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Two frames. One smooth move.", style = MaterialTheme.typography.headlineSmall)
            val transition = rememberInfiniteTransition(label = "Motion example")
            val progress by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Zoom example")
            Box(Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                AsyncImage(File(draft.thumbnailPath), "Animated example: the frame gradually zooms in and back out", Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1 + .35f * progress; scaleY = scaleX
                }, contentScale = ContentScale.Crop)
                Surface(Modifier.align(Alignment.BottomStart).padding(12.dp), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .95f)) {
                    Text("Start 1×  →  End 1.35×", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("1. Turn on Keyframe motion.\n2. Set Start zoom and Start pan for the first frame.\n3. Set End zoom and End pan for the last frame.\n4. Play the reel to see the movement between them.", style = MaterialTheme.typography.bodyMedium)
            Text("Zoom 1× keeps the full frame. Higher zoom moves closer. Pan shifts the image sideways and needs zoom above 1×. Ease in and out makes the move start and finish gently.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("This tool sets two points across the whole reel. It does not add separate points at the playhead.", color = Muted, style = MaterialTheme.typography.bodySmall)
            Button(applyExample, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Try a gentle zoom") }
            TextButton(dismiss, Modifier.align(Alignment.CenterHorizontally)) { Text("Got it") }
        }
    }
}
