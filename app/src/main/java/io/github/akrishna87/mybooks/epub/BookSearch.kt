package io.github.akrishna87.mybooks.epub

/** A match for a search: characters [start, end) of chapter [spine]'s text, with words around it. */
data class SearchHit(val spine: Int, val start: Int, val end: Int, val before: String, val match: String, val after: String)

/** Finding words in a book, by the same character positions reader.js uses. */
object BookSearch {
    private val BODY = Regex("<body[^>]*>(.*)</body\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val SKIP = Regex("<!--.*?-->|<(script|style)\\b.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CDATA = Regex("<!\\[CDATA\\[(.*?)]]>", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]*>")
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);")
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "—", "ndash" to "–", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "hellip" to "…", "copy" to "©", "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç",
        "aelig" to "æ", "oelig" to "œ", "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "pound" to "£", "shy" to "­",
    )

    /**
     * The text of a chapter's body as the browser sees it: every text node, one after another.
     * reader.js counts positions in this same text, so a match here opens at the right place.
     */
    fun chapterText(html: String): String {
        val body = BODY.find(html)?.groupValues?.get(1) ?: html
        val noSkips = SKIP.replace(body, "")
        val cdata = CDATA.replace(noSkips) { it.groupValues[1].replace("<", "\u0000") }
        return decode(TAG.replace(cdata, "")).replace('\u0000', '<')
    }

    private fun decode(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> NAMED[e]
        } ?: m.value
    }

    /** Where [query] appears in [text], ignoring case and treating any run of spaces as one. */
    fun find(text: String, query: String, limit: Int = 200): List<IntRange> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val pattern = Regex(words.joinToString("\\s+") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        return pattern.findAll(text).take(limit).map { it.range }.toList()
    }

    /** Every match of [query] in one chapter, each with a little of the text around it. */
    fun hits(spine: Int, text: String, query: String, limit: Int = 200): List<SearchHit> =
        find(text, query, limit).map { r ->
            val before = text.substring((r.first - 40).coerceAtLeast(0), r.first)
            val after = text.substring(r.last + 1, (r.last + 1 + 60).coerceAtMost(text.length))
            SearchHit(spine, r.first, r.last + 1, tidy(before).trimStart(), text.substring(r), tidy(after).trimEnd())
        }

    private fun tidy(s: String) = s.replace(Regex("\\s+"), " ")
}
