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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Searches the free catalogs. Everything here runs off the main thread. */
object Catalog {
    /**
     * How long to wait for Gutendex before also asking Project Gutenberg's own catalog. Gutendex
     * is often very slow, or accepts the connection and never answers; Gutenberg's OPDS feed
     * usually answers in well under a second.
     */
    private const val GUTENDEX_PATIENCE_MS = 3_500L
    /** After Gutendex fails or is slow, go straight to Gutenberg's own catalog for this long. */
    private const val GUTENDEX_RETRY_AFTER_MS = 30 * 60_000L

    /** When Gutendex last failed or was too slow. Kept across launches (see [init]). */
    @Volatile private var gutendexFailedAt = 0L
    private var prefs: android.content.SharedPreferences? = null

    /**
     * Requests whose answer may arrive after nobody is waiting for it any more. Blocking reads
     * can't be cancelled, so they run here instead of holding up the caller.
     */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: android.content.Context) {
        val p = context.getSharedPreferences("catalog", android.content.Context.MODE_PRIVATE)
        prefs = p
        gutendexFailedAt = p.getLong("gutendex_failed_at", 0L)
    }

    private fun gutendexDown() = System.currentTimeMillis() - gutendexFailedAt < GUTENDEX_RETRY_AFTER_MS

    private fun gutendexFailed() {
        gutendexFailedAt = System.currentTimeMillis()
        prefs?.edit()?.putLong("gutendex_failed_at", gutendexFailedAt)?.apply()
    }

    /**
     * Asks Gutendex, but if it hasn't answered within [GUTENDEX_PATIENCE_MS] asks Gutenberg's
     * own catalog too and takes whichever answers first. While Gutendex is known to be down,
     * goes straight to the fallback.
     */
    private suspend fun <T> gutenberg(primary: () -> T, fallback: () -> T): T {
        if (gutendexDown()) return fallback()
        val first = background.async { runCatching(primary).onFailure { gutendexFailed() } }
        val quick = withTimeoutOrNull(GUTENDEX_PATIENCE_MS) { first.await() }
        if (quick != null) return quick.getOrElse { fallback() }
        gutendexFailed() // too slow to wait for next time
        val second = background.async { runCatching(fallback) }
        val winner = select {
            first.onAwait { it }
            second.onAwait { it }
        }
        if (winner.isSuccess) return winner.getOrThrow()
        // The first to answer failed: wait for the other one.
        val both = listOf(first.await(), second.await())
        return (both.firstOrNull { it.isSuccess } ?: winner).getOrThrow()
    }

    suspend fun search(kind: Kind, query: Query, page: Int): ResultPage = withContext(Dispatchers.IO) {
        when (kind) {
            Kind.AUDIOBOOK -> LibriVox.parseSearch(Http.get(LibriVox.searchUrl(query, page)), page)
            Kind.EBOOK -> bothEbookSources(
                // Standard Ebooks searches titles and authors, not subjects.
                standard = { if (query.isSubject) ResultPage(emptyList(), false) else StandardEbooks.parseList(Http.get(StandardEbooks.searchUrl(query, page))) },
                gutenberg = {
                    gutenberg(
                        { Gutenberg.parseGutendex(Http.get(Gutenberg.searchUrl(query, page), 12_000)) },
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
                        { Gutenberg.parseGutendex(Http.get(Gutenberg.popularUrl(1), 12_000)) },
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
    private suspend fun bothEbookSources(standard: () -> ResultPage, gutenberg: suspend () -> ResultPage): ResultPage = coroutineScope {
        val se = async { runCatching(standard) }
        val pg = async { runCatching { gutenberg() } }
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
                // Only a summary, so don't wait long for it, nor ask while Gutendex is down.
                else -> if (gutendexDown()) null else try {
                    Gutenberg.parseGutendexBook(JSONObject(Http.get(Gutenberg.bookUrl(book.sourceId), 8_000)))
                } catch (e: Exception) {
                    gutendexFailed()
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
}
