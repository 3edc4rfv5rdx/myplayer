package com.myplayer

import android.content.Context
import java.io.File

/** App settings stored as a simple INI file (key=value) in the app's private storage. */
object Settings {
    private const val FILE = "settings.ini"
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_REPLAYGAIN = "replaygain"

    private var loaded = false
    private val values = mutableMapOf<String, String>()

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val f = file(context)
        if (f.exists()) {
            f.forEachLine { raw ->
                val line = raw.trim()
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    val eq = line.indexOf('=')
                    if (eq > 0) values[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
                }
            }
        }
        loaded = true
    }

    @Synchronized
    private fun put(context: Context, key: String, value: String) {
        ensureLoaded(context)
        values[key] = value
        file(context).writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    fun getFolderUri(context: Context): String? {
        ensureLoaded(context)
        return values[KEY_FOLDER]
    }

    fun setFolderUri(context: Context, uri: String) = put(context, KEY_FOLDER, uri)

    fun isReplayGainEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return values[KEY_REPLAYGAIN] == "true"
    }

    fun setReplayGainEnabled(context: Context, enabled: Boolean) =
        put(context, KEY_REPLAYGAIN, enabled.toString())
}
