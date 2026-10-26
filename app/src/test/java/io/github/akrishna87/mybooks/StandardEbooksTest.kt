package io.github.akrishna87.mybooks

import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Kind
import io.github.akrishna87.mybooks.catalog.ResultPage
import io.github.akrishna87.mybooks.catalog.Source
import io.github.akrishna87.mybooks.catalog.StandardEbooks
import io.github.akrishna87.mybooks.catalog.mergeEbooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Parses markup copied from standardebooks.org's search page and a book page. */
class StandardEbooksTest {
    private val list = """
        <ol class="ebooks-list grid">
            <li typeof="schema:Book" about="/ebooks/jane-austen/pride-and-prejudice">
            <div class="thumbnail-container" aria-hidden="true">	<a href="/ebooks/jane-austen/pride-and-prejudice" tabindex="-1" property="schema:url">
                <picture>
                <source srcset="/images/covers/jane-austen_pride-and-prejudice/495dd/cover@2x.avif 2x" type="image/avif"/>
                <img src="/images/covers/jane-austen_pride-and-prejudice/495dd/cover@2x.jpg" alt="" property="schema:image" height="335" width="224" loading="lazy"/>
                </picture>
            </a></div>
            <p><a href="/ebooks/jane-austen/pride-and-prejudice" property="schema:url"><span property="schema:name">Pride and Prejudice</span></a></p>
            <p class="author" typeof="schema:Person" property="schema:author" resource="/ebooks/jane-austen"><a href="https://standardebooks.org/ebooks/jane-austen" property="schema:url"><span property="schema:name">Jane Austen</span></a></p>
            </li>
            <li typeof="schema:Book" about="/ebooks/leo-tolstoy/war-and-peace/louise-maude_aylmer-maude">
            <img src="/images/covers/x/cover@2x.jpg" alt=""/>
            <p><a href="/ebooks/leo-tolstoy/war-and-peace/louise-maude_aylmer-maude"><span property="schema:name">War and Peace</span></a></p>
            <p class="author" typeof="schema:Person" property="schema:author" resource="/ebooks/leo-tolstoy"><a href="#"><span property="schema:name">Leo Tolstoy</span></a></p>
            </li>
        </ol>
    """.trimIndent()

    @Test
    fun searchResults() {
        val page = StandardEbooks.parseList(list)
        assertEquals(2, page.books.size)
        assertFalse(page.hasMore) // fewer than a full page
        val b = page.books[0]
        assertEquals("standardebooks:jane-austen/pride-and-prejudice", b.id)
        assertEquals(Source.STANDARD_EBOOKS, b.source)
        assertEquals(Kind.EBOOK, b.kind)
        assertEquals("Pride and Prejudice", b.title)
        assertEquals("Jane Austen", b.author)
        assertEquals("https://standardebooks.org/images/covers/jane-austen_pride-and-prejudice/495dd/cover@2x.jpg", b.coverUrl)
        assertEquals("https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice/downloads/jane-austen_pride-and-prejudice.epub?source=download", b.fileUrl)
        // Translations have the translators in the path, and in the file name.
        assertEquals(
            "https://standardebooks.org/ebooks/leo-tolstoy/war-and-peace/louise-maude_aylmer-maude/downloads/leo-tolstoy_war-and-peace_louise-maude_aylmer-maude.epub?source=download",
            page.books[1].fileUrl,
        )
    }

    @Test
    fun description() {
        val html = """<meta content="Free epub ebook download of the Standard Ebooks edition of Pride and Prejudice: A Regency-era novel of manners in which five women try to adjust to their new neighbor, an eligible gentleman." name="description"/>"""
        assertEquals("A Regency-era novel of manners in which five women try to adjust to their new neighbor, an eligible gentleman.", StandardEbooks.parseDescription(html))
    }

    @Test
    fun mergeShowsEachBookOnceStandardEbooksFirst() {
        fun pg(id: String, title: String, author: String) = Book("gutenberg:$id", Kind.EBOOK, Source.GUTENBERG, title, author, null)
        val se = StandardEbooks.parseList(list)
        val gutenberg = ResultPage(
            listOf(
                pg("1342", "Pride and Prejudice", "Jane Austen"),
                pg("2600", "War and Peace", "graf Leo Tolstoy"),
                pg("42671", "Pride and Prejudice: A play", "Jane Austen, Mrs. Steele MacKaye"),
                pg("158", "Emma", "Jane Austen"),
            ),
            hasMore = true,
        )
        val merged = mergeEbooks(se, gutenberg)
        assertEquals(
            listOf("standardebooks:jane-austen/pride-and-prejudice", "standardebooks:leo-tolstoy/war-and-peace/louise-maude_aylmer-maude", "gutenberg:2600", "gutenberg:158"),
            merged.books.map { it.id },
        )
        assertEquals(true, merged.hasMore)
        // One source failing still shows the other's books.
        assertEquals(4, mergeEbooks(null, gutenberg).books.size)
    }
}
