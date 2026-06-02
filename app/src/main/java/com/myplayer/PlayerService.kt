package com.myplayer

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/** Holds the ExoPlayer + MediaSession so playback survives screen-off and shows shade controls.
 *  Applies ReplayGain through a custom audio processor when enabled in [Prefs]. */
class PlayerService : MediaSessionService() {

    private var session: MediaSession? = null
    private val gainProcessor = GainAudioProcessor()
    private var currentTrackGainDb: Float? = null

    private val prefsListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Prefs.KEY_REPLAYGAIN) updateGain()
        }

    override fun onCreate() {
        super.onCreate()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // force 16-bit PCM for GainAudioProcessor
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(gainProcessor))
                    .build()
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Play everything from the folder in random order without repeats, reshuffle on loop.
        player.shuffleModeEnabled = true
        player.repeatMode = Player.REPEAT_MODE_ALL

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentTrackGainDb = null
                updateGain()
            }

            override fun onMetadata(metadata: Metadata) {
                ReplayGain.parseTrackGainDb(metadata)?.let {
                    currentTrackGainDb = it
                    updateGain()
                }
            }
        })

        session = MediaSession.Builder(this, player).build()
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
    }

    private fun updateGain() {
        val db = currentTrackGainDb
        gainProcessor.gain =
            if (Prefs.isReplayGainEnabled(this) && db != null) ReplayGain.toLinear(db) else 1f
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
