package io.github.akrishna87.mybooks

import android.app.DownloadManager
import android.content.Context
import android.net.Uri

data class DownloadState(val doneFiles: Int, val totalFiles: Int, val bytes: Long, val totalBytes: Long, val failed: Boolean) {
    val complete: Boolean get() = !failed && totalFiles > 0 && doneFiles == totalFiles
    val fraction: Float
        get() = when {
            totalBytes > 0 -> (bytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            totalFiles > 0 -> doneFiles.toFloat() / totalFiles
            else -> 0f
        }
}

/**
 * Audiobook downloads go through Android's download manager, so they carry on with the app
 * closed, retry by themselves and show their progress in the notification shade.
 */
object AudioDownloads {
    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)

    fun start(context: Context, item: ShelfItem): List<Long> {
        val dm = manager(context)
        BookFiles.dir(context, item.id).mkdirs()
        return item.chapters.mapIndexed { i, ch ->
            BookFiles.chapter(context, item.id, i).delete()
            val request = DownloadManager.Request(Uri.parse(ch.url))
                .setTitle(item.book.title)
                .setDescription("Part ${i + 1} of ${item.chapters.size}")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(context, "books", BookFiles.chapterSubPath(item.id, i))
                .setAllowedOverMetered(true)
                .addRequestHeader("User-Agent", "MyBooks/1.0 (Android app)")
            dm.enqueue(request)
        }
    }

    fun state(context: Context, ids: List<Long>): DownloadState {
        if (ids.isEmpty()) return DownloadState(0, 0, 0, 0, failed = false)
        var done = 0
        var bytes = 0L
        var total = 0L
        var seen = 0
        var failed = false
        manager(context).query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
            val status = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val soFar = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val size = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (c.moveToNext()) {
                seen++
                when (c.getInt(status)) {
                    DownloadManager.STATUS_SUCCESSFUL -> done++
                    DownloadManager.STATUS_FAILED -> failed = true
                }
                bytes += c.getLong(soFar).coerceAtLeast(0)
                total += c.getLong(size).coerceAtLeast(0)
            }
        }
        // A download cancelled from the notification disappears from the list.
        if (seen < ids.size) failed = true
        return DownloadState(done, ids.size, bytes, total, failed)
    }

    /** Stops any downloads still going. The files themselves are deleted separately. */
    fun cancel(context: Context, ids: List<Long>) {
        if (ids.isNotEmpty()) manager(context).remove(*ids.toLongArray())
    }
}
