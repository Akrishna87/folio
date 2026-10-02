package io.github.akrishna87.mybooks.catalog

import org.json.JSONArray
import org.json.JSONObject

/**
 * LibriVox audiobooks: public-domain books read by volunteers. LibriVox keeps its recordings
 * on the Internet Archive (the "librivoxaudio" collection), whose search is much better than
 * LibriVox's own API, so books are found and played from there. Only that collection is
 * searched, so everything listed is in the public domain.
 */
object LibriVox {
    private const val ARCHIVE = "https://archive.org"
    const val PAGE_SIZE = 30

    private val FIELDS = listOf("identifier", "title", "creator", "description", "downloads", "language")

    fun searchUrl(query: Query, page: Int): String = advancedSearch(clauseFor(query), page)

    fun popularUrl(page: Int): String = advancedSearch(null, page)

    fun metadataUrl(identifier: String) = "$ARCHIVE/metadata/${urlEncode(identifier)}"

    fun coverUrl(identifier: String) = "$ARCHIVE/services/img/${urlEncode(identifier)}"

    fun pageUrl(identifier: String) = "$ARCHIVE/details/${urlEncode(identifier)}"

    fun fileUrl(identifier: String, name: String) = "$ARCHIVE/download/${urlEncode(identifier)}/${encodePath(name)}"

    private fun advancedSearch(clause: String?, page: Int): String {
        val q = "collection:(librivoxaudio) AND mediatype:(audio)" + (clause?.let { " AND $it" } ?: "")
        val fields = FIELDS.joinToString("") { "&fl%5B%5D=$it" }
        return "$ARCHIVE/advancedsearch.php?q=${urlEncode(q)}$fields" +
            "&sort%5B%5D=${urlEncode("downloads desc")}&rows=$PAGE_SIZE&page=$page&output=json"
    }

    /** Words typed by the person, made safe for the Archive's search syntax. */
    fun words(text: String): List<String> =
        text.lowercase()
            .replace(Regex("[+\\-&|!(){}\\[\\]^\"~*?:\\\\/]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(8)

    fun clauseFor(query: Query): String? {
        val w = words(query.text)
        if (w.isEmpty()) return null
        val all = w.joinToString(" AND ")
        // LibriVox puts the genre in the description ("Genre(s): Romance") more often than in the subjects.
        return if (query.isSubject) "(subject:($all) OR description:($all))" else "(title:($all) OR creator:($all))"
    }

    fun parseSearch(json: String, page: Int): ResultPage {
        val response = JSONObject(json).optJSONObject("response") ?: return ResultPage(emptyList(), false)
        val docs = response.optJSONArray("docs") ?: JSONArray()
        val books = ArrayList<Book>()
        for (i in 0 until docs.length()) {
            val d = docs.getJSONObject(i)
            val id = d.optString("identifier")
            if (id.isEmpty()) continue
            books += Book(
                id = "librivox:$id",
                kind = Kind.AUDIOBOOK,
                source = Source.LIBRIVOX,
                title = cleanTitle(firstString(d, "title")).ifEmpty { id },
                author = joinStrings(d, "creator").ifEmpty { "Unknown author" },
                coverUrl = coverUrl(id),
                description = htmlToText(firstString(d, "description")),
                language = languageName(firstString(d, "language")).takeIf { it.isNotEmpty() } ?: "",
                downloads = d.optInt("downloads"),
                pageUrl = pageUrl(id),
            )
        }
        val found = response.optInt("numFound")
        return ResultPage(books, hasMore = page * PAGE_SIZE < found)
    }

    /** Chapters and the full description from the Archive's metadata for one recording. */
    fun parseMetadata(identifier: String, json: String): AudioDetails {
        val root = JSONObject(json)
        val meta = root.optJSONObject("metadata") ?: JSONObject()
        val files = root.optJSONArray("files") ?: JSONArray()
        val mp3s = (0 until files.length()).map { files.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".mp3", ignoreCase = true) }
        // The same chapters are usually there in two qualities; the 64 kbps copies are a third of
        // the size and sound the same for speech.
        val byFormat = mp3s.groupBy { it.optString("format") }
        val preferred = listOf("64Kbps MP3", "128Kbps MP3", "VBR MP3")
        val chosen = preferred.firstNotNullOfOrNull { byFormat[it] }
            ?: byFormat.values.maxByOrNull { it.size }
            ?: emptyList()
        val chapters = chosen
            // LibriVox numbers its files in reading order ("book_01_author_64kb.mp3"); the track tags
            // inside the files are less reliable, so they only break ties.
            .sortedWith(compareBy<JSONObject, String>(NaturalOrder) { it.optString("name") }.thenBy { trackNumber(it.optString("track")) })
            .map { f ->
                val name = f.optString("name")
                Chapter(
                    title = f.optString("title").trim().ifEmpty { prettyFileName(name) },
                    url = fileUrl(identifier, name),
                    durationSec = parseLength(f.optString("length")),
                    sizeBytes = f.optString("size").toLongOrNull() ?: 0L,
                )
            }
        return AudioDetails(htmlToText(firstString(meta, "description")), chapters)
    }

    /** Compares text with numbers in it by the numbers' values, so "part_2" comes before "part_10". */
    object NaturalOrder : Comparator<String> {
        private val chunks = Regex("\\d+|\\D+")
        override fun compare(a: String, b: String): Int {
            val x = chunks.findAll(a.lowercase()).map { it.value }.toList()
            val y = chunks.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(x.size, y.size)) {
                val p = x[i]
                val q = y[i]
                val c = if (p[0].isDigit() && q[0].isDigit()) {
                    p.trimStart('0').length.compareTo(q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0'))
                } else {
                    p.compareTo(q)
                }
                if (c != 0) return c
            }
            return x.size.compareTo(y.size)
        }
    }

    /** "01", "1/61" and "" all sort sensibly. */
    private fun trackNumber(track: String): Int = Regex("\\d+").find(track)?.value?.toIntOrNull() ?: Int.MAX_VALUE

    private fun prettyFileName(name: String) =
        name.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').replace(Regex(" 64kb$"), "")

    /** The Archive returns a field as a string or an array of strings. */
    private fun firstString(o: JSONObject, key: String): String = when (val v = o.opt(key)) {
        is JSONArray -> if (v.length() > 0) v.optString(0) else ""
        null, JSONObject.NULL -> ""
        else -> v.toString()
    }

    private fun joinStrings(o: JSONObject, key: String): String = when (val v = o.opt(key)) {
        is JSONArray -> (0 until v.length()).map { v.optString(it).trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", ")
        null, JSONObject.NULL -> ""
        else -> v.toString().trim()
    }

    /** Drops "(LibriVox)" style tags from titles. */
    private fun cleanTitle(t: String) = t.replace(Regex("\\s*\\((?i:librivox)\\)\\s*"), " ").trim()
}
