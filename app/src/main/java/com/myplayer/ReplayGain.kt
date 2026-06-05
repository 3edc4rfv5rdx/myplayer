package com.myplayer

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlin.math.pow

/** Reads REPLAYGAIN_TRACK_GAIN tags (FLAC Vorbis comments / MP3 ID3 TXXX); returns the gain in dB. */
object ReplayGain {

    private const val TAG_KEY = "REPLAYGAIN_TRACK_GAIN"

    /** Cap positive gain to avoid heavy clipping on boost. */
    const val MAX_BOOST_DB = 12f

    /** Returns the track gain in dB if present in the stream metadata, else null. */
    @UnstableApi
    fun parseTrackGainDb(metadata: Metadata): Float? {
        for (i in 0 until metadata.length()) {
            val raw: String? = when (val entry = metadata.get(i)) {
                is VorbisComment ->
                    if (entry.key.equals(TAG_KEY, ignoreCase = true)) entry.value else null
                is TextInformationFrame ->
                    if (entry.description.equals(TAG_KEY, ignoreCase = true))
                        entry.values.firstOrNull() else null
                else -> null
            }
            if (raw != null) return parseDb(raw)
        }
        return null
    }

    /** Parses values like "-6.48 dB" or "-6.48". */
    private fun parseDb(raw: String): Float? =
        raw.trim().substringBefore(' ').removeSuffix("dB").trim().toFloatOrNull()

    /** Linear volume multiplier (<= 1.0) for a non-positive [db]; for attenuation via player volume. */
    fun attenuationVolume(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    /** Capped boost in millibels for a positive [db], for LoudnessEnhancer (1 dB = 100 mB). */
    fun boostMillibels(db: Float): Int = (db.coerceAtMost(MAX_BOOST_DB) * 100).toInt()
}
