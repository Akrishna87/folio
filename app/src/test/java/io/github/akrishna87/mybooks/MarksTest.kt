package io.github.akrishna87.mybooks

import io.github.akrishna87.mybooks.epub.Bookmark
import io.github.akrishna87.mybooks.epub.Highlight
import io.github.akrishna87.mybooks.epub.HighlightColor
import io.github.akrishna87.mybooks.epub.Marks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksTest {
    private val b1 = Bookmark("b1", spine = 3, offset = 900, chapter = "CHAPTER III", snippet = "Not all that", createdAt = 1)
    private val b2 = Bookmark("b2", spine = 1, offset = 10, chapter = "CHAPTER I", snippet = "It is a truth", createdAt = 2)
    private val h1 = Highlight("h1", spine = 1, start = 0, end = 12, text = "It is a truth", chapter = "CHAPTER I", createdAt = 3)

    @Test
    fun keptInReadingOrderAndSurviveJson() {
        val m = Marks().withBookmark(b1).withBookmark(b2).withHighlight(h1)
        assertEquals(listOf("b2", "b1"), m.bookmarks.map { it.id })
        assertEquals(m, Marks.fromJson(JSONObject(m.toJson().toString())))
        assertEquals(listOf("b2"), m.withoutBookmark("b1").bookmarks.map { it.id })
        assertTrue(m.withoutHighlight("h1").highlights.isEmpty())
    }

    @Test
    fun chapterListsForTheReader() {
        val m = Marks().withBookmark(b1).withBookmark(b2).withHighlight(h1)
        assertEquals("[[0,12,\"h1\",\"yellow\",0]]", m.highlightsJs(1))
        assertEquals("[]", m.highlightsJs(3))
        assertEquals("[[900,\"b1\"]]", m.bookmarksJs(3))
    }

    @Test
    fun highlightColourAndNoteCanChange() {
        val m = Marks().withHighlight(h1).withHighlight(h1.copy(color = HighlightColor.BLUE, note = "Famous first line"))
        assertEquals(1, m.highlights.size)
        assertEquals(HighlightColor.BLUE, m.highlights[0].color)
        assertEquals(m, Marks.fromJson(JSONObject(m.toJson().toString())))
        // Highlights saved before colours existed come back yellow.
        val old = JSONObject("{\"id\":\"x\",\"spine\":0,\"start\":1,\"end\":2}")
        assertEquals(HighlightColor.YELLOW, Highlight.fromJson(old).color)
    }
}
