package com.reelshare.feature.creator

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun StudioTextStyleControls(entry: TimedText, enabled: Boolean, change: (TimedText) -> Unit) {
    val context = LocalContext.current
    val fonts = remember(context) { StudioTextFonts(context) }
    Text("Font", style = MaterialTheme.typography.labelLarge)
    TextSampleGallery("Font", TimedText.FONTS, entry.font, enabled, fonts,
        sample = { entry.copy(text = "Aa", size = .18f, rotation = 0f, font = it, style = "Shadow") }) {
        change(entry.copy(font = it))
    }
    Text("Style", style = MaterialTheme.typography.labelLarge)
    TextSampleGallery("Style", TimedText.STYLES, entry.style, enabled, fonts,
        sample = { entry.copy(text = "Aa", size = .18f, rotation = 0f, style = it) }) {
        change(entry.copy(style = it))
    }
    Text(when (entry.style) {
        "Outline" -> "An outline keeps your words readable over busy footage."
        "Label" -> "Your chosen color fills the label behind dark text."
        "Shadow" -> "A soft shadow separates your words from the video."
        else -> "Use a background for contrast, or turn it off for a lighter look."
    }, color = Muted, style = MaterialTheme.typography.bodySmall)
    if (entry.style == "Classic") SettingSwitch("Text background", entry.background, enabled) { change(entry.copy(background = it)) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Rotation", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
        IconButton({ change(entry.copy(rotation = 0f)) }, enabled = enabled && entry.rotation != 0f) {
            Icon(Icons.Default.RestartAlt, "Reset text rotation")
        }
    }
    EditSlider("Angle", entry.rotation, -180f..180f, enabled, { "${it.roundToInt()}°" }) { change(entry.copy(rotation = it.roundToInt().toFloat())) }
    Text("0° is upright. Drag on the video to position your text; rotation stays within the frame.", color = Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun TextSampleGallery(label: String, values: List<String>, selected: String, enabled: Boolean,
    fonts: StudioTextFonts, sample: (String) -> TimedText, choose: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(values, key = { it }) { value ->
            val text = sample(value).copy(startMs = 0, endMs = 100, x = null, y = null, animated = false,
                animation = "None", animationMs = 150, wordHighlight = false, wordTimings = emptyList(), wordTimingText = "", highlightColor = "Yellow")
            // Small fixed-size samples use the same rasterizer as the native overlay.
            val bitmap = remember(text, fonts) { TextPlacement.draw(text, TextPlacement.raster(text, fonts.get(text.font), 180)).asImageBitmap() }
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(if (value == selected) 2.dp else 1.dp,
                    if (value == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.width(88.dp).selectable(value == selected, enabled = enabled, role = Role.RadioButton) { choose(value) }
                    .semantics { contentDescription = "$label $value" }) {
                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(color = Color(0xFF363D4B), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        Image(bitmap, null, Modifier.padding(4.dp), contentScale = ContentScale.Fit, alpha = if (enabled) 1f else .45f)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(value, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
