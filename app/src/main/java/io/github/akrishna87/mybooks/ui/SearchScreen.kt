package io.github.akrishna87.mybooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.akrishna87.mybooks.BooksViewModel
import io.github.akrishna87.mybooks.SearchFilter
import io.github.akrishna87.mybooks.catalog.Kind
import io.github.akrishna87.mybooks.catalog.Query
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(vm: BooksViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    // Search a moment after typing stops.
    LaunchedEffect(vm.query) {
        val text = vm.query.trim()
        if (text.length < 2 || vm.activeQuery?.let { it.text == text || (it.isSubject && SUBJECTS.any { s -> s.first == text }) } == true) return@LaunchedEffect
        delay(800)
        vm.search(Query(text))
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            Text("Search", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            TextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                placeholder = { Text("Title or author") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (vm.query.isNotEmpty()) IconButton(onClick = vm::clearSearch) { Icon(Icons.Rounded.Close, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Palette.Elevated2,
                    unfocusedContainerColor = Palette.Elevated2,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    vm.search(Query(vm.query.trim()))
                    keyboard?.hide()
                    focus.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchFilter.entries.forEach { f ->
                    FilterChip(
                        selected = vm.filter == f,
                        onClick = { vm.filter = f },
                        label = { Text(f.label) },
                        shape = CircleShape,
                    )
                }
            }
        }

        if (vm.activeQuery == null) {
            LazyColumn(contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
                item { SectionTitle("Browse by subject") }
                item { SubjectGrid(vm) }
            }
        } else {
            Results(vm)
        }
    }
}

@Composable
private fun Results(vm: BooksViewModel) {
    val kinds = when (vm.filter) {
        SearchFilter.ALL -> listOf(Kind.AUDIOBOOK, Kind.EBOOK)
        SearchFilter.AUDIOBOOKS -> listOf(Kind.AUDIOBOOK)
        SearchFilter.EBOOKS -> listOf(Kind.EBOOK)
    }
    val limited = kinds.size > 1
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = LocalBottomSpace.current)) {
        for (kind in kinds) {
            val r = vm.results(kind)
            val title = if (kind == Kind.AUDIOBOOK) "Audiobooks" else "Ebooks"
            val shown = if (limited) r.books.take(5) else r.books
            item(key = "title-$kind") {
                SectionTitle(
                    title,
                    action = if (limited && r.books.size > shown.size) "See all" else null,
                    onAction = { vm.filter = if (kind == Kind.AUDIOBOOK) SearchFilter.AUDIOBOOKS else SearchFilter.EBOOKS },
                )
            }
            items(shown, key = { "$kind-${it.id}" }) { book ->
                BookRow(book, onClick = { vm.openBook(book) }, note = book.language.takeIf { it.isNotEmpty() && it != "English" })
            }
            item(key = "footer-$kind") {
                when {
                    r.loading -> Loading()
                    r.error != null -> LoadFailed(r.error, onRetry = { vm.retry(kind) })
                    r.books.isEmpty() -> Text(
                        if (kind == Kind.AUDIOBOOK) "No audiobooks found" else "No ebooks found",
                        color = Palette.SubText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    !limited && r.hasMore -> {
                        // Reaching the end of the list loads the next page.
                        LaunchedEffect(r.page) { vm.loadMore(kind) }
                        Loading()
                    }
                }
            }
        }
    }
}
