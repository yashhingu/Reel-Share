package com.reelshare.feature.creator

import com.reelshare.core.database.entity.DraftEntity
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Bounded edit history lives in the same Room row as the current decisions. No nested histories or media copies. */
internal object DraftHistory {
    private const val KEY = "_history"
    private const val LIMIT = 20
    private const val MAX_BYTES = 256_000
    data class Meta(val undo: Boolean = false, val redo: Boolean = false, val token: String = "")
    private data class History(val undo: List<JSONObject> = emptyList(), val redo: List<JSONObject> = emptyList(),
        val token: String = "", val group: String = "", val time: Long = 0)

    fun meta(draft: DraftEntity): Meta = read(draft.editRecipe).let { Meta(it.undo.isNotEmpty(), it.redo.isNotEmpty(), it.token) }
    fun clear(draft: DraftEntity) = draft.copy(editRecipe = RecipeCodec.encode(draft.recipe()))

    fun record(before: DraftEntity, proposed: DraftEntity, now: Long, group: String? = null): DraftEntity {
        val old = snapshot(before)
        if (old.toString() == snapshot(proposed).toString()) return before
        val h = read(before.editRecipe)
        val merge = !group.isNullOrEmpty() && h.group == group && now - h.time in 0..1200 && h.redo.isEmpty() && h.undo.isNotEmpty()
        return decorate(proposed, h.copy(undo = if (merge) h.undo else (h.undo + old).takeLast(LIMIT), redo = emptyList(),
            group = group.orEmpty(), time = now))
    }

    fun restore(current: DraftEntity, redo: Boolean): DraftEntity? {
        val h = read(current.editRecipe)
        val stack = if (redo) h.redo else h.undo
        val state = stack.lastOrNull() ?: return null
        val restored = runCatching {
            current.copy(title = state.getString("title"), caption = state.getString("caption"), durationMs = state.getLong("duration"),
                trimStartMs = state.getLong("start"), trimEndMs = state.getLong("end"), aspect = state.getString("aspect"),
                quality = state.getString("quality"), mute = state.getBoolean("mute"), audioStartMs = state.getLong("audioStart"),
                audioEndMs = state.getLong("audioEnd"), audioVolume = state.getDouble("audioVolume").toFloat(),
                editRecipe = state.getJSONObject("recipe").toString())
        }.getOrNull() ?: return null
        val next = if (redo) h.copy(redo = h.redo.dropLast(1), undo = (h.undo + snapshot(current)).takeLast(LIMIT))
            else h.copy(undo = h.undo.dropLast(1), redo = (h.redo + snapshot(current)).takeLast(LIMIT))
        return decorate(restored, next.copy(token = UUID.randomUUID().toString(), group = "", time = 0))
    }

    private fun snapshot(d: DraftEntity) = JSONObject().apply {
        put("title", d.title); put("caption", d.caption); put("duration", d.durationMs)
        put("start", d.trimStartMs); put("end", d.trimEndMs); put("aspect", d.aspect); put("quality", d.quality); put("mute", d.mute)
        put("audioStart", d.audioStartMs); put("audioEnd", d.audioEndMs); put("audioVolume", d.audioVolume.toDouble())
        put("recipe", JSONObject(RecipeCodec.encode(d.recipe())))
    }

    private fun read(json: String): History = runCatching {
        val h = JSONObject(json).optJSONObject(KEY) ?: return History()
        require(h.optInt("version") == 1)
        fun entries(key: String): List<JSONObject> {
            val a = h.optJSONArray(key) ?: return emptyList()
            return (maxOf(0, a.length() - LIMIT) until a.length()).map { a.getJSONObject(it) }
        }
        History(entries("undo"), entries("redo"), h.optString("token"), h.optString("group"), h.optLong("time"))
    }.getOrDefault(History())

    private fun decorate(draft: DraftEntity, history: History): DraftEntity {
        val root = JSONObject(RecipeCodec.encode(draft.recipe()))
        var undo = history.undo; var redo = history.redo
        while (true) {
            root.put(KEY, JSONObject().apply {
                put("version", 1); put("undo", JSONArray(undo)); put("redo", JSONArray(redo))
                put("token", history.token); put("group", history.group); put("time", history.time)
            })
            val json = root.toString()
            if (json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES || undo.isEmpty() && redo.isEmpty()) return draft.copy(editRecipe = json)
            if (undo.size >= redo.size && undo.isNotEmpty()) undo = undo.drop(1) else redo = redo.drop(1)
        }
    }
}
