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

/** The colours a highlight can be; [UNDERLINE] draws a line under the text instead. */
enum class HighlightColor(val label: String, val css: String) {
    YELLOW("Yellow", "rgba(255, 196, 0, 0.42)"),
    GREEN("Green", "rgba(76, 175, 80, 0.36)"),
    BLUE("Blue", "rgba(66, 133, 244, 0.32)"),
    PINK("Pink", "rgba(240, 98, 146, 0.34)"),
    PURPLE("Purple", "rgba(149, 117, 205, 0.36)"),
    UNDERLINE("Underline", "transparent"),
    ;

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: YELLOW
    }
}

/** Highlighted text: characters [start, end) of a chapter's text, with an optional note. */
data class Highlight(
    val id: String,
    val spine: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val chapter: String,
    val createdAt: Long,
    val color: HighlightColor = HighlightColor.YELLOW,
    val note: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("spine", spine).put("start", start).put("end", end)
        .put("text", text).put("chapter", chapter).put("createdAt", createdAt)
        .put("color", color.name).put("note", note)

    companion object {
        fun fromJson(o: JSONObject) = Highlight(
            o.getString("id"), o.getInt("spine"), o.getInt("start"), o.getInt("end"),
            o.optString("text"), o.optString("chapter"), o.optLong("createdAt"),
            HighlightColor.of(o.optString("color")), o.optString("note"),
        )
    }
}

/** Everything saved inside one book, in reading order. */
data class Marks(val bookmarks: List<Bookmark> = emptyList(), val highlights: List<Highlight> = emptyList()) {
    fun withBookmark(b: Bookmark) = copy(bookmarks = (bookmarks + b).sortedWith(compareBy({ it.spine }, { it.offset })))
    /** Adds [h], or replaces the highlight with its id (a new colour or note). */
    fun withHighlight(h: Highlight) =
        copy(highlights = (highlights.filter { it.id != h.id } + h).sortedWith(compareBy({ it.spine }, { it.start })))
    fun withoutBookmark(id: String) = copy(bookmarks = bookmarks.filter { it.id != id })
    fun withoutHighlight(id: String) = copy(highlights = highlights.filter { it.id != id })

    /** For reader.js: [[start, end, id, colour, hasNote], …] of one chapter's highlights. */
    fun highlightsJs(spine: Int): String = JSONArray(
        highlights.filter { it.spine == spine }.map {
            JSONArray(listOf(it.start, it.end, it.id, it.color.name.lowercase(), if (it.note.isNotBlank()) 1 else 0))
        },
    ).toString()

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
