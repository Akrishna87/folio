package io.github.akrishna87.mybooks.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mybooks.BooksViewModel
import io.github.akrishna87.mybooks.Load
import io.github.akrishna87.mybooks.catalog.AudioDetails
import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Chapter
import io.github.akrishna87.mybooks.catalog.Kind
import io.github.akrishna87.mybooks.catalog.formatBytes
import io.github.akrishna87.mybooks.catalog.formatDuration

/** A confirmation before something that can't be undone. */
private data class Confirm(val title: String, val text: String, val action: String, val onConfirm: () -> Unit)

@Composable
fun BookScreen(vm: BooksViewModel, found: Book) {
    LaunchedEffect(found.id) { vm.loadDetails(found) }
    val item = vm.shelfItem(found.id)
    val book = item?.book?.takeIf { it.description.isNotEmpty() } ?: vm.ebookDetails[found.id] ?: item?.book ?: found
    val details = vm.audioDetails[found.id]
    val chapters: List<Chapter> = item?.chapters?.takeIf { it.isNotEmpty() } ?: (details as? Load.Ready<AudioDetails>)?.value?.chapters.orEmpty()
    val description = book.description.ifEmpty { (details as? Load.Ready<AudioDetails>)?.value?.description.orEmpty() }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val context = LocalContext.current
    val tint = placeholderColor(book.title)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(tint.deep(0.35f), Palette.Background)))
                    .statusBarsPadding(),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Spacer(Modifier.weight(1f))
                    book.pageUrl?.let { url ->
                        IconButton(onClick = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (e: Exception) {
                                vm.say("No browser to open the page with")
                            }
                        }) { Icon(Icons.Rounded.Public, "Open the ${book.source.label} page") }
                    }
                    IconButton(onClick = { vm.toggleShelf(book) }) {
                        Icon(
                            if (item != null) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            if (item != null) "Remove from shelf" else "Save to shelf",
                            tint = if (item != null) MaterialTheme.colorScheme.primary else Palette.Text,
                        )
                    }
                }
                Cover(
                    book,
                    Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally).width(176.dp).height(256.dp),
                    RoundedCornerShape(8.dp),
                    titleSize = 18,
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    book.author,
                    color = Palette.SubText,
                    textAlign = TextAlign.Center,
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    KindLabel(book.kind)
                    val facts = buildList {
                        if (book.kind == Kind.AUDIOBOOK && chapters.isNotEmpty()) {
                            add(formatDuration(chapters.sumOf { it.durationSec }))
                            add(if (chapters.size == 1) "1 part" else "${chapters.size} parts")
                        }
                        if (book.language.isNotEmpty()) add(book.language)
                    }
                    if (facts.isNotEmpty()) Text("  ·  " + facts.joinToString("  ·  "), color = Palette.SubText, fontSize = 12.sp)
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                if (book.kind == Kind.AUDIOBOOK) {
                    AudioActions(vm, book, chapters, details) { confirm = it }
                } else {
                    EbookActions(vm, book) { confirm = it }
                }
            }
        }

        item {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = Palette.Teal, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("${book.rights} · ${book.source.label}", color = Palette.SubText, fontSize = 12.sp)
            }
        }

        if (description.isNotEmpty()) {
            item { Description(description) }
        }

        if (book.kind == Kind.AUDIOBOOK) {
            when {
                chapters.isNotEmpty() -> {
                    item { SectionTitle("Parts") }
                    itemsIndexed(chapters) { i, ch -> ChapterRow(vm, book, i, ch) }
                }
                details is Load.Failed -> item { LoadFailed(details.message, onRetry = { vm.audioDetails.remove(book.id); vm.loadDetails(book) }) }
                else -> item { Loading() }
            }
        }
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(c.title) },
            text = { Text(c.text) },
            confirmButton = { TextButton(onClick = { confirm = null; c.onConfirm() }) { Text(c.action) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun AudioActions(vm: BooksViewModel, book: Book, chapters: List<Chapter>, details: Load<*>?, ask: (Confirm) -> Unit) {
    val item = vm.shelfItem(book.id)
    val current = vm.currentBookId == book.id
    val started = item != null && vm.progressOf(item) > 0f
    Button(
        onClick = { if (current && vm.isPlaying) vm.togglePlay() else vm.playBook(book) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
    ) {
        Icon(if (current && vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                current && vm.isPlaying -> "Pause"
                started -> "Resume"
                else -> "Play"
            },
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
        )
    }
    Spacer(Modifier.height(10.dp))
    val state = vm.downloads[book.id]
    val size = chapters.sumOf { it.sizeBytes }
    when {
        item?.downloaded == true -> OutlinedButton(
            onClick = {
                ask(Confirm("Remove download?", "“${book.title}” will stream from the internet again. Your place is kept.", "Remove") { vm.deleteDownload(book.id) })
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = Palette.Teal)
            Spacer(Modifier.width(8.dp))
            Text("Downloaded")
        }
        item != null && item.downloadIds.isNotEmpty() -> {
            OutlinedButton(
                onClick = { ask(Confirm("Stop downloading?", "The parts downloaded so far will be deleted.", "Stop") { vm.cancelDownload(book.id) }) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = CircleShape,
            ) {
                Text("Downloading ${((state?.fraction ?: 0f) * 100).toInt()}%")
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { state?.fraction ?: 0f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }
        else -> OutlinedButton(
            onClick = { vm.downloadAudio(book) },
            enabled = chapters.isNotEmpty() || details !is Load.Loading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (size > 0) "Download · ${formatBytes(size)}" else "Download")
        }
    }
}

@Composable
private fun EbookActions(vm: BooksViewModel, book: Book, ask: (Confirm) -> Unit) {
    val item = vm.shelfItem(book.id)
    val busy = book.id in vm.fetching
    if (busy) {
        val (done, total) = vm.fetchProgress[book.id] ?: (0L to -1L)
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape) {
            Text(
                when {
                    done == 0L -> "Getting the book…"
                    total > 0 -> "Downloading ${(done * 100 / total).toInt()}%"
                    else -> "Downloading ${formatBytes(done)}"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (total > 0) {
            LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (total > 0) "${formatBytes(done)} of ${formatBytes(total)}" else "",
                color = Palette.SubText,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f).padding(start = 24.dp),
            )
            TextButton(onClick = { vm.cancelEbookDownload(book.id) }) { Text("Cancel") }
        }
        return
    }
    Button(
        onClick = { vm.openEbook(book) },
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
    ) {
        Icon(Icons.Rounded.AutoStories, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (item != null && vm.progressOf(item) > 0f) "Continue reading" else "Read", fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
    if (item?.downloaded == true) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { ask(Confirm("Remove from this phone?", "You can download “${book.title}” again any time. Your place is kept.", "Remove") { vm.deleteEbook(book.id) }) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = Palette.Teal)
            Spacer(Modifier.width(8.dp))
            Text("On this phone")
        }
    } else {
        Spacer(Modifier.height(6.dp))
        Text(
            "Reading downloads the book so it works offline. Very large illustrated editions are swapped for the text-only one.",
            color = Palette.Faint,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Description(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).animateContentSize()) {
        Text(
            text,
            color = Palette.SubText,
            lineHeight = 21.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 5,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (!expanded && text.length > 240) {
            Text("More", color = Palette.Text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp).clickable { expanded = true })
        }
    }
}

@Composable
private fun ChapterRow(vm: BooksViewModel, book: Book, index: Int, chapter: Chapter) {
    val current = vm.currentBookId == book.id && vm.chapterIndex == index
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.playBook(book, index) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp)) {
            if (current) {
                Icon(Icons.Rounded.GraphicEq, contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            } else {
                Text("${index + 1}", color = Palette.SubText)
            }
        }
        Text(
            chapter.title,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (current) MaterialTheme.colorScheme.primary else Palette.Text,
        )
        if (chapter.durationSec > 0) {
            Text(formatClock((chapter.durationSec * 1000).toLong()), color = Palette.SubText, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
