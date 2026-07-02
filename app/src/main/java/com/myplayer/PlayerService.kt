package com.myplayer

import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ShuffleOrder
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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

        /** Custom session command: re-apply the volume-normalization mode (ReplayGain / auto-level)
         *  to the current track live. */
        const val CMD_VOLUME_NORM = "com.myplayer.VOLUME_NORM_CHANGED"

        /** Custom session command: re-apply the skip-silence setting to the player live. */
        const val CMD_SKIP_SILENCE = "com.myplayer.SKIP_SILENCE_CHANGED"

        /** Custom session command: re-apply settings affected by the generated inter-track gap. */
        const val CMD_TRACK_GAP = "com.myplayer.TRACK_GAP_CHANGED"

        /** Custom session command: declare the active queue's book key (empty = plain music, no
         *  position tracking). Sent by the activity whenever it installs a new queue. */
        const val CMD_BOOK_MODE = "com.myplayer.BOOK_MODE"
        const val KEY_BOOK_FOLDER = "book_folder"

        /** Custom session command: arm/cancel/query the sleep timer. The timer lives here (not the
         *  activity) so it fires with the screen off and the app backgrounded. */
        const val CMD_SLEEP_TIMER = "com.myplayer.SLEEP_TIMER"
        // Request args.
        const val KEY_SLEEP_ACTION = "sleep_action"   // "minutes" | "chapter" | "off" | "query"
        const val KEY_SLEEP_MINUTES = "sleep_minutes" // int, for the "minutes" action
        // Reply args (current state).
        const val KEY_SLEEP_MODE = "sleep_mode"        // 0 = off, 1 = minutes, 2 = end of chapter
        const val KEY_SLEEP_DEADLINE = "sleep_deadline" // elapsedRealtime ms for mode 1, else -1

        // How often the playing book's position is persisted, so a process kill loses at most this.
        private const val SAVE_INTERVAL_MS = 10_000L
        private const val US_PER_SECOND = 1_000_000L
        // Initial placeholder duration for a track whose real length isn't cached yet, kept far longer
        // than any file so a resume seek is never clamped before the actual duration replaces it.
        private const val GAP_PLACEHOLDER_MS = 24L * 60 * 60 * 1000
    }

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    // ReplayGain boost (positive tag gain) and real-time auto-leveling are both audio-session effects;
    // they're recreated whenever the session id changes and toggled by the active [normMode].
    private var enhancer: LoudnessEnhancer? = null
    private var leveler: android.media.audiofx.DynamicsProcessing? = null
    // The current output audio session id, so effects can be (re)created lazily by [applyNorm] only
    // for the mode that needs them. A disabled effect left attached to the session still perturbs the
    // audio path and caused random stops, so an unused mode keeps nothing attached.
    private var audioSessionId: Int? = null
    private var currentTrackGainDb: Float? = null
    // Cached so applyNorm() (called on every metadata/transition/session callback) needn't hit the DB.
    // Loaded from Settings in onCreate, before the player or any listener exists.
    private var normMode = VolumeNorm.Off

    // Book key of the active queue, or null for plain music. When set, the current file uri and
    // offset are persisted as the book's resume point (see [saveBookPosition]).
    private var bookFolderKey: String? = null
    private val saveHandler = Handler(Looper.getMainLooper())
    // Periodic resume-point save while playing. Started on play and stopped on pause/stop (see
    // onIsPlayingChanged) so it isn't a permanent 10s heartbeat waking the looper while idle.
    private val saveTick = object : Runnable {
        override fun run() {
            if (player?.isPlaying != true) return
            saveBookPosition()
            saveHandler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    // Sleep timer (not persisted; lives only while the service does). For a fixed duration,
    // [sleepDeadlineMs] is the elapsedRealtime to pause at and [sleepRunnable] is posted to it;
    // for "end of chapter", [sleepEndOfChapter] makes the next auto track-transition pause instead.
    private var sleepDeadlineMs = 0L
    private var sleepEndOfChapter = false
    private val sleepRunnable = Runnable { fireSleep() }

    override fun onCreate() {
        super.onCreate()
        normMode = Settings.getVolumeNorm(this)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(GapMediaSourceFactory(this))
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
            if (Settings.REPEAT_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        player.skipSilenceEnabled = effectiveSkipSilenceEnabled()

        player.addListener(object : Player.Listener {
            @UnstableApi
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                this@PlayerService.audioSessionId = audioSessionId
                // Drop effects bound to the old session; applyNorm re-creates only what the mode needs.
                releaseEnhancer()
                releaseLeveler()
                applyNorm()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTrackGainDb = null
                applyNorm()
                // "Until end of chapter" fires when the current track finishes on its own: the queue
                // auto-advances to the next file (position ~0), so pausing here leaves the resume
                // point cleanly at the start of the next chapter.
                if (sleepEndOfChapter &&
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) fireSleep()
                // A real move to another file (auto-advance or skip) makes it the book's new resume
                // point; the initial setMediaItems (PLAYLIST_CHANGED) must not overwrite the restore.
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) saveBookPosition()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    // Run the periodic save only while actually playing.
                    saveHandler.postDelayed(saveTick, SAVE_INTERVAL_MS)
                } else {
                    saveHandler.removeCallbacks(saveTick)
                    // Pausing/stopping is the most important moment to persist the exact position —
                    // but not when the book just ended, or we'd re-save the end position right after
                    // onPlaybackStateChanged(STATE_ENDED) cleared it (the two events race).
                    if (player.playbackState != Player.STATE_ENDED) saveBookPosition()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Book genuinely finished (repeat is always off): forget the resume point so it
                // starts over. Guard on a non-empty queue so clearing items on exit — which also
                // reports STATE_ENDED — doesn't wipe the saved position.
                if (playbackState == Player.STATE_ENDED && player.mediaItemCount > 0) {
                    // The queue is over; a pending sleep timer has nothing left to pause.
                    cancelSleep()
                    bookFolderKey?.let {
                        Settings.clearBookPos(this@PlayerService, it)
                        // The finished book's cached durations go with the resume point; a
                        // re-listen re-resolves them lazily.
                        DurationCache.remove(
                            this@PlayerService,
                            List(player.mediaItemCount) { i -> player.getMediaItemAt(i).mediaId }
                        )
                    }
                    // Stop tracking this book so a later teardown (swipe-away/kill) can't re-save the
                    // end position over the cleared resume point; the next queue resends its book mode.
                    bookFolderKey = null
                    // Drop the finished queue so the shade Play button can't resurrect it (which would
                    // restart a book untracked from file 1). This also dismisses the notification for
                    // music that ended — a deliberate trade-off.
                    player.clearMediaItems()
                }
            }

            @UnstableApi
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) reseedShuffleOrder()
            }

            @UnstableApi
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) reseedShuffleOrder()
            }

            @UnstableApi
            override fun onMetadata(metadata: Metadata) {
                ReplayGain.parseTrackGainDb(metadata)?.let {
                    currentTrackGainDb = it
                    applyNorm()
                }
            }
        })

        this.player = player
        session = MediaSession.Builder(this, player).setCallback(SessionCallback()).build()
        // The periodic save starts when playback begins (onIsPlayingChanged), not here.
    }

    /** Re-seeds the shuffle order with the current track first, so a shuffled queue always plays
     *  every remaining item. ExoPlayer's default order is a random permutation played from the
     *  start track's position in it to its end — with repeat off, a queue started on a random
     *  track ended after a random number of items instead of all of them. Run when a new playlist
     *  arrives and when shuffle is switched on mid-play; no-op for sequential (book) queues. */
    @UnstableApi
    private fun reseedShuffleOrder() {
        val p = player ?: return
        if (!p.shuffleModeEnabled || p.mediaItemCount == 0) return
        val current = p.currentMediaItemIndex
        // Already current-first: nothing to do. Crucially this also breaks the feedback loop —
        // setShuffleOrder itself emits onTimelineChanged(PLAYLIST_CHANGED), which lands back here
        // and would otherwise reseed (and re-emit) forever, freezing the main thread.
        if (p.currentTimeline.getFirstWindowIndex(/* shuffleModeEnabled = */ true) == current) return
        val rest = (0 until p.mediaItemCount).filter { it != current }.shuffled()
        val order = (listOf(current) + rest).toIntArray()
        p.setShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order, System.currentTimeMillis()))
    }

    /** Persists the playing book's current file uri and offset as its resume point. No-op for plain
     *  music queues (no [bookFolderKey]) or before a track is loaded. */
    private fun saveBookPosition() {
        val key = bookFolderKey ?: return
        val p = player ?: return
        val uri = p.currentMediaItem?.mediaId ?: return
        Settings.setBookPos(this, key, uri, p.currentPosition.coerceAtLeast(0L))
    }

    /** Arms a fixed-duration sleep timer that pauses playback after [minutes] (replaces any pending
     *  timer; a non-positive value just cancels). */
    private fun armSleepMinutes(minutes: Int) {
        cancelSleep()
        if (minutes <= 0) return
        val delay = minutes * 60_000L
        sleepDeadlineMs = SystemClock.elapsedRealtime() + delay
        saveHandler.postDelayed(sleepRunnable, delay)
    }

    /** Arms an "until end of chapter" timer: the next auto track-transition pauses playback. */
    private fun armSleepChapter() {
        cancelSleep()
        sleepEndOfChapter = true
    }

    /** Cancels any pending sleep timer. */
    private fun cancelSleep() {
        saveHandler.removeCallbacks(sleepRunnable)
        sleepDeadlineMs = 0L
        sleepEndOfChapter = false
    }

    /** Pauses playback and disarms — the one-shot effect of a fired timer. */
    private fun fireSleep() {
        cancelSleep()
        player?.pause()
    }

    /** Current sleep-timer state for the controller (mode + deadline). */
    private fun sleepStateBundle(): Bundle {
        val mode = when {
            sleepEndOfChapter -> 2
            sleepDeadlineMs > 0L -> 1
            else -> 0
        }
        return Bundle().apply {
            putInt(KEY_SLEEP_MODE, mode)
            putLong(KEY_SLEEP_DEADLINE, if (mode == 1) sleepDeadlineMs else -1L)
        }
    }

    /** Grants the ReplayGain command to controllers and applies the toggle live on request. */
    private inner class SessionCallback : MediaSession.Callback {
        @UnstableApi
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(CMD_VOLUME_NORM, Bundle.EMPTY))
                .add(SessionCommand(CMD_SKIP_SILENCE, Bundle.EMPTY))
                .add(SessionCommand(CMD_TRACK_GAP, Bundle.EMPTY))
                .add(SessionCommand(CMD_BOOK_MODE, Bundle.EMPTY))
                .add(SessionCommand(CMD_SLEEP_TIMER, Bundle.EMPTY))
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
            if (customCommand.customAction == CMD_VOLUME_NORM) {
                normMode = Settings.getVolumeNorm(this@PlayerService)
                applyNorm()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_SKIP_SILENCE) {
                applySkipSilence()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_TRACK_GAP) {
                applySkipSilence()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_BOOK_MODE) {
                val newKey = args.getString(KEY_BOOK_FOLDER)?.takeIf { it.isNotEmpty() }
                // A key change is the outgoing book's last chance to persist its exact position:
                // the activity detaches (empty key) before mutating the queue, so the current
                // item still belongs to the old book here. No-op when no book was tracked.
                if (newKey != bookFolderKey) saveBookPosition()
                bookFolderKey = newKey
                // Switching between a music and a book queue flips whether leveling applies —
                // and whether skip-silence does (books keep it even while a music gap is set).
                applyNorm()
                applySkipSilence()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == CMD_SLEEP_TIMER) {
                when (args.getString(KEY_SLEEP_ACTION)) {
                    "minutes" -> armSleepMinutes(args.getInt(KEY_SLEEP_MINUTES))
                    "chapter" -> armSleepChapter()
                    "off" -> cancelSleep()
                    // "query" only reads the state returned below.
                }
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS, sleepStateBundle())
                )
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }

    /** Applies the active [normMode]: nothing (Off), per-track tag gain (ReplayGain), or the
     *  real-time compressor/limiter (AutoLevel). The two leveling paths are mutually exclusive.
     *  Leveling is music-only — a book queue ([bookFolderKey] set) always plays raw, since
     *  compressing speech and squashing its pauses hurts more than it helps. */
    private fun applyNorm() {
        val p = player ?: return
        val mode = if (bookFolderKey != null) VolumeNorm.Off else normMode
        when (mode) {
            VolumeNorm.AutoLevel -> {
                // Tag-based path off; the audio-session effect does the work, leaving player volume flat.
                p.volume = 1f
                releaseEnhancer()
                ensureLeveler()
                leveler?.enabled = true
            }
            VolumeNorm.ReplayGain -> {
                // The compressor isn't used here; detach it so nothing extra sits on the session.
                releaseLeveler()
                applyReplayGain(p, currentTrackGainDb)
            }
            VolumeNorm.Off -> {
                // Attach nothing: a disabled-but-attached effect still perturbs the output.
                p.volume = 1f
                releaseEnhancer()
                releaseLeveler()
            }
        }
    }

    private fun applySkipSilence() {
        player?.skipSilenceEnabled = effectiveSkipSilenceEnabled()
    }

    // The gap is music-only, so it only supersedes skip-silence for music queues (where it would
    // cut the generated silence away); a book queue keeps skip-silence regardless of the gap.
    private fun effectiveSkipSilenceEnabled(): Boolean =
        Settings.isSkipSilenceEnabled(this) &&
            (bookFolderKey != null || Settings.getTrackGapSeconds(this) == 0)

    /** Lazily creates the real-time leveler on the current session (no-op until a session exists). */
    private fun ensureLeveler() {
        if (leveler == null) audioSessionId?.let { leveler = AutoLevel.create(it) }
    }

    private fun releaseLeveler() {
        leveler?.release()
        leveler = null
    }

    /** Lazily creates the loudness effect used for positive ReplayGain boost. */
    private fun ensureEnhancer() {
        if (enhancer == null) audioSessionId?.let {
            enhancer = try {
                LoudnessEnhancer(it)
            } catch (e: Exception) {
                Log.w(TAG, "LoudnessEnhancer unavailable; ReplayGain boost is a no-op", e)
                null
            }
        }
    }

    private fun releaseEnhancer() {
        enhancer?.release()
        enhancer = null
    }

    /** Applies the current track's ReplayGain (capped at +12 dB), or resets to unity when [db] is
     *  null (untagged track). Tracks that need no boost *release* the enhancer rather than disable
     *  it — the attach-nothing rule (see [audioSessionId]) applies within a mode too; it is
     *  recreated lazily on the next boosted track. */
    private fun applyReplayGain(p: ExoPlayer, db: Float?) {
        if (db == null) {
            p.volume = 1f
            releaseEnhancer()
            return
        }
        if (db <= 0f) {
            // Attenuate quietly and precisely via the player volume.
            p.volume = ReplayGain.attenuationVolume(db)
            releaseEnhancer()
        } else {
            // Boost via the loudness effect (player volume can't exceed 1.0).
            p.volume = 1f
            ensureEnhancer()
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
        saveHandler.removeCallbacks(sleepRunnable)
        Settings.flush() // make sure the final position write reaches disk before the process can die
        enhancer?.release()
        enhancer = null
        leveler?.release()
        leveler = null
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    /** Adds the configured inter-track gap as actual silent audio inside each music media item
     *  (book items pass through untouched — the gap is music-only).
     *
     *  The old implementations paused the player between files. A pause can make
     *  MediaSessionService leave foreground playback, which lets Android kill the service while the
     *  screen is off. Keeping the player continuously playing silent samples preserves the lock-screen
     *  session and avoids rebuilding the visible queue with synthetic items.
     *
     *  The silence is appended after the track. ConcatenatingMediaSource2 refuses a progressive
     *  (mp3/flac) source whose duration it doesn't yet know ("must define an initial placeholder
     *  duration"), so the track is added with a placeholder: the cached duration when known
     *  (accurate, no timeline jump), else a large fallback so a book's resume seek is never clamped
     *  short before the real duration arrives and replaces the placeholder. */
    private class GapMediaSourceFactory(context: android.content.Context) : MediaSource.Factory {
        private val app = context.applicationContext
        private val delegate = DefaultMediaSourceFactory(app)

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val source = delegate.createMediaSource(mediaItem)
            val gapSec = Settings.getTrackGapSeconds(app)
            if (gapSec <= 0) return source
            // The gap is a music feature: book items (stamped at scan time) keep their natural
            // chapter flow — and their skip-silence, see effectiveSkipSilenceEnabled.
            if (mediaItem.mediaMetadata.extras
                    ?.getBoolean(MusicScanner.EXTRA_IS_BOOK) == true) return source
            val silence = SilenceMediaSource(gapSec * US_PER_SECOND)
            val placeholderMs = DurationCache.peek(app, mediaItem.mediaId) ?: GAP_PLACEHOLDER_MS
            return ConcatenatingMediaSource2.Builder()
                .setMediaItem(mediaItem)
                .add(source, placeholderMs)
                .add(silence)
                .build()
        }

        override fun getSupportedTypes(): IntArray = delegate.supportedTypes

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider
        ): MediaSource.Factory {
            delegate.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: LoadErrorHandlingPolicy
        ): MediaSource.Factory {
            delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }
    }
}
