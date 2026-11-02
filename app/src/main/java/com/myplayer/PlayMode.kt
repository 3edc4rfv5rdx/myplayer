package com.myplayer

import android.os.Bundle

/** How a music queue plays, picked from the mode button. Not persisted. The service drops back
 *  to [Normal] when the queue is cleared or ends, when another folder or a book starts, and —
 *  but for [RepeatAll] — on pause; [RepeatOne] also on a skip to another track. */
enum class PlayMode {
    Normal,

    /** The current track loops until the user stops or skips. */
    RepeatOne,

    /** The whole queue loops, reshuffled on every round when shuffle is on. */
    RepeatAll,

    /** A short sample of each track in folder order (shuffle held off), see [introWindow]. */
    Intro;

    companion object {
        /** Session-extras key the service publishes the live mode under. */
        const val KEY = "play_mode"

        /** Command arg: the folder key ([Settings.bookKey]) the mode is picked for, absent for
         *  whatever queue is live. */
        const val KEY_FOLDER = "play_mode_folder"

        /** The mode published in [extras], [Normal] when absent or unknown. */
        fun fromExtras(extras: Bundle): PlayMode =
            entries.firstOrNull { it.name == extras.getString(KEY) } ?: Normal

        fun toExtras(mode: PlayMode, folderKey: String? = null): Bundle = Bundle().apply {
            putString(KEY, mode.name)
            if (folderKey != null) putString(KEY_FOLDER, folderKey)
        }
    }
}

/** Where an intro sample starts into a track, when the track is long enough. */
const val INTRO_SKIP_MS = 45_000L

/** How long an intro sample plays. */
const val INTRO_PLAY_MS = 15_000L

/** The intro sample of a track [trackMs] long, as start to end in ms: [INTRO_PLAY_MS] from
 *  [INTRO_SKIP_MS] in; a shorter track plays its last [INTRO_PLAY_MS], one shorter still in full. */
fun introWindow(trackMs: Long): Pair<Long, Long> {
    val start = INTRO_SKIP_MS.coerceAtMost((trackMs - INTRO_PLAY_MS).coerceAtLeast(0L))
    return start to (start + INTRO_PLAY_MS).coerceAtMost(trackMs)
}
