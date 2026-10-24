package com.reelshare.feature.creator

import com.reelshare.core.database.entity.DraftEntity
import org.json.JSONArray
import org.json.JSONObject

internal object RecipeCodec {
    private fun tracks(array: JSONArray?) = array.objects(8).map { a ->
        AudioTrack(a.getString("id"), a.getString("path"), a.optString("name", "Audio"), a.getLong("duration"),
            a.optLong("start"), a.getLong("end"), a.optLong("offset"), a.optDouble("volume", 1.0).toFloat(),
            a.optLong("fadeIn"), a.optLong("fadeOut"), a.optLong("envelopeOffset"), a.optLong("envelopeLength"))
    }
    private fun encodeTracks(tracks: List<AudioTrack>) = JSONArray(tracks.map { a -> JSONObject().apply {
        put("id", a.id); put("path", a.path); put("name", a.name); put("duration", a.durationMs)
        put("start", a.startMs); put("end", a.endMs); put("offset", a.offsetMs); put("volume", a.volume)
        put("fadeIn", a.fadeInMs); put("fadeOut", a.fadeOutMs)
        put("envelopeOffset", a.envelopeOffsetMs); put("envelopeLength", a.envelopeLengthMs)
    } })
    // Detect the retired preset before decode normalizes it to Original.
    fun hasRetiredFilter(value: String): Boolean = runCatching {
        JSONObject(value).optString("filter") == "Mono"
    }.getOrDefault(false)

    fun decode(value: String): EditRecipe = runCatching {
        val j = JSONObject(value)
        require(j.optInt("version", 1) == 1) { "This draft needs a newer version of ReelShare." }
        fun f(k: String, d: Float = 0f) = j.optDouble(k, d.toDouble()).toFloat()
        EditRecipe(f("brightness"), f("contrast"), f("saturation"), f("warmth"), j.optString("filter", "None"), f("strength", 1f),
            f("speed", 1f), j.optBoolean("ramp"), f("rampEnd", 1f), j.optLong("fadeIn"), j.optLong("fadeOut"),
            j.optBoolean("motion"), f("zoomStart", 1f), f("zoomEnd", 1.2f), f("panStart"), f("panEnd"), j.optBoolean("smooth", true),
            j.optString("transition", "Cut"), j.optLong("transitionMs", 500),
            j.optJSONArray("texts").objects(200).map { t -> TimedText(t.getString("id"), t.getString("text"), t.getLong("start"), t.getLong("end"),
                t.optString("position", "Bottom"), t.optDouble("size", .065).toFloat(), t.optString("color", "White"), t.optBoolean("background", true), t.optBoolean("animated", true), t.optBoolean("caption"),
                t.optDouble("x").takeIf { t.has("x") && it.isFinite() }?.toFloat(), t.optDouble("y").takeIf { t.has("y") && it.isFinite() }?.toFloat(),
                t.optDouble("rotation", 0.0).toFloat(), t.optString("font", "Bold"), t.optString("style", "Classic"),
                t.optString("animation"), t.optLong("animationMs", 150), t.optBoolean("wordHighlight"), t.optString("highlightColor", "Yellow"),
                runCatching { t.optJSONArray("wordTimings")?.let { words -> (0 until minOf(words.length(), 32)).map { i ->
                    val w = words.getJSONArray(i); CaptionWord(w.getInt(0), w.getInt(1), w.getLong(2), w.getLong(3))
                } } ?: emptyList() }.getOrDefault(emptyList()), t.optString("wordTimingText")) },
            j.optJSONArray("clips").objects(12).map { c -> VideoClip(c.getString("id"), c.getString("path"), c.getString("name"), c.getLong("duration"),
                c.getLong("start"), c.getLong("end"), c.getInt("width"), c.getInt("height"),
                c.optDouble("speed").takeIf { c.has("speed") && !c.isNull("speed") }?.toFloat(), c.optDouble("volume", 1.0).toFloat(),
                c.optDouble("frameZoom").takeIf { c.has("frameZoom") && !c.isNull("frameZoom") }?.toFloat(),
                c.optDouble("frameX").toFloat(), c.optDouble("frameY").toFloat(),
                c.optLong("rampStart").takeIf { c.has("rampStart") }, c.optLong("rampEnd").takeIf { c.has("rampEnd") }) })
            .copy(collage = j.optJSONObject("collage")?.let { c ->
                Collage(c.optJSONArray("assets").objects(4).map { a ->
                    CollageAsset(a.getString("id"), a.getString("path"), a.optString("name", "Media"),
                        a.optBoolean("image"), a.optLong("duration"), a.getInt("width"), a.getInt("height"),
                        a.optDouble("zoom", 1.0).toFloat(), a.optDouble("panX").toFloat(), a.optDouble("panY").toFloat(), a.optBoolean("placeholder"))
                }, c.optString("layout", "Grid"), c.optDouble("gap", .012).toFloat(), c.optInt("audioIndex", -1))
            }, audioTracks = tracks(j.optJSONArray("audioTracks")), musicTracks = tracks(j.optJSONArray("musicTracks")), audioMix = j.optJSONObject("audioMix")?.let { a ->
                AudioMix(a.optLong("musicFadeIn"), a.optLong("musicFadeOut"), a.optBoolean("duckMusic"),
                    a.optDouble("duckAmount", .7).toFloat(), a.optLong("musicOffset"))
            } ?: AudioMix(), frameZoom = f("frameZoom", 1f), frameX = f("frameX"), frameY = f("frameY")).bounded()
    }.getOrDefault(EditRecipe())

    fun encode(r: EditRecipe): String {
        val x = r.bounded()
        return JSONObject().apply {
            put("version", 1); put("brightness", x.brightness); put("contrast", x.contrast); put("saturation", x.saturation); put("warmth", x.warmth)
            put("audioMix", JSONObject().apply {
                put("musicFadeIn", x.audioMix.musicFadeInMs); put("musicFadeOut", x.audioMix.musicFadeOutMs)
                put("duckMusic", x.audioMix.duckMusic); put("duckAmount", x.audioMix.duckAmount)
                put("musicOffset", x.audioMix.musicOffsetMs)
            })
            put("audioTracks", encodeTracks(x.audioTracks)); put("musicTracks", encodeTracks(x.musicTracks))
            put("filter", x.filter); put("strength", x.strength); put("speed", x.speed); put("ramp", x.ramp); put("rampEnd", x.rampEnd)
            put("fadeIn", x.fadeInMs); put("fadeOut", x.fadeOutMs); put("motion", x.motion); put("zoomStart", x.zoomStart); put("zoomEnd", x.zoomEnd)
            put("panStart", x.panStart); put("panEnd", x.panEnd); put("smooth", x.smooth); put("transition", x.transition); put("transitionMs", x.transitionMs)
            put("frameZoom", x.frameZoom); put("frameX", x.frameX); put("frameY", x.frameY)
            put("texts", JSONArray(x.texts.map { t -> JSONObject().apply {
                t.x?.let { put("x", it) }; t.y?.let { put("y", it) }
                put("id", t.id); put("text", t.text); put("start", t.startMs); put("end", t.endMs); put("position", t.position)
                put("size", t.size); put("color", t.color); put("background", t.background); put("animated", t.animated); put("caption", t.caption)
                put("rotation", t.rotation); put("font", t.font); put("style", t.style)
                put("animation", t.animation); put("animationMs", t.animationMs)
                put("wordHighlight", t.wordHighlight); put("highlightColor", t.highlightColor)
                if (t.wordTimings.isNotEmpty()) {
                    put("wordTimingText", t.wordTimingText)
                    put("wordTimings", JSONArray(t.wordTimings.map { w -> JSONArray(listOf(w.from, w.until, w.startMs, w.endMs)) }))
                }
            } }))
            put("clips", JSONArray(x.clips.map { c -> JSONObject().apply {
                put("id", c.id); put("path", c.path); put("name", c.name); put("duration", c.durationMs); put("start", c.startMs)
                put("end", c.endMs); put("width", c.width); put("height", c.height)
                c.speed?.let { put("speed", it) }; put("volume", c.volume)
                c.frameZoom?.let { put("frameZoom", it); put("frameX", c.frameX); put("frameY", c.frameY) }
                c.rampStartMs?.let { put("rampStart", it) }; c.rampEndMs?.let { put("rampEnd", it) }
            } }))
            x.collage?.let { c -> put("collage", JSONObject().apply {
                put("layout", c.layout); put("gap", c.gap); put("audioIndex", c.audioIndex)
                put("assets", JSONArray(c.assets.map { a -> JSONObject().apply {
                    put("id", a.id); put("path", a.path); put("name", a.name); put("image", a.image)
                    put("duration", a.durationMs); put("width", a.width); put("height", a.height)
                    put("zoom", a.zoom); put("panX", a.panX); put("panY", a.panY)
                    put("placeholder", a.placeholder)
                } }))
            }) }
        }.toString()
    }
    private fun JSONArray?.objects(limit: Int) = if (this == null) emptyList() else (0 until minOf(length(), limit)).map { getJSONObject(it) }
}

internal fun DraftEntity.recipe() = RecipeCodec.decode(editRecipe)
internal fun DraftEntity.timeline(r: EditRecipe = recipe()): List<VideoClip> = r.clips.ifEmpty {
    listOf(VideoClip(id, sourcePath, title, durationMs, trimStartMs, trimEndMs, width, height))
}
internal fun DraftEntity.outputDuration(r: EditRecipe = recipe()) = if (r.collage != null)
    SpeedPlan(trimEndMs - trimStartMs, r).outputMs else timeline(r).sumOf { it.speedPlan(r).outputMs }
