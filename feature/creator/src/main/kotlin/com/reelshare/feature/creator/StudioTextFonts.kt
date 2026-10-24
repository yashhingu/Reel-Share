package com.reelshare.feature.creator

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.reelshare.core.designsystem.R

/** Resolved once per editor/export; platform fallback also covers scripts absent from Manrope. */
internal class StudioTextFonts(context: Context) {
    private val bold = ResourcesCompat.getFont(context, R.font.manrope_bold) ?: Typeface.DEFAULT_BOLD
    private val simple = ResourcesCompat.getFont(context, R.font.manrope_regular) ?: Typeface.DEFAULT
    private val serif = Typeface.create("serif", Typeface.BOLD)
    private val typewriter = Typeface.create("monospace", Typeface.NORMAL)

    fun get(name: String): Typeface = when (name) {
        "Simple" -> simple
        "Serif" -> serif
        "Typewriter" -> typewriter
        else -> bold
    }
}
