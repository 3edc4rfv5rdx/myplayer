package com.myplayer

import android.content.Context
import android.content.SharedPreferences

/** Single place for persisted settings: chosen root folder and ReplayGain toggle. */
object Prefs {
    const val NAME = "myplayer_prefs"
    const val KEY_REPLAYGAIN = "replaygain_enabled"
    private const val KEY_FOLDER = "folder_uri"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getFolderUri(context: Context): String? =
        prefs(context).getString(KEY_FOLDER, null)

    fun setFolderUri(context: Context, uri: String) {
        prefs(context).edit().putString(KEY_FOLDER, uri).apply()
    }

    fun isReplayGainEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REPLAYGAIN, false)

    fun setReplayGainEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REPLAYGAIN, enabled).apply()
    }
}
