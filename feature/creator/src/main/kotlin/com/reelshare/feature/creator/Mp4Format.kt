package com.reelshare.feature.creator

internal object Mp4Format {
    fun requireValidHeader(header: ByteArray, count: Int, mime: String?) {
        require(count >= 12 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp" &&
            String(header, 8, 4, Charsets.US_ASCII) != "qt  ") {
            "Only MP4 videos are supported. Choose a valid .mp4 file."
        }
        require(mime == null || mime in setOf("video/mp4", "application/mp4", "application/octet-stream")) {
            "Only MP4 videos are supported."
        }
    }
}
