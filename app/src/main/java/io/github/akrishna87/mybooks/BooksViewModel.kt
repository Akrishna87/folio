package io.github.akrishna87.mybooks

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.akrishna87.mybooks.catalog.AudioDetails
import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Kind
import io.github.akrishna87.mybooks.catalog.Query
import io.github.akrishna87.mybooks.catalog.formatBytes
import io.github.akrishna87.mybooks.epub.ReaderFont
import io.github.akrishna87.mybooks.epub.ReaderSettings
import io.github.akrishna87.mybooks.epub.ReaderTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Section { HOME, SEARCH, SHELF }

enum class SearchFilter(val label: String) { ALL("All"), AUDIOBOOKS("Audiobooks"), EBOOKS("Ebooks") }

sealed interface Screen {
    data class BookPage(val book: Book) : Screen
    data class Reader(val bookId: String) : Screen
}

/** Something loaded from the internet. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Failed(val message: String) : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
}

data class Results(
    val books: List<Book> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Above this, an illustrated ebook is swapped for its text-only edition. */
const val LARGE_EPUB = 15_000_000L

val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)

/** A friendly message for a failed request. */
fun friendlyError(e: Throwable): String = when {
    e is java.net.UnknownHostException -> "No internet connection"
    e is java.net.SocketTimeoutException -> "The catalog is taking too long to answer"
    else -> "Couldn't reach the catalog"
}

class BooksViewModel(app: Application) : AndroidViewModel(app) {

    private val context get() = getApplication<Application>()
    private val prefs = app.getSharedPreferences("ui", 0)

    // ----- Navigation -----
    val screens = mutableStateListOf<Screen>()
    var section by mutableStateOf(Section.HOME); private set
    var showPlayer by mutableStateOf(false)
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    fun say(text: String) {
        messageChannel.trySend(text)
    }

    fun selectSection(s: Section) {
        showPlayer = false
        screens.clear()
        section = s
    }

    fun open(screen: Screen) {
        showPlayer = false
        screens += screen
    }

    fun openBook(book: Book) = open(Screen.BookPage(book))

    fun back(): Boolean = when {
        showPlayer -> { showPlayer = false; true }
        screens.isNotEmpty() -> { screens.removeAt(screens.lastIndex); true }
        else -> false
    }

    // ----- Home -----
    var popularAudio by mutableStateOf<Load<List<Book>>>(Load.Loading); private set
    var popularEbooks by mutableStateOf<Load<List<Book>>>(Load.Loading); private set

    fun loadHome() {
        if (popularAudio !is Load.Ready) viewModelScope.launch {
            popularAudio = Load.Loading
            popularAudio = try { Load.Ready(Catalog.popular(Kind.AUDIOBOOK)) } catch (e: Exception) { Load.Failed(friendlyError(e)) }
        }
        if (popularEbooks !is Load.Ready) viewModelScope.launch {
            popularEbooks = Load.Loading
            popularEbooks = try { Load.Ready(Catalog.popular(Kind.EBOOK)) } catch (e: Exception) { Load.Failed(friendlyError(e)) }
        }
    }

    // ----- Search -----
    var query by mutableStateOf("")
    var activeQuery by mutableStateOf<Query?>(null); private set
    var filter by mutableStateOf(SearchFilter.ALL)
    var audioResults by mutableStateOf(Results()); private set
    var ebookResults by mutableStateOf(Results()); private set
    private val searchJobs = HashMap<Kind, Job>()

    fun results(kind: Kind) = if (kind == Kind.AUDIOBOOK) audioResults else ebookResults

    private fun setResults(kind: Kind, r: Results) {
        if (kind == Kind.AUDIOBOOK) audioResults = r else ebookResults = r
    }

    fun search(q: Query = Query(query)) {
        if (q.text.isBlank()) return
        if (q.isSubject) query = q.text
        activeQuery = q
        Kind.entries.forEach { kind ->
            setResults(kind, Results())
            load(kind, q, 1)
        }
    }

    fun clearSearch() {
        query = ""
        activeQuery = null
        searchJobs.values.forEach { it.cancel() }
        Kind.entries.forEach { setResults(it, Results()) }
    }

    fun loadMore(kind: Kind) {
        val q = activeQuery ?: return
        val r = results(kind)
        if (r.loading || !r.hasMore) return
        load(kind, q, r.page + 1)
    }

    fun retry(kind: Kind) {
        val q = activeQuery ?: return
        load(kind, q, results(kind).page + 1)
    }

    private fun load(kind: Kind, q: Query, page: Int) {
        searchJobs[kind]?.cancel()
        setResults(kind, results(kind).copy(loading = true, error = null))
        searchJobs[kind] = viewModelScope.launch {
            try {
                val found = Catalog.search(kind, q, page)
                if (activeQuery != q) return@launch
                val before = results(kind).books
                val known = before.mapTo(HashSet()) { it.id }
                setResults(kind, Results(before + found.books.filter { it.id !in known }, page, found.hasMore))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (activeQuery == q) setResults(kind, results(kind).copy(loading = false, error = friendlyError(e)))
            }
        }
    }

    // ----- Book pages -----
    val audioDetails = mutableStateMapOf<String, Load<AudioDetails>>()
    /** Ebooks with their summary filled in, for ones found without it. */
    val ebookDetails = mutableStateMapOf<String, Book>()

    fun loadDetails(book: Book) {
        when (book.kind) {
            Kind.AUDIOBOOK -> {
                val saved = shelfItem(book.id)
                if (audioDetails[book.id] is Load.Ready) return
                if (saved != null && saved.chapters.isNotEmpty()) {
                    audioDetails[book.id] = Load.Ready(AudioDetails(saved.book.description, saved.chapters))
                    return
                }
                audioDetails[book.id] = Load.Loading
                viewModelScope.launch {
                    audioDetails[book.id] = try {
                        Load.Ready(Catalog.audioDetails(book))
                    } catch (e: Exception) {
                        Load.Failed(friendlyError(e))
                    }
                }
            }
            Kind.EBOOK -> {
                if (book.description.isNotEmpty() || book.id in ebookDetails) return
                viewModelScope.launch { Catalog.ebookDetails(book)?.let { ebookDetails[book.id] = it } }
            }
        }
    }

    // ----- Shelf -----
    var shelf by mutableStateOf(ShelfStore.load(app).sortedByDescending { maxOf(it.openedAt, it.addedAt) }); private set
    val downloads = mutableStateMapOf<String, DownloadState>()
    /** Ebooks being fetched before they open. */
    val fetching = mutableStateListOf<String>()
    /** How far each ebook download has got: (bytes so far, total bytes or -1). */
    val fetchProgress = mutableStateMapOf<String, Pair<Long, Long>>()
    private val fetchJobs = HashMap<String, Job>()
    /** Bumped whenever reading or listening progress may have moved, so progress bars refresh. */
    var progressTick by mutableIntStateOf(0); private set

    fun shelfItem(id: String): ShelfItem? = shelf.firstOrNull { it.id == id }

    private fun saveShelf(list: List<ShelfItem>) {
        shelf = list.sortedByDescending { maxOf(it.openedAt, it.addedAt) }
        ShelfStore.save(context, shelf)
    }

    private fun upsert(item: ShelfItem) = saveShelf(listOf(item) + shelf.filter { it.id != item.id })

    private fun update(id: String, change: (ShelfItem) -> ShelfItem) {
        val item = shelfItem(id) ?: return
        saveShelf(shelf.map { if (it.id == id) change(item) else it })
    }

    private fun ensureOnShelf(book: Book, details: AudioDetails? = null): ShelfItem {
        val existing = shelfItem(book.id)
        val described = if (details != null && details.description.length > book.description.length) book.copy(description = details.description) else book
        val item = existing?.copy(
            book = if (existing.book.description.length >= described.description.length) existing.book else described,
            chapters = existing.chapters.ifEmpty { details?.chapters.orEmpty() },
        ) ?: ShelfItem(described, details?.chapters.orEmpty())
        upsert(item)
        return item
    }

    fun toggleShelf(book: Book) {
        if (shelfItem(book.id) != null) {
            removeFromShelf(book.id)
        } else {
            val details = (audioDetails[book.id] as? Load.Ready<AudioDetails>)?.value
            ensureOnShelf(ebookDetails[book.id] ?: book, details)
            say("Saved to your shelf")
        }
    }

    fun removeFromShelf(id: String) {
        val item = shelfItem(id) ?: return
        AudioDownloads.cancel(context, item.downloadIds)
        BookFiles.delete(context, id)
        Progress.clear(context, id)
        downloads.remove(id)
        if (currentBookId == id) controller?.let { it.stop(); it.clearMediaItems() }
        saveShelf(shelf.filter { it.id != id })
        say("Removed “${item.book.title}” from your shelf")
    }

    private fun touch(id: String) = update(id) { it.copy(openedAt = System.currentTimeMillis()) }

    /** How far through a book you are, 0..1. */
    fun progressOf(item: ShelfItem): Float {
        if (progressTick < 0) return 0f // reading the tick makes progress bars refresh with it
        return when (item.book.kind) {
            Kind.EBOOK -> Progress.readingPercent(context, item.id)
            Kind.AUDIOBOOK -> {
                val (index, pos) = Progress.audio(context, item.id) ?: return 0f
                val total = item.chapters.sumOf { it.durationSec }
                if (total <= 0 || index !in item.chapters.indices) return 0f
                val done = item.chapters.take(index).sumOf { it.durationSec } + pos / 1000.0
                (done / total).toFloat().coerceIn(0f, 1f)
            }
        }
    }

    // ----- Downloads -----

    fun downloadAudio(book: Book) {
        val details = (audioDetails[book.id] as? Load.Ready<AudioDetails>)?.value
        val item = ensureOnShelf(book, details)
        if (item.chapters.isEmpty()) {
            say("Still loading the chapters. Try again in a moment.")
            return
        }
        val ids = try {
            AudioDownloads.start(context, item)
        } catch (e: Exception) {
            say("Couldn't start the download")
            return
        }
        upsert(item.copy(downloadIds = ids, downloaded = false))
        downloads[item.id] = DownloadState(0, ids.size, 0, item.chapters.sumOf { it.sizeBytes }, failed = false)
        say("Downloading “${book.title}”")
    }

    fun cancelDownload(id: String) {
        val item = shelfItem(id) ?: return
        AudioDownloads.cancel(context, item.downloadIds)
        BookFiles.delete(context, id)
        downloads.remove(id)
        upsert(item.copy(downloadIds = emptyList(), downloaded = false))
    }

    fun deleteDownload(id: String) {
        val item = shelfItem(id) ?: return
        cancelDownload(id)
        say("Removed the download of “${item.book.title}”")
        // If it's playing from the phone, keep going from the internet instead.
        if (currentBookId == id && item.book.kind == Kind.AUDIOBOOK) shelfItem(id)?.let { restartQueue(it) }
    }

    private suspend fun pollDownloads() {
        val active = shelf.filter { it.downloadIds.isNotEmpty() }
        for (item in active) {
            val state = withContext(Dispatchers.IO) { AudioDownloads.state(context, item.downloadIds) }
            downloads[item.id] = state
            when {
                state.complete -> {
                    upsert(item.copy(downloadIds = emptyList(), downloaded = true))
                    downloads.remove(item.id)
                    say("“${item.book.title}” is downloaded")
                    if (currentBookId == item.id) shelfItem(item.id)?.let { restartQueue(it) }
                }
                state.failed -> {
                    cancelDownload(item.id)
                    say("The download of “${item.book.title}” stopped. Check your connection and try again.")
                }
            }
        }
    }

    // ----- Ebooks -----

    fun openEbook(book: Book) {
        val full = ebookDetails[book.id] ?: book
        val file = BookFiles.epub(context, book.id)
        if (file.isFile) {
            ensureOnShelf(shelfItem(book.id)?.book ?: full)
            update(book.id) { it.copy(downloaded = true, openedAt = System.currentTimeMillis()) }
            open(Screen.Reader(book.id))
            return
        }
        val url = full.fileUrl ?: return say("This ebook has no EPUB to download")
        if (book.id in fetching) return
        fetching += book.id
        fetchProgress[book.id] = 0L to -1L
        fetchJobs[book.id] = viewModelScope.launch {
            try {
                var skippedBytes = 0L
                withContext(Dispatchers.IO) {
                    val scope = this
                    val report = { done: Long, total: Long ->
                        scope.ensureActive() // stops reading as soon as the download is cancelled
                        fetchProgress[book.id] = done to total
                    }
                    // Illustrated editions can be huge; past LARGE_EPUB, get the text-only one instead.
                    val textOnly = Catalog.textOnlyEpub(url)
                    val gotIllustrated = Http.download(url, file, accept = { size ->
                        val ok = textOnly == null || size <= LARGE_EPUB
                        if (!ok) skippedBytes = size
                        ok
                    }, onProgress = report)
                    if (!gotIllustrated && textOnly != null) Http.download(textOnly, file, onProgress = report)
                }
                if (skippedBytes > 0) {
                    say("Got the text-only edition: the illustrated one is ${formatBytes(skippedBytes)}")
                }
                ensureOnShelf(full)
                update(book.id) { it.copy(downloaded = true, openedAt = System.currentTimeMillis()) }
                if (screens.lastOrNull().let { it is Screen.BookPage && it.book.id == book.id }) open(Screen.Reader(book.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(
                    when {
                        e is java.net.UnknownHostException -> "No internet connection"
                        e is java.net.SocketTimeoutException -> "The download stalled. Check your connection and tap Read to try again."
                        e is Http.DownloadInterrupted -> "The connection kept dropping. Tap Read to try again."
                        // Say what actually went wrong, so a problem can be reported and fixed.
                        else -> "Couldn't download this ebook (${e.message ?: e.javaClass.simpleName}). Tap Read to try again."
                    },
                )
            } finally {
                fetching -= book.id
                fetchProgress.remove(book.id)
                fetchJobs.remove(book.id)
            }
        }
    }

    fun cancelEbookDownload(id: String) {
        fetchJobs[id]?.cancel()
        say("Download stopped")
    }

    fun deleteEbook(id: String) {
        BookFiles.delete(context, id)
        update(id) { it.copy(downloaded = false) }
        say("Removed the ebook from this phone")
    }

    fun saveReading(id: String, spine: Int, fraction: Float, overall: Float) {
        Progress.saveReading(context, id, spine, fraction, overall)
        progressTick++
    }

    var readerSettings by mutableStateOf(
        ReaderSettings(
            theme = ReaderTheme.entries.getOrElse(prefs.getInt("reader_theme", 0)) { ReaderTheme.LIGHT },
            font = ReaderFont.entries.getOrElse(prefs.getInt("reader_font", 0)) { ReaderFont.SERIF },
            textZoom = prefs.getInt("reader_zoom", 110),
        ),
    ); private set

    fun updateReaderSettings(s: ReaderSettings) {
        readerSettings = s
        prefs.edit()
            .putInt("reader_theme", s.theme.ordinal)
            .putInt("reader_font", s.font.ordinal)
            .putInt("reader_zoom", s.textZoom)
            .apply()
    }

    // ----- Audiobook player -----
    var currentBookId by mutableStateOf<String?>(null); private set
    var chapterIndex by mutableIntStateOf(0); private set
    var chapterCount by mutableIntStateOf(0); private set
    var chapterTitle by mutableStateOf(""); private set
    var isPlaying by mutableStateOf(false); private set
    var isBuffering by mutableStateOf(false); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var speed by mutableFloatStateOf(PlaybackService.savedSpeed(app)); private set
    /** Milliseconds until the sleep timer pauses, or 0. */
    var sleepLeftMs by mutableLongStateOf(0L); private set
    var sleepAtChapterEnd by mutableStateOf(false); private set
    /** A book that was asked to play before the player was ready. */
    private var pendingPlay: (() -> Unit)? = null

    val currentItem: ShelfItem? get() = currentBookId?.let { shelfItem(it) }

    private var controller: MediaController? = null
    private val controllerFuture = MediaController.Builder(
        app,
        SessionToken(app, ComponentName(app, PlaybackService::class.java)),
    ).buildAsync()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = syncFromPlayer()

        override fun onPlayerError(error: PlaybackException) {
            say("Couldn't play this part. Check your connection and press play to try again.")
        }
    }

    init {
        viewModelScope.launch {
            val c = try { controllerFuture.await() } catch (e: Exception) { return@launch }
            controller = c
            c.addListener(playerListener)
            syncFromPlayer()
            pendingPlay?.invoke()
            pendingPlay = null
        }
        viewModelScope.launch {
            var tick = 0
            while (true) {
                controller?.let {
                    positionMs = it.currentPosition.coerceAtLeast(0)
                    durationMs = it.duration.let { d -> if (d == C.TIME_UNSET || d < 0) 0 else d }
                }
                val until = SleepTimer.until(context)
                sleepLeftMs = if (until > 0) (until - System.currentTimeMillis()).coerceAtLeast(0) else 0
                sleepAtChapterEnd = SleepTimer.endOfChapter(context)
                if (++tick % 2 == 0) pollDownloads()
                if (tick % 10 == 0 && isPlaying) progressTick++
                delay(500)
            }
        }
        // Pick up downloads that finished while the app was closed.
        if (shelf.any { it.downloadIds.isNotEmpty() }) viewModelScope.launch { pollDownloads() }
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
    }

    private fun syncFromPlayer() {
        val c = controller ?: return
        val parsed = parseMediaId(c.currentMediaItem?.mediaId)
        if (parsed != null && parsed.first != currentBookId) progressTick++
        currentBookId = parsed?.first
        chapterIndex = parsed?.second ?: 0
        chapterCount = c.mediaItemCount
        chapterTitle = c.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
        isPlaying = c.isPlaying
        isBuffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady
        positionMs = c.currentPosition.coerceAtLeast(0)
        speed = c.playbackParameters.speed
    }

    /** Plays an audiobook from where you left off, or from the start of [chapter]. */
    fun playBook(book: Book, chapter: Int? = null) {
        val c = controller
        if (c == null) {
            pendingPlay = { playBook(book, chapter) }
            return
        }
        if (chapter == null && currentBookId == book.id && c.mediaItemCount > 0) {
            if (!c.isPlaying) resume(c)
            showPlayer = true
            return
        }
        val existing = shelfItem(book.id)
        if (existing != null && existing.chapters.isNotEmpty()) {
            startBook(c, existing, chapter)
            return
        }
        viewModelScope.launch {
            val details = when (val d = audioDetails[book.id]) {
                is Load.Ready -> d.value
                else -> try {
                    Catalog.audioDetails(book).also { audioDetails[book.id] = Load.Ready(it) }
                } catch (e: Exception) {
                    say(friendlyError(e))
                    return@launch
                }
            }
            if (details.chapters.isEmpty()) {
                say("This recording has no audio files to play")
                return@launch
            }
            startBook(c, ensureOnShelf(book, details), chapter)
        }
    }

    private fun startBook(c: MediaController, item: ShelfItem, chapter: Int?) {
        val saved = Progress.audio(context, item.id)
        val last = item.chapters.size - 1
        var (index, pos) = when {
            chapter != null -> chapter to 0L
            saved != null -> saved
            else -> 0 to 0L
        }
        // Finished books start again from the beginning.
        if (chapter == null && index >= last && pos > 0 && item.chapters.getOrNull(last)?.let { pos >= it.durationSec * 1000 - 2_000 } == true) {
            index = 0; pos = 0
        }
        c.setMediaItems(audioItems(context, item), index.coerceIn(0, last), pos)
        c.prepare()
        c.setPlaybackSpeed(PlaybackService.savedSpeed(context))
        c.play()
        touch(item.id)
        Progress.saveAudio(context, item.id, index.coerceIn(0, last), pos)
        showPlayer = true
    }

    /** Swaps the queue between streaming and downloaded files, keeping the place. */
    private fun restartQueue(item: ShelfItem) {
        val c = controller ?: return
        val playing = c.playWhenReady
        val index = c.currentMediaItemIndex
        val pos = c.currentPosition
        c.setMediaItems(audioItems(context, item), index.coerceIn(0, (item.chapters.size - 1).coerceAtLeast(0)), pos)
        c.prepare()
        if (playing) c.play()
    }

    private fun resume(c: MediaController) {
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else resume(c)
    }

    fun seekBack() = controller?.seekBack()
    fun seekForward() = controller?.seekForward()
    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun nextChapter() = controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    fun previousChapter() = controller?.let {
        // Like a CD player: back to the start of this part, or the one before if just started.
        if (it.currentPosition > 5_000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem()
    }

    fun jumpToChapter(index: Int) = controller?.let {
        it.seekTo(index, 0L)
        resume(it)
    }

    fun changeSpeed(value: Float) {
        PlaybackService.saveSpeed(context, value)
        speed = value
        controller?.setPlaybackSpeed(value)
    }

    /** Minutes until pausing; -1 for the end of the chapter, 0 to turn the timer off. */
    fun setSleep(minutes: Int) {
        SleepTimer.set(context, minutes)
        sleepAtChapterEnd = minutes < 0
        sleepLeftMs = if (minutes > 0) minutes * 60_000L else 0L
        say(
            when {
                minutes > 0 -> "Sleep timer: $minutes min"
                minutes < 0 -> "Pausing at the end of this part"
                else -> "Sleep timer off"
            },
        )
    }
}
