package com.myplayer

import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlin.math.pow

/** Holds the ExoPlayer + MediaSession (background playback + shade controls).
 *  ReplayGain is applied without touching the render pipeline: attenuation via player volume,
 *  boost via a LoudnessEnhancer audio effect. Toggled live from [Settings]. */
class PlayerService : MediaSessionService() {

    private var session: MediaSession? = null
    private var player: ExoPlayer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var currentTrackGainDb: Float? = null

    override fun onCreate() {
        super.onCreate()

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
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                enhancer?.release()
                enhancer = try {
                    LoudnessEnhancer(audioSessionId)
                } catch (e: Exception) {
                    null
                }
                applyGain()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTrackGainDb = null
                applyGain()
            }

            override fun onMetadata(metadata: Metadata) {
                ReplayGain.parseTrackGainDb(metadata)?.let {
                    currentTrackGainDb = it
                    applyGain()
                }
            }
        })

        this.player = player
        session = MediaSession.Builder(this, player).build()
    }

    /** Applies the current track's ReplayGain (capped at +12 dB) when enabled in [Settings]. */
    private fun applyGain() {
        val p = player ?: return
        val db = currentTrackGainDb
        if (!Settings.isReplayGainEnabled(this) || db == null) {
            p.volume = 1f
            enhancer?.enabled = false
            return
        }
        val capped = db.coerceAtMost(12f)
        if (capped <= 0f) {
            // Attenuate quietly and precisely via the player volume.
            p.volume = 10f.pow(capped / 20f)
            enhancer?.enabled = false
        } else {
            // Boost via the loudness effect (player volume can't exceed 1.0).
            p.volume = 1f
            enhancer?.let {
                runCatching {
                    it.setTargetGain((capped * 100).toInt())
                    it.enabled = true
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
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
