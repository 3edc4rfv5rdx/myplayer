package com.myplayer

import android.media.audiofx.DynamicsProcessing
import android.util.Log

/** Real-time loudness leveling via Android's [DynamicsProcessing]: one full-range compressor band
 *  with makeup gain, followed by a brick-wall limiter. Quiet tracks are lifted and loud ones reined
 *  in on the fly, with no per-file analysis — the trade-off versus tag-based ReplayGain is squashed
 *  dynamics rather than exact track-to-track matching. Used for [VolumeNorm.AutoLevel]. */
object AutoLevel {
    private const val TAG = "AutoLevel"

    // Single broadband compressor: start well below 0 dBFS so quiet music lifts, then make up the loss.
    private const val BAND_CUTOFF_HZ = 20_000f
    private const val MBC_THRESHOLD_DB = -28f
    private const val MBC_RATIO = 4f
    private const val MBC_ATTACK_MS = 15f
    private const val MBC_RELEASE_MS = 300f
    private const val MBC_KNEE_DB = 8f
    private const val MAKEUP_GAIN_DB = 12f
    // Brick-wall limiter just under 0 dBFS so the makeup gain can't clip.
    private const val LIMITER_THRESHOLD_DB = -1f
    private const val LIMITER_RATIO = 20f
    private const val LIMITER_ATTACK_MS = 1f
    private const val LIMITER_RELEASE_MS = 60f

    /** Builds a configured (but disabled) effect on [audioSessionId], or null if the platform can't
     *  provide one. The caller toggles [DynamicsProcessing.setEnabled] to apply it. */
    fun create(audioSessionId: Int): DynamicsProcessing? = try {
        // Probe the output channel count so every channel gets the same processing.
        val channels = DynamicsProcessing(audioSessionId).let { probe ->
            val c = probe.channelCount
            probe.release()
            c
        }
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            channels,
            /* preEqInUse = */ false, /* preEqBandCount = */ 0,
            /* mbcInUse = */ true, /* mbcBandCount = */ 1,
            /* postEqInUse = */ false, /* postEqBandCount = */ 0,
            /* limiterInUse = */ true
        ).build()
        val dp = DynamicsProcessing(0, audioSessionId, config)
        for (ch in 0 until channels) {
            val mbc = dp.getMbcByChannelIndex(ch)
            mbc.getBand(0).apply {
                isEnabled = true
                cutoffFrequency = BAND_CUTOFF_HZ
                attackTime = MBC_ATTACK_MS
                releaseTime = MBC_RELEASE_MS
                ratio = MBC_RATIO
                threshold = MBC_THRESHOLD_DB
                kneeWidth = MBC_KNEE_DB
                preGain = 0f
                postGain = MAKEUP_GAIN_DB
            }
            dp.setMbcByChannelIndex(ch, mbc)
            dp.getLimiterByChannelIndex(ch).apply {
                isEnabled = true
                attackTime = LIMITER_ATTACK_MS
                releaseTime = LIMITER_RELEASE_MS
                ratio = LIMITER_RATIO
                threshold = LIMITER_THRESHOLD_DB
                postGain = 0f
            }.also { dp.setLimiterByChannelIndex(ch, it) }
        }
        dp.enabled = false
        dp
    } catch (e: Exception) {
        Log.w(TAG, "DynamicsProcessing unavailable; auto-level is a no-op", e)
        null
    }
}
