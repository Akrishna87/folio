package io.github.akrishna87.mybooks.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.StickyNote2
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.akrishna87.mybooks.BookFiles
import io.github.akrishna87.mybooks.BooksViewModel
import io.github.akrishna87.mybooks.MarksStore
import io.github.akrishna87.mybooks.Progress
import io.github.akrishna87.mybooks.catalog.encodePath
import io.github.akrishna87.mybooks.epub.BookSearch
import io.github.akrishna87.mybooks.epub.Bookmark
import io.github.akrishna87.mybooks.epub.Epub
import io.github.akrishna87.mybooks.epub.Highlight
import io.github.akrishna87.mybooks.epub.HighlightColor
import io.github.akrishna87.mybooks.epub.PageTurn
import io.github.akrishna87.mybooks.epub.SearchHit
import io.github.akrishna87.mybooks.epub.Marks
import io.github.akrishna87.mybooks.epub.ReaderFont
import io.github.akrishna87.mybooks.epub.ReaderSettings
import io.github.akrishna87.mybooks.epub.ReaderTheme
import io.github.akrishna87.mybooks.epub.ZipEpubFiles
import io.github.akrishna87.mybooks.epub.injectReader
import io.github.akrishna87.mybooks.epub.readerCss
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Chapters are served to the WebView from this made-up address, straight out of the EPUB file. */
private const val HOST = "book.local"
private const val BASE = "https://$HOST/"
private const val SCRIPT_PATH = "__reader/reader.js"

private fun parseColor(hex: String) = Color(android.graphics.Color.parseColor(hex))

/** Everything the reader needs while a book is open: the EPUB, where you are, and the WebView. */
internal class ReaderController(private val context: Context, private val vm: BooksViewModel, val bookId: String) {
    var epub by mutableStateOf<Epub?>(null)
    var error by mutableStateOf<String?>(null)
    var spineIndex by mutableIntStateOf(0)
    var page by mutableIntStateOf(0)
    var pages by mutableIntStateOf(0)
    var chrome by mutableStateOf(false)
    /** Bookmarks and highlights in this book. */
    var marks by mutableStateOf(Marks())
    /** Where the text on the current page starts, its first words, and a bookmark on it ("" if none). */
    var pageStart by mutableIntStateOf(0)
    var pageSnippet by mutableStateOf("")
    var pageBookmark by mutableStateOf("")
    var hasSelection by mutableStateOf(false)
    /** The highlight that was just tapped, for its colour / note / remove sheet. */
    var tappedHighlight by mutableStateOf<String?>(null)
    /** Search in the book: what was asked, what was found, and whether it's still looking. */
    var searchQuery by mutableStateOf("")
    var searchHits by mutableStateOf<List<SearchHit>>(emptyList())
    var searching by mutableStateOf(false)
    @Volatile var turnStyle = "slide"
    /** When a page last turned; reading time only counts while pages keep turning. */
    @Volatile var lastActivity = 0L

    var webView: WebView? = null
    /** A picture of the last page, shown while the next chapter loads behind it (see [turnTo]). */
    var cover: android.widget.ImageView? = null
    /** +1 or -1 while a chapter loads behind [cover]; 0 otherwise. */
    private var pendingTurn = 0
    private val revealTimeout = Runnable { reveal() }
    private var files: ZipEpubFiles? = null
    private val main = Handler(Looper.getMainLooper())

    /** Where the next chapter that loads should open: "" (first page), "end", or "f:<fraction>". */
    @Volatile private var start = ""
    @Volatile var css = ""

    suspend fun open() {
        try {
            val parsed = withContext(Dispatchers.IO) {
                val zip = ZipEpubFiles(BookFiles.epub(context, bookId))
                files = zip
                Epub.parse(zip)
            }
            marks = withContext(Dispatchers.IO) { MarksStore.load(context, bookId) }
            epub = parsed
        } catch (e: Exception) {
            error = "This book couldn't be opened. Try removing it from your phone and downloading it again."
        }
    }

    fun close() {
        webView?.destroy()
        webView = null
        try {
            files?.close()
        } catch (e: Exception) {
            // already closed
        }
    }

    fun openAtSavedPlace() {
        val book = epub ?: return
        val saved = Progress.reading(context, bookId)
        if (saved != null && saved.first in book.spine.indices) load(saved.first, "f:${saved.second}") else load(0)
    }

    fun load(index: Int, startAt: String = "") {
        val book = epub ?: return
        if (index !in book.spine.indices) return
        spineIndex = index
        start = startAt
        webView?.loadUrl(BASE + encodePath(book.spine[index].path))
    }

    /**
     * Turns into the next ([dir] = 1) or previous (-1) chapter like any other page: the page you
     * were on stays on screen while the chapter loads behind it, then slides (or fades) away.
     */
    private fun turnTo(index: Int, startAt: String, dir: Int) {
        val wv = webView
        val cv = cover
        if (wv == null || cv == null || wv.width == 0 || wv.height == 0) {
            load(index, startAt)
            return
        }
        pendingTurn = dir
        snapshot(wv) { picture ->
            if (picture != null && pendingTurn == dir) {
                cv.animate().cancel()
                cv.setImageBitmap(picture)
                cv.translationX = 0f
                cv.alpha = 1f
                cv.visibility = View.VISIBLE
                main.postDelayed(revealTimeout, 2_500) // a slow chapter shows anyway
            } else {
                pendingTurn = 0
            }
            load(index, startAt)
        }
    }

    /** The pixels the WebView is showing right now, or null. */
    private fun snapshot(wv: WebView, done: (android.graphics.Bitmap?) -> Unit) {
        val bitmap = try {
            android.graphics.Bitmap.createBitmap(wv.width, wv.height, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return done(null)
        }
        val window = context.findActivity()?.window
        if (window != null) {
            val at = IntArray(2)
            wv.getLocationInWindow(at)
            val rect = android.graphics.Rect(at[0], at[1], at[0] + wv.width, at[1] + wv.height)
            try {
                android.view.PixelCopy.request(window, rect, bitmap, { result ->
                    done(if (result == android.view.PixelCopy.SUCCESS) bitmap else drawn(wv, bitmap))
                }, main)
                return
            } catch (e: Exception) {
                // fall back to drawing it
            }
        }
        done(drawn(wv, bitmap))
    }

    private fun drawn(wv: WebView, bitmap: android.graphics.Bitmap): android.graphics.Bitmap? = try {
        wv.draw(android.graphics.Canvas(bitmap))
        bitmap
    } catch (e: Exception) {
        null
    }

    /** The new chapter is laid out: move the old page out of the way. */
    private fun reveal() {
        main.removeCallbacks(revealTimeout)
        val dir = pendingTurn
        pendingTurn = 0
        val cv = cover ?: return
        if (cv.visibility != View.VISIBLE) return
        val wv = webView
        val done = {
            cv.visibility = View.GONE
            cv.setImageDrawable(null)
            cv.translationX = 0f
            cv.alpha = 1f
            wv?.translationX = 0f
        }
        // Give the WebView a moment to paint the new page before uncovering it.
        cv.postDelayed({
            val w = cv.width.toFloat()
            val ease = android.view.animation.DecelerateInterpolator(1.6f)
            if (turnStyle == "none") {
                done()
            } else if (turnStyle == "slide" && dir != 0) {
                wv?.translationX = dir * w
                wv?.animate()?.translationX(0f)?.setDuration(300)?.setInterpolator(ease)?.start()
                cv.animate().translationX(-dir * w).setDuration(300).setInterpolator(ease).withEndAction(done).start()
            } else {
                cv.animate().alpha(0f).setDuration(200).withEndAction(done).start()
            }
        }, 60)
    }

    fun loadEntry(path: String, fragment: String) {
        val book = epub ?: return
        val index = book.spineIndexOf(path)
        if (index >= 0) spineIndex = index
        start = ""
        webView?.loadUrl(BASE + encodePath(path) + if (fragment.isNotEmpty()) "#$fragment" else "")
    }

    /** A link inside the book was followed. */
    fun followed(url: Uri) {
        val book = epub ?: return
        val index = book.spineIndexOf(url.path.orEmpty().trimStart('/'))
        if (index >= 0) spineIndex = index
        start = ""
    }

    private fun saveMarks(m: Marks) {
        marks = m
        MarksStore.save(context, bookId, m)
        webView?.evaluateJavascript("window.__reader && window.__reader.setBookmarks(${JSONObject.quote(m.bookmarksJs(spineIndex))})", null)
    }

    private fun newId() = java.util.UUID.randomUUID().toString()

    /** Bookmarks the current page, or removes the bookmark that's on it. */
    fun toggleBookmark() {
        val book = epub ?: return
        if (pageBookmark.isNotEmpty()) {
            saveMarks(marks.withoutBookmark(pageBookmark))
            vm.say("Bookmark removed")
        } else {
            saveMarks(marks.withBookmark(Bookmark(newId(), spineIndex, pageStart, book.chapterTitle(spineIndex), pageSnippet, System.currentTimeMillis())))
            vm.say("Bookmarked")
        }
    }

    /** The selected text and where it is, handed to [use] (on the main thread). */
    private fun withSelection(use: (start: Int, end: Int, text: String) -> Unit) {
        val wv = webView ?: return
        wv.evaluateJavascript("window.__reader ? window.__reader.selection() : null") { json ->
            val o = try { JSONObject(json) } catch (e: Exception) { null } ?: return@evaluateJavascript
            if (o.optInt("end") > o.optInt("start")) use(o.optInt("start"), o.optInt("end"), o.optString("text"))
        }
    }

    fun clearSelection() {
        webView?.evaluateJavascript("window.__reader && window.__reader.clearSelection()", null)
        hasSelection = false
    }

    /** Highlights the selected text in [color]; returns its id through [then] (to add a note, say). */
    fun highlightSelection(color: HighlightColor = HighlightColor.YELLOW, then: (String) -> Unit = {}) {
        val book = epub ?: return
        withSelection { start, end, text ->
            val h = Highlight(newId(), spineIndex, start, end, text, book.chapterTitle(spineIndex), System.currentTimeMillis(), color)
            marks = marks.withHighlight(h)
            MarksStore.save(context, bookId, marks)
            webView?.evaluateJavascript("window.__reader.mark(${h.start}, ${h.end}, ${JSONObject.quote(h.id)}, '${color.name.lowercase()}', false)", null)
            clearSelection()
            then(h.id)
        }
    }

    fun highlight(id: String): Highlight? = marks.highlights.firstOrNull { it.id == id }

    /** A new colour or note for a highlight. */
    fun updateHighlight(h: Highlight) {
        marks = marks.withHighlight(h)
        MarksStore.save(context, bookId, marks)
        webView?.evaluateJavascript(
            "window.__reader && window.__reader.recolor(${JSONObject.quote(h.id)}, '${h.color.name.lowercase()}', ${h.note.isNotBlank()})",
            null,
        )
    }

    fun removeHighlight(id: String) {
        marks = marks.withoutHighlight(id)
        MarksStore.save(context, bookId, marks)
        webView?.evaluateJavascript("window.__reader && window.__reader.unmark(${JSONObject.quote(id)})", null)
    }

    fun copySelection() = withSelection { _, _, text -> copy(text); clearSelection() }
    fun shareSelection() = withSelection { _, _, text -> share(text); clearSelection() }
    fun lookUpSelection() = withSelection { _, _, text -> lookUp(text); clearSelection() }

    fun copy(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Quote", text))
        vm.say("Copied")
    }

    /** Shares a quote with where it's from. */
    fun share(text: String) {
        val book = vm.shelfItem(bookId)?.book
        val from = listOfNotNull(book?.title ?: epub?.title, book?.author?.takeIf { it.isNotBlank() }).joinToString(", ")
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "“$text”" + if (from.isNotEmpty()) "\n— $from" else "")
        try {
            context.startActivity(Intent.createChooser(intent, "Share quote").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            vm.say("Nothing to share with")
        }
    }

    /** Looks a word up in Wiktionary, or searches the web for a phrase. */
    fun lookUp(text: String) {
        val words = text.trim().split(Regex("\\s+"))
        val url = if (words.size == 1) {
            "https://en.wiktionary.org/wiki/" + Uri.encode(words[0].trim('“', '”', '"', '\'', '‘', '’', ',', '.', ';', ':', '!', '?', '(', ')').lowercase())
        } else {
            "https://duckduckgo.com/?q=" + Uri.encode(text.trim())
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            vm.say("No browser to look it up in")
        }
    }

    /** Looks for [query] in every part of the book. */
    suspend fun search(query: String) {
        val book = epub ?: return
        searchQuery = query
        if (query.isBlank()) {
            searchHits = emptyList()
            return
        }
        searching = true
        val found = withContext(Dispatchers.IO) {
            val all = mutableListOf<SearchHit>()
            for ((i, item) in book.spine.withIndex()) {
                if (all.size >= 500) break
                val bytes = try { files?.read(item.path) } catch (e: Exception) { null } ?: continue
                all += BookSearch.hits(i, BookSearch.chapterText(String(bytes, Charsets.UTF_8)), query, 500 - all.size)
            }
            all
        }
        if (searchQuery == query) {
            searchHits = found
            searching = false
        }
    }

    fun openHit(hit: SearchHit) {
        if (hit.spine == spineIndex) webView?.evaluateJavascript("window.__reader && window.__reader.find(${hit.start}, ${hit.end})", null)
        else load(hit.spine, "s:${hit.start}-${hit.end}")
    }

    /** Opens the place [fraction] of the way through the whole book (from the slider). */
    fun jumpTo(fraction: Float) {
        val book = epub ?: return
        val n = book.spine.size
        val pos = (fraction.coerceIn(0f, 1f) * n).coerceAtMost(n - 0.0001f)
        val spine = pos.toInt()
        val within = pos - spine
        if (spine == spineIndex) webView?.evaluateJavascript("window.__reader && window.__reader.goToFraction($within)", null)
        else load(spine, "f:$within")
    }

    fun removeBookmark(id: String) = saveMarks(marks.withoutBookmark(id))

    /** Opens the page holding character [offset] of chapter [spine]. */
    fun goTo(spine: Int, offset: Int) {
        if (spine == spineIndex) webView?.evaluateJavascript("window.__reader && window.__reader.goTo($offset)", null)
        else load(spine, "c:$offset")
    }

    fun overall(): Float {
        val n = epub?.spine?.size ?: return 0f
        val within = if (pages > 0) (page + 1).toFloat() / pages else 0f
        return ((spineIndex + within) / n).coerceIn(0f, 1f)
    }

    fun intercept(url: Uri): WebResourceResponse = try {
        serve(url)
    } catch (e: Exception) {
        // A broken file in the book (or the book closing mid-load) shouldn't take the app down.
        android.util.Log.w("MyBooks", "Couldn't serve $url", e)
        notFound()
    }

    private fun serve(url: Uri): WebResourceResponse {
        if (url.host != HOST) return notFound() // the reader never loads anything from the internet
        val path = url.path.orEmpty().trimStart('/')
        if (path == SCRIPT_PATH) {
            return WebResourceResponse("text/javascript", "utf-8", context.assets.open("reader.js"))
        }
        val book = epub ?: return notFound()
        val bytes = files?.read(path) ?: return notFound()
        val mime = book.mediaTypes[path]?.takeIf { it.isNotEmpty() } ?: guessMime(path)
        if (mime.contains("html")) {
            val html = injectReader(String(bytes, Charsets.UTF_8), css, BASE + SCRIPT_PATH)
            return WebResourceResponse(mime, "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
        return WebResourceResponse(mime, null, ByteArrayInputStream(bytes))
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun guessMime(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "xhtml", "xht" -> "application/xhtml+xml"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "svg" -> "image/svg+xml"
        "webp" -> "image/webp"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    /** Called by reader.js, on WebView's own thread; everything is handed to the main thread. */
    inner class Bridge {
        @JavascriptInterface
        fun start(): String = start

        @JavascriptInterface
        fun highlights(): String = marks.highlightsJs(spineIndex)

        @JavascriptInterface
        fun bookmarks(): String = marks.bookmarksJs(spineIndex)

        @JavascriptInterface
        fun onSelection(selected: Boolean) {
            main.post { hasSelection = selected }
        }

        @JavascriptInterface
        fun onHighlightTap(id: String) {
            main.post { tappedHighlight = id }
        }

        @JavascriptInterface
        fun turnStyle(): String = this@ReaderController.turnStyle

        @JavascriptInterface
        fun onPage(p: Int, n: Int, startOffset: Int, bookmarkId: String, snippet: String) {
            lastActivity = System.currentTimeMillis()
            main.post {
                if (pendingTurn != 0) reveal()
                page = p
                pages = n
                pageStart = startOffset
                pageBookmark = bookmarkId
                pageSnippet = snippet
                val fraction = if (n > 1) p.toFloat() / (n - 1) else 0f
                vm.saveReading(bookId, spineIndex, fraction, overall())
            }
        }

        @JavascriptInterface
        fun next() {
            main.post {
                if (pendingTurn != 0) return@post // already turning
                val book = epub
                if (book != null && spineIndex < book.spine.lastIndex) {
                    turnTo(spineIndex + 1, "", 1)
                } else {
                    settle()
                    vm.reachedEnd(bookId)
                }
            }
        }

        @JavascriptInterface
        fun prev() {
            main.post {
                if (pendingTurn != 0) return@post
                if (spineIndex > 0) turnTo(spineIndex - 1, "end", -1) else settle()
            }
        }

        /** Nowhere to turn to: put the page back where it belongs. */
        private fun settle() {
            webView?.evaluateJavascript("window.__reader && window.__reader.settle()", null)
        }

        @JavascriptInterface
        fun toggleChrome() {
            main.post { chrome = !chrome }
        }
    }
}

/** Passes the WebView's text-selection callbacks through, but leaves the menu empty. */
private class EmptyMenu(private val inner: android.view.ActionMode.Callback) : android.view.ActionMode.Callback2() {
    override fun onCreateActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
        inner.onCreateActionMode(mode, menu)
        menu.clear()
        return true
    }

    override fun onPrepareActionMode(mode: android.view.ActionMode, menu: android.view.Menu): Boolean {
        inner.onPrepareActionMode(mode, menu)
        menu.clear()
        return true
    }

    override fun onActionItemClicked(mode: android.view.ActionMode, item: android.view.MenuItem) = inner.onActionItemClicked(mode, item)

    override fun onDestroyActionMode(mode: android.view.ActionMode) = inner.onDestroyActionMode(mode)

    override fun onGetContentRect(mode: android.view.ActionMode, view: View, outRect: android.graphics.Rect) {
        if (inner is android.view.ActionMode.Callback2) inner.onGetContentRect(mode, view, outRect) else super.onGetContentRect(mode, view, outRect)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ReaderScreen(vm: BooksViewModel, bookId: String) {
    val context = LocalContext.current
    val controller = remember(bookId) { ReaderController(context, vm, bookId) }
    val chosen = vm.readerSettings
    // "Match the phone": the Night theme while the phone is in dark mode.
    val phoneDark = androidx.compose.foundation.isSystemInDarkTheme()
    val style = if (chosen.autoNight && phoneDark && !chosen.theme.dark) chosen.copy(theme = ReaderTheme.DARK) else chosen
    val theme = style.theme
    val background = parseColor(theme.background)
    val textColor = parseColor(theme.text)
    var panel by remember { mutableStateOf<String?>(null) } // "toc", "style" or "search"
    var noteFor by remember { mutableStateOf<String?>(null) } // the highlight whose note is being written
    controller.css = readerCss(style)
    controller.turnStyle = style.pageTurn.name.lowercase()

    LaunchedEffect(bookId) { controller.open() }
    // Reading time for the daily goal: counted in 15-second steps while pages are turning
    // (a page turned in the last three minutes).
    LaunchedEffect(controller) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            if (System.currentTimeMillis() - controller.lastActivity < 180_000) vm.addReadingTime(15)
        }
    }
    DisposableEffect(controller) { onDispose { controller.close() } }
    BackHandler(enabled = panel != null) { panel = null }
    BackHandler(enabled = panel == null && controller.tappedHighlight != null) { controller.tappedHighlight = null }
    BackHandler(enabled = panel == null && controller.tappedHighlight == null && controller.hasSelection) { controller.clearSelection() }

    // Full screen while reading; the status bar comes back with the menu.
    val view = LocalView.current
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(window, controller.chrome, theme) {
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controller.chrome) insets?.show(WindowInsetsCompat.Type.systemBars()) else insets?.hide(WindowInsetsCompat.Type.systemBars())
        insets?.isAppearanceLightStatusBars = !theme.dark
        insets?.isAppearanceLightNavigationBars = !theme.dark
        onDispose {
            insets?.show(WindowInsetsCompat.Type.systemBars())
            insets?.isAppearanceLightStatusBars = false
            insets?.isAppearanceLightNavigationBars = false
        }
    }

    // New colours, font or size: restyle the open chapter in place.
    LaunchedEffect(style) {
        controller.webView?.let { wv ->
            wv.setBackgroundColor(background.toArgb())
            if (wv.settings.textZoom != style.textZoom) {
                wv.settings.textZoom = style.textZoom
            }
            wv.evaluateJavascript("window.__reader && window.__reader.setCss(${JSONObject.quote(controller.css)})", null)
            wv.evaluateJavascript("window.__reader && window.__reader.setTurn('${controller.turnStyle}')", null)
        }
    }

    Box(Modifier.fillMaxSize().background(background)) {
        val epub = controller.epub
        when {
            controller.error != null -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(controller.error.orEmpty(), color = textColor)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.back() }) { Text("Back", color = textColor) }
                    if (epub != null) Button(onClick = { controller.error = null }) { Text("Try again") }
                }
            }
            epub == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> AndroidView(
                modifier = Modifier.fillMaxSize().displayCutoutPadding(),
                onRelease = { frame ->
                    val wv = frame.getChildAt(0) as? WebView
                    if (controller.webView === wv) controller.webView = null
                    if (controller.cover === frame.getChildAt(1)) controller.cover = null
                    wv?.destroy()
                },
                factory = { ctx ->
                  android.widget.FrameLayout(ctx).apply {
                    clipChildren = true
                    addView(
                    // Selected text gets Folio's own toolbar (colours, note, copy, share, look up)
                    // instead of the system's menu. The system's action mode still starts (the
                    // WebView drops the selection if it can't), just with nothing in it.
                    object : WebView(ctx) {
                        override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode? =
                            if (callback != null && type == android.view.ActionMode.TYPE_FLOATING) super.startActionMode(EmptyMenu(callback), type)
                            else super.startActionMode(callback, type)
                    }.apply {
                        setBackgroundColor(background.toArgb())
                        isVerticalScrollBarEnabled = false
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        settings.textZoom = vm.readerSettings.textZoom
                        addJavascriptInterface(controller.Bridge(), "Reader")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                                controller.intercept(request.url)

                            // If the page's renderer dies (out of memory, say), Android would close the whole
                            // app unless we say we've handled it.
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                android.util.Log.w("MyBooks", "Reader renderer gone (crashed=${detail.didCrash()})")
                                if (controller.webView === view) controller.webView = null
                                controller.error = "The page couldn't be shown."
                                return true
                            }

                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                if (url.host == HOST) {
                                    controller.followed(url)
                                    return false
                                }
                                // Links out of the book open in the browser.
                                try {
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, url))
                                } catch (e: Exception) {
                                    vm.say("No browser to open the link with")
                                }
                                return true
                            }
                        }
                        controller.webView = this
                        // Load once the WebView has its real size, so the first layout of the
                        // chapter isn't done for a screen of the wrong height.
                        doOnLayout { controller.openAtSavedPlace() }
                    }, android.widget.FrameLayout.LayoutParams(-1, -1)) // fill the frame
                    // The last page of a chapter, shown over the reader while the next one loads.
                    addView(
                        android.widget.ImageView(ctx).apply {
                            visibility = View.GONE
                            scaleType = android.widget.ImageView.ScaleType.FIT_XY
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            controller.cover = this
                        },
                        android.widget.FrameLayout.LayoutParams(-1, -1),
                    )
                  }
                },
            )
        }

        if (epub != null) {
            // Where you are, always shown, quietly.
            Text(
                if (controller.pages > 0) "Page ${controller.page + 1} of ${controller.pages}" else "",
                color = textColor.copy(alpha = 0.55f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
            )
        }

        AnimatedVisibility(controller.chrome && epub != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(background)
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close book", tint = textColor) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            vm.shelfItem(bookId)?.book?.title ?: epub?.title.orEmpty(),
                            color = textColor,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val chapter = epub?.chapterTitle(controller.spineIndex).orEmpty()
                        if (chapter.isNotEmpty()) {
                            Text(chapter, color = textColor.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val bookmarked = controller.pageBookmark.isNotEmpty()
                    IconButton(onClick = controller::toggleBookmark) {
                        Icon(
                            if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            if (bookmarked) "Remove bookmark" else "Add bookmark",
                            tint = if (bookmarked) parseColor(theme.link) else textColor,
                        )
                    }
                    IconButton(onClick = { panel = "search" }) { Icon(Icons.Rounded.Search, "Search in book", tint = textColor) }
                    IconButton(onClick = { panel = "toc" }) { Icon(Icons.Rounded.FormatListNumbered, "Contents", tint = textColor) }
                    IconButton(onClick = { panel = "style" }) { Icon(Icons.Rounded.TextFields, "Text and colours", tint = textColor) }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(background)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    val overall = controller.overall()
                    // Drag to scrub through the whole book; it opens where you let go.
                    var scrub by remember { mutableStateOf<Float?>(null) }
                    val shown = scrub ?: overall
                    val parts = epub?.spine?.size ?: 1
                    val scrubPart = (shown * parts).toInt().coerceIn(0, parts - 1)
                    Text(
                        if (scrub != null) epub?.chapterTitle(scrubPart)?.ifEmpty { null } ?: "Part ${scrubPart + 1}"
                        else {
                            val left = controller.pages - controller.page - 1
                            when {
                                controller.pages <= 0 -> ""
                                left <= 0 -> "Last page in this part"
                                left == 1 -> "1 page left in this part"
                                else -> "$left pages left in this part"
                            }
                        },
                        color = textColor.copy(alpha = 0.8f),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Slider(
                        value = shown,
                        onValueChange = { scrub = it },
                        onValueChangeFinished = {
                            scrub?.let { controller.jumpTo(it) }
                            scrub = null
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = parseColor(theme.link),
                            activeTrackColor = parseColor(theme.link),
                            inactiveTrackColor = parseColor(theme.faint),
                        ),
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Place in book" },
                    )
                    Row {
                        Text("${(shown * 100).toInt()}% of the book", color = textColor.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            "Part ${(if (scrub != null) scrubPart else controller.spineIndex) + 1} of $parts",
                            color = textColor.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        if (controller.hasSelection && epub != null && controller.tappedHighlight == null) {
            SelectionBar(
                theme = theme,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp),
                onColor = { controller.highlightSelection(it) },
                onNote = { controller.highlightSelection(HighlightColor.YELLOW) { id -> noteFor = id } },
                onCopy = controller::copySelection,
                onShare = controller::shareSelection,
                onLookUp = controller::lookUpSelection,
            )
        }

        controller.tappedHighlight?.let { id ->
            val h = controller.highlight(id)
            if (h == null) controller.tappedHighlight = null
            else HighlightSheet(
                h,
                theme,
                onColor = { controller.updateHighlight(h.copy(color = it)) },
                onNote = { noteFor = id; controller.tappedHighlight = null },
                onCopy = { controller.copy(h.text) },
                onShare = { controller.share(h.text) },
                onRemove = { controller.removeHighlight(id); controller.tappedHighlight = null },
                onClose = { controller.tappedHighlight = null },
            )
        }

        noteFor?.let { id ->
            val h = controller.highlight(id)
            if (h == null) noteFor = null
            else NoteDialog(h.text, h.note, onSave = { controller.updateHighlight(h.copy(note = it.trim())); noteFor = null }, onDismiss = { noteFor = null })
        }

        when (panel) {
            "toc" -> epub?.let { TocPanel(it, controller, theme) { panel = null } }
            "style" -> StylePanel(chosen, vm::updateReaderSettings) { panel = null }
            "search" -> SearchPanel(controller, theme) { panel = null }
        }
    }
}

@Composable
private fun TocPanel(epub: Epub, controller: ReaderController, theme: ReaderTheme, onClose: () -> Unit) {
    val text = parseColor(theme.text)
    val accent = parseColor(theme.link)
    val currentTitle = epub.chapterTitle(controller.spineIndex)
    val currentIndex = epub.toc.indexOfFirst { it.title == currentTitle && epub.spineIndexOf(it.path) <= controller.spineIndex }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 3).coerceAtLeast(0))
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClose)) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(parseColor(theme.background))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {} // taps on the panel stay on it
                .navigationBarsPadding(),
        ) {
            var tab by remember { mutableStateOf("Contents") }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) {
                    listOf("Contents", "Bookmarks", "Highlights").forEach { t ->
                        Text(
                            t,
                            color = if (t == tab) accent else text.copy(alpha = 0.7f),
                            fontWeight = if (t == tab) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 16.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { tab = t }.padding(horizontal = 8.dp, vertical = 10.dp),
                        )
                    }
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = text) }
            }
            if (tab != "Contents") {
                MarksList(epub, controller, text, accent, bookmarks = tab == "Bookmarks", onClose = onClose)
                return@Column
            }
            if (epub.toc.isEmpty()) {
                Text("This book has no table of contents.", color = text.copy(alpha = 0.7f), modifier = Modifier.padding(20.dp))
            }
            LazyColumn(state = state) {
                itemsIndexed(epub.toc) { i, entry ->
                    Text(
                        entry.title,
                        color = if (i == currentIndex) accent else text,
                        fontWeight = if (i == currentIndex) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                controller.loadEntry(entry.path, entry.fragment)
                                controller.chrome = false
                                onClose()
                            }
                            .padding(start = (20 + entry.depth * 16).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/** The book's bookmarks or highlights: tap one to go there, or remove it. */
@Composable
private fun MarksList(epub: Epub, controller: ReaderController, text: Color, accent: Color, bookmarks: Boolean, onClose: () -> Unit) {
    data class MarkRow(val id: String, val spine: Int, val offset: Int, val title: String, val body: String)
    val rows = if (bookmarks) {
        controller.marks.bookmarks.map { MarkRow(it.id, it.spine, it.offset, it.chapter, it.snippet) }
    } else {
        controller.marks.highlights.map { MarkRow(it.id, it.spine, it.start, it.chapter, it.text) }
    }
    if (rows.isEmpty()) {
        Text(
            if (bookmarks) "No bookmarks yet. Tap the bookmark at the top of a page to add one."
            else "No highlights yet. Press and hold a word, drag to select the text, then tap Highlight.",
            color = text.copy(alpha = 0.7f),
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    LazyColumn {
        itemsIndexed(rows, key = { _, r -> r.id }) { _, r ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        controller.goTo(r.spine, r.offset)
                        controller.chrome = false
                        onClose()
                    }
                    .padding(start = 20.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!bookmarks) {
                    Box(Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp)).background(swatch(controller.highlight(r.id)?.color ?: HighlightColor.YELLOW, accent)))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(r.title.ifEmpty { epub.title.ifEmpty { "Part ${r.spine + 1}" } }, color = accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(r.body.ifEmpty { "Part ${r.spine + 1}" }, color = text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    val note = if (bookmarks) "" else controller.highlight(r.id)?.note.orEmpty()
                    if (note.isNotBlank()) {
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.AutoMirrored.Rounded.StickyNote2, contentDescription = "Note", tint = accent, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(note, color = text.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                IconButton(onClick = { if (bookmarks) controller.removeBookmark(r.id) else controller.removeHighlight(r.id) }) {
                    Icon(Icons.Rounded.DeleteOutline, if (bookmarks) "Remove bookmark" else "Remove highlight", tint = text.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** The colour a highlight shows in lists and swatches. */
private fun swatch(c: HighlightColor, accent: Color): Color = when (c) {
    HighlightColor.YELLOW -> Color(0xFFFFC400)
    HighlightColor.GREEN -> Color(0xFF4CAF50)
    HighlightColor.BLUE -> Color(0xFF4285F4)
    HighlightColor.PINK -> Color(0xFFF06292)
    HighlightColor.PURPLE -> Color(0xFF9575CD)
    HighlightColor.UNDERLINE -> accent
}

/** One round colour button; the underline one shows a line instead of a fill. */
@Composable
private fun ColorDot(c: HighlightColor, accent: Color, text: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (c == HighlightColor.UNDERLINE) Color.Transparent else swatch(c, accent))
            .border(if (selected) 3.dp else 1.dp, if (selected) text else text.copy(alpha = 0.25f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (c == HighlightColor.UNDERLINE) "Underline" else "Highlight ${c.label.lowercase()}" },
        contentAlignment = Alignment.Center,
    ) {
        if (c == HighlightColor.UNDERLINE) Icon(Icons.Rounded.FormatUnderlined, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun BarAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}

/** Shown while text is selected: highlight colours, then note, copy, share and look up. */
@Composable
private fun SelectionBar(
    theme: ReaderTheme,
    modifier: Modifier,
    onColor: (HighlightColor) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onLookUp: () -> Unit,
) {
    val text = parseColor(theme.text)
    val accent = parseColor(theme.link)
    Surface(
        modifier = modifier.padding(horizontal = 12.dp),
        shape = RoundedCornerShape(18.dp),
        color = parseColor(theme.background),
        shadowElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, parseColor(theme.faint)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HighlightColor.entries.forEach { c -> ColorDot(c, accent, text, selected = false) { onColor(c) } }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                BarAction(Icons.AutoMirrored.Rounded.StickyNote2, "Note", text, onNote)
                BarAction(Icons.Rounded.ContentCopy, "Copy", text, onCopy)
                BarAction(Icons.Rounded.Share, "Share", text, onShare)
                BarAction(Icons.AutoMirrored.Rounded.MenuBook, "Look up", text, onLookUp)
            }
        }
    }
}

/** A tapped highlight: change its colour, write a note, copy, share or remove it. */
@Composable
private fun HighlightSheet(
    h: Highlight,
    theme: ReaderTheme,
    onColor: (HighlightColor) -> Unit,
    onNote: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    onClose: () -> Unit,
) {
    val text = parseColor(theme.text)
    val accent = parseColor(theme.link)
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(parseColor(theme.background))
                .border(1.dp, parseColor(theme.faint), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("“${h.text}”", color = text, maxLines = 3, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif)
            if (h.note.isNotBlank()) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.AutoMirrored.Rounded.StickyNote2, contentDescription = "Note", tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(h.note, color = text.copy(alpha = 0.8f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HighlightColor.entries.forEach { c -> ColorDot(c, accent, text, selected = c == h.color) { onColor(c) } }
            }
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                BarAction(Icons.AutoMirrored.Rounded.StickyNote2, if (h.note.isBlank()) "Add note" else "Edit note", text, onNote)
                BarAction(Icons.Rounded.ContentCopy, "Copy", text, onCopy)
                BarAction(Icons.Rounded.Share, "Share", text, onShare)
                BarAction(Icons.Rounded.DeleteOutline, "Remove", text, onRemove)
            }
        }
    }
}

@Composable
private fun NoteDialog(quote: String, note: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("“$quote”", maxLines = 3, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif, fontSize = 14.sp)
                OutlinedTextField(value, { value = it }, placeholder = { Text("Your thoughts…") }, minLines = 3, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Search the whole book; tap a result to go there. */
@Composable
private fun SearchPanel(controller: ReaderController, theme: ReaderTheme, onClose: () -> Unit) {
    val text = parseColor(theme.text)
    val accent = parseColor(theme.link)
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(controller.searchQuery) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClose)) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(parseColor(theme.background))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search in this book", color = text.copy(alpha = 0.5f)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = text.copy(alpha = 0.6f)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { scope.launch { controller.search(query) } }),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = text, unfocusedTextColor = text, cursorColor = accent, focusedBorderColor = accent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = text) }
            }
            when {
                controller.searching -> CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally), color = accent)
                controller.searchQuery.isNotBlank() && controller.searchHits.isEmpty() ->
                    Text("No matches for “${controller.searchQuery}”.", color = text.copy(alpha = 0.7f), modifier = Modifier.padding(20.dp))
                controller.searchHits.isNotEmpty() -> {
                    val n = controller.searchHits.size
                    Text(
                        if (n >= 500) "500+ matches" else if (n == 1) "1 match" else "$n matches",
                        color = text.copy(alpha = 0.6f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp),
                    )
                    val epub = controller.epub
                    LazyColumn {
                        itemsIndexed(controller.searchHits) { _, hit ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        controller.openHit(hit)
                                        controller.chrome = false
                                        onClose()
                                    }
                                    .padding(horizontal = 20.dp, vertical = 10.dp),
                            ) {
                                Text(epub?.chapterTitle(hit.spine)?.ifEmpty { null } ?: "Part ${hit.spine + 1}", color = accent, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    androidx.compose.ui.text.buildAnnotatedString {
                                        append(hit.before)
                                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold, color = accent))
                                        append(hit.match.replace(Regex("\\s+"), " "))
                                        pop()
                                        append(hit.after)
                                    },
                                    color = text,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingLabel(label: String, color: Color) {
    Text(label.uppercase(), color = color.copy(alpha = 0.6f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
}

/** A row of equal choices, one picked. */
@Composable
private fun <T> Choices(options: List<T>, selected: T, theme: ReaderTheme, label: (T) -> String, onPick: (T) -> Unit) {
    val text = parseColor(theme.text)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(if (on) 2.dp else 1.dp, if (on) parseColor(theme.link) else parseColor(theme.faint), RoundedCornerShape(10.dp))
                    .clickable { onPick(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), color = text, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, theme: ReaderTheme, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Text(label, color = parseColor(theme.text), modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = parseColor(theme.link)))
    }
}

/** Themes and settings: colours, typeface, size, spacing, margins, page turns. */
@Composable
private fun StylePanel(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit, onClose: () -> Unit) {
    val theme = settings.theme
    val text = parseColor(theme.text)
    Box(Modifier.fillMaxSize().clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(parseColor(theme.background))
                .border(1.dp, parseColor(theme.faint), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {} // taps on the panel stay on it
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Themes & Settings", color = text, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = text) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onChange(settings.copy(textZoom = (settings.textZoom - 10).coerceAtLeast(70))) },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) { Text("A", color = text, fontSize = 13.sp) }
                Text("${settings.textZoom}%", color = text, modifier = Modifier.width(72.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                OutlinedButton(
                    onClick = { onChange(settings.copy(textZoom = (settings.textZoom + 10).coerceAtMost(220))) },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                ) { Text("A", color = text, fontSize = 21.sp) }
            }
            SettingLabel("Theme", text)
            // Two rows of page colours, each showing an "Aa" in its own colours.
            ReaderTheme.entries.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { t ->
                        val selected = t == theme
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(parseColor(t.background))
                                .border(if (selected) 2.dp else 1.dp, if (selected) parseColor(theme.link) else parseColor(theme.faint), RoundedCornerShape(12.dp))
                                .clickable { onChange(settings.copy(theme = t)) }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text("Aa", color = parseColor(t.text), fontFamily = FontFamily.Serif, fontSize = 20.sp, fontWeight = if (t == ReaderTheme.FOCUS) FontWeight.Bold else FontWeight.Normal)
                            Text(t.label, color = parseColor(t.text), fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            SwitchRow("Match the phone's dark mode", settings.autoNight, theme) { onChange(settings.copy(autoNight = it)) }
            SettingLabel("Font", text)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderFont.entries.forEach { f ->
                    val selected = f == settings.font
                    Box(
                        Modifier
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(if (selected) 2.dp else 1.dp, if (selected) parseColor(theme.link) else parseColor(theme.faint), RoundedCornerShape(10.dp))
                            .clickable { onChange(settings.copy(font = f)) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            f.label,
                            color = text,
                            fontFamily = when (f) {
                                ReaderFont.SERIF, ReaderFont.ORIGINAL -> FontFamily.Serif
                                ReaderFont.MONO -> FontFamily.Monospace
                                else -> FontFamily.SansSerif
                            },
                            fontSize = 16.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
            SettingLabel("Line spacing", text)
            Choices(ReaderSettings.LINE_HEIGHTS, ReaderSettings.LINE_HEIGHTS.minByOrNull { kotlin.math.abs(it - settings.lineHeight) }!!, theme, { listOf("Tight", "Normal", "Loose")[ReaderSettings.LINE_HEIGHTS.indexOf(it)] }) {
                onChange(settings.copy(lineHeight = it))
            }
            SettingLabel("Margins", text)
            Choices(ReaderSettings.MARGINS, ReaderSettings.MARGINS.minByOrNull { kotlin.math.abs(it - settings.side) }!!, theme, { listOf("Narrow", "Normal", "Wide")[ReaderSettings.MARGINS.indexOf(it)] }) {
                onChange(settings.copy(side = it))
            }
            SettingLabel("Page turn", text)
            Choices(PageTurn.entries, settings.pageTurn, theme, { it.label }) { onChange(settings.copy(pageTurn = it)) }
            SwitchRow("Justify text", settings.justify, theme) { onChange(settings.copy(justify = it)) }
            SwitchRow("Bold text", settings.bold, theme) { onChange(settings.copy(bold = it)) }
        }
    }
}
