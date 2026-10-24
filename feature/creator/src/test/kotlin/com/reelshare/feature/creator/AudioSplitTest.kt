package com.reelshare.feature.creator

import org.junit.Assert.*
import org.junit.Test

class AudioSplitTest {
    private val track = AudioTrack("a", "/audio", "Take", 9000, 1000, 8000, 500, .7f, 1000, 2000)

    @Test fun splitRetainsExactSourceBoundaryAndOutputPlacement() {
        val pieces = AudioSplit.pieces(track, 3500, 9000, "b")
        assertEquals(2, pieces.size)
        assertEquals(4000, pieces[0].endMs)
        assertEquals(pieces[0].endMs, pieces[1].startMs)
        assertEquals(3500, pieces[1].offsetMs)
        assertEquals(track.endMs, pieces[1].endMs)
        assertEquals(track.volume, pieces[1].volume, 0f)
        assertEquals(1, AudioSplit.peakConcurrency(pieces, 9000))
        assertEquals(2, AudioSplit.peakConcurrency(pieces + track.copy(id = "overlap"), 9000))
        assertEquals(2, AudioSplit.peakConcurrency(pieces + track.copy(id = "muted", volume = 0f), 9000))
    }

    @Test fun splitKeepsFadeCurveIncludingRepeatedCuts() {
        val pieces = AudioSplit.pieces(track, 3500, 9000, "b")
        val repeated = AudioSplit.pieces(pieces[1], 5500, 9000, "c")
        val original = AudioEnvelope(7000, 1000, 2000)
        for (piece in listOf(pieces[0]) + repeated) {
            val envelope = AudioEnvelope(piece.envelopeLengthMs, piece.fadeInMs, piece.fadeOutMs)
            for (local in listOf(0L, 200L, 900L)) {
                val time = (piece.envelopeOffsetMs + local) * 1000
                assertEquals(original.gainAtUs(time), envelope.gainAtUs(time), 0f)
            }
        }
        assertEquals(5000, repeated.last().envelopeOffsetMs)
    }

    @Test fun rejectsEdgesAndHiddenTailWithoutChangingOriginal() {
        for (at in listOf(0L, 501L, 1200L, 7499L, 8000L))
            assertEquals(listOf(track), AudioSplit.pieces(track, at, 7600, "b"))
        assertFalse(AudioSplit.canSplit(track, 3500, 4000))
        assertTrue(AudioSplit.canSplit(track, 3500, 4500))
    }

    @Test fun adjustsOnlySelectedPieceAndCanReplaceInheritedFade() {
        val parts = AudioSplit.pieces(track, 3500, 9000, "b")
        val recipe = EditRecipe(musicTracks = parts)
        val volume = AudioSplit.update(recipe, "b") { it.copy(volume = .35f) }
        assertEquals(parts[0], volume.musicTracks[0])
        assertEquals(.35f, volume.musicTracks[1].volume, 0f)
        assertEquals(parts[1].envelopeOffsetMs, volume.musicTracks[1].envelopeOffsetMs)
        val fades = AudioSplit.update(volume, "b") {
            it.copy(fadeInMs = 400, fadeOutMs = 500, envelopeOffsetMs = 0, envelopeLengthMs = 0)
        }
        assertEquals(parts[0], fades.musicTracks[0])
        assertEquals(400L, fades.musicTracks[1].fadeInMs)
        assertEquals(0L, fades.musicTracks[1].envelopeOffsetMs)
    }
}
