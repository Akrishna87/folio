package io.github.akrishna87.mybooks

import io.github.akrishna87.mybooks.epub.BookSearch
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSearchTest {
    private val html = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter I</title>
        <style>p { color: red }</style></head><body class="x">
        <h2>Chapter I</h2>
        <p>It is a truth <i>universally</i> acknowledged, that a single man &amp; a good
        fortune must be in want of a wife.</p><!-- a comment -->
        <p>&#8220;My dear Mr.&nbsp;Bennet,&#x201D; said his lady.</p>
        </body></html>"""

    @Test
    fun chapterTextIsWhatTheBrowserSees() {
        val text = BookSearch.chapterText(html)
        // The page title, styles and comments aren't part of the text; entities are decoded.
        assertEquals(false, text.contains("color: red"))
        assertEquals(false, text.contains("a comment"))
        assertEquals(true, text.contains("It is a truth universally acknowledged, that a single man & a good"))
        assertEquals(true, text.contains("“My dear Mr. Bennet,” said his lady."))
        assertEquals(1, text.split("Chapter I").size - 1)
    }

    @Test
    fun findIgnoresCaseAndLineBreaks() {
        val text = BookSearch.chapterText(html)
        val hits = BookSearch.hits(2, text, "GOOD fortune")
        assertEquals(1, hits.size)
        assertEquals(2, hits[0].spine)
        assertEquals("good\n        fortune", text.substring(hits[0].start, hits[0].end))
        assertEquals(true, hits[0].before.endsWith("single man & a "))
        assertEquals(true, hits[0].after.startsWith(" must be in want"))
        assertEquals(emptyList<IntRange>(), BookSearch.find(text, "   "))
        assertEquals(1, BookSearch.find(text, "a wife").size)
    }
}
