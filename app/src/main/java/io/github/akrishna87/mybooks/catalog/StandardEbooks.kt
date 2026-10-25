package io.github.akrishna87.mybooks.catalog

/**
 * Standard Ebooks: public-domain books carefully proofread and typeset by volunteers, released
 * free (CC0). Their catalog feeds are for supporters, so this reads the public search pages,
 * which mark each book up with schema.org properties.
 */
object StandardEbooks {
    private const val SITE = "https://standardebooks.org"
    const val PAGE_SIZE = 24

    fun searchUrl(query: Query, page: Int): String =
        "$SITE/ebooks?query=${urlEncode(query.text.trim())}&page=$page&per-page=$PAGE_SIZE"

    fun popularUrl(page: Int): String = "$SITE/ebooks?sort=popularity&page=$page&per-page=$PAGE_SIZE"

    fun pageUrl(path: String) = "$SITE/ebooks/$path"

    /** "jane-austen/pride-and-prejudice" → its EPUB, "…/downloads/jane-austen_pride-and-prejudice.epub". */
    fun epubUrl(path: String) = "$SITE/ebooks/$path/downloads/${path.replace('/', '_')}.epub?source=download"

    private val ITEM = Regex("<li typeof=\"schema:Book\" about=\"/ebooks/([^\"]+)\">(.*?)</li>", RegexOption.DOT_MATCHES_ALL)
    private val NAME = Regex("<span property=\"schema:name\">([^<]*)</span>")
    private val AUTHOR = Regex("property=\"schema:author\".*?<span property=\"schema:name\">([^<]*)</span>", RegexOption.DOT_MATCHES_ALL)
    private val IMAGE = Regex("<img src=\"([^\"]+)\"")

    /** A page of search results or the popular list. */
    fun parseList(html: String): ResultPage {
        val books = ITEM.findAll(html).map { m ->
            val path = m.groupValues[1]
            val body = m.groupValues[2]
            val title = NAME.find(body)?.groupValues?.get(1)?.let(::decodeEntities).orEmpty()
            val authors = AUTHOR.findAll(body).map { decodeEntities(it.groupValues[1]) }.toList()
            val cover = IMAGE.find(body)?.groupValues?.get(1)?.let { if (it.startsWith("/")) SITE + it else it }
            Book(
                id = "standardebooks:$path",
                kind = Kind.EBOOK,
                source = Source.STANDARD_EBOOKS,
                title = title.ifEmpty { "Untitled" },
                author = authors.joinToString(", ").ifEmpty { "Unknown author" },
                coverUrl = cover,
                language = "English",
                fileUrl = epubUrl(path),
                pageUrl = pageUrl(path),
            )
        }.toList()
        return ResultPage(books, hasMore = books.size >= PAGE_SIZE)
    }

    private val DESCRIPTION = Regex("<meta content=\"([^\"]*)\" name=\"description\"")
    private val PREFIX = Regex("^Free epub ebook download of the Standard Ebooks edition of [^:]*:\\s*")

    /** The one-line summary on a book's page. */
    fun parseDescription(html: String): String =
        DESCRIPTION.find(html)?.groupValues?.get(1)?.let { decodeEntities(it).replace(PREFIX, "").trim() }.orEmpty()
}

/** "The Count of Monte Cristo" and "Count of Monte Cristo, The" match; so do "Jane Austen" and "Austen, Jane". */
private fun matchKey(b: Book): String {
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { it.isNotEmpty() && it !in setOf("the", "a", "an") }.sorted().joinToString(" ")
    return norm(b.title.substringBefore(';').substringBefore(':')) + "|" + norm(b.author.substringBefore(',').substringBefore(" and "))
}

/** Standard Ebooks' results first, then Gutenberg's, without the books Standard Ebooks already gave. */
fun mergeEbooks(standard: ResultPage?, gutenberg: ResultPage?): ResultPage {
    val first = standard?.books.orEmpty()
    val seen = first.map(::matchKey).toSet()
    val rest = gutenberg?.books.orEmpty().filter { matchKey(it) !in seen }
    return ResultPage(first + rest, hasMore = (standard?.hasMore ?: false) || (gutenberg?.hasMore ?: false))
}
