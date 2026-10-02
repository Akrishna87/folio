package io.github.akrishna87.mybooks

import android.content.Context
import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Chapter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A book on your shelf: one you've saved, started, or downloaded. */
data class ShelfItem(
    val book: Book,
    /** An audiobook's files, saved so it can play (and be downloaded) without asking the catalog again. */
    val chapters: List<Chapter> = emptyList(),
    val addedAt: Long = System.currentTimeMillis(),
    /** When it was last played or read; 0 if never. */
    val openedAt: Long = 0L,
    /** Android download ids for an audiobook's files while they're downloading. */
    val downloadIds: List<Long> = emptyList(),
    /** Every file is on the phone. */
    val downloaded: Boolean = false,
) {
    val id: String get() = book.id

    fun toJson(): JSONObject = JSONObject()
        .put("book", book.toJson())
        .put("chapters", Chapter.listToJson(chapters))
        .put("addedAt", addedAt)
        .put("openedAt", openedAt)
        .put("downloadIds", JSONArray(downloadIds))
        .put("downloaded", downloaded)

    companion object {
        fun fromJson(o: JSONObject): ShelfItem {
            val ids = o.optJSONArray("downloadIds")
            return ShelfItem(
                book = Book.fromJson(o.getJSONObject("book")),
                chapters = Chapter.listFromJson(o.optJSONArray("chapters")),
                addedAt = o.optLong("addedAt"),
                openedAt = o.optLong("openedAt"),
                downloadIds = if (ids == null) emptyList() else List(ids.length()) { ids.getLong(it) },
                downloaded = o.optBoolean("downloaded"),
            )
        }
    }
}

/** The shelf, saved as a small JSON file. Shared by the screens and the playback service. */
object ShelfStore {
    private var cache: List<ShelfItem>? = null

    private fun file(context: Context) = File(context.filesDir, "shelf.json")

    @Synchronized
    fun load(context: Context): List<ShelfItem> {
        cache?.let { return it }
        val list = try {
            val arr = JSONArray(file(context).readText())
            List(arr.length()) { ShelfItem.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
        cache = list
        return list
    }

    @Synchronized
    fun save(context: Context, list: List<ShelfItem>) {
        cache = list
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val f = file(context)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }

    fun get(context: Context, id: String): ShelfItem? = load(context).firstOrNull { it.id == id }
}

/** Where a book's files live on the phone. Removed with the app. */
object BookFiles {
    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9._-]"), "_")

    fun dir(context: Context, id: String): File {
        val root = context.getExternalFilesDir("books") ?: File(context.filesDir, "books")
        return File(root, safe(id))
    }

    fun epub(context: Context, id: String) = File(dir(context, id), "book.epub")

    /** Relative to the app's external "books" folder, as Android's download manager wants it. */
    fun chapterSubPath(id: String, index: Int) = "${safe(id)}/ch_%04d.mp3".format(index)

    fun chapter(context: Context, id: String, index: Int) = File(dir(context, id), "ch_%04d.mp3".format(index))

    fun delete(context: Context, id: String) {
        dir(context, id).deleteRecursively()
    }

    fun sizeOf(context: Context, id: String): Long = dir(context, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/**
 * Where you are in each book. Kept apart from the shelf because the player saves it every few
 * seconds. Audiobooks store (chapter, milliseconds); ebooks store (spine item, fraction of it).
 */
object Progress {
    private fun prefs(context: Context) = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    fun saveAudio(context: Context, id: String, chapter: Int, positionMs: Long) {
        prefs(context).edit().putString("pos:$id", "$chapter|$positionMs").putString("last_audio", id).apply()
    }

    fun audio(context: Context, id: String): Pair<Int, Long>? = prefs(context).getString("pos:$id", null)?.let {
        val (a, b) = it.split('|').let { p -> p.getOrNull(0) to p.getOrNull(1) }
        val chapter = a?.toIntOrNull() ?: return null
        chapter to (b?.toLongOrNull() ?: 0L)
    }

    fun lastAudio(context: Context): String? = prefs(context).getString("last_audio", null)

    fun saveReading(context: Context, id: String, spine: Int, fraction: Float, overall: Float) {
        prefs(context).edit().putString("pos:$id", "$spine|$fraction").putFloat("pct:$id", overall).apply()
    }

    fun reading(context: Context, id: String): Pair<Int, Float>? = prefs(context).getString("pos:$id", null)?.let {
        val p = it.split('|')
        val spine = p.getOrNull(0)?.toIntOrNull() ?: return null
        spine to (p.getOrNull(1)?.toFloatOrNull() ?: 0f)
    }

    fun readingPercent(context: Context, id: String): Float = prefs(context).getFloat("pct:$id", 0f)

    fun clear(context: Context, id: String) {
        prefs(context).edit().remove("pos:$id").remove("pct:$id").apply()
    }
}
