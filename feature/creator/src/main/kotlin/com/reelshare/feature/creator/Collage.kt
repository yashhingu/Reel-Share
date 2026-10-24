package com.reelshare.feature.creator

/** Owned media paths and normalized cells, shared by the picker, preview and export. */
internal data class CollageAsset(val id: String, val path: String, val name: String,
    val image: Boolean, val durationMs: Long, val width: Int, val height: Int,
    val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f, val placeholder: Boolean = false)

internal data class Collage(val assets: List<CollageAsset>, val layout: String = "Grid",
    val gap: Float = .012f, val audioIndex: Int = -1) {
    fun bounded(): Collage? {
        if (assets.size !in 2..4 || assets.count { !it.image } > 3 ||
            assets.any { it.path.isBlank() || it.width <= 0 || it.height <= 0 || !it.image && it.durationMs < 1000 }) return null
        return copy(layout = layout.takeIf { it in LAYOUTS } ?: "Grid", gap = gap.finite(0f, .04f),
            audioIndex = audioIndex.takeIf { it in assets.indices && !assets[it].image } ?: -1,
            assets = assets.map { it.copy(zoom = it.zoom.finite(1f, 2f, 1f),
                panX = it.panX.finite(-1f, 1f), panY = it.panY.finite(-1f, 1f)) })
    }
    companion object { val LAYOUTS = listOf("Grid", "Rows", "Columns", "Focus") }
}

internal data class CollageCell(val left: Float, val top: Float, val width: Float, val height: Float) {
    val centerX get() = left + width / 2
    val centerY get() = top + height / 2
}

internal object CollageLayout {
    fun cells(count: Int, layout: String, gap: Float = 0f): List<CollageCell> {
        require(count in 2..4)
        val raw = when {
            layout == "Rows" -> List(count) { CollageCell(0f, it.toFloat() / count, 1f, 1f / count) }
            layout == "Columns" ->
                List(count) { CollageCell(it.toFloat() / count, 0f, 1f / count, 1f) }
            layout == "Focus" || count == 3 -> listOf(CollageCell(0f, 0f, 1f, .6f)) +
                List(count - 1) { CollageCell(it.toFloat() / (count - 1), .6f, 1f / (count - 1), .4f) }
            count == 2 -> List(2) { CollageCell(it * .5f, 0f, .5f, 1f) }
            else -> List(4) { CollageCell((it % 2) * .5f, (it / 2) * .5f, .5f, .5f) }
        }
        val inset = gap.finite(0f, .04f) / 2
        return raw.map { it.copy(left = it.left + inset, top = it.top + inset,
            width = it.width - inset * 2, height = it.height - inset * 2) }
    }
}

private fun Float.finite(min: Float, max: Float, fallback: Float = 0f) = if (isFinite()) coerceIn(min, max) else fallback
