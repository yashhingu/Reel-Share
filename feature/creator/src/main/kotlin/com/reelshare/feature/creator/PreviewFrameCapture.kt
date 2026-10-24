package com.reelshare.feature.creator

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Copy only the small preview buffer, and never delay recovery on an unavailable surface. */
internal suspend fun capturePreviewFrame(view: SurfaceView?, width: Int, height: Int): Bitmap? {
    if (view?.holder?.surface?.isValid != true) return null
    return withTimeoutOrNull(250) {
        suspendCancellableCoroutine { continuation ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                PixelCopy.request(view, bitmap, { result ->
                    if (result == PixelCopy.SUCCESS && continuation.isActive) continuation.resume(bitmap)
                    else {
                        bitmap.recycle()
                        if (continuation.isActive) continuation.resume(null)
                    }
                }, Handler(Looper.getMainLooper()))
            } catch (_: IllegalArgumentException) {
                bitmap.recycle()
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }
}
