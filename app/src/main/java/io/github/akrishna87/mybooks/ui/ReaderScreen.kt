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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.akrishna87.mybooks.BookFiles
import io.github.akrishna87.mybooks.BooksViewModel
import io.github.akrishna87.mybooks.Progress
import io.github.akrishna87.mybooks.catalog.encodePath
import io.github.akrishna87.mybooks.epub.Epub
import io.github.akrishna87.mybooks.epub.ReaderFont
import io.github.akrishna87.mybooks.epub.ReaderSettings
import io.github.akrishna87.mybooks.epub.ReaderTheme
import io.github.akrishna87.mybooks.epub.ZipEpubFiles
import io.github.akrishna87.mybooks.epub.injectReader
import io.github.akrishna87.mybooks.epub.readerCss
import kotlinx.coroutines.Dispatchers
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

    var webView: WebView? = null
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

    fun overall(): Float {
        val n = epub?.spine?.size ?: return 0f
        val within = if (pages > 0) (page + 1).toFloat() / pages else 0f
        return ((spineIndex + within) / n).coerceIn(0f, 1f)
    }

    fun intercept(url: Uri): WebResourceResponse {
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
        fun onPage(p: Int, n: Int) {
            main.post {
                page = p
                pages = n
                val fraction = if (n > 1) p.toFloat() / (n - 1) else 0f
                vm.saveReading(bookId, spineIndex, fraction, overall())
            }
        }

        @JavascriptInterface
        fun next() {
            main.post {
                val book = epub
                if (book != null && spineIndex < book.spine.lastIndex) load(spineIndex + 1) else vm.say("That's the end of the book")
            }
        }

        @JavascriptInterface
        fun prev() {
            main.post { if (spineIndex > 0) load(spineIndex - 1, "end") }
        }

        @JavascriptInterface
        fun toggleChrome() {
            main.post { chrome = !chrome }
        }
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
    val style = vm.readerSettings
    val theme = style.theme
    val background = parseColor(theme.background)
    val textColor = parseColor(theme.text)
    var panel by remember { mutableStateOf<String?>(null) } // "toc" or "style"
    controller.css = readerCss(style)

    LaunchedEffect(bookId) { controller.open() }
    DisposableEffect(controller) { onDispose { controller.close() } }
    BackHandler(enabled = panel != null) { panel = null }

    // Full screen while reading; the status bar comes back with the menu.
    val view = LocalView.current
    val window = remember(context) { context.findActivity()?.window }
    DisposableEffect(window, controller.chrome, theme) {
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controller.chrome) insets?.show(WindowInsetsCompat.Type.systemBars()) else insets?.hide(WindowInsetsCompat.Type.systemBars())
        insets?.isAppearanceLightStatusBars = theme != ReaderTheme.DARK
        insets?.isAppearanceLightNavigationBars = theme != ReaderTheme.DARK
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
                Button(onClick = { vm.back() }) { Text("Back") }
            }
            epub == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> AndroidView(
                modifier = Modifier.fillMaxSize().displayCutoutPadding(),
                factory = { ctx ->
                    WebView(ctx).apply {
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
                        controller.openAtSavedPlace()
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
                    LinearProgressIndicator(
                        progress = { overall },
                        modifier = Modifier.fillMaxWidth(),
                        color = parseColor(theme.link),
                        trackColor = parseColor(theme.faint),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("${(overall * 100).toInt()}% of the book", color = textColor.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text(
                            "Part ${controller.spineIndex + 1} of ${epub?.spine?.size ?: 0}",
                            color = textColor.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        when (panel) {
            "toc" -> epub?.let { TocPanel(it, controller, theme) { panel = null } }
            "style" -> StylePanel(style, vm::updateReaderSettings) { panel = null }
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
                .clickable(enabled = false) {}
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Contents", color = text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = text) }
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

@Composable
private fun StylePanel(settings: ReaderSettings, onChange: (ReaderSettings) -> Unit, onClose: () -> Unit) {
    val theme = settings.theme
    val text = parseColor(theme.text)
    Box(Modifier.fillMaxSize().clickable(onClick = onClose)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(parseColor(theme.background))
                .border(1.dp, parseColor(theme.faint), RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Text size", color = text, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { onChange(settings.copy(textZoom = (settings.textZoom - 10).coerceAtLeast(70))) },
                    shape = CircleShape,
                ) { Text("A−", color = text, fontSize = 13.sp) }
                Text("${settings.textZoom}%", color = text, modifier = Modifier.width(64.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                OutlinedButton(
                    onClick = { onChange(settings.copy(textZoom = (settings.textZoom + 10).coerceAtMost(220))) },
                    shape = CircleShape,
                ) { Text("A+", color = text, fontSize = 18.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReaderTheme.entries.forEach { t ->
                    val selected = t == theme
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(parseColor(t.background))
                            .border(if (selected) 2.dp else 1.dp, if (selected) parseColor(theme.link) else parseColor(theme.faint), RoundedCornerShape(10.dp))
                            .clickable { onChange(settings.copy(theme = t)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t.label, color = parseColor(t.text), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReaderFont.entries.forEach { f ->
                    val selected = f == settings.font
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(if (selected) 2.dp else 1.dp, if (selected) parseColor(theme.link) else parseColor(theme.faint), RoundedCornerShape(10.dp))
                            .clickable { onChange(settings.copy(font = f)) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            f.label,
                            color = text,
                            fontFamily = if (f == ReaderFont.SERIF) FontFamily.Serif else FontFamily.SansSerif,
                            fontSize = 17.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
