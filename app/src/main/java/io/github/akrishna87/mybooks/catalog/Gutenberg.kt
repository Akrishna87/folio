package io.github.akrishna87.mybooks.catalog

import org.json.JSONObject

/**
 * Project Gutenberg's public-domain ebooks. Searched through Gutendex (a free JSON API over
 * Gutenberg's catalog), with Gutenberg's own OPDS catalog as a fallback when Gutendex is down.
 * Gutenberg asks apps not to scrape its web pages, so only these two catalog feeds are used.
 */
object Gutenberg {
    private const val GUTENDEX = "https://gutendex.com/books/"
    private const val SITE = "https://www.gutenberg.org"
    private const val OPDS_PAGE_SIZE = 25

    fun searchUrl(query: Query, page: Int): String {
        val param = if (query.isSubject) "topic" else "search"
        return "$GUTENDEX?$param=${urlEncode(query.text.trim())}&page=$page"
    }

    fun popularUrl(page: Int): String = "$GUTENDEX?languages=en&page=$page"

    fun bookUrl(number: String): String = "$GUTENDEX$number"

    fun opdsSearchUrl(query: Query, page: Int): String {
        val start = 1 + (page - 1) * OPDS_PAGE_SIZE
        return "$SITE/ebooks/search.opds/?query=${urlEncode(query.text.trim())}&start_index=$start"
    }

    fun opdsPopularUrl(page: Int): String =
        "$SITE/ebooks/search.opds/?sort_order=downloads&start_index=${1 + (page - 1) * OPDS_PAGE_SIZE}"

    fun epubUrl(number: String) = "$SITE/ebooks/$number.epub3.images"
    fun coverUrl(number: String) = "$SITE/cache/epub/$number/pg$number.cover.medium.jpg"
    fun pageUrl(number: String) = "$SITE/ebooks/$number"

    fun parseGutendex(json: String): ResultPage {
        val root = JSONObject(json)
        val results = root.optJSONArray("results")
        val books = ArrayList<Book>()
        if (results != null) {
            for (i in 0 until results.length()) parseGutendexBook(results.getJSONObject(i))?.let { books += it }
        }
        return ResultPage(books, hasMore = !root.isNull("next") && root.optString("next").isNotEmpty())
    }

    /** One Gutendex book, or null if Gutenberg has no EPUB for it (audio, sheet music …). */
    fun parseGutendexBook(o: JSONObject): Book? {
        val number = o.opt("id")?.toString() ?: return null
        val formats = o.optJSONObject("formats") ?: JSONObject()
        val epub = formats.keys().asSequence()
            .filter { it.startsWith("application/epub") }
            .map { formats.getString(it) }
            .firstOrNull() ?: return null
        val authors = o.optJSONArray("authors")
        val author = if (authors == null || authors.length() == 0) "Unknown author"
        else (0 until authors.length()).joinToString(", ") { flipName(authors.getJSONObject(it).optString("name")) }
        val summaries = o.optJSONArray("summaries")
        val summary = if (summaries != null && summaries.length() > 0) summaries.optString(0) else ""
        val languages = o.optJSONArray("languages")
        val cover = formats.keys().asSequence().firstOrNull { it.startsWith("image/") }?.let { formats.getString(it) }
        val copyrighted = o.optBoolean("copyright", false)
        return Book(
            id = "gutenberg:$number",
            kind = Kind.EBOOK,
            source = Source.GUTENBERG,
            title = o.optString("title").trim().ifEmpty { "Untitled" },
            author = author,
            coverUrl = cover?.let(::httpsOnly) ?: coverUrl(number),
            description = summary.trim(),
            language = if (languages != null && languages.length() > 0) languageName(languages.optString(0)) else "",
            downloads = o.optInt("download_count"),
            fileUrl = httpsOnly(epub),
            pageUrl = pageUrl(number),
            rights = if (copyrighted) "Shared by Project Gutenberg with the copyright holder's permission" else Source.GUTENBERG.licence,
        )
    }

    private val EBOOK_NUMBER = Regex("/ebooks/(\\d+)(?:\\.opds)?/?$")

    /** Gutenberg's own OPDS (Atom) search results. */
    fun parseOpds(xml: String): ResultPage {
        val doc = parseXml(xml)
        val feed = doc.documentElement
        val books = ArrayList<Book>()
        for (entry in feed.children("entry")) {
            val number = (entry.children("link").map { it.getAttribute("href") } + entry.child("id")?.text.orEmpty())
                .firstNotNullOfOrNull { EBOOK_NUMBER.find(it)?.groupValues?.get(1) } ?: continue
            val title = entry.child("title")?.text.orEmpty()
            val author = entry.child("author")?.child("name")?.text?.let(::flipName)
                ?: entry.child("content")?.text.orEmpty()
            books += Book(
                id = "gutenberg:$number",
                kind = Kind.EBOOK,
                source = Source.GUTENBERG,
                title = title.ifEmpty { "Ebook #$number" },
                author = author.ifEmpty { "Unknown author" },
                coverUrl = coverUrl(number),
                fileUrl = epubUrl(number),
                pageUrl = pageUrl(number),
            )
        }
        val hasNext = feed.children("link").any { it.getAttribute("rel") == "next" }
        return ResultPage(books, hasNext)
    }

    private fun httpsOnly(url: String) = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
}

/** "en" → "English". */
fun languageName(code: String): String = when (code.lowercase()) {
    "en", "eng", "english" -> "English"
    "fr", "fre", "fra", "french" -> "French"
    "de", "ger", "deu", "german" -> "German"
    "es", "spa", "spanish" -> "Spanish"
    "it", "ita", "italian" -> "Italian"
    "nl", "dut", "nld", "dutch" -> "Dutch"
    "pt", "por", "portuguese" -> "Portuguese"
    "fi", "fin", "finnish" -> "Finnish"
    "zh", "chi", "zho", "chinese" -> "Chinese"
    "ja", "jpn", "japanese" -> "Japanese"
    "la", "lat", "latin" -> "Latin"
    "el", "gre", "ell", "greek" -> "Greek"
    "ru", "rus", "russian" -> "Russian"
    "sv", "swe", "swedish" -> "Swedish"
    "hi", "hin", "hindi" -> "Hindi"
    else -> code.replaceFirstChar { it.uppercase() }
}
