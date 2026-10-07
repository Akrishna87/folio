package io.github.akrishna87.mybooks

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTPS requests to the book catalogs. */
object Http {
    private const val USER_AGENT = "Folio/1.0 (Android app; https://github.com/Akrishna87/folio)"

    /** A download that keeps dropping, even after picking up where it stopped a few times. */
    class DownloadInterrupted(cause: IOException) : IOException("The connection kept dropping", cause)

    private fun open(url: String, timeoutMs: Int, fromByte: Long = 0): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "*/*")
            if (fromByte > 0) c.setRequestProperty("Range", "bytes=$fromByte-")
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location") ?: throw IOException("Redirect without a location from $current")
                c.disconnect()
                current = URL(URL(current), location).toString()
                if (!current.startsWith("https://")) throw IOException("Refusing to follow a redirect to $current")
                return@repeat
            }
            if (code !in 200..299) {
                c.disconnect()
                throw IOException("HTTP $code from ${URL(current).host}")
            }
            return c
        }
        throw IOException("Too many redirects from $url")
    }

    fun get(url: String, timeoutMs: Int = 25_000): String {
        val c = open(url, timeoutMs)
        try {
            return c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Downloads to [dest], only replacing it once the whole file has arrived. [accept] sees the
     * file's size (or -1 if the server doesn't say) before anything is downloaded; if it says no,
     * nothing is downloaded and this returns false. [onProgress] gets (bytes so far, total or -1).
     * If the connection drops part way, it picks up where it stopped, a few times over.
     */
    fun download(
        url: String,
        dest: File,
        accept: (Long) -> Boolean = { true },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Boolean {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        part.delete()
        var total = -1L
        var retries = 0
        try {
            while (true) {
                val done = part.length()
                // A stalled connection gives up after 30 s instead of spinning for minutes.
                val c = open(url, 30_000, fromByte = done)
                try {
                    val resumed = done > 0 && c.responseCode == HttpURLConnection.HTTP_PARTIAL
                    if (done == 0L) {
                        total = c.contentLengthLong
                        if (!accept(total)) return false
                    } else if (!resumed) {
                        part.delete() // the server sent the whole file again
                    }
                    copy(c, part, append = resumed, total, onProgress)
                    // Some connections end quietly part way instead of failing: carry on from there.
                    if (total > 0 && part.length() < total) throw IOException("The download stopped early")
                    break
                } catch (e: IOException) {
                    // Only a drop part way through is worth another go; HTTP errors are thrown by open().
                    if (++retries > 3) throw DownloadInterrupted(e)
                    android.util.Log.i("Folio", "Download of $url dropped at ${part.length()} bytes (${e.message}); retrying")
                    Thread.sleep(1_500L * retries)
                } finally {
                    c.disconnect()
                }
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        if (part.length() == 0L) {
            part.delete()
            throw IOException("The download was empty")
        }
        if (!part.renameTo(dest)) {
            part.delete()
            throw IOException("Couldn't save the book")
        }
        return true
    }

    private fun copy(c: HttpURLConnection, part: File, append: Boolean, total: Long, onProgress: (Long, Long) -> Unit) {
        var done = if (append) part.length() else 0L
        onProgress(done, total)
        c.inputStream.use { input ->
            java.io.FileOutputStream(part, append).use { out ->
                val buffer = ByteArray(64 * 1024)
                var reported = done
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    if (done - reported >= 256 * 1024) {
                        reported = done
                        onProgress(done, total)
                    }
                }
            }
        }
        onProgress(done, total)
    }
}
