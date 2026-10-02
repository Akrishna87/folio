package io.github.akrishna87.mybooks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.akrishna87.mybooks.catalog.Book
import io.github.akrishna87.mybooks.catalog.Kind

/** Space at the bottom of scrolling screens so the last row clears the mini player and tab bar. */
val LocalBottomSpace = compositionLocalOf { 0.dp }

fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Pairs of colours for covers that haven't loaded (or don't exist), chosen by title so they stay put. */
private val placeholderPairs = listOf(
    0xFF1F9E8A to 0xFFFFB547,
    0xFF3949AB to 0xFF00ACC1,
    0xFF8E24AA to 0xFFD81B60,
    0xFFF4511E to 0xFFFFB300,
    0xFF00897B to 0xFF7CB342,
    0xFF5E35B1 to 0xFFEC407A,
    0xFF6D4C41 to 0xFFD4A373,
    0xFF1E88E5 to 0xFF26A69A,
)

private fun pairFor(key: String) = placeholderPairs[(key.hashCode() and 0x7fffffff) % placeholderPairs.size]

fun placeholderBrush(key: String): Brush = pairFor(key).let { (a, b) -> Brush.linearGradient(listOf(Color(a), Color(b))) }

fun placeholderColor(key: String): Color = Color(pairFor(key).first)

/** A book's cover, drawn over a coloured card with its title in case the cover is missing. */
@Composable
fun Cover(book: Book, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp), titleSize: Int = 12) {
    Box(modifier.clip(shape).background(placeholderBrush(book.title)), contentAlignment = Alignment.Center) {
        Text(
            book.title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = titleSize.sp,
            lineHeight = (titleSize + 3).sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(8.dp),
        )
        if (book.coverUrl != null) {
            AsyncImage(
                model = book.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun KindLabel(kind: Kind, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (kind == Kind.AUDIOBOOK) Icons.Rounded.Headphones else Icons.Rounded.AutoStories,
            contentDescription = null,
            tint = if (kind == Kind.AUDIOBOOK) Palette.Teal else Palette.Amber,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(kind.label, color = Palette.SubText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** A thin progress bar for how far through a book you are. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(modifier.height(3.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
fun BookRow(
    book: Book,
    onClick: () -> Unit,
    progress: Float? = null,
    note: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(book, Modifier.width(56.dp).height(80.dp), RoundedCornerShape(4.dp), titleSize = 8)
        Column(Modifier.weight(1f)) {
            Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(2.dp))
            Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.SubText, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindLabel(book.kind)
                if (note != null) {
                    Text("  ·  $note", color = Palette.SubText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (progress != null && progress > 0f) {
                Spacer(Modifier.height(6.dp))
                ProgressLine(progress, Modifier.fillMaxWidth(0.8f))
            }
        }
        trailing?.invoke()
    }
}

/** A cover with its title underneath, for the rows on Home. */
@Composable
fun BookCard(book: Book, onClick: () -> Unit, progress: Float? = null, width: Int = 128) {
    Column(Modifier.width(width.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(4.dp)) {
        Box {
            Cover(book, Modifier.fillMaxWidth().aspectRatio(0.68f))
            Box(
                Modifier.align(Alignment.TopEnd).padding(5.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (book.kind == Kind.AUDIOBOOK) Icons.Rounded.Headphones else Icons.Rounded.AutoStories,
                    contentDescription = book.kind.label,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (progress != null && progress > 0f) {
            Spacer(Modifier.height(6.dp))
            ProgressLine(progress, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(6.dp))
        Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp)
        Text(book.author, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Palette.SubText, fontSize = 12.sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action, color = Palette.SubText) }
    }
}

@Composable
fun LoadFailed(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Palette.SubText)
        Spacer(Modifier.width(10.dp))
        Text(message, color = Palette.SubText, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
    }
}
