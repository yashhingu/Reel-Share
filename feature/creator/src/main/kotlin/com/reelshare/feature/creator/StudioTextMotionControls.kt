package com.reelshare.feature.creator

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun StudioTextMotionControls(entry: TimedText, enabled: Boolean, preview: () -> Unit, change: (TimedText) -> Unit) {
    ToolHeading("Text motion", "Animate the whole title or caption at its start and end.")
    ChoiceChips(TimedText.ANIMATIONS, entry.animationName, enabled) {
        change(entry.copy(animation = it, animated = it != "None"))
    }
    if (entry.animationName != "None") {
        Text(when (entry.animationName) {
            "Pop" -> "Grows gently into place, then shrinks away."
            "Slide" -> "Moves in from below and slides away, staying inside the video."
            else -> "Fades in and out without moving your words."
        }, color = Muted, style = MaterialTheme.typography.bodySmall)
        EditSlider("Animation length", entry.animationMs.toFloat(), 100f..800f, enabled,
            { "${it.toInt()} ms" }) { change(entry.copy(animationMs = it.toLong())) }
        Text("Each entrance and exit uses this length, shortened to fit brief text.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    if (entry.caption) {
        SettingSwitch("Highlight each word", entry.wordHighlight, enabled && entry.text.isNotBlank()) { change(entry.copy(wordHighlight = it)) }
        if (entry.wordHighlight) {
            Text(if (CaptionWords.hasSpeechTiming(entry)) "Speech word timing" else "Estimated word timing",
                color = Accent, style = MaterialTheme.typography.labelLarge)
            Text(if (CaptionWords.hasSpeechTiming(entry)) "Highlights follow recognized speech, including pauses. Review the timing before saving."
                else "SRT and edited captions use an estimated reading pace across the line. Generate captions from speech for word timing.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("Highlight color", style = MaterialTheme.typography.labelLarge)
            ChoiceChips(listOf("Yellow", "Blue", "White"), entry.highlightColor, enabled) { change(entry.copy(highlightColor = it)) }
        }
    }
    OutlinedButton(preview, enabled = enabled && entry.text.isNotBlank()) {
        Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Play from text start")
    }
}
