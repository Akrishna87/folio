package io.github.akrishna87.mybooks.epub

import org.json.JSONArray
import org.json.JSONObject

/**
 * A saved place in a book. [offset] counts characters of text from the start of the chapter
 * ([spine] item), so it stays put whatever the text size or screen.
 */
data class Bookmark(val id: String, val spine: Int, val offset: Int, val chapter: String, val snippet: String, val createdAt: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("spine", spine).put("offset", offset)
        .put("chapter", chapter).put("snippet", snippet).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Bookmark(
            o.getString("id"), o.getInt("spine"), o.getInt("offset"),
            o.optString("chapter"), o.optString("snippet"), o.optLong("createdAt"),
        )
    }
}

/** Highlighted text: characters [start, end) of a chapter's text. */
data class Highlight(val id: String, val spine: Int, val start: Int, val end: Int, val text: String, val chapter: String, val createdAt: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("spine", spine).put("start", start).put("end", end)
        .put("text", text).put("chapter", chapter).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Highlight(
            o.getString("id"), o.getInt("spine"), o.getInt("start"), o.getInt("end"),
            o.optString("text"), o.optString("chapter"), o.optLong("createdAt"),
        )
    }
}

/** Everything saved inside one book, in reading order. */
data class Marks(val bookmarks: List<Bookmark> = emptyList(), val highlights: List<Highlight> = emptyList()) {
    fun withBookmark(b: Bookmark) = copy(bookmarks = (bookmarks + b).sortedWith(compareBy({ it.spine }, { it.offset })))
    fun withHighlight(h: Highlight) = copy(highlights = (highlights + h).sortedWith(compareBy({ it.spine }, { it.start })))
    fun withoutBookmark(id: String) = copy(bookmarks = bookmarks.filter { it.id != id })
    fun withoutHighlight(id: String) = copy(highlights = highlights.filter { it.id != id })

    /** For reader.js: [[start, end, id], …] of one chapter's highlights. */
    fun highlightsJs(spine: Int): String =
        JSONArray(highlights.filter { it.spine == spine }.map { JSONArray(listOf(it.start, it.end, it.id)) }).toString()

    /** For reader.js: [[offset, id], …] of one chapter's bookmarks. */
    fun bookmarksJs(spine: Int): String =
        JSONArray(bookmarks.filter { it.spine == spine }.map { JSONArray(listOf(it.offset, it.id)) }).toString()

    fun toJson(): JSONObject = JSONObject()
        .put("bookmarks", JSONArray(bookmarks.map { it.toJson() }))
        .put("highlights", JSONArray(highlights.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): Marks {
            val b = o.optJSONArray("bookmarks") ?: JSONArray()
            val h = o.optJSONArray("highlights") ?: JSONArray()
            return Marks(
                List(b.length()) { Bookmark.fromJson(b.getJSONObject(it)) },
                List(h.length()) { Highlight.fromJson(h.getJSONObject(it)) },
            )
        }
    }
}
