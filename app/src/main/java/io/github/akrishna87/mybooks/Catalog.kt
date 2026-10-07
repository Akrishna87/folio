package io.github.akrishna87.mybooks

import io.github.akrishna87.mybooks.catalog.AudioDetails
import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Gutenberg
import io.github.akrishna87.mybooks.catalog.Kind
import io.github.akrishna87.mybooks.catalog.LibriVox
import io.github.akrishna87.mybooks.catalog.Query
import io.github.akrishna87.mybooks.catalog.ResultPage
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
            Kind.EBOOK -> gutenberg(
                { Gutenberg.parseGutendex(Http.get(Gutenberg.searchUrl(query, page), 20_000)) },
                { Gutenberg.parseOpds(Http.get(Gutenberg.opdsSearchUrl(query, page))) },
            )
        }
    }

    suspend fun popular(kind: Kind): List<Book> = withContext(Dispatchers.IO) {
        when (kind) {
            Kind.AUDIOBOOK -> LibriVox.parseSearch(Http.get(LibriVox.popularUrl(1)), 1).books
            Kind.EBOOK -> gutenberg(
                { Gutenberg.parseGutendex(Http.get(Gutenberg.popularUrl(1), 20_000)).books },
                { Gutenberg.parseOpds(Http.get(Gutenberg.opdsPopularUrl(1))).books },
            )
        }
    }

    suspend fun audioDetails(book: Book): AudioDetails = withContext(Dispatchers.IO) {
        LibriVox.parseMetadata(book.sourceId, Http.get(LibriVox.metadataUrl(book.sourceId)))
    }

    fun textOnlyEpub(url: String): String? = Gutenberg.textOnlyEpub(url)

    /** Fills in what an ebook found through the fallback catalog is missing (its summary). */
    suspend fun ebookDetails(book: Book): Book? = withContext(Dispatchers.IO) {
        try {
            Gutenberg.parseGutendexBook(JSONObject(Http.get(Gutenberg.bookUrl(book.sourceId), 15_000)))
        } catch (e: Exception) {
            null
        }
    }
}
