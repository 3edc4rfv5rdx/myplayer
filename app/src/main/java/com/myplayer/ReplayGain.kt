package com.myplayer

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import kotlin.math.pow

/** Reads REPLAYGAIN_TRACK_GAIN tags (FLAC Vorbis comments / MP3 ID3 TXXX) and converts dB to a
 *  linear multiplier for [GainAudioProcessor]. */
object ReplayGain {

    private const val TAG_KEY = "REPLAYGAIN_TRACK_GAIN"
    private const val PREAMP_DB = 0f
    private const val MAX_LINEAR = 4f // cap at +12 dB to avoid heavy clipping

    /** Returns the track gain in dB if present in the stream metadata, else null. */
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

    fun toLinear(db: Float): Float =
        10.0.pow(((db + PREAMP_DB) / 20.0)).toFloat().coerceIn(0f, MAX_LINEAR)
}
