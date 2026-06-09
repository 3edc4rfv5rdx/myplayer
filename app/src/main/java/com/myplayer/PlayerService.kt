package com.myplayer

import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Holds the ExoPlayer + MediaSession (background playback + shade controls).
 *  ReplayGain is applied without touching the render pipeline: attenuation via player volume,
 *  boost via a LoudnessEnhancer audio effect. Toggled live from [Settings]. */
class PlayerService : MediaSessionService() {

    companion object {
        private const val TAG = "PlayerService"

        /** Custom session command: re-apply the ReplayGain setting to the current track live. */
        const val CMD_REPLAYGAIN = "com.myplayer.REPLAYGAIN_CHANGED"

        /** Custom session command: declare the active queue's book key (empty = plain music, no
         *  position tracking). Sent by the activity whenever it installs a new queue. */
        const val CMD_BOOK_MODE = "com.myplayer.BOOK_MODE"
        const val KEY_BOOK_FOLDER = "book_folder"

        // How often the playing book's position is persisted, so a process kill loses at most this.
        private const val SAVE_INTERVAL_MS = 10_000L
    }

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var currentTrackGainDb: Float? = null
    // Cached so applyGain() (called on every metadata/transition/session callback) needn't hit the DB.
    // Loaded from Settings in onCreate, before the player or any listener exists.
    private var replayGainEnabled = false

    // Book key of the active queue, or null for plain music. When set, the current file uri and
    // offset are persisted as the book's resume point (see [saveBookPosition]).
    private var bookFolderKey: String? = null
    private val saveHandler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() {
            if (player?.isPlaying == true) saveBookPosition()
            saveHandler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        replayGainEnabled = Settings.isReplayGainEnabled(this)

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.repeatMode =
            if (Settings.isLoopEnabled(this)) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

        player.addListener(object : Player.Listener {
            @UnstableApi
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                enhancer?.release()
                enhancer = try {
                    LoudnessEnhancer(audioSessionId)
                } catch (e: Exception) {
                    Log.w(TAG, "LoudnessEnhancer unavailable; ReplayGain boost is a no-op", e)
                    null
                }
                applyGain()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTrackGainDb = null
                applyGain()
                // A real move to another file (auto-advance or skip) makes it the book's new resume
                // point; the initial setMediaItems (PLAYLIST_CHANGED) must not overwrite the restore.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) saveBookPosition()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Pausing/stopping is the most important moment to persist the exact position — but
                // not when the book just ended, or we'd re-save the end position right after
                // onPlaybackStateChanged(STATE_ENDED) cleared it (the two events race).
                if (!isPlaying && player?.playbackState != Player.STATE_ENDED) saveBookPosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Book genuinely finished (repeat is off in abook): forget the resume point so it
                // starts over. Guard on a non-empty queue so clearing items on exit — which also
                // reports STATE_ENDED — doesn't wipe the saved position.
                if (playbackState == Player.STATE_ENDED && (player?.mediaItemCount ?: 0) > 0) {
                    bookFolderKey?.let { Settings.clearBookPos(this@PlayerService, it) }
                }
            }

            @UnstableApi
            override fun onMetadata(metadata: Metadata) {
                ReplayGain.parseTrackGainDb(metadata)?.let {
                    currentTrackGainDb = it
                    applyGain()
                }
            }
        })

        this.player = player
        session = MediaSession.Builder(this, player).setCallback(SessionCallback()).build()
        saveHandler.postDelayed(saveTick, SAVE_INTERVAL_MS)
    }

    /** Persists the playing book's current file uri and offset as its resume point. No-op for plain
     *  music queues (no [bookFolderKey]) or before a track is loaded. */
    private fun saveBookPosition() {
        val key = bookFolderKey ?: return
        val p = player ?: return
        val uri = p.currentMediaItem?.mediaId ?: return
        Settings.setBookPos(this, key, uri, p.currentPosition.coerceAtLeast(0L))
    }

    /** Grants the ReplayGain command to controllers and applies the toggle live on request. */
    private inner class SessionCallback : MediaSession.Callback {
        @UnstableApi
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_REPLAYGAIN, Bundle.EMPTY))
                .add(SessionCommand(CMD_BOOK_MODE, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == CMD_REPLAYGAIN) {
                replayGainEnabled = Settings.isReplayGainEnabled(this@PlayerService)
                applyGain()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_BOOK_MODE) {
                bookFolderKey = args.getString(KEY_BOOK_FOLDER)?.takeIf { it.isNotEmpty() }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    /** Applies the current track's ReplayGain (capped at +12 dB) when enabled in [Settings]. */
    private fun applyGain() {
        val p = player ?: return
        val db = currentTrackGainDb
        if (!replayGainEnabled || db == null) {
            p.volume = 1f
            enhancer?.enabled = false
            return
        }
        if (db <= 0f) {
            // Attenuate quietly and precisely via the player volume.
            p.volume = ReplayGain.attenuationVolume(db)
            enhancer?.enabled = false
        } else {
            // Boost via the loudness effect (player volume can't exceed 1.0).
            p.volume = 1f
            enhancer?.let {
                runCatching {
                    it.setTargetGain(ReplayGain.boostMillibels(db))
                    it.enabled = true
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        saveBookPosition()
        saveHandler.removeCallbacks(saveTick)
        Settings.flush() // make sure the final position write reaches disk before the process can die
        enhancer?.release()
        enhancer = null
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }
}
