package io.github.akrishna87.mybooks

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTPS requests to the book catalogs. */
object Http {
    private const val USER_AGENT = "MyBooks/1.0 (Android app; https://github.com/Akrishna87/Akrishna87)"

    private fun open(url: String, timeoutMs: Int): HttpURLConnection {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "*/*")
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

    /** Downloads to [dest], only replacing it once the whole file has arrived. */
    fun download(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")
        val c = open(url, 60_000)
        try {
            c.inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        if (part.length() == 0L) {
            part.delete()
            throw IOException("The download was empty")
        }
        if (!part.renameTo(dest)) {
            part.delete()
            throw IOException("Couldn't save the book")
        }
    }
}
