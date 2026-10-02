package io.github.akrishna87.mybooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.akrishna87.mybooks.BooksViewModel
import io.github.akrishna87.mybooks.SPEEDS
import java.util.Locale

/** "1.0×", "1.25×". */
fun speedLabel(s: Float): String = (if (s == s.toInt().toFloat()) String.format(Locale.US, "%.1f", s) else s.toString()) + "×"

/** The full audiobook player. */
@Composable
fun PlayerScreen(vm: BooksViewModel) {
    val item = vm.currentItem ?: return
    val book = item.book
    val tint = placeholderColor(book.title)
    var showChapters by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(tint.deep(0.3f), Palette.Background, Palette.Background)))
            // Keep taps from reaching the screen underneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showPlayer = false }, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Close player", modifier = Modifier.size(32.dp))
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("LISTENING TO", style = MaterialTheme.typography.labelSmall, color = Palette.SubText)
                    Text(book.title, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { vm.openBook(book) }, modifier = Modifier.offset(x = 12.dp)) {
                    Icon(Icons.Rounded.Info, "About this book")
                }
            }

            Spacer(Modifier.weight(0.6f))
            Cover(book, Modifier.fillMaxWidth(0.72f).aspectRatio(0.7f), RoundedCornerShape(10.dp), titleSize = 22)
            Spacer(Modifier.weight(0.6f))

            Text(
                vm.chapterTitle,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${book.author}  ·  Part ${vm.chapterIndex + 1} of ${vm.chapterCount}",
                color = Palette.SubText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))

            val duration = vm.durationMs
            val shown = dragging ?: if (duration > 0) (vm.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Slider(
                value = shown,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { vm.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = 0.2f)),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatClock(if (dragging != null) (shown * duration).toLong() else vm.positionMs), color = Palette.SubText, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    if (duration > 0) "-" + formatClock(((1 - shown) * duration).toLong()) else "",
                    color = Palette.SubText,
                    fontSize = 12.sp,
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::previousChapter) { Icon(Icons.Rounded.SkipPrevious, "Previous part", modifier = Modifier.size(32.dp)) }
                IconButton(onClick = vm::seekBack, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.Replay10, "Back 10 seconds", modifier = Modifier.size(36.dp)) }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Color.White).clickable(onClick = vm::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    if (vm.isBuffering) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = Color.Black, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (vm.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (vm.isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                IconButton(onClick = vm::seekForward, modifier = Modifier.size(56.dp)) { Icon(Icons.Rounded.Forward30, "Forward 30 seconds", modifier = Modifier.size(36.dp)) }
                IconButton(onClick = vm::nextChapter) { Icon(Icons.Rounded.SkipNext, "Next part", modifier = Modifier.size(32.dp)) }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                PlayerPill(Icons.Rounded.Speed, speedLabel(vm.speed), "Playback speed") { showSpeed = true }
                PlayerPill(
                    Icons.Rounded.Bedtime,
                    when {
                        vm.sleepAtChapterEnd -> "End of part"
                        vm.sleepLeftMs > 0 -> formatClock(vm.sleepLeftMs)
                        else -> "Sleep"
                    },
                    "Sleep timer",
                    active = vm.sleepAtChapterEnd || vm.sleepLeftMs > 0,
                ) { showSleep = true }
                PlayerPill(Icons.Rounded.FormatListNumbered, "Parts", "Parts") { showChapters = true }
            }
        }
    }

    if (showSpeed) {
        ChoiceDialog(
            title = "Playback speed",
            options = SPEEDS.map { speedLabel(it) },
            selected = SPEEDS.indexOf(vm.speed),
            onPick = { vm.changeSpeed(SPEEDS[it]); showSpeed = false },
            onDismiss = { showSpeed = false },
        )
    }
    if (showSleep) {
        val choices = listOf(0 to "Off", 5 to "5 minutes", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", -1 to "End of this part")
        ChoiceDialog(
            title = "Sleep timer",
            options = choices.map { it.second },
            selected = when {
                vm.sleepAtChapterEnd -> choices.lastIndex
                vm.sleepLeftMs > 0 -> -1
                else -> 0
            },
            onPick = { vm.setSleep(choices[it].first); showSleep = false },
            onDismiss = { showSleep = false },
        )
    }
    if (showChapters) {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (vm.chapterIndex - 2).coerceAtLeast(0))
        AlertDialog(
            onDismissRequest = { showChapters = false },
            title = { Text("Parts") },
            text = {
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 480.dp)) {
                    itemsIndexed(item.chapters) { i, ch ->
                        val current = i == vm.chapterIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { vm.jumpToChapter(i); showChapters = false }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("${i + 1}", color = Palette.SubText, modifier = Modifier.width(32.dp))
                            Text(
                                ch.title,
                                modifier = Modifier.weight(1f),
                                color = if (current) MaterialTheme.colorScheme.primary else Palette.Text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (ch.durationSec > 0) Text(formatClock((ch.durationSec * 1000).toLong()), color = Palette.SubText, fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChapters = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun PlayerPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, description: String, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClickLabel = description, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = if (active) MaterialTheme.colorScheme.primary else Palette.Text)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ChoiceDialog(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEachIndexed { i, label ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(i) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = i == selected, onClick = { onPick(i) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
