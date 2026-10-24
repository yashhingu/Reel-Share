package com.reelshare.feature.creator

import android.content.Context
import android.net.Uri

internal object Mp4Input {
    const val MAX_BYTES = 512L * 1024 * 1024
    fun requireValid(context: Context, uri: Uri) {
        val header = ByteArray(32)
        val count = context.contentResolver.openInputStream(uri)?.use { it.read(header) } ?: 0
        Mp4Format.requireValidHeader(header, count, context.contentResolver.getType(uri))
    }
}
