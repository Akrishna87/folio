package io.github.akrishna87.mybooks

import io.github.akrishna87.mybooks.catalog.AudioDetails
import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Gutenberg
import io.github.akrishna87.mybooks.catalog.Kind
import io.github.akrishna87.mybooks.catalog.LibriVox
import io.github.akrishna87.mybooks.catalog.Query
import io.github.akrishna87.mybooks.catalog.ResultPage
import io.github.akrishna87.mybooks.catalog.Source
import io.github.akrishna87.mybooks.catalog.StandardEbooks
import io.github.akrishna87.mybooks.catalog.mergeEbooks
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Searches the free catalogs. Everything here runs off the main thread. */
object Catalog {
    /** When Gutendex last failed; for a while after that, go straight to Gutenberg's own catalog. */
    @Volatile private var gutendexFailedAt = 0L

    private inline fun <T> gutenberg(primary: () -> T, fallback: () -> T): T {
        if (System.currentTimeMillis() - gutendexFailedAt < 10 * 60_000) return fallback()
        return try {
            primary()
        } catch (e: Exception) {
            gutendexFailedAt = System.currentTimeMillis()
            fallback()
        }
    }

    suspend fun search(kind: Kind, query: Query, page: Int): ResultPage = withContext(Dispatchers.IO) {
        when (kind) {
            Kind.AUDIOBOOK -> LibriVox.parseSearch(Http.get(LibriVox.searchUrl(query, page)), page)
            Kind.EBOOK -> bothEbookSources(
                // Standard Ebooks searches titles and authors, not subjects.
                standard = { if (query.isSubject) ResultPage(emptyList(), false) else StandardEbooks.parseList(Http.get(StandardEbooks.searchUrl(query, page))) },
                gutenberg = {
                    gutenberg(
                        { Gutenberg.parseGutendex(Http.get(Gutenberg.searchUrl(query, page), 20_000)) },
                        { Gutenberg.parseOpds(Http.get(Gutenberg.opdsSearchUrl(query, page))) },
                    )
                },
            )
        }
    }

    suspend fun popular(kind: Kind): List<Book> = withContext(Dispatchers.IO) {
        when (kind) {
            Kind.AUDIOBOOK -> LibriVox.parseSearch(Http.get(LibriVox.popularUrl(1)), 1).books
            Kind.EBOOK -> bothEbookSources(
                standard = { StandardEbooks.parseList(Http.get(StandardEbooks.popularUrl(1))) },
                gutenberg = {
                    gutenberg(
                        { Gutenberg.parseGutendex(Http.get(Gutenberg.popularUrl(1), 20_000)) },
                        { Gutenberg.parseOpds(Http.get(Gutenberg.opdsPopularUrl(1))) },
                    )
                },
            ).books
        }
    }

    /**
     * Asks Standard Ebooks and Project Gutenberg at once. Standard Ebooks' editions (proofread and
     * typeset) come first; a book Gutenberg also has is shown once, in its Standard Ebooks edition.
     * One source being down doesn't stop the other's books showing.
     */
    private suspend fun bothEbookSources(standard: () -> ResultPage, gutenberg: () -> ResultPage): ResultPage = coroutineScope {
        val se = async { runCatching(standard) }
        val pg = async { runCatching(gutenberg) }
        val a = se.await()
        val b = pg.await()
        if (a.isFailure && b.isFailure) throw b.exceptionOrNull()!!
        mergeEbooks(a.getOrNull(), b.getOrNull())
    }

    suspend fun audioDetails(book: Book): AudioDetails = withContext(Dispatchers.IO) {
        LibriVox.parseMetadata(book.sourceId, Http.get(LibriVox.metadataUrl(book.sourceId)))
    }

    fun textOnlyEpub(url: String): String? = Gutenberg.textOnlyEpub(url)

    /** Fills in what a search result is missing (its summary). */
    suspend fun ebookDetails(book: Book): Book? = withContext(Dispatchers.IO) {
        try {
            when (book.source) {
                Source.STANDARD_EBOOKS -> book.copy(description = StandardEbooks.parseDescription(Http.get(StandardEbooks.pageUrl(book.sourceId), 15_000)))
                else -> Gutenberg.parseGutendexBook(JSONObject(Http.get(Gutenberg.bookUrl(book.sourceId), 15_000)))
            }
        } catch (e: Exception) {
            null
        }
    }
}
