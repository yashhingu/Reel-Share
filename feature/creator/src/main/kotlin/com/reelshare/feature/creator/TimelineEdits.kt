package com.reelshare.feature.creator

import com.reelshare.core.database.entity.DraftEntity
import java.util.UUID

internal object TimelineEdits {
    fun sourceAtPlayhead(draft: DraftEntity, clipId: String, outputMs: Long, r: EditRecipe = draft.recipe()): Long? {
        if (r.collage != null) return null
        var offset = 0L
        for (clip in draft.timeline(r)) {
            val plan = clip.speedPlan(r)
            if (clip.id == clipId) return if (outputMs in offset..(offset + plan.outputMs))
                clip.startMs + plan.outputToSourceMs(outputMs - offset) else null
            offset += plan.outputMs
        }
        return null
    }

    fun center(draft: DraftEntity, clipId: String, r: EditRecipe = draft.recipe()): Long {
        var offset = 0L
        for (clip in draft.timeline(r)) {
            val duration = clip.speedPlan(r).outputMs
            if (clip.id == clipId) return offset + duration / 2
            offset += duration
        }
        return 0
    }

    fun split(draft: DraftEntity, clipId: String, outputMs: Long): DraftEntity {
        val r = draft.recipe()
        require(r.collage == null) { "Collage cells play together. Split a regular video instead." }
        val clips = draft.timeline(r)
        require(clips.size < 12) { "A reel supports up to 12 clips." }
        val clip = requireNotNull(clips.firstOrNull { it.id == clipId }) { "Select a clip first." }
        val source = requireNotNull(sourceAtPlayhead(draft, clipId, outputMs, r)) { "Move the playhead inside the selected clip." }
        require(source - clip.startMs >= 1000 && clip.endMs - source >= 1000) { "Keep at least one second on each side of the cut." }
        val splitClip = if (r.ramp && clip.speed == null) clip.copy(
            rampStartMs = clip.rampStartMs ?: clip.startMs,
            rampEndMs = clip.rampEndMs ?: clip.endMs) else clip
        val parts = clips.flatMap { if (it.id == clipId) listOf(splitClip.copy(endMs = source),
            splitClip.copy(id = UUID.randomUUID().toString(), startMs = source)) else listOf(it) }
        return draft.copy(editRecipe = RecipeCodec.encode(r.copy(clips = parts)))
    }

    fun duplicate(draft: DraftEntity, clipId: String): DraftEntity {
        val r = draft.recipe()
        require(r.collage == null) { "Duplicate a regular video clip instead." }
        val clips = draft.timeline(r)
        val clip = requireNotNull(clips.firstOrNull { it.id == clipId }) { "Select a clip first." }
        require(clips.size < 12) { "A reel supports up to 12 clips." }
        require(clips.sumOf { it.lengthMs } + clip.lengthMs <= 600_000) { "Keep the timeline under 10 minutes." }
        return draft.copy(editRecipe = RecipeCodec.encode(r.copy(clips = clips.flatMap {
            if (it.id == clipId) listOf(it, it.copy(id = UUID.randomUUID().toString())) else listOf(it)
        })))
    }
}
