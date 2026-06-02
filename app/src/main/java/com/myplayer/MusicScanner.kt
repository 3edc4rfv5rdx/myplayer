package com.myplayer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Folder navigation + collecting playable mp3/flac files from a SAF tree. */
object MusicScanner {

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac")

    /** Wraps a SAF tree URI as a DocumentFile, or null if access is gone. */
    fun treeFolder(context: Context, treeUri: Uri): DocumentFile? =
        DocumentFile.fromTreeUri(context, treeUri)

    /** All mp3/flac under [dir] (recursively) as playable MediaItems. */
    fun collectAudio(dir: DocumentFile): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        collect(dir, out)
        return out
    }

    private fun collect(dir: DocumentFile, out: MutableList<MediaItem>) {
        for (file in dir.listFiles()) {
            when {
                file.isDirectory -> collect(file, out)
                file.isFile && isAudio(file.name) -> out.add(toMediaItem(file))
            }
        }
    }

    private fun isAudio(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext in AUDIO_EXTENSIONS
    }

    private fun toMediaItem(file: DocumentFile): MediaItem {
        val title = file.name?.substringBeforeLast('.') ?: "Unknown"
        val metadata = MediaMetadata.Builder().setTitle(title).build()
        return MediaItem.Builder()
            .setUri(file.uri)
            .setMediaId(file.uri.toString())
            .setMediaMetadata(metadata)
            .build()
    }
}
