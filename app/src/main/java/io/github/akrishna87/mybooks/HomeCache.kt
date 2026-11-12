package io.github.akrishna87.mybooks

import android.content.Context
import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Kind
import org.json.JSONArray
import java.io.File

/**
 * The last popular audiobooks and ebooks Home showed, so it can show them straight away on the
 * next launch while fresh lists load in the background.
 */
object HomeCache {
    private fun file(context: Context, kind: Kind) = File(File(context.cacheDir, "home"), "popular_${kind.name.lowercase()}.json")

    fun load(context: Context, kind: Kind): List<Book>? = try {
        val f = file(context, kind)
        if (!f.isFile) null
        else {
            val arr = JSONArray(f.readText())
            List(arr.length()) { Book.fromJson(arr.getJSONObject(it)) }.ifEmpty { null }
        }
    } catch (e: Exception) {
        null
    }

    fun save(context: Context, kind: Kind, books: List<Book>) {
        if (books.isEmpty()) return
        try {
            val f = file(context, kind)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONArray().apply { books.forEach { put(it.toJson()) } }.toString())
            tmp.renameTo(f)
        } catch (e: Exception) {
            // Only a cache.
        }
    }
}
