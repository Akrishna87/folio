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
import androidx.compose.runtime.*
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
import io.github.akrishna87.mybooks.stats.ReadingStats
import java.time.LocalDate
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
    val continuing = vm.shelf.filter { it.openedAt > 0 && !vm.isFinished(it) }.sortedByDescending { it.openedAt }.take(12)
    val wantToRead = vm.shelf.filter { it.openedAt == 0L && !vm.isFinished(it) }.take(12)
    var goalDialog by remember { mutableStateOf(false) }
    // Listening in the background adds time too, so look again now and then.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshStats()
            kotlinx.coroutines.delay(30_000)
        }
    }
    if (goalDialog) {
        ChoiceDialog(
            title = "Daily reading goal",
            options = ReadingStats.GOALS.map { "$it minutes a day" },
            selected = ReadingStats.GOALS.indexOf(vm.stats.goalMinutes),
            onPick = { vm.setDailyGoal(ReadingStats.GOALS[it]); goalDialog = false },
            onDismiss = { goalDialog = false },
        )
    }
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
        item { GoalCard(vm) { goalDialog = true } }
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
        if (wantToRead.isNotEmpty()) {
            item { SectionTitle("Want to Read", action = "Shelf") { vm.selectSection(Section.SHELF) } }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(wantToRead, key = { it.id }) { item -> BookCard(item.book, onClick = { vm.openBook(item.book) }) }
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

/** Today's reading against the daily goal, the streak, and the last seven days. Tap to change the goal. */
@Composable
private fun GoalCard(vm: BooksViewModel, onEditGoal: () -> Unit) {
    val stats = vm.stats
    val today = LocalDate.now()
    val goalSec = stats.goalMinutes * 60
    val todaySec = stats.seconds(today)
    val fraction = (todaySec.toFloat() / goalSec).coerceIn(0f, 1f)
    val streak = stats.streak(today)
    val finished = vm.finishedThisYear()
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Elevated)
            .clickable(onClickLabel = "Change daily goal", onClick = onEditGoal)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxSize(),
                color = if (fraction >= 1f) Palette.Teal else MaterialTheme.colorScheme.primary,
                trackColor = Color.White.copy(alpha = 0.08f),
                strokeWidth = 7.dp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${todaySec / 60}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("of ${stats.goalMinutes} min", color = Palette.SubText, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("Reading Goals", fontWeight = FontWeight.Bold)
            Text(
                when {
                    fraction >= 1f -> "Goal met today. Nice!"
                    todaySec == 0 -> "Read or listen for ${stats.goalMinutes} minutes today"
                    else -> "${(goalSec - todaySec + 59) / 60} min to go today"
                },
                color = Palette.SubText,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                GoalStat(if (streak == 1) "1 day" else "$streak days", "streak")
                GoalStat("$finished", if (finished == 1) "book this year" else "books this year")
            }
            Spacer(Modifier.height(8.dp))
            // The last seven days, today on the right; full bars met the goal.
            val week = stats.week(today)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom, modifier = Modifier.height(28.dp)) {
                week.forEachIndexed { i, sec ->
                    val day = today.minusDays((6 - i).toLong())
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .width(10.dp)
                                .height((4 + 14 * (sec.toFloat() / goalSec).coerceIn(0f, 1f)).dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (sec >= goalSec) Palette.Teal else if (sec > 0) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f)),
                        )
                        Text(day.dayOfWeek.name.take(1), color = if (i == 6) Palette.Text else Palette.Faint, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalStat(value: String, label: String) {
    Column {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(label, color = Palette.SubText, fontSize = 11.sp)
    }
}
