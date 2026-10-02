package io.github.akrishna87.mybooks.catalog

import org.json.JSONArray
import org.json.JSONObject

enum class Kind(val label: String) { AUDIOBOOK("Audiobook"), EBOOK("Ebook") }

/** Where a book comes from. Only catalogs whose books are free and legal to download. */
enum class Source(val label: String, val licence: String) {
    LIBRIVOX("LibriVox", "Public domain recording, free to download and share"),
    GUTENBERG("Project Gutenberg", "Public domain in the USA, free to download"),
}

data class Book(
    /** "librivox:<Internet Archive identifier>" or "gutenberg:<ebook number>". */
    val id: String,
    val kind: Kind,
    val source: Source,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val description: String = "",
    val language: String = "",
    val downloads: Int = 0,
    /** The EPUB file, for ebooks. */
    val fileUrl: String? = null,
    /** The book's page on the source's website. */
    val pageUrl: String? = null,
    /** "Public domain in the USA", or a note when the source says otherwise. */
    val rights: String = source.licence,
) {
    /** The part after "source:", e.g. the Archive identifier or Gutenberg number. */
    val sourceId: String get() = id.substringAfter(':')

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("kind", kind.name)
        .put("source", source.name)
        .put("title", title)
        .put("author", author)
        .put("cover", coverUrl ?: "")
        .put("description", description)
        .put("language", language)
        .put("downloads", downloads)
        .put("file", fileUrl ?: "")
        .put("page", pageUrl ?: "")
        .put("rights", rights)

    companion object {
        fun fromJson(o: JSONObject): Book {
            val source = Source.valueOf(o.getString("source"))
            return Book(
                id = o.getString("id"),
                kind = Kind.valueOf(o.getString("kind")),
                source = source,
                title = o.optString("title"),
                author = o.optString("author"),
                coverUrl = o.optString("cover").ifEmpty { null },
                description = o.optString("description"),
                language = o.optString("language"),
                downloads = o.optInt("downloads"),
                fileUrl = o.optString("file").ifEmpty { null },
                pageUrl = o.optString("page").ifEmpty { null },
                rights = o.optString("rights").ifEmpty { source.licence },
            )
        }
    }
}

/** One audio file of an audiobook: usually a chapter or a few chapters. */
data class Chapter(val title: String, val url: String, val durationSec: Double, val sizeBytes: Long) {
    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("url", url)
        .put("duration", durationSec)
        .put("size", sizeBytes)

    companion object {
        fun fromJson(o: JSONObject) = Chapter(
            title = o.optString("title"),
            url = o.getString("url"),
            durationSec = o.optDouble("duration", 0.0),
            sizeBytes = o.optLong("size"),
        )

        fun listToJson(list: List<Chapter>) = JSONArray().apply { list.forEach { put(it.toJson()) } }

        fun listFromJson(arr: JSONArray?): List<Chapter> =
            if (arr == null) emptyList() else List(arr.length()) { fromJson(arr.getJSONObject(it)) }
    }
}

/** What an audiobook's page needs beyond the search result. */
data class AudioDetails(val description: String, val chapters: List<Chapter>) {
    val totalSeconds: Double get() = chapters.sumOf { it.durationSec }
    val totalBytes: Long get() = chapters.sumOf { it.sizeBytes }
}

/** One page of search results. */
data class ResultPage(val books: List<Book>, val hasMore: Boolean)

/** A search: free text, or a subject picked from the browse tiles. */
data class Query(val text: String, val isSubject: Boolean = false)
