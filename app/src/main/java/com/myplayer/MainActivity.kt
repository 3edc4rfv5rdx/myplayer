package com.myplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { Browser, Settings }

// Keys for the browser state saved across activity recreation (rotation, background return).
private const val STATE_TREE = "tree_uri"
private const val STATE_SELECTED_ROOT = "selected_root"
private const val STATE_PATH_IDS = "path_ids"
private const val STATE_PATH_NAMES = "path_names"
private const val STATE_SELECTED = "selected_index"
private const val STATE_SCREEN = "screen"
private const val STATE_PLAY_FOLDER = "play_folder_id"
private const val STATE_SHUFFLE = "shuffle"
private const val STATE_PLAYING_ABOOK = "playing_abook"
private const val STATE_PLAYING_DOC_ID = "playing_doc_id"
private const val STATE_VISITED_PATH_IDS = "visited_path_ids"

// Discrete slider positions between SPEED_MIN and SPEED_MAX (endpoints excluded), one per SPEED_STEP.
private val SPEED_SLIDER_STEPS =
    ((Settings.SPEED_MAX - Settings.SPEED_MIN) / Settings.SPEED_STEP).roundToInt() - 1

// Fixed jump for the rewind/fast-forward buttons.
private const val SEEK_STEP_MS = 30_000L

// Shared geometry for the control grid: every button is the same width/height, the play rectangle
// is exactly two buttons plus the gap tall, and rows/stacks are spaced by half a button.
private val CONTROL_BTN_WIDTH = 88.dp
private val CONTROL_BTN_HEIGHT = 36.dp
private val CONTROL_GAP = 18.dp
private val PLAY_HEIGHT = CONTROL_BTN_HEIGHT * 2 + CONTROL_GAP

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controllerState = mutableStateOf<MediaController?>(null)
    private val errorState = mutableStateOf<String?>(null)

    // Ordered list of root folders. The home screen lists these; null treeUri/empty path == home.
    private val rootsState = mutableStateOf<List<Uri>>(emptyList())
    private val treeUriState = mutableStateOf<Uri?>(null)
    // Root last entered; stays highlighted in the home list even after navigating back to it.
    private val selectedRootState = mutableStateOf<Uri?>(null)
    private val pathState = mutableStateOf<List<Node>>(emptyList())
    private val selectedIndexState = mutableStateOf<Int?>(null)
    // documentId of the currently playing track; highlighted and followed in the browser.
    private val playingDocIdState = mutableStateOf<String?>(null)
    // documentIds of the last visited path's folders — set by entering a folder and by playback
    // (the playing track's ancestors) — the highlight shown on folder rows at every level, so
    // coming back up still shows where you were. Cleared by entering a root.
    private val visitedPathIdsState = mutableStateOf<Set<String>>(emptySet())
    private val screenState = mutableStateOf(Screen.Browser)
    private val rescanTickState = mutableStateOf(0)
    private val clearTitleTickState = mutableStateOf(0)
    private val themeState = mutableStateOf(ThemeMode.System)

    // Random playback order, on by default; intentionally not persisted (resets each launch).
    private val shuffleState = mutableStateOf(true)

    // Audiobook mode of the folder currently open in the browser (persisted per folder). Mirrors
    // Settings.isAbook for the open folder; kept in sync as the browser navigates.
    private val abookState = mutableStateOf(false)

    // Whether the open folder sits inside a book (an ancestor is flagged abook). Book mode covers
    // the whole subtree, so the checkbox then shows checked and is locked — the mode belongs to
    // the ancestor and is only editable there.
    private val abookInheritedState = mutableStateOf(false)

    // Whether the live queue is a book (set when a queue is installed). Gates the book progress
    // readout, which is meaningless for shuffled music.
    private val playingAbookState = mutableStateOf(false)

    // The book open in the browser (own flag or inherited): its root's key and saved speed. The
    // speed button edits *this* book — the same referent as the abook checkbox — never some other
    // folder's live queue. Null/default in plain folders and on the home screen.
    private val browsedBookKeyState = mutableStateOf<String?>(null)
    private val browsedBookSpeedState = mutableStateOf(Settings.SPEED_DEFAULT)

    // Book key of the folder the live queue plays from; null when nothing is playing. Locks the
    // abook checkbox of that folder, and gates applying a speed edit to the live player (only when
    // the browsed book *is* the live queue). Set when a queue is installed.
    private val playingFolderKeyState = mutableStateOf<String?>(null)

    // documentId of the folder the current playback was started from.
    private var playingFolderId: String? = null

    // Cached so onMediaItemTransition needn't read the DB on every track change.
    private var followEnabled = true

    // The in-flight folder/file scan; cancelled when a newer Play request supersedes it so the last
    // request wins (not the last scan to finish).
    private var playbackLoadJob: Job? = null

    // The in-flight folder deletion; used to ignore a second confirm while a delete is still running.
    private var deleteJob: Job? = null

    /** Adds a root folder. This is the only place a SAF permission is requested. */
    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                rootsState.value = Settings.addRoot(this, uri.toString()).map(Uri::parse)
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        themeState.value = Settings.getThemeMode(this)
        followEnabled = Settings.isFollowEnabled(this)
        rootsState.value = Settings.getRoots(this).map(Uri::parse)
        savedInstanceState?.let(::restoreUiState)

        setContent {
            val theme by themeState
            val dark = when (theme) {
                ThemeMode.System -> isSystemInDarkTheme()
                ThemeMode.Light -> false
                ThemeMode.Dark -> true
            }
            val colorScheme = if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val controller by controllerState
                    val roots by rootsState
                    val selectedRoot by selectedRootState
                    val treeUri by treeUriState
                    val path by pathState
                    val error by errorState
                    val selectedIndex by selectedIndexState
                    val playingDocId by playingDocIdState
                    val visitedPathIds by visitedPathIdsState
                    val screen by screenState
                    val rescanTick by rescanTickState
                    val clearTitleTick by clearTitleTickState
                    val shuffle by shuffleState
                    val playingAbook by playingAbookState
                    var replayGain by remember { mutableStateOf(Settings.isReplayGainEnabled(this)) }
                    var follow by remember { mutableStateOf(Settings.isFollowEnabled(this)) }
                    var remaining by remember { mutableStateOf(Settings.isRemainingTime(this)) }
                    var defaultSpeed by remember { mutableStateOf(Settings.getDefaultSpeed(this)) }

                    BackHandler(enabled = screen == Screen.Settings || path.isNotEmpty()) {
                        if (screen == Screen.Settings) screenState.value = Screen.Browser else goUp()
                    }

                    when (screen) {
                        Screen.Settings -> SettingsScreen(
                            version = appVersionName(),
                            build = appVersionCode(),
                            themeMode = theme,
                            replayGainEnabled = replayGain,
                            followEnabled = follow,
                            remainingEnabled = remaining,
                            defaultSpeed = defaultSpeed,
                            onThemeChange = {
                                themeState.value = it
                                Settings.setThemeMode(this, it)
                            },
                            onReplayGainChange = {
                                replayGain = it
                                Settings.setReplayGainEnabled(this, it)
                                sendReplayGainChanged()
                            },
                            onFollowChange = {
                                follow = it
                                followEnabled = it
                                Settings.setFollowEnabled(this, it)
                                followPlayingTrack(controllerState.value?.currentMediaItem)
                            },
                            onRemainingChange = {
                                remaining = it
                                Settings.setRemainingTime(this, it)
                            },
                            onDefaultSpeedChange = {
                                defaultSpeed = it
                                Settings.setDefaultSpeed(this, it)
                            },
                            onRescan = {
                                FolderCache.clear(this)
                                rescanTickState.value++
                                screenState.value = Screen.Browser
                            },
                            onBack = { screenState.value = Screen.Browser }
                        )

                        Screen.Browser -> {
                            // The book key of the folder open in the browser; null on the home
                            // screen. Drives the abook checkbox and keeps it in sync as we navigate.
                            val currentFolderKey = treeUri?.let { t ->
                                path.lastOrNull()?.let { Settings.bookKey(t.toString(), it.documentId) }
                            }
                            val abook by abookState
                            val abookInherited by abookInheritedState
                            // Re-keyed on `abook` too so toggling this folder's checkbox (which
                            // doesn't change currentFolderKey) immediately re-resolves the book key
                            // and speed — otherwise the speed button only appears after re-entering.
                            LaunchedEffect(currentFolderKey, abook) {
                                abookState.value = currentFolderKey != null &&
                                    Settings.isAbook(this@MainActivity, currentFolderKey)
                                val t = treeUriState.value
                                val p = pathState.value
                                val bookPath =
                                    if (t != null && p.isNotEmpty()) bookRootPath(t, p) else null
                                // Inherited = the outermost flagged folder is a strict ancestor.
                                abookInheritedState.value =
                                    bookPath != null && bookPath.size < p.size
                                val bookKey = bookPath?.let {
                                    Settings.bookKey(t!!.toString(), it.last().documentId)
                                }
                                browsedBookKeyState.value = bookKey
                                browsedBookSpeedState.value = bookKey
                                    ?.let { Settings.getSpeed(this@MainActivity, it) }
                                    ?: Settings.SPEED_DEFAULT
                            }
                            val browsedBookKey by browsedBookKeyState
                            val browsedBookSpeed by browsedBookSpeedState
                            val playingFolderKey by playingFolderKeyState
                            // Locked when the mode isn't this folder's to edit: inherited from an
                            // ancestor book (editable only there), or the folder is the live
                            // queue's folder — the queue keeps its start mode anyway, so an edit
                            // would silently apply only on the next start — confusing — and worse,
                            // let the flag contradict what is audibly playing.
                            val abookLocked = abookInherited ||
                                (currentFolderKey != null && currentFolderKey == playingFolderKey)
                            PlayerScreen(
                            controller = controller,
                            roots = roots,
                            selectedRoot = selectedRoot,
                            treeUri = treeUri,
                            path = path,
                            selectedIndex = selectedIndex,
                            playingDocId = playingDocId,
                            visitedPathIds = visitedPathIds,
                            rescanTick = rescanTick,
                            clearTitleTick = clearTitleTick,
                            shuffleEnabled = shuffle,
                            onShuffleToggle = {
                                shuffleState.value = it
                                controllerState.value?.shuffleModeEnabled = it
                            },
                            abookEnabled = abook || abookInherited,
                            abookVisible = currentFolderKey != null,
                            abookLocked = abookLocked,
                            playingAbook = playingAbook,
                            remainingTime = remaining,
                            onAbookToggle = { enabled ->
                                currentFolderKey?.let { key ->
                                    abookState.value = enabled
                                    // A pure persisted folder property: it changes how this folder
                                    // plays on its next start (startQueue forces books sequential),
                                    // never the live queue, which keeps the mode stamped on it at
                                    // start (EXTRA_IS_BOOK). While this folder *is* the live queue
                                    // the checkbox is locked, so the flag can't contradict it.
                                    Settings.setAbook(this, key, enabled)
                                }
                            },
                            onEnterRoot = { enterRoot(it) },
                            onOpenHistoryEntry = { openHistoryEntry(it) },
                            onAddRoot = { folderPicker.launch(null) },
                            onRemoveRoot = { removeRoot(it) },
                            onExit = { exitApp() },
                            onOpenSettings = { screenState.value = Screen.Settings },
                            onDescend = {
                                pathState.value = path + it
                                selectedIndexState.value = null
                                // Entering a folder marks it (and its ancestors) as the visited
                                // path, highlighted on the way back up.
                                visitedPathIdsState.value =
                                    (path + it).map { n -> n.documentId }.toSet()
                            },
                            onUp = { goUp() },
                            onDeleteBook = { deleteBook(it) },
                            onSelectFile = { selectedIndexState.value = it },
                            onPlayPause = { togglePlay() },
                            speed = browsedBookSpeed,
                            speedEnabled = browsedBookKey != null,
                            speedLive = browsedBookKey != null &&
                                browsedBookKey == playingFolderKey,
                            onSpeedChange = { s ->
                                browsedBookKey?.let { key ->
                                    Settings.setSpeed(this, key, s)
                                    browsedBookSpeedState.value = s
                                    // The browsed book is also the live queue: apply audibly now.
                                    // Otherwise it's just the saved speed for its next start.
                                    if (key == playingFolderKey) {
                                        controllerState.value?.setPlaybackSpeed(s)
                                    }
                                }
                            }
                            )
                        }
                    }

                    // Errors (connect, unreadable folder, nothing to play, delete, playback) all land
                    // in errorState and surface here as a single dialog, on the app's primary colour.
                    error?.let { msg ->
                        AlertDialog(
                            onDismissRequest = { errorState.value = null },
                            containerColor = MaterialTheme.colorScheme.primary,
                            titleContentColor = MaterialTheme.colorScheme.onPrimary,
                            textContentColor = MaterialTheme.colorScheme.onPrimary,
                            title = { Text(stringResource(R.string.error)) },
                            text = { Text(msg) },
                            confirmButton = {
                                Button(
                                    onClick = { errorState.value = null },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.onPrimary,
                                        contentColor = MaterialTheme.colorScheme.primary
                                    )
                                ) { Text(stringResource(R.string.ok)) }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controllerState.value = c
                errorState.value = null
                c.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        playingFolderId = playFolderIdOf(mediaItem) ?: playingFolderId
                        followPlayingTrack(mediaItem)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        errorState.value = "${error.errorCodeName}: ${error.message}"
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // Queue reached its end (repeat is always off; see Settings.REPEAT_ALL): clear
                        // the now-playing labels, bar, and book progress so a 100%-done book doesn't
                        // linger on screen. Deliberately no mediaItemCount guard: the service clears
                        // the finished queue itself and controller events coalesce, so the clear can
                        // already be visible when STATE_ENDED arrives — a guard would then skip this
                        // and leave shuffle locked (and speed live) on an empty queue. Our own
                        // queue-clearing paths can land here too; they reset this state themselves,
                        // and re-clearing it is a no-op.
                        if (playbackState == Player.STATE_ENDED) {
                            playingAbookState.value = false
                            playingFolderId = null
                            playingFolderKeyState.value = null
                            // The browser highlight survives as a "where I stopped" mark.
                            clearTitleTickState.value++
                        }
                    }
                })
                // The service keeps playing across activity recreation; recover which folder the
                // live playlist was started from so Play in the same folder resumes, not restarts.
                playingFolderId = playFolderIdOf(c.currentMediaItem)
                followPlayingTrack(c.currentMediaItem)
                // "Is the live queue a book?" comes from the mode stamped on the queue at start
                // (EXTRA_IS_BOOK), never the current checkbox value: the abook flag may have been
                // re-toggled for another folder's benefit since, and a flipped flag must not
                // reclassify a queue that is already playing with its original mode.
                val liveIsBook = playIsBookOf(c.currentMediaItem)
                // A paused book stays the live queue across activity recreation (the foreground
                // service outlives the activity): like paused music, it resumes in place with the
                // big Play. Shuffle stays locked while it lingers — the queue genuinely is a book.
                // Reconcile the shuffle toggle with the controller. A live music queue's shuffle is
                // authoritative; a book forces it off (not the user's preference), so don't read a
                // book's value back or the next music would silently un-shuffle. With no live queue
                // the fresh player defaults to shuffle off, so push the UI's value to keep the
                // switch and engine in agreement.
                if (c.mediaItemCount > 0) {
                    if (!liveIsBook) shuffleState.value = c.shuffleModeEnabled
                } else c.shuffleModeEnabled = shuffleState.value
                // Lock shuffle / show book progress only for a genuinely live book; the STATE_ENDED
                // guard keeps a finished-but-still-loaded book from re-locking it.
                playingAbookState.value =
                    c.mediaItemCount > 0 && liveIsBook && c.playbackState != Player.STATE_ENDED
                // Recover which folder the live queue plays from (abook-checkbox lock and live
                // speed-apply both key off it). The speed *value* shown is the browsed book's
                // saved one, so nothing needs mirroring from the player here.
                playingFolderKeyState.value = playFolderTreeUriOf(c.currentMediaItem)?.let { t ->
                    playFolderIdOf(c.currentMediaItem)?.let { Settings.bookKey(t, it) }
                }
            } catch (e: Exception) {
                errorState.value = "Connect: ${e.message}"
            }
        }, MoreExecutors.directExecutor())
        controllerFuture = future
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerState.value = null
        super.onStop()
    }

    /** Persist the browser location so recreation returns here instead of the roots home screen.
     *  The path holds only folders (always dirs), so saving ids+names is enough to rebuild it. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val path = pathState.value
        outState.putString(STATE_TREE, treeUriState.value?.toString())
        outState.putString(STATE_SELECTED_ROOT, selectedRootState.value?.toString())
        outState.putStringArray(STATE_PATH_IDS, path.map { it.documentId }.toTypedArray())
        outState.putStringArray(STATE_PATH_NAMES, path.map { it.name }.toTypedArray())
        outState.putString(STATE_SCREEN, screenState.value.name)
        outState.putString(STATE_PLAY_FOLDER, playingFolderId)
        outState.putBoolean(STATE_SHUFFLE, shuffleState.value)
        outState.putBoolean(STATE_PLAYING_ABOOK, playingAbookState.value)
        // The "where I stopped" highlight must survive recreation: with the queue cleared on
        // navigate-away there may be no live item to re-derive it from on reconnect.
        outState.putString(STATE_PLAYING_DOC_ID, playingDocIdState.value)
        outState.putStringArray(STATE_VISITED_PATH_IDS, visitedPathIdsState.value.toTypedArray())
        selectedIndexState.value?.let { outState.putInt(STATE_SELECTED, it) }
    }

    private fun restoreUiState(state: Bundle) {
        treeUriState.value = state.getString(STATE_TREE)?.let(Uri::parse)
        selectedRootState.value = state.getString(STATE_SELECTED_ROOT)?.let(Uri::parse)
        val ids = state.getStringArray(STATE_PATH_IDS)
        val names = state.getStringArray(STATE_PATH_NAMES)
        if (ids != null && names != null && ids.size == names.size) {
            pathState.value = ids.indices.map { Node(ids[it], names[it], true) }
        }
        selectedIndexState.value = if (state.containsKey(STATE_SELECTED)) state.getInt(STATE_SELECTED) else null
        screenState.value = runCatching { Screen.valueOf(state.getString(STATE_SCREEN) ?: "") }
            .getOrDefault(Screen.Browser)
        playingFolderId = state.getString(STATE_PLAY_FOLDER)
        shuffleState.value = state.getBoolean(STATE_SHUFFLE, true)
        playingAbookState.value = state.getBoolean(STATE_PLAYING_ABOOK, false)
        playingDocIdState.value = state.getString(STATE_PLAYING_DOC_ID)
        visitedPathIdsState.value =
            state.getStringArray(STATE_VISITED_PATH_IDS)?.toSet() ?: emptySet()
    }

    /** Always highlights the playing track wherever it is visible. When Follow is enabled it also
     *  navigates the browser to the track's folder so the list can center it (no-op on the roots
     *  home screen). The folder path comes from the item's extras captured at scan time (no
     *  documentId string-splitting); if they are missing or the root is no longer held, the track is
     *  only highlighted where found and navigation is skipped. */
    private fun followPlayingTrack(item: MediaItem?) {
        // A transition to "no item" (queue cleared or ended) keeps the last highlight as a "where
        // I stopped" mark; it is dropped explicitly only when the content itself goes
        // (stopAndClearQueue(clearHighlight = true): root removed, files deleted).
        if (item == null) return
        val fileDocId = item.mediaId
            .let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() }
        playingDocIdState.value = fileDocId
        item.mediaMetadata.extras?.getStringArray(MusicScanner.EXTRA_PATH_IDS)
            ?.let { visitedPathIdsState.value = it.toSet() }
        if (!followEnabled) return
        if (treeUriState.value == null) return
        val extras = item.mediaMetadata.extras ?: return
        val owner = extras.getString(MusicScanner.EXTRA_TREE_URI)?.let(Uri::parse) ?: return
        val ids = extras.getStringArray(MusicScanner.EXTRA_PATH_IDS) ?: return
        val names = extras.getStringArray(MusicScanner.EXTRA_PATH_NAMES) ?: return
        if (ids.isEmpty() || ids.size != names.size) return
        if (rootsState.value.none { it == owner }) return
        selectedRootState.value = owner
        treeUriState.value = owner
        pathState.value = ids.indices.map { Node(ids[it], names[it], true) }
        selectedIndexState.value = null
    }

    /** The folder id the playlist of [item] was started from, recorded in its extras (or null). */
    private fun playFolderIdOf(item: MediaItem?): String? =
        item?.mediaMetadata?.extras?.getString(MusicScanner.EXTRA_PLAY_FOLDER_ID)

    /** The root tree URI [item] belongs to, recorded in its extras (or null). */
    private fun playFolderTreeUriOf(item: MediaItem?): String? =
        item?.mediaMetadata?.extras?.getString(MusicScanner.EXTRA_TREE_URI)

    /** Whether [item]'s queue was started in book mode — the mode stamped at start (EXTRA_IS_BOOK),
     *  deliberately independent of where the abook checkbox stands now. */
    private fun playIsBookOf(item: MediaItem?): Boolean =
        item?.mediaMetadata?.extras?.getBoolean(MusicScanner.EXTRA_IS_BOOK) == true

    /** Enters [treeUri] from the roots list, showing its top folder and marking it selected. The
     *  folder appears immediately with a cheap fallback label; the real display name is resolved off
     *  the main thread, since the SAF name query can block on a slow provider. */
    private fun enterRoot(treeUri: Uri) {
        selectedRootState.value = treeUri
        treeUriState.value = treeUri
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        pathState.value = listOf(Node(docId, fallbackRootLabel(treeUri), true))
        selectedIndexState.value = null
        // Entering a root starts a fresh walk: the previous visited-path mark is dropped.
        visitedPathIdsState.value = emptySet()
        lifecycleScope.launch {
            val node = withContext(Dispatchers.IO) { MusicScanner.rootNode(this@MainActivity, treeUri) }
            // Apply only if still sitting at this root's top folder (user hasn't navigated away).
            val path = pathState.value
            if (treeUriState.value == treeUri && path.size == 1 && path.first().documentId == docId) {
                pathState.value = listOf(node)
            }
        }
    }

    /** Opens a history entry: restores the browser to that folder (tree + full path) without
     *  playing. Same state shape as [enterRoot], so the user lands on the folder and presses Play. */
    private fun openHistoryEntry(entry: Settings.HistoryEntry) {
        val tree = Uri.parse(entry.treeUri)
        selectedRootState.value = tree
        treeUriState.value = tree
        pathState.value = entry.ids.indices.map { Node(entry.ids[it], entry.names[it], true) }
        selectedIndexState.value = null
        visitedPathIdsState.value = entry.ids.toSet()
    }

    /** Stops playback, drops the queue, and clears the now-playing UI state (labels, bar).
     *  [clearHighlight] also drops the browser's playing-track highlight — wanted when the content
     *  is gone (root removed, files deleted), not when merely navigating away, where the highlight
     *  stays as a "where I stopped" mark. pause() first so onIsPlayingChanged(false) reaches the
     *  UI (and saves a book's resume point) before the queue goes. */
    private fun stopAndClearQueue(clearHighlight: Boolean = true) {
        val controller = controllerState.value ?: return
        controller.pause()
        controller.clearMediaItems()
        controller.stop()
        // The cleared queue belongs to no book: drop the service's key now rather than leaving it
        // stale until the next startQueue. Sent after the clear, so the pause-save above (still
        // under the old key, with the old queue current) is not skipped.
        sendBookMode(controller, null)
        playingFolderId = null
        playingAbookState.value = false
        playingFolderKeyState.value = null
        if (clearHighlight) {
            playingDocIdState.value = null
            visitedPathIdsState.value = emptySet()
        }
        // clearMediaItems() emits no metadata event, so clear the now-playing labels ourselves.
        clearTitleTickState.value++
    }

    /** Navigating away from a stopped/paused track ends it: the queue and the now-playing UI are
     *  dropped (music and book alike), but the track stays highlighted in the browser; a playing
     *  track keeps everything while you browse. A paused book's resume point is already saved, so
     *  Play in its folder picks it back up. */
    private fun clearNowPlayingIfStopped() {
        if (controllerState.value?.isPlaying == true) return
        stopAndClearQueue(clearHighlight = false)
    }

    /** Returns to the roots list (the home screen). */
    private fun goHome() {
        treeUriState.value = null
        pathState.value = emptyList()
        selectedIndexState.value = null
        clearNowPlayingIfStopped()
    }

    /** Up one level; from a root's top folder this returns to the roots list. */
    private fun goUp() {
        val path = pathState.value
        if (path.size > 1) {
            pathState.value = path.dropLast(1)
            selectedIndexState.value = null
            clearNowPlayingIfStopped()
        } else {
            goHome()
        }
    }

    /** Drops [treeUri] from the roots list, releasing its permission and its cached listings. If the
     *  current playlist was started from this root, stop it first so we don't keep playing content
     *  URIs whose permission we just released; playback from other roots is left untouched. */
    private fun removeRoot(treeUri: Uri) {
        val controller = controllerState.value
        val playingThisRoot =
            controller != null &&
                playFolderTreeUriOf(controller.currentMediaItem) == treeUri.toString()
        if (playingThisRoot) stopAndClearQueue()
        runCatching {
            contentResolver.releasePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (selectedRootState.value == treeUri) selectedRootState.value = null
        rootsState.value = Settings.removeRoot(this, treeUri.toString()).map(Uri::parse)
        // Removing a root forgets it entirely: book flags/positions/speeds go with the listings,
        // so re-adding the same tree later starts clean.
        Settings.clearRootState(this, treeUri.toString())
        FolderCache.clearRoot(this, treeUri)
    }

    /** Play/pause. Selected file -> play it. Otherwise: a different folder than what's playing
     *  starts that folder (random); the same paused folder resumes. */
    private fun togglePlay() {
        val controller = controllerState.value ?: return
        if (controller.isPlaying) {
            controller.pause()
            return
        }
        val index = selectedIndexState.value
        val dir = pathState.value.lastOrNull()
        // Compare against the folder a Play here would actually start — the enclosing book root
        // when inside a book — so Play anywhere in a paused book's subtree resumes it in place.
        val playTargetId = treeUriState.value
            ?.let { bookRootPath(it, pathState.value)?.last()?.documentId }
            ?: dir?.documentId
        when {
            index != null && dir != null -> playFile(dir, index)
            dir != null && playTargetId != playingFolderId -> playFolder(dir)
            // Treat an ended queue as no queue: resuming it would restart a finished book from file 1
            // untracked (and with shuffle unlocked). Inside a folder we re-run playFolder, which
            // re-installs book mode/speed/tracking; at home (no folder) there is nothing to resume.
            controller.mediaItemCount > 0 && controller.playbackState != Player.STATE_ENDED ->
                controller.play()
            dir != null -> playFolder(dir)
        }
    }

    /** Fully quits: stops playback, tears down the player service (and its notification), and
     *  removes the task so nothing lingers in the background. */
    private fun exitApp() {
        controllerState.value?.run {
            pause()
            clearMediaItems()
            stop()
        }
        stopService(Intent(this, PlayerService::class.java))
        finishAndRemoveTask()
    }

    /** The path prefix ending at the outermost folder flagged abook, or null when nothing on
     *  [path] is flagged. Book mode is inherited by the whole subtree: any play action inside a
     *  book plays that book, so its queue, resume key, and speed stay the book's own. */
    private fun bookRootPath(tree: Uri, path: List<Node>): List<Node>? {
        for (i in path.indices) {
            if (Settings.isAbook(this, Settings.bookKey(tree.toString(), path[i].documentId))) {
                return path.subList(0, i + 1)
            }
        }
        return null
    }

    /** Plays everything under [folder] recursively. ExoPlayer's shuffle (toggled live) handles the
     *  order; with shuffle on we start on a random track, off starts from the top. Inside a book
     *  the queue is the whole book (recursive from the book root): the book root itself resumes
     *  from the saved position, a subfolder is an explicit jump to that part's first track. */
    private fun playFolder(folder: Node) {
        if (controllerState.value == null) return
        val tree = treeUriState.value ?: return
        val path = pathState.value
        playbackLoadJob?.cancel()
        playbackLoadJob = lifecycleScope.launch {
            // The queue's root and mode are decided here, before the scan, and stamped on every
            // item (EXTRA_IS_BOOK): the live queue keeps them for its whole life even if the
            // checkbox is re-toggled later.
            val bookPath = bookRootPath(tree, path)
            val abook = bookPath != null
            val queuePath = bookPath ?: path
            val key = Settings.bookKey(tree.toString(), queuePath.last().documentId)
            val items = try {
                withContext(Dispatchers.IO) {
                    MusicScanner.collectAudio(this@MainActivity, tree, queuePath, abook)
                }
            } catch (e: ScanException) {
                if (liveController() != null) errorState.value = getString(R.string.folder_unreadable)
                return@launch
            }
            val controller = liveController() ?: return@launch
            if (items.isEmpty()) {
                errorState.value = getString(R.string.nothing_to_play)
                return@launch
            }
            // Playing a folder strictly inside the book jumps to its first track (falls through to
            // the resume logic if that subfolder turned out to hold no audio).
            val jumpIdx = if (abook && queuePath.size < path.size) {
                items.indexOfFirst {
                    it.mediaMetadata.extras?.getStringArray(MusicScanner.EXTRA_PATH_IDS)
                        ?.contains(folder.documentId) == true
                }
            } else -1
            // Resume an abook from its saved file + offset; new books start at 0.
            val saved = if (abook && jumpIdx < 0) Settings.getBookPos(this@MainActivity, key) else null
            val savedIdx = saved?.let { s -> items.indexOfFirst { it.mediaId == s.fileUri } } ?: -1
            val start = when {
                jumpIdx >= 0 -> jumpIdx
                abook -> savedIdx.coerceAtLeast(0)
                shuffleState.value -> items.indices.random()
                else -> 0
            }
            // Rewind for context only after a real break; the rewound position is re-saved on the
            // next pause, so without the age check every quick out-and-back ate another 15s.
            val pausedFor = saved?.savedAtMs?.let { System.currentTimeMillis() - it }
            val rewind =
                if (pausedFor == null || pausedFor >= Settings.RESUME_REWIND_MIN_PAUSE_MS)
                    Settings.RESUME_REWIND_MS
                else 0L
            val startPos = if (savedIdx >= 0) (saved!!.ms - rewind).coerceAtLeast(0L) else 0L
            // Mark this folder as playing only now that a queue is actually starting; a failed or
            // empty scan must leave playingFolderId pointing at whatever was playing before.
            playingFolderId = queuePath.last().documentId
            // Remember the folder the user launched (the open path, not the book root) so the
            // history dialog can jump the browser straight back to it.
            Settings.addHistory(
                this@MainActivity,
                Settings.HistoryEntry(
                    tree.toString(), path.map { it.documentId }, path.map { it.name }, abook
                )
            )
            startQueue(controller, items, start, abook, key, startPos)
        }
    }

    /** The controller, but only while the activity is still started and the scan wasn't superseded;
     *  guards against touching a controller released in onStop. */
    private fun liveController(): MediaController? {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return null
        return controllerState.value
    }

    /** Installs and starts [items] at [startIndex]. In abook mode the queue plays sequentially and
     *  the service tracks its position under [bookFolderKey]; otherwise it follows the live shuffle
     *  setting and position tracking is off. Nothing ever loops (repeat is always off). */
    private fun startQueue(
        controller: MediaController,
        items: List<MediaItem>,
        startIndex: Int,
        abook: Boolean,
        bookFolderKey: String? = null,
        startPositionMs: Long = 0L
    ) {
        errorState.value = null
        playingAbookState.value = abook
        // Detach the previous book key before any player mutation (the service persists the
        // outgoing book's position on detach, while its queue is still current) and attach the
        // new key right after the new queue is installed — the key is never wrong for the live
        // queue, so no stray save can write one queue's uri under another book's resume point.
        sendBookMode(controller, null)
        controller.shuffleModeEnabled = !abook && shuffleState.value
        controller.repeatMode =
            if (!abook && Settings.REPEAT_ALL) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        controller.setMediaItems(items, startIndex, startPositionMs)
        sendBookMode(controller, if (abook) bookFolderKey else null)
        controller.prepare()
        controller.play()
        playingFolderKeyState.value = bookFolderKey
        // Speed is an audiobook feature: books restore their saved (or default) speed; plain music
        // always plays at 1.0, never inheriting a previous book's speed or the global default.
        if (abook && bookFolderKey != null) applyFolderSpeed(controller, bookFolderKey)
        else controller.setPlaybackSpeed(Settings.SPEED_DEFAULT)
    }

    /** Applies the folder's saved (or default) playback speed to [controller]. */
    private fun applyFolderSpeed(controller: MediaController, folderKey: String) {
        controller.setPlaybackSpeed(Settings.getSpeed(this, folderKey))
    }

    /** Tells the service which book the active queue belongs to (null = plain music, no tracking). */
    private fun sendBookMode(controller: MediaController, folderKey: String?) {
        val command = SessionCommand(PlayerService.CMD_BOOK_MODE, Bundle.EMPTY)
        if (controller.isSessionCommandAvailable(command)) {
            val args = Bundle().apply { putString(PlayerService.KEY_BOOK_FOLDER, folderKey ?: "") }
            controller.sendCustomCommand(command, args)
        }
    }

    /** Tells the service to re-apply ReplayGain to the current track immediately (no track wait). */
    private fun sendReplayGainChanged() {
        val controller = controllerState.value ?: return
        val command = SessionCommand(PlayerService.CMD_REPLAYGAIN, Bundle.EMPTY)
        if (controller.isSessionCommandAvailable(command)) {
            controller.sendCustomCommand(command, Bundle.EMPTY)
        }
    }

    /** Plays the selected file first, then the rest of the folder. ExoPlayer's shuffle (toggled
     *  live) decides whether the remainder is shuffled or continues in scan order. Inside a book
     *  (own flag or inherited) the tap means "jump to this chapter": the queue becomes the whole
     *  book, recursive from the book root, positioned at the tapped file. The selection is
     *  cleared only once playback actually starts, so a failed load leaves it intact. */
    private fun playFile(folder: Node, index: Int) {
        if (controllerState.value == null) return
        val tree = treeUriState.value ?: return
        val path = pathState.value
        playbackLoadJob?.cancel()
        playbackLoadJob = lifecycleScope.launch {
            val bookPath = bookRootPath(tree, path)
            val loaded = try {
                withContext(Dispatchers.IO) {
                    val files = FolderCache.children(this@MainActivity, tree, folder).second
                    val items =
                        if (bookPath != null)
                            MusicScanner.collectAudio(this@MainActivity, tree, bookPath, true)
                        else MusicScanner.mediaItems(tree, path, files, false)
                    files to items
                }
            } catch (e: ScanException) {
                if (liveController() != null) errorState.value = getString(R.string.folder_unreadable)
                return@launch
            }
            val (files, items) = loaded
            val controller = liveController() ?: return@launch
            if (index !in files.indices || items.isEmpty()) {
                errorState.value = getString(R.string.nothing_to_play)
                return@launch
            }
            val queueRoot = bookPath?.last() ?: folder
            val key = Settings.bookKey(tree.toString(), queueRoot.documentId)
            val start = if (bookPath != null) {
                val uri = DocumentsContract
                    .buildDocumentUriUsingTree(tree, files[index].documentId).toString()
                items.indexOfFirst { it.mediaId == uri }.coerceAtLeast(0)
            } else index
            // Set only on the success path so a failed load leaves playingFolderId untouched.
            playingFolderId = queueRoot.documentId
            startQueue(controller, items, start, bookPath != null, key)
            selectedIndexState.value = null
        }
    }

    /** Permanently deletes [folder] and its files from storage, then forgets its book state and
     *  refreshes the parent listing. Stops playback first if the playing track is inside it. */
    private fun deleteBook(folder: Node) {
        val tree = treeUriState.value ?: return
        val parent = pathState.value.lastOrNull() ?: return
        if (deleteJob?.isActive == true) return // ignore a second confirm while a delete is in flight
        // Touches the controller, so it must run on the main thread before the IO hop.
        stopIfPlayingUnder(folder)
        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, folder.documentId)
        // The book (if any) the deleted folder belongs to — its resume point may live inside.
        val enclosingBookKey = bookRootPath(tree, pathState.value)
            ?.let { Settings.bookKey(tree.toString(), it.last().documentId) }
        deleteJob = lifecycleScope.launch {
            // The recursive SAF delete (and the SQLite cache invalidations) can take seconds on a
            // slow provider, so keep them off the main thread to avoid an ANR.
            val ok = withContext(Dispatchers.IO) {
                val deleted = runCatching {
                    DocumentsContract.deleteDocument(contentResolver, docUri)
                }.getOrDefault(false)
                if (deleted) {
                    Settings.clearBook(
                        this@MainActivity, Settings.bookKey(tree.toString(), folder.documentId)
                    )
                    // Forget the deleted folder (and its descendants) in history, so a later tap
                    // can't reopen a folder that's gone.
                    Settings.removeHistoryForFolder(
                        this@MainActivity, tree.toString(), folder.documentId
                    )
                    // Drop the deleted folder's own (and descendants') cached listings, then the
                    // parent's so the now-missing folder disappears from it on re-scan.
                    val removedUris =
                        FolderCache.invalidateSubtree(this@MainActivity, tree, folder.documentId)
                    FolderCache.invalidate(this@MainActivity, tree, parent.documentId)
                    // The enclosing book's resume point died with the subtree: forget it, so the
                    // row doesn't sit stale forever (the next start falls back to file 1 anyway).
                    if (enclosingBookKey != null) {
                        val saved = Settings.getBookPos(this@MainActivity, enclosingBookKey)
                        if (saved != null && saved.fileUri in removedUris) {
                            Settings.clearBookPos(this@MainActivity, enclosingBookKey)
                        }
                    }
                }
                deleted
            }
            if (!ok) {
                errorState.value = getString(R.string.delete_failed)
                return@launch
            }
            rescanTickState.value++
        }
    }

    /** Stops and clears playback when the playing track lives inside [folder]. When only *other*
     *  queued tracks (from a recursive parent queue) are under [folder], those items are removed
     *  instead — so deleting it can't leave the player on now-dangling content URIs. */
    private fun stopIfPlayingUnder(folder: Node) {
        val controller = controllerState.value ?: return
        val extras = controller.currentMediaItem?.mediaMetadata?.extras
        val ids = extras?.getStringArray(MusicScanner.EXTRA_PATH_IDS)
        val playFolderId = extras?.getString(MusicScanner.EXTRA_PLAY_FOLDER_ID)
        val under = playFolderId == folder.documentId || ids?.contains(folder.documentId) == true
        if (!under) {
            // The current track is elsewhere, but a recursive queue may still hold tracks under the
            // deleted folder. Drop them (backwards, so indices stay valid) before they go dangling.
            for (i in controller.mediaItemCount - 1 downTo 0) {
                val itemIds = controller.getMediaItemAt(i)
                    .mediaMetadata.extras?.getStringArray(MusicScanner.EXTRA_PATH_IDS)
                if (itemIds?.contains(folder.documentId) == true) controller.removeMediaItem(i)
            }
            return
        }
        stopAndClearQueue()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo() = packageManager.getPackageInfo(packageName, 0)
    private fun appVersionName(): String = packageInfo().versionName ?: ""
    private fun appVersionCode(): Long = packageInfo().longVersionCode
}

@Composable
private fun PlayerScreen(
    controller: MediaController?,
    roots: List<Uri>,
    selectedRoot: Uri?,
    treeUri: Uri?,
    path: List<Node>,
    selectedIndex: Int?,
    playingDocId: String?,
    visitedPathIds: Set<String>,
    rescanTick: Int,
    clearTitleTick: Int,
    shuffleEnabled: Boolean,
    onShuffleToggle: (Boolean) -> Unit,
    abookEnabled: Boolean,
    abookVisible: Boolean,
    abookLocked: Boolean,
    playingAbook: Boolean,
    remainingTime: Boolean,
    onAbookToggle: (Boolean) -> Unit,
    onEnterRoot: (Uri) -> Unit,
    onOpenHistoryEntry: (Settings.HistoryEntry) -> Unit,
    onAddRoot: () -> Unit,
    onRemoveRoot: (Uri) -> Unit,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    onDescend: (Node) -> Unit,
    onUp: () -> Unit,
    onDeleteBook: (Node) -> Unit,
    onSelectFile: (Int) -> Unit,
    onPlayPause: () -> Unit,
    speed: Float,
    speedEnabled: Boolean,
    speedLive: Boolean,
    onSpeedChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 40.dp, end = 16.dp, bottom = 16.dp)
    ) {
        val current = path.lastOrNull()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (current == null || treeUri == null) {
                RootsList(
                    roots = roots,
                    selectedRoot = selectedRoot,
                    onEnterRoot = onEnterRoot,
                    onOpenHistoryEntry = onOpenHistoryEntry,
                    onAddRoot = onAddRoot,
                    onRemoveRoot = onRemoveRoot,
                    onExit = onExit,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FolderBrowser(
                    treeUri = treeUri,
                    current = current,
                    title = stringResource(
                        if (abookEnabled) R.string.mode_audiobook else R.string.mode_music
                    ),
                    selectedIndex = selectedIndex,
                    playingDocId = playingDocId,
                    visitedPathIds = visitedPathIds,
                    rescanTick = rescanTick,
                    onUp = onUp,
                    onDescend = onDescend,
                    onDeleteBook = onDeleteBook,
                    onSelectFile = onSelectFile,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        // On the home screen the controls are secondary (usually nothing is playing): dim the whole
        // block, but keep it interactive so background playback can still be paused from here.
        Column(modifier = Modifier.alpha(if (treeUri == null) 0.45f else 1f)) {
            NowPlaying(
                controller, clearTitleTick, treeUri == null,
                shuffleEnabled, onShuffleToggle,
                abookEnabled, abookVisible, abookLocked, onAbookToggle,
                playingAbook, remainingTime, onPlayPause,
                speed, speedEnabled, speedLive, onSpeedChange
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A readable placeholder label for a SAF tree URI until its real display name resolves: the last
 *  path component of the tree documentId, without the `primary:`-style provider/volume prefix. */
private fun fallbackRootLabel(uri: Uri): String {
    val seg = uri.lastPathSegment ?: return uri.toString()
    return seg.substringAfterLast(':').substringAfterLast('/').ifEmpty { seg }
}

/** Formats a millisecond position as m:ss (or h:mm:ss for tracks an hour or longer). */
private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val s = totalSec % 60
    val m = (totalSec / 60) % 60
    val h = totalSec / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Shared top bar for the home and browser screens: a leading icon button (close on home, back in
 *  the browser), an optional title, an optional add button (home only), and settings. */
@Composable
private fun TopBar(
    leadingIcon: Painter,
    leadingDesc: String,
    onLeading: () -> Unit,
    title: String?,
    onAdd: (() -> Unit)?,
    onSettings: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // A neutral grey toolbar strip so the bar reads as a panel, distinct from the content;
        // the icons keep their own look and tint.
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    ) {
        IconButton(onClick = onLeading) {
            Icon(painter = leadingIcon, contentDescription = leadingDesc)
        }
        if (title != null) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_circle),
                    contentDescription = stringResource(R.string.add_folder),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings)
                )
            }
        }
    }
}

/** Home screen: the list of root folders on a distinct background, with add/remove. */
@Composable
private fun RootsList(
    roots: List<Uri>,
    selectedRoot: Uri?,
    onEnterRoot: (Uri) -> Unit,
    onOpenHistoryEntry: (Settings.HistoryEntry) -> Unit,
    onAddRoot: () -> Unit,
    onRemoveRoot: (Uri) -> Unit,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingRemoval by remember { mutableStateOf<Uri?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var labeled by remember(roots) {
        mutableStateOf(
            roots.map { it to fallbackRootLabel(it) }
                .sortedBy { it.second.lowercase() }
        )
    }
    LaunchedEffect(roots) {
        labeled = withContext(Dispatchers.IO) {
            roots.map { uri ->
                val name = runCatching { MusicScanner.rootNode(context, uri).name }.getOrNull()
                uri to (name?.takeIf { it.isNotEmpty() } ?: fallbackRootLabel(uri))
            }.sortedBy { it.second.lowercase() }
        }
    }

    Column(modifier = modifier) {
        TopBar(
            leadingIcon = painterResource(R.drawable.ic_close),
            leadingDesc = stringResource(R.string.exit),
            onLeading = onExit,
            title = stringResource(R.string.folders),
            onAdd = onAddRoot,
            onSettings = onOpenSettings,
        )

        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
        ) {
            if (labeled.isEmpty()) {
                Text(
                    text = stringResource(R.string.no_folders),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp)
                )
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
                    items(labeled, key = { it.first.toString() }) { (uri, name) ->
                        val selected = uri == selectedRoot
                        val foreground =
                            if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { onEnterRoot(uri) }
                                .padding(start = 8.dp, top = 2.dp, bottom = 2.dp)
                        ) {
                            Text(
                                text = "📁  $name",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 22.sp,
                                color = if (selected) foreground else Color.Unspecified,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { pendingRemoval = uri }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.remove_folder),
                                    tint = foreground
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showHistory = true },
            modifier = Modifier.align(Alignment.CenterHorizontally),
            // ~5 mm extra on each side on top of the Button's default 24dp horizontal padding.
            contentPadding = PaddingValues(horizontal = 56.dp, vertical = 8.dp)
        ) {
            Text(stringResource(R.string.history))
        }

        if (showHistory) {
            // Read once per open; the dialog leaves the composition when closed, so a later open
            // re-reads the up-to-date list.
            val entries = remember { Settings.getHistory(context) }
            AlertDialog(
                onDismissRequest = { showHistory = false },
                title = { Text(stringResource(R.string.history)) },
                text = {
                    if (entries.isEmpty()) {
                        Text(stringResource(R.string.no_history))
                    } else {
                        Column {
                            entries.forEach { entry ->
                                val icon = if (entry.isBook) "📖" else "🎵"
                                Text(
                                    text = "$icon  ${entry.names.lastOrNull().orEmpty()}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 20.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            showHistory = false
                                            onOpenHistoryEntry(entry)
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { showHistory = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        pendingRemoval?.let { uri ->
            val name = labeled.firstOrNull { it.first == uri }?.second ?: uri.toString()
            AlertDialog(
                onDismissRequest = { pendingRemoval = null },
                title = { Text(stringResource(R.string.remove_folder)) },
                text = { Text(stringResource(R.string.remove_folder_message, name) + "?") },
                confirmButton = {
                    Button(onClick = {
                        onRemoveRoot(uri)
                        pendingRemoval = null
                    }) { Text(stringResource(R.string.remove)) }
                },
                dismissButton = {
                    Button(onClick = { pendingRemoval = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderBrowser(
    treeUri: Uri,
    current: Node,
    title: String,
    selectedIndex: Int?,
    playingDocId: String?,
    visitedPathIds: Set<String>,
    rescanTick: Int,
    onUp: () -> Unit,
    onDescend: (Node) -> Unit,
    onDeleteBook: (Node) -> Unit,
    onSelectFile: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDelete by remember { mutableStateOf<Node?>(null) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var contents by remember(current.documentId) {
        mutableStateOf<Pair<List<Node>, List<Node>>?>(null)
    }
    var loadFailed by remember(current.documentId) { mutableStateOf(false) }
    var retryTick by remember(current.documentId) { mutableStateOf(0) }
    // documentIds of subfolders that are books, resolved off-thread with the listing so the rows
    // don't each run a main-thread Settings.isAbook DB read during composition.
    var bookIds by remember(current.documentId) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(current.documentId, rescanTick, retryTick) {
        loadFailed = false
        contents = null
        try {
            val (loaded, books) = withContext(Dispatchers.IO) {
                val c = FolderCache.children(context, treeUri, current)
                val ids = c.first.filter {
                    Settings.isAbook(context, Settings.bookKey(treeUri.toString(), it.documentId))
                }.map { it.documentId }.toSet()
                c to ids
            }
            contents = loaded
            bookIds = books
        } catch (e: ScanException) {
            loadFailed = true
        }
    }

    // Scroll the playing track into the middle of the list (not flush against an edge).
    LaunchedEffect(contents, playingDocId) {
        val c = contents ?: return@LaunchedEffect
        val fileIdx = c.second.indexOfFirst { it.documentId == playingDocId }
        if (fileIdx < 0) return@LaunchedEffect
        val target = c.first.size + fileIdx
        if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
            listState.scrollToItem(target)
        }
        val info = listState.layoutInfo
        val viewport = info.viewportEndOffset - info.viewportStartOffset
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
        if (item != null && viewport > 0) {
            listState.animateScrollBy((item.offset - (viewport - item.size) / 2).toFloat())
        }
    }

    Column(modifier = modifier) {
        TopBar(
            leadingIcon = painterResource(R.drawable.ic_arrow_back),
            leadingDesc = stringResource(R.string.back),
            onLeading = onUp,
            title = title,
            onAdd = null,
            onSettings = onOpenSettings,
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = current.name,
            // Always two lines tall so navigating between short- and long-named folders doesn't
            // resize this field and shift the list below.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 17.sp,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        val c = contents
        if (loadFailed) {
            Text(stringResource(R.string.folder_unreadable))
            Spacer(Modifier.height(8.dp))
            Button(onClick = { retryTick++ }) { Text(stringResource(R.string.retry)) }
        } else if (c != null && c.first.isEmpty() && c.second.isEmpty()) {
            Text(stringResource(R.string.empty_folder))
        } else if (c != null) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                items(c.first, key = { it.documentId }) { folder ->
                    val isBook = folder.documentId in bookIds
                    // The visited path (last entered folder or the playing track's ancestors) is
                    // highlighted like the track's own file row, visible from any level.
                    val highlighted = folder.documentId in visitedPathIds
                    val background =
                        if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent
                    val foreground =
                        if (highlighted) MaterialTheme.colorScheme.onPrimary else Color.Unspecified
                    Text(
                        text = "${if (isBook) "📖" else "📁"}  ${folder.name}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 19.sp,
                        color = foreground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(background)
                            .combinedClickable(
                                onClick = { onDescend(folder) },
                                onLongClick = { pendingDelete = folder }
                            )
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            // The highlighted row scrolls its full name into view instead of clipping.
                            .then(if (highlighted) Modifier.basicMarquee() else Modifier)
                    )
                }
                itemsIndexed(c.second, key = { _, file -> file.documentId }) { index, file ->
                    val highlighted = index == selectedIndex || file.documentId == playingDocId
                    val background =
                        if (highlighted) MaterialTheme.colorScheme.primary else Color.Transparent
                    val foreground =
                        if (highlighted) MaterialTheme.colorScheme.onPrimary else Color.Unspecified
                    Text(
                        text = "🎵  ${file.name}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 19.sp,
                        color = foreground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(background)
                            .clickable { onSelectFile(index) }
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                            // The highlighted (playing/selected) row scrolls its full name into view.
                            .then(if (highlighted) Modifier.basicMarquee() else Modifier)
                    )
                }
            }
        }

        pendingDelete?.let { folder ->
            var confirmed by remember(folder) { mutableStateOf(false) }
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(stringResource(R.string.delete_book)) },
                text = {
                    Column {
                        Text(stringResource(R.string.delete_book_message, folder.name) + "?")
                        Spacer(Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { confirmed = !confirmed }
                        ) {
                            // Delete stays disabled until this is checked, so it can't be a one-tap mistake.
                            Checkbox(checked = confirmed, onCheckedChange = null)
                            Text(stringResource(R.string.delete_book_confirm))
                        }
                    }
                },
                confirmButton = {
                    Button(
                        enabled = confirmed,
                        onClick = {
                            onDeleteBook(folder)
                            pendingDelete = null
                        }
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    Button(onClick = { pendingDelete = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun NowPlaying(
    controller: MediaController?,
    clearTitleTick: Int,
    atHome: Boolean,
    shuffleEnabled: Boolean,
    onShuffleToggle: (Boolean) -> Unit,
    abookEnabled: Boolean,
    abookVisible: Boolean,
    abookLocked: Boolean,
    onAbookToggle: (Boolean) -> Unit,
    playingAbook: Boolean,
    remainingTime: Boolean,
    onPlayPause: () -> Unit,
    speed: Float,
    speedEnabled: Boolean,
    speedLive: Boolean,
    onSpeedChange: (Float) -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }
    // Current track index and queue size, for the book progress readout.
    var mediaIndex by remember { mutableStateOf(0) }
    var mediaCount by remember { mutableStateOf(0) }
    // Set when navigating away from a stopped/paused track; hides the playback bar too (its duration
    // is kept fresh by polling, so it needs its own gate). Reset when a track plays/metadata arrives.
    var cleared by remember { mutableStateOf(false) }
    // Non-null while the user is dragging the bar: the scrubbed fraction (0..1) overrides the live
    // position so the bar/time follow the finger and don't jump back until the seek lands.
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    // Per-file durations of the live book queue (DurationCache), for the time-based book progress;
    // null until resolved. queueTick bumps on timeline changes so a new or edited queue (e.g. a
    // subfolder deleted mid-book) re-resolves against the current items.
    var bookDurations by remember { mutableStateOf<LongArray?>(null) }
    var queueTick by remember { mutableStateOf(0) }
    val context = LocalContext.current

    // Poll the controller for the playback position (Media3 has no position-changed callback);
    // a light 500 ms tick is enough for a thin progress bar and the edge timestamps.
    LaunchedEffect(controller) {
        while (controller != null) {
            positionMs = controller.currentPosition.coerceAtLeast(0L)
            durationMs = controller.duration.takeIf { it > 0L } ?: 0L
            mediaIndex = controller.currentMediaItemIndex
            mediaCount = controller.mediaItemCount
            delay(500)
        }
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                cleared = false
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                // Resuming re-shows the bar/progress even if it was cleared while stopped/browsing.
                if (playing) cleared = false
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                queueTick++
            }
        }
        c.addListener(listener)
        isPlaying = c.isPlaying
        onDispose { c.removeListener(listener) }
    }

    // Resolve the live book queue's durations for the time-based progress readout. Cached values
    // land in one snapshot right away; a cold book's missing files then refine it batch by batch
    // (slow over SAF), so the readout below improves incrementally instead of all at once.
    // Cancelling on key change lets a superseded queue abandon its walk (the per-file ensureActive
    // in DurationCache).
    LaunchedEffect(controller, playingAbook, queueTick) {
        bookDurations = null
        val c = controller
        if (c == null || !playingAbook || c.mediaItemCount == 0) return@LaunchedEffect
        // mediaId is the file's document uri (see MusicScanner), the duration cache key.
        val uris = List(c.mediaItemCount) { c.getMediaItemAt(it).mediaId }
        withContext(Dispatchers.IO) {
            DurationCache.durations(context, uris) { snapshot -> bookDurations = snapshot }
        }
    }

    // Clear the bar on request: navigating away from a stopped/paused track, or removing the
    // playing root. A playing track keeps its bar while browsing (no tick is sent then).
    LaunchedEffect(clearTitleTick) {
        if (clearTitleTick > 0) {
            cleared = true
        }
    }

    // The track name/path aren't shown here: the browser's folder name and the highlighted row
    // already say what's playing, and errors pop up in their own dialog.
    // Thin playback bar with elapsed/total times at the edges, draggable/tappable to seek. Fixed
    // height so showing/hiding it (no track / on the home screen) never reflows the controls below.
    val showBar = !atHome && !cleared && durationMs > 0L
    val barFraction = scrubFraction ?: (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val leftMs = scrubFraction?.let { (it * durationMs).toLong() } ?: positionMs
    Spacer(Modifier.height(if (atHome) 0.dp else 8.dp))
    Box(modifier = Modifier.fillMaxWidth().height(if (atHome) 0.dp else 32.dp)) {
        if (showBar) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Taller-than-the-bar touch strip so the thin bar is still easy to hit.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .pointerInput(controller, durationMs) {
                            detectTapGestures { offset ->
                                val f = (offset.x / size.width).coerceIn(0f, 1f)
                                val target = (f * durationMs).toLong()
                                controller?.seekTo(target)
                                positionMs = target
                            }
                        }
                        .pointerInput(controller, durationMs) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                },
                                onHorizontalDrag = { change, _ ->
                                    scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    scrubFraction?.let {
                                        val target = (it * durationMs).toLong()
                                        controller?.seekTo(target)
                                        positionMs = target
                                    }
                                    scrubFraction = null
                                },
                                onDragCancel = { scrubFraction = null }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    LinearProgressIndicator(
                        progress = { barFraction },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                    )
                }
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(leftMs), fontSize = 14.sp)
                    // Rightmost: remaining (-mm:ss) or total, per the setting.
                    Text(
                        if (remainingTime) "-${formatTime(durationMs - leftMs)}"
                        else formatTime(durationMs),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
    // Book progress: which file of the book plus the overall percent, with a thin bar. Time-based
    // as soon as some durations are known — still-resolving files are estimated at the known
    // average, so a cold book's percent refines smoothly batch by batch; until anything is known
    // (or if every file failed to read) approximated by file count. Shown only while a book is the
    // live queue, but its height is reserved on the player screen so the browser's bottom edge sits
    // a fixed ~6 mm higher and doesn't shift when a book starts or ends.
    Box(modifier = Modifier.fillMaxWidth().height(if (atHome) 0.dp else 36.dp)) {
        if (playingAbook && !atHome && !cleared && mediaCount > 0) {
            // Guard against a stale array while a queue edit's re-resolve is still in flight.
            val durations = bookDurations?.takeIf { it.size == mediaCount }
            // Elapsed/total of the whole book, once any duration is known; null while cold.
            val timed = durations?.takeIf { d -> d.any { it > 0L } }?.let { d ->
                val known = d.filter { it != DurationCache.UNKNOWN_MS }
                val avgMs = known.sum() / known.size // known is non-empty: something is > 0
                fun ms(i: Int) = if (d[i] == DurationCache.UNKNOWN_MS) avgMs else d[i]
                val totalMs = (0 until mediaCount).sumOf { ms(it) }
                val playedMs =
                    ((0 until mediaIndex).sumOf { ms(it) } + positionMs).coerceIn(0L, totalMs)
                playedMs to totalMs
            }
            val bookFraction = if (timed != null) {
                (timed.first.toFloat() / timed.second).coerceIn(0f, 1f)
            } else {
                val fileFrac =
                    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                ((mediaIndex + fileFrac) / mediaCount).coerceIn(0f, 1f)
            }
            val percent = String.format(Locale.US, "%.1f", bookFraction * 100)
            // Elapsed plus rightmost remaining (-mm:ss) or total, per the setting; empty until
            // durations resolve.
            val timeText = timed?.let { (played, total) ->
                val right = if (remainingTime) "-${formatTime(total - played)}" else formatTime(total)
                "${formatTime(played)}/$right"
            } ?: ""
            // File label left-aligned; "XX.X%     elapsed/-remaining" grouped at the right edge.
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${stringResource(R.string.file_label)} ${mediaIndex + 1}/$mediaCount",
                        fontSize = 14.sp
                    )
                    Text(
                        "$percent%${if (timeText.isEmpty()) "" else "     $timeText"}",
                        fontSize = 14.sp, textAlign = TextAlign.End
                    )
                }
                Spacer(Modifier.height(2.dp))
                LinearProgressIndicator(
                    progress = { bookFraction },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    // Single control block: three equal-height columns. The side stacks (abook / prev / −30s and
    // speed / next / +30s) are spaced half a button apart and define the block height; the centre
    // column fills the same height with play pinned to the top and shuffle to the bottom, so the
    // tops (abook / speed / play) and the bottoms (−30s / shuffle / +30s) all line up.
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CONTROL_GAP)
        ) {
            // Audiobook flag styled as a filled pill to match the buttons (checkbox + label, natural
            // width). Reserve the slot height so the stack doesn't shift when it's hidden off a folder.
            Box(modifier = Modifier.height(CONTROL_BTN_HEIGHT), contentAlignment = Alignment.Center) {
                if (abookVisible) {
                    val onPill = MaterialTheme.colorScheme.onPrimary
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        // Same width as the buttons. Locked (dimmed, not hidden) while this folder is
                        // the live queue: the mode is fixed at queue start, so editing it mid-play
                        // could only mislead.
                        modifier = Modifier
                            .width(CONTROL_BTN_WIDTH)
                            .height(CONTROL_BTN_HEIGHT)
                            .clip(RoundedCornerShape(50))
                            .background(
                                MaterialTheme.colorScheme.primary
                                    .copy(alpha = if (abookLocked) 0.4f else 1f)
                            )
                            .clickable(enabled = !abookLocked) { onAbookToggle(!abookEnabled) }
                    ) {
                        // The whole row toggles; the box stays non-interactive to avoid a double event.
                        // requiredSize pins the box to a small square (no 48dp touch-target padding),
                        // so it keeps a margin from the pill edges and the label fits the button width.
                        Checkbox(
                            checked = abookEnabled,
                            onCheckedChange = null,
                            enabled = !abookLocked,
                            modifier = Modifier.requiredSize(20.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = onPill,
                                checkmarkColor = MaterialTheme.colorScheme.primary,
                                uncheckedColor = onPill,
                                disabledUncheckedColor = onPill,
                                disabledCheckedColor = onPill
                            )
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.audiobook_mode), color = onPill)
                    }
                }
            }
            Button(
                onClick = { controller?.seekToPrevious() },
                enabled = controller != null,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.width(CONTROL_BTN_WIDTH).height(CONTROL_BTN_HEIGHT)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = stringResource(R.string.previous)
                )
            }
            // Rewind by a fixed step. Always available (no-op without a track).
            Button(
                onClick = {
                    val target = (positionMs - SEEK_STEP_MS).coerceAtLeast(0L)
                    controller?.seekTo(target)
                    positionMs = target
                },
                enabled = controller != null,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.width(CONTROL_BTN_WIDTH).height(CONTROL_BTN_HEIGHT)
            ) { Text(stringResource(R.string.rewind_30)) }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CONTROL_GAP)
        ) {
            Button(
                onClick = onPlayPause,
                enabled = controller != null,
                shape = RoundedCornerShape(28.dp),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(CONTROL_BTN_WIDTH).height(PLAY_HEIGHT)
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                    modifier = Modifier.size(if (isPlaying) 40.dp else 56.dp)
                )
            }
            // Shuffle in a button-height slot so it lines up with the ±30s row, not above it.
            // A book plays sequentially, so shuffle is locked while a book is the live queue
            // (playing or paused — a paused book is resumable in place). Starting a music
            // folder, or the book ending, frees the switch again.
            Box(modifier = Modifier.height(CONTROL_BTN_HEIGHT), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = shuffleEnabled,
                        onCheckedChange = onShuffleToggle,
                        enabled = !playingAbook
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_shuffle),
                        contentDescription = stringResource(R.string.shuffle),
                        // Dimmed like the disabled switch while a book locks shuffle off.
                        tint = when {
                            playingAbook -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            shuffleEnabled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CONTROL_GAP)
        ) {
            // Speed is an audiobook feature: in a book it edits that book's saved speed (applied live
            // only when it is the live queue); in music it shows x1.0 disabled, since music plays at 1.0.
            Box(modifier = Modifier.height(CONTROL_BTN_HEIGHT), contentAlignment = Alignment.Center) {
                SpeedButton(
                    label = formatSpeed(if (speedEnabled) speed else Settings.SPEED_DEFAULT),
                    enabled = speedEnabled,
                    width = CONTROL_BTN_WIDTH,
                    onClick = { showSpeedDialog = true }
                )
            }
            Button(
                onClick = { controller?.seekToNext() },
                enabled = controller != null,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.width(CONTROL_BTN_WIDTH).height(CONTROL_BTN_HEIGHT)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = stringResource(R.string.next)
                )
            }
            // Fast-forward by a fixed step. Always available (no-op without a track).
            Button(
                onClick = {
                    val target = (positionMs + SEEK_STEP_MS).coerceAtMost(durationMs)
                    controller?.seekTo(target)
                    positionMs = target
                },
                enabled = controller != null,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.width(CONTROL_BTN_WIDTH).height(CONTROL_BTN_HEIGHT)
            ) { Text(stringResource(R.string.forward_30)) }
        }
    }
    if (showSpeedDialog) {
        SpeedDialog(
            initial = speed,
            // Audible while dragging, but only when the edited book is what's actually playing;
            // a book that isn't the live queue must not drag someone else's playback speed around.
            onPreview = { if (speedLive) controller?.setPlaybackSpeed(it) },
            onConfirm = onSpeedChange,
            onDismiss = { showSpeedDialog = false }
        )
    }
}

/** Compact speed button shared by the player and settings: filled so its enabled (colored) vs
 *  disabled (grey) state is obvious; one line, fixed width so longer values (e.g. x1.45) never wrap. */
@Composable
private fun SpeedButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    width: Dp = 76.dp,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.width(width).height(36.dp)
    ) {
        Text(label, maxLines = 1, softWrap = false, textAlign = TextAlign.Center)
    }
}

/** Slider dialog over the shared 0.5–3.0 speed range. [onPreview] fires live while dragging (e.g. to
 *  apply to the live player); [onConfirm] is the value to keep, fired on confirm or on dismiss. */
@Composable
private fun SpeedDialog(
    initial: Float,
    onPreview: (Float) -> Unit,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var sliderSpeed by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onConfirm(sliderSpeed); onDismiss() },
        title = { Text(stringResource(R.string.playback_speed)) },
        text = {
            Column {
                Text(
                    formatSpeed(sliderSpeed),
                    textAlign = TextAlign.Center,
                    fontSize = 22.sp,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            sliderSpeed = snapSpeed(sliderSpeed - Settings.SPEED_STEP)
                                .coerceAtLeast(Settings.SPEED_MIN)
                            onPreview(sliderSpeed)
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(40.dp)
                    ) { Text("−", fontSize = 20.sp) }
                    Slider(
                        value = sliderSpeed,
                        onValueChange = {
                            sliderSpeed = snapSpeed(it)
                            onPreview(sliderSpeed)
                        },
                        valueRange = Settings.SPEED_MIN..Settings.SPEED_MAX,
                        steps = SPEED_SLIDER_STEPS,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Button(
                        onClick = {
                            sliderSpeed = snapSpeed(sliderSpeed + Settings.SPEED_STEP)
                                .coerceAtMost(Settings.SPEED_MAX)
                            onPreview(sliderSpeed)
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(40.dp)
                    ) { Text("+", fontSize = 20.sp) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(sliderSpeed); onDismiss() }) {
                Text(stringResource(R.string.done))
            }
        }
    )
}

/** Speed as a compact "x" label: whole values keep one decimal (x1.0), finer steps show as needed. */
private fun formatSpeed(speed: Float): String {
    val hundredths = (speed * 100).roundToInt()
    val text = when {
        hundredths % 100 == 0 -> "${hundredths / 100}.0"
        hundredths % 10 == 0 -> String.format(Locale.US, "%.1f", speed)
        else -> String.format(Locale.US, "%.2f", speed)
    }
    return "x$text"
}

/** Snaps a raw slider value to the nearest [Settings.SPEED_STEP]. */
private fun snapSpeed(raw: Float): Float =
    (raw / Settings.SPEED_STEP).roundToInt() * Settings.SPEED_STEP

@Composable
private fun SettingsScreen(
    version: String,
    build: Long,
    themeMode: ThemeMode,
    replayGainEnabled: Boolean,
    followEnabled: Boolean,
    remainingEnabled: Boolean,
    defaultSpeed: Float,
    onThemeChange: (ThemeMode) -> Unit,
    onReplayGainChange: (Boolean) -> Unit,
    onFollowChange: (Boolean) -> Unit,
    onRemainingChange: (Boolean) -> Unit,
    onDefaultSpeedChange: (Float) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 40.dp, end = 16.dp, bottom = 16.dp)
    ) {
        TopBar(
            leadingIcon = painterResource(R.drawable.ic_arrow_back),
            leadingDesc = stringResource(R.string.back),
            onLeading = onBack,
            title = stringResource(R.string.settings),
            onAdd = null,
            onSettings = null,
        )

        Spacer(Modifier.height(24.dp))
        Button(onClick = onRescan, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.rescan))
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.theme))
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ThemeOption(stringResource(R.string.theme_system), themeMode == ThemeMode.System) {
                onThemeChange(ThemeMode.System)
            }
            Spacer(Modifier.width(8.dp))
            ThemeOption(stringResource(R.string.theme_light), themeMode == ThemeMode.Light) {
                onThemeChange(ThemeMode.Light)
            }
            Spacer(Modifier.width(8.dp))
            ThemeOption(stringResource(R.string.theme_dark), themeMode == ThemeMode.Dark) {
                onThemeChange(ThemeMode.Dark)
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.replaygain))
            Spacer(Modifier.weight(1f))
            Switch(checked = replayGainEnabled, onCheckedChange = onReplayGainChange)
        }

        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.follow_playing))
            Spacer(Modifier.weight(1f))
            Switch(checked = followEnabled, onCheckedChange = onFollowChange)
        }

        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(stringResource(R.string.track_time))
                Text(
                    stringResource(
                        if (remainingEnabled) R.string.remaining_time else R.string.total_time
                    ),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(1f))
            Switch(checked = remainingEnabled, onCheckedChange = onRemainingChange)
        }

        Spacer(Modifier.height(10.dp))
        var showSpeedDialog by remember { mutableStateOf(false) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.default_speed))
            Spacer(Modifier.weight(1f))
            SpeedButton(
                label = formatSpeed(defaultSpeed),
                enabled = true,
                onClick = { showSpeedDialog = true }
            )
        }
        if (showSpeedDialog) {
            SpeedDialog(
                initial = defaultSpeed,
                onPreview = {},
                onConfirm = onDefaultSpeedChange,
                onDismiss = { showSpeedDialog = false }
            )
        }

        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.about), fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.app_name))
        Text("${stringResource(R.string.version)} $version")
        Text("${stringResource(R.string.build)} $build")
    }
}

@Composable
private fun ThemeOption(label: String, selected: Boolean, onClick: () -> Unit) {
    // Both filled; selected is primary (colored), unselected is a clearly different grey fill.
    val colors = if (selected) ButtonDefaults.buttonColors()
    else ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Button(onClick = onClick, colors = colors) { Text(label) }
}
