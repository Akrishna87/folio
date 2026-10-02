package io.github.akrishna87.mybooks

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/** The player's queue for an audiobook: one item per file, played from the phone once downloaded. */
@OptIn(UnstableApi::class)
fun audioItems(context: Context, item: ShelfItem): List<MediaItem> = item.chapters.mapIndexed { i, ch ->
    val local = BookFiles.chapter(context, item.id, i)
    val uri = if (item.downloaded && local.isFile) Uri.fromFile(local) else Uri.parse(ch.url)
    MediaItem.Builder()
        .setMediaId(mediaIdFor(item.id, i))
        .setUri(uri)
        // A MediaController strips the uri before handing items to the session; this copy survives.
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(ch.title)
                .setArtist(item.book.author)
                .setAlbumTitle(item.book.title)
                .setAlbumArtist(item.book.author)
                .setTrackNumber(i + 1)
                .setTotalTrackCount(item.chapters.size)
                .setArtworkUri(item.book.coverUrl?.let(Uri::parse))
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                .build(),
        )
        .build()
}

fun mediaIdFor(bookId: String, index: Int) = "$bookId#$index"

/** (book id, chapter index) from a media id. */
fun parseMediaId(mediaId: String?): Pair<String, Int>? {
    if (mediaId.isNullOrEmpty() || '#' !in mediaId) return null
    val index = mediaId.substringAfterLast('#').toIntOrNull() ?: return null
    return mediaId.substringBeforeLast('#') to index
}

/** Restores the playable uri on items that came through a MediaController. */
fun MediaItem.withPlayableUri(): MediaItem =
    if (localConfiguration != null) this
    else requestMetadata.mediaUri?.let { buildUpon().setUri(it).build() } ?: this

/** The sleep timer, shared between the screens (which set it) and the player (which acts on it). */
object SleepTimer {
    private fun prefs(context: Context) = context.getSharedPreferences("sleep", Context.MODE_PRIVATE)

    /** Pause at this time (ms since 1970), or 0. */
    fun until(context: Context): Long = prefs(context).getLong("until", 0L)

    /** Pause when the current chapter ends. */
    fun endOfChapter(context: Context): Boolean = prefs(context).getBoolean("eoc", false)

    fun set(context: Context, minutes: Int) {
        prefs(context).edit()
            .putLong("until", if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L)
            .putBoolean("eoc", minutes < 0)
            .apply()
    }

    fun clear(context: Context) = set(context, 0)
}
