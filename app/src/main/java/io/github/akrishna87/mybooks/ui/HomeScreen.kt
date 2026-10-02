package io.github.akrishna87.mybooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mybooks.BooksViewModel
import io.github.akrishna87.mybooks.Load
import io.github.akrishna87.mybooks.Section
import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Kind
import io.github.akrishna87.mybooks.catalog.Query
import java.util.Calendar

/** Subjects for the browse tiles: (label, what to search). */
val SUBJECTS = listOf(
    "Adventure" to "adventure",
    "Mystery" to "detective",
    "Science fiction" to "science fiction",
    "Romance" to "romance",
    "Children's" to "children",
    "Poetry" to "poetry",
    "History" to "history",
    "Philosophy" to "philosophy",
    "Horror" to "horror",
    "Humour" to "humor",
)

fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/** Continues a book from your shelf: plays an audiobook or opens an ebook. */
fun BooksViewModel.resumeBook(book: Book) {
    if (book.kind == Kind.AUDIOBOOK) playBook(book) else openEbook(book)
}

@Composable
fun HomeScreen(vm: BooksViewModel) {
    val continuing = vm.shelf.filter { it.openedAt > 0 }.sortedByDescending { it.openedAt }.take(12)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomSpace.current)) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Palette.Teal.deep(0.6f), Palette.Background)))
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
            ) {
                Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Free audiobooks and ebooks from the public domain",
                    color = Palette.SubText,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (continuing.isNotEmpty()) {
            item { SectionTitle("Continue") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(continuing, key = { it.id }) { item ->
                        BookCard(item.book, onClick = { vm.resumeBook(item.book) }, progress = vm.progressOf(item))
                    }
                }
            }
        }
        item { SectionTitle("Popular audiobooks", action = "More") { vm.filter = io.github.akrishna87.mybooks.SearchFilter.AUDIOBOOKS; vm.selectSection(Section.SEARCH) } }
        item { BookRowOf(vm, vm.popularAudio) }
        item { SectionTitle("Popular ebooks", action = "More") { vm.filter = io.github.akrishna87.mybooks.SearchFilter.EBOOKS; vm.selectSection(Section.SEARCH) } }
        item { BookRowOf(vm, vm.popularEbooks) }
        item { SectionTitle("Browse") }
        item { SubjectGrid(vm) }
        item {
            Text(
                "Audiobooks come from LibriVox, read by volunteers. Ebooks come from Project Gutenberg. " +
                    "Both are in the public domain (in the USA), so they're free and legal to download. " +
                    "Copyright rules differ between countries; check yours if you're outside the USA.",
                color = Palette.Faint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
    }
}

@Composable
private fun BookRowOf(vm: BooksViewModel, state: Load<List<Book>>) {
    when (state) {
        is Load.Loading -> Loading(Modifier.height(220.dp))
        is Load.Failed -> LoadFailed(state.message, onRetry = vm::loadHome)
        is Load.Ready -> LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.value, key = { it.id }) { book -> BookCard(book, onClick = { vm.openBook(book) }) }
        }
    }
}

@Composable
fun SubjectGrid(vm: BooksViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SUBJECTS.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, subject) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(placeholderBrush(label))
                            .clickable {
                                vm.selectSection(Section.SEARCH)
                                vm.search(Query(subject, isSubject = true))
                                vm.query = label
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        Text(label, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color.White)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
