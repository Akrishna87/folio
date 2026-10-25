package io.github.akrishna87.mybooks.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.akrishna87.mybooks.BookFiles
import io.github.akrishna87.mybooks.BooksViewModel
import io.github.akrishna87.mybooks.Section
import io.github.akrishna87.mybooks.ShelfItem
import io.github.akrishna87.mybooks.catalog.Kind
import io.github.akrishna87.mybooks.catalog.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ShelfFilter(val label: String) {
    ALL("All"), WANT("Want to Read"), READING("Reading"), FINISHED("Finished"),
    AUDIO("Audiobooks"), EBOOKS("Ebooks"), DOWNLOADED("Downloaded"),
}

private enum class ShelfSort(val label: String) { RECENT("Recent"), TITLE("Title"), AUTHOR("Author") }

@Composable
fun ShelfScreen(vm: BooksViewModel) {
    var filter by rememberSaveable { mutableStateOf(ShelfFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(ShelfSort.RECENT) }
    var grid by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var used by remember { mutableLongStateOf(0L) }
    LaunchedEffect(vm.shelf) {
        used = withContext(Dispatchers.IO) { vm.shelf.sumOf { BookFiles.sizeOf(context, it.id) } }
    }
    // A collection picked from the chips, shown instead of the type filters.
    var collection by remember { mutableStateOf<String?>(null) }
    if (collection != null && collection !in vm.collections) collection = null
    var confirmDelete by remember { mutableStateOf(false) }
    val list = vm.shelf.filter {
        val c = collection
        if (c != null) {
            c in it.collections
        } else {
            when (filter) {
                ShelfFilter.ALL -> true
                ShelfFilter.WANT -> it.openedAt == 0L && !vm.isFinished(it)
                ShelfFilter.READING -> it.openedAt > 0 && !vm.isFinished(it)
                ShelfFilter.FINISHED -> vm.isFinished(it)
                ShelfFilter.AUDIO -> it.book.kind == Kind.AUDIOBOOK
                ShelfFilter.EBOOKS -> it.book.kind == Kind.EBOOK
                ShelfFilter.DOWNLOADED -> it.downloaded
            }
        }
    }.let { items ->
        when (sort) {
            ShelfSort.RECENT -> items.sortedByDescending { maxOf(it.openedAt, it.addedAt) }
            ShelfSort.TITLE -> items.sortedBy { it.book.title.lowercase().removePrefix("the ").removePrefix("a ") }
            ShelfSort.AUTHOR -> items.sortedBy { it.book.author.lowercase() }
        }
    }
    if (confirmDelete) {
        val name = collection.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “$name”?") },
            text = { Text("The collection goes, but its books stay on your shelf.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteCollection(name); collection = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }

    val columnWidth = (LocalConfiguration.current.screenWidthDp - 24) / 3
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your shelf", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Sort") }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            ShelfSort.entries.forEach { o ->
                                DropdownMenuItem(
                                    text = { Text(o.label) },
                                    leadingIcon = { if (o == sort) Icon(Icons.Rounded.Check, null) },
                                    onClick = { sort = o; sortMenu = false },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { grid = !grid }) {
                        Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, if (grid) "Show as list" else "Show as grid")
                    }
                }
                if (used > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text("Downloads use ${formatBytes(used)} on this phone", color = Palette.SubText, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShelfFilter.entries.forEach { f ->
                        FilterChip(
                            selected = collection == null && filter == f,
                            onClick = { filter = f; collection = null },
                            label = { Text(f.label) },
                            shape = CircleShape,
                        )
                    }
                }
                // Collections get a row of their own so they're in view, not past the filters.
                if (vm.collections.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.collections.forEach { name ->
                        FilterChip(
                            selected = collection == name,
                            onClick = { collection = name },
                            label = { Text(name) },
                            leadingIcon = { Icon(Icons.Rounded.CollectionsBookmark, contentDescription = null, modifier = Modifier.size(16.dp)) },
                            shape = CircleShape,
                        )
                    }
                }
                collection?.let { name ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${list.size} in “$name”", color = Palette.SubText, modifier = Modifier.weight(1f))
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete collection") }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        if (list.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.CollectionsBookmark, contentDescription = null, tint = Palette.Faint, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (vm.shelf.isEmpty()) "Books you play, read, download or save show up here." else "Nothing here yet.",
                        color = Palette.SubText,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { vm.selectSection(Section.SEARCH) }, shape = CircleShape) { Text("Find a book") }
                }
            }
        }
        if (grid) {
            // Covers, three to a row.
            items(list.chunked(3), key = { row -> row.first().id }) { row ->
                Row(Modifier.padding(horizontal = 12.dp)) {
                    row.forEach { item ->
                        Box(Modifier.weight(1f)) {
                            BookCard(item.book, onClick = { vm.openBook(item.book) }, progress = vm.progressOf(item).takeIf { it > 0f }, width = columnWidth)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        } else {
            items(list, key = { it.id }) { item -> ShelfRow(vm, item) }
        }
    }
}

@Composable
private fun ShelfRow(vm: BooksViewModel, item: ShelfItem) {
    var menu by remember { mutableStateOf(false) }
    val progress = vm.progressOf(item)
    val download = vm.downloads[item.id]
    val note = when {
        item.downloadIds.isNotEmpty() -> "Downloading ${((download?.fraction ?: 0f) * 100).toInt()}%"
        vm.isFinished(item) -> "Finished"
        progress > 0f -> "${(progress * 100).toInt()}%"
        item.downloaded -> "Downloaded"
        else -> null
    }
    BookRow(item.book, onClick = { vm.openBook(item.book) }, progress = progress, note = note) {
        IconButton(onClick = { vm.resumeBook(item.book) }) {
            Icon(
                if (item.book.kind == Kind.AUDIOBOOK) Icons.Rounded.PlayCircle else Icons.Rounded.AutoStories,
                contentDescription = if (item.book.kind == Kind.AUDIOBOOK) "Listen" else "Read",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (item.downloaded || item.downloadIds.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(if (item.downloaded) "Remove download" else "Stop downloading") },
                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                        onClick = {
                            menu = false
                            when {
                                item.book.kind == Kind.EBOOK -> vm.deleteEbook(item.id)
                                item.downloaded -> vm.deleteDownload(item.id)
                                else -> vm.cancelDownload(item.id)
                            }
                        },
                    )
                }
                val finished = vm.isFinished(item)
                DropdownMenuItem(
                    text = { Text(if (finished) "Mark as not finished" else "Mark as finished") },
                    leadingIcon = { Icon(if (finished) Icons.Rounded.RemoveDone else Icons.Rounded.DoneAll, null) },
                    onClick = { menu = false; vm.markFinished(item.id, !finished) },
                )
                DropdownMenuItem(
                    text = { Text("Remove from shelf") },
                    leadingIcon = { Icon(Icons.Rounded.BookmarkRemove, null) },
                    onClick = { menu = false; vm.removeFromShelf(item.id) },
                )
            }
        }
    }
}
