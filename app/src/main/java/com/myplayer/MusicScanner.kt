package com.myplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** A serializable folder/file entry within a SAF tree (no DocumentFile overhead). */
data class Node(val documentId: String, val name: String, val isDir: Boolean)

/** Lists and collects mp3/flac from a SAF tree via DocumentsContract (fast, cache-friendly). */
object MusicScanner {

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac")

    /** Root node of the granted [treeUri]. */
    fun rootNode(context: Context, treeUri: Uri): Node {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val name = queryName(context, treeUri, docId) ?: treeUri.lastPathSegment ?: "root"
        return Node(docId, name, true)
    }

    private fun queryName(context: Context, treeUri: Uri, documentId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    /** Immediate subfolders and audio files of [parent], each sorted by name. */
    fun children(context: Context, treeUri: Uri, parent: Node): Pair<List<Node>, List<Node>> {
        val folders = ArrayList<Node>()
        val files = ArrayList<Node>()
        val childrenUri =
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent.documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    folders.add(Node(id, name, true))
                } else if (isAudio(name)) {
                    files.add(Node(id, name, false))
                }
            }
        }
        folders.sortBy { it.name.lowercase() }
        files.sortBy { it.name.lowercase() }
        return folders to files
    }

    /** All audio under [folder] (recursively) as playable MediaItems. */
    fun collectAudio(context: Context, treeUri: Uri, folder: Node): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        collect(context, treeUri, folder, out)
        return out
    }

    private fun collect(context: Context, treeUri: Uri, folder: Node, out: MutableList<MediaItem>) {
        // Go through the cache so a recursive walk (e.g. "Play this folder") also fills it.
        val (subFolders, files) = FolderCache.children(context, treeUri, folder)
        for (file in files) out.add(mediaItem(treeUri, file))
        for (sub in subFolders) collect(context, treeUri, sub, out)
    }

    fun mediaItems(treeUri: Uri, files: List<Node>): List<MediaItem> =
        files.map { mediaItem(treeUri, it) }

    private fun mediaItem(treeUri: Uri, file: Node): MediaItem {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId)
        val title = file.name.substringBeforeLast('.')
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setSubtitle(relativeDir(treeUri, file)).build()
            )
            .build()
    }

    /** Folder path of [file] relative to the root, without the filename (empty if directly in root). */
    private fun relativeDir(treeUri: Uri, file: Node): String {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rel = if (file.documentId.startsWith(rootId)) {
            file.documentId.substring(rootId.length).trimStart('/')
        } else {
            file.documentId.substringAfter(':', file.documentId)
        }
        return if (rel.contains('/')) rel.substringBeforeLast('/') else ""
    }

    private fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
}
