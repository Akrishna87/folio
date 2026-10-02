package io.github.akrishna87.mybooks

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the audiobook player. Media3 turns the session into the media notification, lock-screen
 * controls and headphone/car/Bluetooth buttons, and keeps playback going in the background.
 * Remembers where you are in each book every few seconds.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L

        fun savedSpeed(context: Context) = context.getSharedPreferences("player", Context.MODE_PRIVATE).getFloat("speed", 1f)

        fun saveSpeed(context: Context, speed: Float) =
            context.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putFloat("speed", speed).apply()
    }

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK) // streaming keeps Wi-Fi awake too
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        player.setPlaybackSpeed(savedSpeed(this))

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = savePosition()

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && SleepTimer.endOfChapter(this@PlaybackService)) {
                    player.pause()
                    SleepTimer.clear(this@PlaybackService)
                }
                savePosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) savePosition()
            }

            override fun onPlayerError(error: PlaybackException) = savePosition()
        })

        scope.launch {
            var tick = 0
            while (isActive) {
                delay(1_000)
                val until = SleepTimer.until(this@PlaybackService)
                if (until > 0 && System.currentTimeMillis() >= until) {
                    SleepTimer.clear(this@PlaybackService)
                    if (player.isPlaying) player.pause()
                }
                if (++tick % 5 == 0 && player.isPlaying) savePosition()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps the book playing if it's playing; otherwise shut down.
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        savePosition()
        scope.cancel()
        session?.release()
        session = null
        player.release()
        super.onDestroy()
    }

    private fun savePosition() {
        val (bookId, index) = parseMediaId(player.currentMediaItem?.mediaId) ?: return
        val ended = player.playbackState == Player.STATE_ENDED
        Progress.saveAudio(this, bookId, index, if (ended) player.duration.coerceAtLeast(0) else player.currentPosition.coerceAtLeast(0))
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.map { it.withPlayableUri() }.toMutableList())

        /** A headphone/car "play" press after the app was closed picks up the last audiobook. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val context = this@PlaybackService
            val item = Progress.lastAudio(context)?.let { ShelfStore.get(context, it) }
            if (item == null || item.chapters.isEmpty()) {
                return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            }
            val (index, pos) = Progress.audio(context, item.id) ?: (0 to 0L)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(audioItems(context, item), index.coerceIn(0, item.chapters.size - 1), pos),
            )
        }
    }
}
