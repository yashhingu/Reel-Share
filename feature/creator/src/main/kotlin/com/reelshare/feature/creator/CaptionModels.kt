package com.reelshare.feature.creator

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.net.ssl.HttpsURLConnection

internal object CaptionModels {
    private data class Spec(val name: String, val sha: String)
    private val specs = mapOf(
        "English" to Spec("vosk-model-small-en-us-0.15", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"),
        "Hindi" to Spec("vosk-model-small-hi-0.22", "7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48")
    )
    fun installed(context: Context, language: String): Boolean = specs[language]?.let {
        File(context.noBackupFilesDir, "caption_models/${it.name}/ready").isFile
    } ?: false
    suspend fun prepare(context: Context, language: String, report: suspend (Int, String) -> Unit): File {
        val spec = requireNotNull(specs[language]) { "Choose English or Hindi." }
        val root = File(context.noBackupFilesDir, "caption_models").apply { mkdirs() }.canonicalFile
        val target = File(root, spec.name)
        if (installed(context, language)) return target
        val staging = File(root, "${spec.name}_partial").canonicalFile
        check(staging.parentFile == root)
        staging.deleteRecursively(); check(staging.mkdirs())
        val archive = File(root, "${spec.name}.zip")
        try {
            require(root.usableSpace > 250L * 1024 * 1024) { "Free at least 250 MB to install a caption model." }
            report(0, "Downloading $language model")
            val connection = URL("https://alphacephei.com/vosk/models/${spec.name}.zip").openConnection() as HttpsURLConnection
            connection.connectTimeout = 15_000; connection.readTimeout = 15_000; connection.instanceFollowRedirects = false
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                require(connection.responseCode == 200) { "Model download unavailable. Try again when connected." }
                val expected = connection.contentLengthLong
                var total = 0L
                connection.inputStream.use { input -> archive.outputStream().use { out ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= 64L * 1024 * 1024) { "Unexpected model size." }
                        digest.update(buffer, 0, count); out.write(buffer, 0, count)
                        report(if (expected > 0) (total * 25 / expected).toInt().coerceAtMost(25) else 0, "Downloading $language model")
                    }
                } }
            } finally { connection.disconnect() }
            require(digest.digest().joinToString("") { "%02x".format(it) } == spec.sha) { "Model verification failed. Retry the download." }
            report(26, "Installing $language model")
            var unpacked = 0L
            var entries = 0
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(++entries <= 2000 && entry.name.startsWith("${spec.name}/")) { "Invalid model archive." }
                    val file = File(staging, entry.name.removePrefix("${spec.name}/")).canonicalFile
                    require(file == staging || file.path.startsWith(staging.path + File.separator)) { "Invalid model path." }
                    if (entry.isDirectory) file.mkdirs() else {
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { out ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = zip.read(buffer); if (count < 0) break
                                unpacked += count; require(unpacked <= 200L * 1024 * 1024) { "Model expands beyond the storage limit." }
                                out.write(buffer, 0, count)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            require(File(staging, "am/final.mdl").isFile && File(staging, "conf/model.conf").isFile) { "Incomplete model." }
            File(staging, "ready").writeText(spec.sha)
            check(target.canonicalFile.parentFile == root); target.deleteRecursively()
            check(staging.renameTo(target)) { "Could not finish model installation." }
            return target
        } finally { archive.delete(); staging.deleteRecursively() }
    }
}
