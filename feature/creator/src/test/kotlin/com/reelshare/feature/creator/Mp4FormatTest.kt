package com.reelshare.feature.creator

import org.junit.Test

class Mp4FormatTest {
    private fun header(brand: String) = byteArrayOf(0, 0, 0, 24) + "ftyp$brand".toByteArray(Charsets.US_ASCII)

    @Test fun acceptsMp4FromDocumentProvider() {
        val bytes = header("isom")
        Mp4Format.requireValidHeader(bytes, bytes.size, "video/mp4")
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsWebmRenamedAsMp4() {
        Mp4Format.requireValidHeader(ByteArray(32), 32, "video/mp4")
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsQuickTimeContainer() {
        val bytes = header("qt  ")
        Mp4Format.requireValidHeader(bytes, bytes.size, null)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyInput() { Mp4Format.requireValidHeader(ByteArray(32), 0, "video/mp4") }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsOtherVideoMime() {
        val bytes = header("3gp4")
        Mp4Format.requireValidHeader(bytes, bytes.size, "video/3gpp")
    }
}
