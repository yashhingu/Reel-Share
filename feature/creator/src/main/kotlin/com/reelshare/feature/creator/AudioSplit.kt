package com.reelshare.feature.creator

import com.reelshare.core.database.entity.DraftEntity

internal object AudioSplit {
    fun update(recipe: EditRecipe, id: String, change: (AudioTrack) -> AudioTrack): EditRecipe = recipe.copy(
        musicTracks = recipe.musicTracks.map { if (it.id == id) change(it).bounded() else it },
        audioTracks = recipe.audioTracks.map { if (it.id == id) change(it).bounded() else it })

    fun at(draft: DraftEntity, id: String, atMs: Long, newId: String): DraftEntity {
        val recipe = draft.recipe()
        val music = music(draft)
        if (music.size + recipe.audioTracks.size >= 8) return draft
        val isMusic = music.any { it.id == id }
        val list = if (isMusic) music else recipe.audioTracks
        val selected = list.firstOrNull { it.id == id } ?: return draft
        if (!canSplit(selected, atMs, draft.outputDuration())) return draft
        val changed = list.flatMap { if (it.id == id) pieces(it, atMs, draft.outputDuration(), newId) else listOf(it) }
        return draft.copy(editRecipe = RecipeCodec.encode(if (isMusic) recipe.copy(musicTracks = changed)
            else recipe.copy(audioTracks = changed)))
    }

    fun music(draft: DraftEntity): List<AudioTrack> {
        val r = draft.recipe()
        if (r.musicTracks.isNotEmpty()) return r.musicTracks
        if (draft.audioPath.isBlank()) return emptyList()
        val w = AudioWindow.bounded(draft.audioStartMs, draft.audioEndMs, draft.audioDurationMs, draft.outputDuration())
        return listOf(AudioTrack("music", draft.audioPath, draft.audioName, draft.audioDurationMs, w.startMs, w.endMs,
            r.audioMix.musicOffsetMs, draft.audioVolume, r.audioMix.musicFadeInMs, r.audioMix.musicFadeOutMs))
    }

    fun canSplit(track: AudioTrack, atMs: Long, reelMs: Long) =
        atMs - track.offsetMs >= 1000 && track.offsetMs + track.lengthOnReel(reelMs) - atMs >= 1000

    fun pieces(track: AudioTrack, atMs: Long, reelMs: Long, newId: String): List<AudioTrack> {
        if (!canSplit(track, atMs, reelMs)) return listOf(track)
        val cut = atMs - track.offsetMs
        val envelope = track.envelopeLengthMs.takeIf { it > 0 } ?: track.lengthOnReel(reelMs)
        return listOf(track.copy(endMs = track.startMs + cut, envelopeLengthMs = envelope),
            track.copy(id = newId, startMs = track.startMs + cut, offsetMs = atMs,
                envelopeOffsetMs = track.envelopeOffsetMs + cut, envelopeLengthMs = envelope))
    }

    fun peakConcurrency(tracks: List<AudioTrack>, reelMs: Long): Int {
        val events = tracks.filter { it.lengthOnReel(reelMs) > 0 }.flatMap {
            listOf(it.offsetMs to 1, it.offsetMs + it.lengthOnReel(reelMs) to -1)
        }.sortedWith(compareBy<Pair<Long, Int>> { it.first }.thenBy { it.second })
        var active = 0; var peak = 0
        for ((_, delta) in events) { active += delta; peak = maxOf(peak, active) }
        return peak
    }
}
