package com.reelshare.feature.creator

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun AudioFadeControls(label: String, lengthMs: Long, fadeInMs: Long, fadeOutMs: Long,
    enabled: Boolean, save: (Long, Long) -> Unit) {
    val maximum = minOf(5000, lengthMs / 2).coerceAtLeast(0)
    var fadeIn by remember(fadeInMs, maximum) { mutableFloatStateOf(fadeInMs.coerceAtMost(maximum).toFloat()) }
    var fadeOut by remember(fadeOutMs, maximum) { mutableFloatStateOf(fadeOutMs.coerceAtMost(maximum).toFloat()) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label fades", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        IconButton({ save(0, 0) }, enabled = enabled && (fadeInMs > 0 || fadeOutMs > 0)) {
            Icon(Icons.Default.RestartAlt, "Reset $label fades", Modifier.size(20.dp))
        }
    }
    Text("Fade in · ${if (fadeIn == 0f) "Off" else audioTime(fadeIn.toLong())}", style = MaterialTheme.typography.labelLarge)
    Slider(fadeIn, { fadeIn = it }, Modifier.systemGestureExclusion().semantics { contentDescription = "$label fade in" },
        valueRange = 0f..maximum.coerceAtLeast(1).toFloat(), enabled = enabled && maximum > 0,
        onValueChangeFinished = { save(fadeIn.toLong(), fadeOut.toLong()) })
    Text("Fade out · ${if (fadeOut == 0f) "Off" else audioTime(fadeOut.toLong())}", style = MaterialTheme.typography.labelLarge)
    Slider(fadeOut, { fadeOut = it }, Modifier.systemGestureExclusion().semantics { contentDescription = "$label fade out" },
        valueRange = 0f..maximum.coerceAtLeast(1).toFloat(), enabled = enabled && maximum > 0,
        onValueChangeFinished = { save(fadeIn.toLong(), fadeOut.toLong()) })
    Text("A gentle start and finish for the part that plays. Each fade is limited to half its length, up to 5 seconds.",
        style = MaterialTheme.typography.bodySmall, color = Muted)
}
