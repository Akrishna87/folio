package io.github.akrishna87.mybooks

import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Gutenberg
import io.github.akrishna87.mybooks.catalog.Kind
import io.github.akrishna87.mybooks.catalog.LibriVox
import io.github.akrishna87.mybooks.catalog.Query
import io.github.akrishna87.mybooks.catalog.Source
import io.github.akrishna87.mybooks.catalog.formatDuration
import io.github.akrishna87.mybooks.catalog.htmlToText
import io.github.akrishna87.mybooks.catalog.parseLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parses responses shaped like the real catalogs' (trimmed down). */
class CatalogTest {

    @Test
    fun gutendexResults() {
        val json = """
            {"count": 2, "next": "https://gutendex.com/books/?page=2&search=pride", "previous": null,
             "results": [
              {"id": 1342, "title": "Pride and Prejudice",
               "authors": [{"name": "Austen, Jane", "birth_year": 1775, "death_year": 1817}],
               "summaries": ["A novel about the Bennet family."],
               "languages": ["en"], "copyright": false, "media_type": "Text",
               "formats": {"text/html": "https://www.gutenberg.org/ebooks/1342.html.images",
                           "application/epub+zip": "https://www.gutenberg.org/ebooks/1342.epub3.images",
                           "image/jpeg": "https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg"},
               "download_count": 50000},
              {"id": 10, "title": "Some Recording", "authors": [], "languages": ["en"],
               "formats": {"audio/mpeg": "https://www.gutenberg.org/files/10/10.mp3"}, "download_count": 3}
             ]}
        """.trimIndent()
        val page = Gutenberg.parseGutendex(json)
        assertTrue(page.hasMore)
        assertEquals(1, page.books.size) // the one without an EPUB is left out
        val b = page.books[0]
        assertEquals("gutenberg:1342", b.id)
        assertEquals(Kind.EBOOK, b.kind)
        assertEquals("Pride and Prejudice", b.title)
        assertEquals("Jane Austen", b.author)
        assertEquals("English", b.language)
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", b.fileUrl)
        assertEquals("https://www.gutenberg.org/cache/epub/1342/pg1342.cover.medium.jpg", b.coverUrl)
        assertEquals("A novel about the Bennet family.", b.description)
    }

    @Test
    fun textOnlyEditionOfIllustratedEbooks() {
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub.noimages", Gutenberg.textOnlyEpub("https://www.gutenberg.org/ebooks/1342.epub3.images"))
        assertEquals("https://www.gutenberg.org/ebooks/84.epub.noimages", Gutenberg.textOnlyEpub("https://www.gutenberg.org/ebooks/84.epub.images"))
        assertEquals(null, Gutenberg.textOnlyEpub("https://www.gutenberg.org/ebooks/1342.epub.noimages"))
        assertEquals(null, Gutenberg.textOnlyEpub("https://example.org/ebooks/1342.epub3.images"))
    }

    @Test
    fun gutenbergOpdsFallback() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/">
              <id>https://www.gutenberg.org/ebooks/search.opds/?query=pride</id>
              <title>Books: pride</title>
              <link rel="next" type="application/atom+xml;profile=opds-catalog" href="/ebooks/search.opds/?query=pride&amp;start_index=26"/>
              <entry>
                <title>Sort Alphabetically by Title</title>
                <id>https://www.gutenberg.org/ebooks/search.opds/?query=pride&amp;sort_order=title</id>
                <link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/search.opds/?query=pride&amp;sort_order=title"/>
              </entry>
              <entry>
                <title>Pride and Prejudice</title>
                <content type="text">Jane Austen</content>
                <id>https://www.gutenberg.org/ebooks/1342.opds</id>
                <link type="application/atom+xml;profile=opds-catalog" rel="subsection" href="/ebooks/1342.opds"/>
                <link type="image/jpeg" rel="http://opds-spec.org/image/thumbnail" href="/cache/epub/1342/pg1342.cover.small.jpg"/>
              </entry>
            </feed>
        """.trimIndent()
        val page = Gutenberg.parseOpds(xml)
        assertTrue(page.hasMore)
        assertEquals(listOf("gutenberg:1342"), page.books.map { it.id })
        assertEquals("Jane Austen", page.books[0].author)
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", page.books[0].fileUrl)
    }

    @Test
    fun archiveSearch() {
        val json = """
            {"responseHeader": {"status": 0},
             "response": {"numFound": 45, "start": 0, "docs": [
               {"identifier": "pride_and_prejudice_librivox", "title": "Pride and Prejudice",
                "creator": "Jane Austen", "description": "<p>One of the <b>most</b> popular novels &amp; more.</p>",
                "downloads": 123456, "language": "eng"},
               {"identifier": "short_poetry_001", "title": ["Short Poetry Collection 001"],
                "creator": ["Various", "Various"], "downloads": 50}
             ]}}
        """.trimIndent()
        val page = LibriVox.parseSearch(json, 1)
        assertTrue(page.hasMore)
        assertEquals(2, page.books.size)
        val b = page.books[0]
        assertEquals("librivox:pride_and_prejudice_librivox", b.id)
        assertEquals(Kind.AUDIOBOOK, b.kind)
        assertEquals(Source.LIBRIVOX, b.source)
        assertEquals("Jane Austen", b.author)
        assertEquals("One of the most popular novels & more.", b.description)
        assertEquals("English", b.language)
        assertEquals("https://archive.org/services/img/pride_and_prejudice_librivox", b.coverUrl)
        assertEquals("Various", page.books[1].author)
        assertEquals("Short Poetry Collection 001", page.books[1].title)
        assertFalse(LibriVox.parseSearch(json, 2).hasMore)
    }

    @Test
    fun archiveQueryIsEscaped() {
        assertEquals("(title:(pride AND prejudice) OR creator:(pride AND prejudice))", LibriVox.clauseFor(Query("Pride & Prejudice")))
        assertEquals("(subject:(science AND fiction) OR description:(science AND fiction))", LibriVox.clauseFor(Query("Science fiction", isSubject = true)))
        assertEquals(null, LibriVox.clauseFor(Query("  \"*\" ")))
        val url = LibriVox.searchUrl(Query("war: peace"), 2)
        assertTrue(url, url.startsWith("https://archive.org/advancedsearch.php?q="))
        assertTrue(url, url.contains("page=2") && url.contains("output=json") && url.contains("fl%5B%5D=identifier"))
    }

    @Test
    fun archiveChapters() {
        val json = """
            {"metadata": {"identifier": "pp", "title": "Pride and Prejudice", "description": "Read by volunteers.<br>Enjoy"},
             "files": [
               {"name": "pp_02_austen.mp3", "source": "original", "format": "128Kbps MP3", "track": "02", "title": "Chapter 2", "length": "600.5", "size": "9600000"},
               {"name": "pp_02_austen_64kb.mp3", "source": "derivative", "format": "64Kbps MP3", "track": "2/3", "title": "Chapter 2", "length": "10:00", "size": "4800000"},
               {"name": "pp_01_austen_64kb.mp3", "source": "derivative", "format": "64Kbps MP3", "track": "01", "title": "Chapter 1", "length": "300.0", "size": "2400000"},
               {"name": "pp_03 austen_64kb.mp3", "source": "derivative", "format": "64Kbps MP3", "length": "1:00:00", "size": "1000"},
               {"name": "pp_cover.jpg", "format": "JPEG"}
             ]}
        """.trimIndent()
        val d = LibriVox.parseMetadata("pp", json)
        assertEquals(listOf("Chapter 1", "Chapter 2", "pp 03 austen"), d.chapters.map { it.title })
        // File names decide the order, even when the track tags disagree.
        val tagged = LibriVox.parseMetadata("x", """{"files": [
            {"name": "b_10_a_64kb.mp3", "format": "64Kbps MP3", "track": "1", "title": "Ten"},
            {"name": "b_2_a_64kb.mp3", "format": "64Kbps MP3", "track": "3", "title": "Two"},
            {"name": "b_01_a_64kb.mp3", "format": "64Kbps MP3", "track": "2", "title": "One"}]}""")
        assertEquals(listOf("One", "Two", "Ten"), tagged.chapters.map { it.title })
        assertEquals("https://archive.org/download/pp/pp_01_austen_64kb.mp3", d.chapters[0].url)
        assertEquals("https://archive.org/download/pp/pp_03%20austen_64kb.mp3", d.chapters[2].url)
        assertEquals(600.0, d.chapters[1].durationSec, 0.01)
        assertEquals(3600.0, d.chapters[2].durationSec, 0.01)
        assertEquals(7_201_000L, d.totalBytes)
        assertEquals("Read by volunteers.\nEnjoy", d.description)
    }

    @Test
    fun bookSurvivesJson() {
        val b = Book("gutenberg:1", Kind.EBOOK, Source.GUTENBERG, "T", "A", null, fileUrl = "https://x/1.epub")
        assertEquals(b, Book.fromJson(org.json.JSONObject(b.toJson().toString())))
    }

    @Test
    fun helpers() {
        assertEquals(1234.5, parseLength("1234.5"), 0.001)
        assertEquals(3723.0, parseLength("1:02:03"), 0.001)
        assertEquals("11 h 39 min", formatDuration(41972.0))
        assertEquals("24 min", formatDuration(1440.0))
        assertEquals("Tom & Jerry — “hi”", htmlToText("<p>Tom &amp; Jerry &mdash; &ldquo;hi&rdquo;</p>"))
    }
}
