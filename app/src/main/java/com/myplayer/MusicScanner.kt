package com.myplayer

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** A serializable folder/file entry within a SAF tree (no DocumentFile overhead). */
data class Node(val documentId: String, val name: String, val isDir: Boolean)

/** A folder could not be read (revoked permission, unavailable provider, null cursor). Distinct
 *  from a genuinely empty folder so callers don't cache emptiness or crash on access loss. */
class ScanException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Lists and collects mp3/flac from a SAF tree via DocumentsContract (fast, cache-friendly). */
object MusicScanner {

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac")

    // MediaItem extras carry provider-agnostic navigation data captured at scan time, so the app
    // never has to reparse a SAF documentId as a slash-separated filesystem path (not all providers
    // honor that). Single source for the producer here and the consumers in MainActivity (follow,
    // playingFolder restore, root-removal checks). PATH_IDS/PATH_NAMES run root..containing folder.
    const val EXTRA_TREE_URI = "com.myplayer.TREE_URI"
    const val EXTRA_PLAY_FOLDER_ID = "com.myplayer.PLAY_FOLDER_ID"
    const val EXTRA_PATH_IDS = "com.myplayer.PATH_IDS"
    const val EXTRA_PATH_NAMES = "com.myplayer.PATH_NAMES"

    /** Root node of the granted [treeUri]. */
    fun rootNode(context: Context, treeUri: Uri): Node {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val name = queryName(context, treeUri, docId) ?: treeUri.lastPathSegment ?: "root"
        return Node(docId, name, true)
    }

    private fun queryName(context: Context, treeUri: Uri, documentId: String): String? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        }
        return null
    }

    /** Immediate subfolders and audio files of [parent], each sorted by name.
     *  @throws ScanException if the provider can't be queried (don't confuse with an empty folder). */
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
        val cursor = try {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (e: Exception) {
            throw ScanException("Cannot query ${parent.name}", e)
        } ?: throw ScanException("Null cursor for ${parent.name}")
        cursor.use { c ->
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

    /** All audio under the folder at the end of [path] (root..folder), recursively, as MediaItems.
     *  [path] is recorded on each item as its ancestor chain; its last node is the play-start folder.
     *  Iterative (explicit stack) so a deeply nested tree can't overflow the call stack.
     *  @throws ScanException if the chosen folder itself can't be read (so the caller can say
     *  "unreadable" rather than "nothing to play"); unreadable *subfolders* are skipped, not fatal. */
    fun collectAudio(context: Context, treeUri: Uri, path: List<Node>): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val playFolderId = path.last().documentId
        val stack = ArrayDeque<List<Node>>()
        stack.addLast(path)
        var isTarget = true
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            // Go through the cache so a recursive walk (e.g. "Play this folder") also fills it.
            // An unreadable subfolder is skipped; an unreadable target folder is reported.
            val target = isTarget
            isTarget = false
            val (subFolders, files) = try {
                FolderCache.children(context, treeUri, cur.last())
            } catch (e: ScanException) {
                if (target) throw e
                continue
            }
            if (files.isNotEmpty()) {
                val ctx = PathContext.of(treeUri, cur, playFolderId)
                for (file in files) out.add(mediaItem(treeUri, file, ctx))
            }
            // Push subfolders reversed so they pop back in sorted order (pre-order DFS).
            for (i in subFolders.indices.reversed()) stack.addLast(cur + subFolders[i])
        }
        return out
    }

    /** MediaItems for [files] sitting directly inside the folder at the end of [path] (root..folder). */
    fun mediaItems(treeUri: Uri, path: List<Node>, files: List<Node>): List<MediaItem> {
        val ctx = PathContext.of(treeUri, path, path.last().documentId)
        return files.map { mediaItem(treeUri, it, ctx) }
    }

    /** Per-folder navigation data shared by every file in that folder, so the path id/name arrays
     *  and subtitle are computed once per folder instead of once per file. */
    private class PathContext(
        val treeUri: String,
        val playFolderId: String,
        val pathIds: Array<String>,
        val pathNames: Array<String>,
        val subtitle: String
    ) {
        companion object {
            fun of(treeUri: Uri, path: List<Node>, playFolderId: String): PathContext = PathContext(
                treeUri.toString(),
                playFolderId,
                path.map { it.documentId }.toTypedArray(),
                path.map { it.name }.toTypedArray(),
                displayDir(path)
            )
        }
    }

    private fun mediaItem(treeUri: Uri, file: Node, ctx: PathContext): MediaItem {
        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId)
        val title = file.name.substringBeforeLast('.')
        val extras = Bundle().apply {
            putString(EXTRA_TREE_URI, ctx.treeUri)
            putString(EXTRA_PLAY_FOLDER_ID, ctx.playFolderId)
            putStringArray(EXTRA_PATH_IDS, ctx.pathIds)
            putStringArray(EXTRA_PATH_NAMES, ctx.pathNames)
        }
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(uri.toString())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(ctx.subtitle)
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /** Folder path for the subtitle: ancestor names below the root (empty if directly in the root). */
    private fun displayDir(path: List<Node>): String =
        path.drop(1).joinToString("/") { it.name }

    private fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
}
