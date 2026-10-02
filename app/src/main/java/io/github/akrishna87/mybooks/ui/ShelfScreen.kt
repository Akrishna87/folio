package io.github.akrishna87.mybooks.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private enum class ShelfFilter(val label: String) { ALL("All"), AUDIO("Audiobooks"), EBOOKS("Ebooks"), DOWNLOADED("Downloaded") }

@Composable
fun ShelfScreen(vm: BooksViewModel) {
    var filter by remember { mutableStateOf(ShelfFilter.ALL) }
    val context = LocalContext.current
    var used by remember { mutableLongStateOf(0L) }
    LaunchedEffect(vm.shelf) {
        used = withContext(Dispatchers.IO) { vm.shelf.sumOf { BookFiles.sizeOf(context, it.id) } }
    }
    val list = vm.shelf.filter {
        when (filter) {
            ShelfFilter.ALL -> true
            ShelfFilter.AUDIO -> it.book.kind == Kind.AUDIOBOOK
            ShelfFilter.EBOOKS -> it.book.kind == Kind.EBOOK
            ShelfFilter.DOWNLOADED -> it.downloaded
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                Text("Your shelf", style = MaterialTheme.typography.headlineMedium)
                if (used > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text("Downloads use ${formatBytes(used)} on this phone", color = Palette.SubText, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShelfFilter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }, shape = CircleShape)
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
        items(list, key = { it.id }) { item -> ShelfRow(vm, item) }
    }
}

@Composable
private fun ShelfRow(vm: BooksViewModel, item: ShelfItem) {
    var menu by remember { mutableStateOf(false) }
    val progress = vm.progressOf(item)
    val download = vm.downloads[item.id]
    val note = when {
        item.downloadIds.isNotEmpty() -> "Downloading ${((download?.fraction ?: 0f) * 100).toInt()}%"
        progress >= 0.995f -> "Finished"
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
                DropdownMenuItem(
                    text = { Text("Remove from shelf") },
                    leadingIcon = { Icon(Icons.Rounded.BookmarkRemove, null) },
                    onClick = { menu = false; vm.removeFromShelf(item.id) },
                )
            }
        }
    }
}
