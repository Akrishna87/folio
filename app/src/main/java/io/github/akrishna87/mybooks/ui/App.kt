package io.github.akrishna87.mybooks.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.akrishna87.mybooks.BooksViewModel
import io.github.akrishna87.mybooks.Screen
import io.github.akrishna87.mybooks.Section

@Composable
fun BooksApp(vm: BooksViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.loadHome() }
    BackHandler(enabled = vm.showPlayer || vm.screens.isNotEmpty() || vm.section != Section.HOME) {
        if (!vm.back()) vm.selectSection(Section.HOME)
    }

    val top = vm.screens.lastOrNull()
    if (top is Screen.Reader) {
        // The reader takes the whole screen; no tabs or mini player.
        Box(Modifier.fillMaxSize()) {
            ReaderScreen(vm, top.bookId)
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 48.dp))
        }
        return
    }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomSpace = navInset + 64.dp + (if (vm.currentBookId != null) 66.dp else 0.dp) + 16.dp

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        CompositionLocalProvider(LocalBottomSpace provides bottomSpace) {
            AnimatedContent(
                targetState = top to vm.section,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen",
            ) { (screen, section) ->
                when {
                    screen is Screen.BookPage -> BookScreen(vm, screen.book)
                    section == Section.HOME -> HomeScreen(vm)
                    section == Section.SEARCH -> SearchScreen(vm)
                    else -> ShelfScreen(vm)
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            MiniPlayer(vm)
            BottomBar(vm)
        }

        AnimatedVisibility(
            visible = vm.showPlayer && vm.currentBookId != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            PlayerScreen(vm)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomSpace - 8.dp))
    }
}

@Composable
private fun BottomBar(vm: BooksViewModel) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Palette.Background.copy(alpha = 0.92f), Palette.Background))),
    ) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val atRoot = vm.screens.isEmpty()
            NavItem("Home", Icons.Rounded.Home, Icons.Outlined.Home, vm.section == Section.HOME && atRoot) { vm.selectSection(Section.HOME) }
            NavItem("Search", Icons.Rounded.Search, Icons.Rounded.Search, vm.section == Section.SEARCH && atRoot) { vm.selectSection(Section.SEARCH) }
            NavItem("Shelf", Icons.Rounded.CollectionsBookmark, Icons.Outlined.CollectionsBookmark, vm.section == Section.SHELF && atRoot) {
                vm.selectSection(Section.SHELF)
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(label: String, selectedIcon: ImageVector, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Palette.Text else Palette.Faint
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(if (selected) selectedIcon else icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

/** The audiobook that's playing, above the tabs. */
@Composable
private fun MiniPlayer(vm: BooksViewModel) {
    val item = vm.currentItem ?: return
    val book = item.book
    val fraction = if (vm.durationMs > 0) (vm.positionMs.toFloat() / vm.durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.padding(horizontal = 8.dp).padding(bottom = 2.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(placeholderColor(book.title).deep(0.6f))
                .clickable { vm.showPlayer = true },
        ) {
            Row(
                Modifier.fillMaxWidth().height(60.dp).padding(start = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Cover(book, Modifier.size(44.dp), RoundedCornerShape(6.dp), titleSize = 6)
                Column(Modifier.weight(1f)) {
                    Text(book.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(vm.chapterTitle, color = Color.White.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
                IconButton(onClick = vm::seekBack) {
                    Icon(Icons.Rounded.Replay10, "Back 10 seconds", tint = Color.White)
                }
                IconButton(onClick = vm::togglePlay) {
                    Icon(
                        if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (vm.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color.White))
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
