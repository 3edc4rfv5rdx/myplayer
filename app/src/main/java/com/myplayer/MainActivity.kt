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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

// Discrete slider positions between SPEED_MIN and SPEED_MAX (endpoints excluded), one per SPEED_STEP.
private val SPEED_SLIDER_STEPS =
    ((Settings.SPEED_MAX - Settings.SPEED_MIN) / Settings.SPEED_STEP).roundToInt() - 1

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
    private val screenState = mutableStateOf(Screen.Browser)
    private val rescanTickState = mutableStateOf(0)
    private val clearTitleTickState = mutableStateOf(0)
    private val themeState = mutableStateOf(ThemeMode.System)

    // Random playback order, on by default; intentionally not persisted (resets each launch).
    private val shuffleState = mutableStateOf(true)

    // Audiobook mode of the folder currently open in the browser (persisted per folder). Mirrors
    // Settings.isAbook for the open folder; kept in sync as the browser navigates.
    private val abookState = mutableStateOf(false)

    // Whether the live queue is a book (set when a queue is installed). Gates the book progress
    // readout, which is meaningless for shuffled music.
    private val playingAbookState = mutableStateOf(false)

    // Playback speed of the live queue's folder; mirrored to the controller and persisted per folder.
    private val playbackSpeedState = mutableStateOf(Settings.SPEED_DEFAULT)

    // Book key of the folder the live queue plays from; null when nothing is playing. Drives the
    // speed button (enabled + which folder its value is saved under). Set when a queue is installed.
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
                    val screen by screenState
                    val rescanTick by rescanTickState
                    val clearTitleTick by clearTitleTickState
                    val shuffle by shuffleState
                    val playingAbook by playingAbookState
                    var replayGain by remember { mutableStateOf(Settings.isReplayGainEnabled(this)) }
                    var loop by remember { mutableStateOf(Settings.isLoopEnabled(this)) }
                    var follow by remember { mutableStateOf(Settings.isFollowEnabled(this)) }
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
                            loopEnabled = loop,
                            followEnabled = follow,
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
                            onLoopChange = {
                                loop = it
                                Settings.setLoopEnabled(this, it)
                                controllerState.value?.repeatMode =
                                    if (it) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                            },
                            onFollowChange = {
                                follow = it
                                followEnabled = it
                                Settings.setFollowEnabled(this, it)
                                followPlayingTrack(controllerState.value?.currentMediaItem)
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
                            LaunchedEffect(currentFolderKey) {
                                abookState.value = currentFolderKey != null &&
                                    Settings.isAbook(this@MainActivity, currentFolderKey)
                            }
                            // Speed drives the live queue, so it is controllable whenever something
                            // is playing (keyed by the playing folder, not whatever the browser shows).
                            val playbackSpeed by playbackSpeedState
                            val playingFolderKey by playingFolderKeyState
                            PlayerScreen(
                            controller = controller,
                            roots = roots,
                            selectedRoot = selectedRoot,
                            treeUri = treeUri,
                            path = path,
                            error = error,
                            selectedIndex = selectedIndex,
                            playingDocId = playingDocId,
                            rescanTick = rescanTick,
                            clearTitleTick = clearTitleTick,
                            shuffleEnabled = shuffle,
                            onShuffleToggle = {
                                shuffleState.value = it
                                controllerState.value?.shuffleModeEnabled = it
                            },
                            abookEnabled = abook,
                            abookVisible = currentFolderKey != null,
                            playingAbook = playingAbook,
                            onAbookToggle = { enabled ->
                                currentFolderKey?.let { key ->
                                    abookState.value = enabled
                                    // A pure persisted folder property: it changes how this folder
                                    // plays on its next start (startQueue forces books sequential),
                                    // never the live queue, which keeps the mode it started with.
                                    Settings.setAbook(this, key, enabled)
                                }
                            },
                            onEnterRoot = { enterRoot(it) },
                            onAddRoot = { folderPicker.launch(null) },
                            onRemoveRoot = { removeRoot(it) },
                            onExit = { exitApp() },
                            onOpenSettings = { screenState.value = Screen.Settings },
                            onDescend = {
                                pathState.value = path + it
                                selectedIndexState.value = null
                            },
                            onUp = { goUp() },
                            onPlayFolder = { playFolder(it) },
                            onDeleteBook = { deleteBook(it) },
                            onSelectFile = { selectedIndexState.value = it },
                            onPlayPause = { togglePlay() },
                            speed = playbackSpeed,
                            speedEnabled = playingFolderKey != null,
                            onSpeedChange = { s ->
                                playingFolderKey?.let { key ->
                                    Settings.setSpeed(this, key, s)
                                    controllerState.value?.setPlaybackSpeed(s)
                                    playbackSpeedState.value = s
                                }
                            }
                            )
                        }
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

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        // Queue reached its end (book finished, or a playlist with repeat off): clear
                        // the now-playing labels, bar, and book progress so a 100%-done book doesn't
                        // linger on screen. Guard on a non-empty queue so clearing items isn't caught.
                        if (playbackState == Player.STATE_ENDED && c.mediaItemCount > 0) {
                            playingAbookState.value = false
                            playingFolderId = null
                            playingFolderKeyState.value = null
                            playingDocIdState.value = null
                            clearTitleTickState.value++
                        }
                    }
                })
                // The service keeps playing across activity recreation; recover which folder the
                // live playlist was started from so Play in the same folder resumes, not restarts.
                playingFolderId = playFolderIdOf(c.currentMediaItem)
                followPlayingTrack(c.currentMediaItem)
                // Reconcile the shuffle toggle with the controller. A live music queue's shuffle is
                // authoritative; a book's shuffle is forced off (not the user's preference), so don't
                // read it back or the next music would silently un-shuffle. Decide "is the live queue
                // a book?" from the item's own folder, not the transient playingAbookState, so an
                // already-finished but still-loaded book queue can't leak its shuffle-off either.
                // With no live queue the fresh player defaults to shuffle off, so push the UI's value
                // to keep the switch and engine in agreement.
                val liveIsBook = playFolderTreeUriOf(c.currentMediaItem)?.let { t ->
                    playFolderIdOf(c.currentMediaItem)?.let { Settings.isAbook(this, Settings.bookKey(t, it)) }
                } ?: false
                if (c.mediaItemCount > 0) {
                    if (!liveIsBook) shuffleState.value = c.shuffleModeEnabled
                } else c.shuffleModeEnabled = shuffleState.value
                // Reconcile the book flag with the live queue: a restored playingAbook=true after
                // process death would otherwise lock the shuffle switch with nothing playing (the
                // service died, so the queue is empty). The STATE_ENDED guard keeps a finished-but-
                // still-loaded book queue (which saved playingAbook=false) from being re-locked.
                playingAbookState.value =
                    c.mediaItemCount > 0 && liveIsBook && c.playbackState != Player.STATE_ENDED
                // The service retains its speed across activity recreation; mirror it to the UI, and
                // recover which folder it plays from so the speed button stays live.
                playbackSpeedState.value = c.playbackParameters.speed
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
    }

    /** Always highlights the playing track wherever it is visible. When Follow is enabled it also
     *  navigates the browser to the track's folder so the list can center it (no-op on the roots
     *  home screen). The folder path comes from the item's extras captured at scan time (no
     *  documentId string-splitting); if they are missing or the root is no longer held, the track is
     *  only highlighted where found and navigation is skipped. */
    private fun followPlayingTrack(item: MediaItem?) {
        val fileDocId = item?.mediaId
            ?.let { runCatching { DocumentsContract.getDocumentId(Uri.parse(it)) }.getOrNull() }
        playingDocIdState.value = fileDocId
        if (!followEnabled) return
        if (treeUriState.value == null || item == null) return
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

    /** Enters [treeUri] from the roots list, showing its top folder and marking it selected. The
     *  folder appears immediately with a cheap fallback label; the real display name is resolved off
     *  the main thread, since the SAF name query can block on a slow provider. */
    private fun enterRoot(treeUri: Uri) {
        selectedRootState.value = treeUri
        treeUriState.value = treeUri
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        pathState.value = listOf(Node(docId, fallbackRootLabel(treeUri), true))
        selectedIndexState.value = null
        lifecycleScope.launch {
            val node = withContext(Dispatchers.IO) { MusicScanner.rootNode(this@MainActivity, treeUri) }
            // Apply only if still sitting at this root's top folder (user hasn't navigated away).
            val path = pathState.value
            if (treeUriState.value == treeUri && path.size == 1 && path.first().documentId == docId) {
                pathState.value = listOf(node)
            }
        }
    }

    /** Drops the now-playing labels/bar when nothing is actively playing, so navigating away from a
     *  stopped or paused track lets it go; a playing track keeps its now-playing while you browse.
     *  Resuming re-populates the labels from the controller (see NowPlaying.onIsPlayingChanged). */
    private fun clearNowPlayingIfStopped() {
        val controller = controllerState.value
        if (controller?.isPlaying == true) return
        // Leaving a folder while a *book* is stopped ends its live queue: book mode is over, the
        // switch unlocks for music, and the saved resume point lets re-entering the folder pick it
        // back up. Music keeps its paused queue (it has no saved position to fall back on).
        if (controller != null && playingAbookState.value) {
            controller.clearMediaItems()
            controller.stop()
            playingFolderId = null
            playingAbookState.value = false
            playingFolderKeyState.value = null
            playingDocIdState.value = null
        }
        clearTitleTickState.value++
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
        if (playingThisRoot) {
            // pause() first so onIsPlayingChanged(false) reaches the UI and the button leaves its
            // "pause" icon; then drop the queue and reset playback state.
            controller.pause()
            controller.clearMediaItems()
            controller.stop()
            playingFolderId = null
            playingAbookState.value = false
            playingFolderKeyState.value = null
            playingDocIdState.value = null
            // clearMediaItems() emits no metadata event, so clear the now-playing labels ourselves.
            clearTitleTickState.value++
        }
        runCatching {
            contentResolver.releasePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        if (selectedRootState.value == treeUri) selectedRootState.value = null
        rootsState.value = Settings.removeRoot(this, treeUri.toString()).map(Uri::parse)
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
        when {
            index != null && dir != null -> playFile(dir, index)
            dir != null && dir.documentId != playingFolderId -> playFolder(dir)
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

    /** Plays everything under [folder] recursively. ExoPlayer's shuffle (toggled live) handles the
     *  order; with shuffle on we start on a random track, off starts from the top. */
    private fun playFolder(folder: Node) {
        if (controllerState.value == null) return
        val tree = treeUriState.value ?: return
        val path = pathState.value
        playbackLoadJob?.cancel()
        playbackLoadJob = lifecycleScope.launch {
            val items = try {
                withContext(Dispatchers.IO) { MusicScanner.collectAudio(this@MainActivity, tree, path) }
            } catch (e: ScanException) {
                if (liveController() != null) errorState.value = getString(R.string.folder_unreadable)
                return@launch
            }
            val controller = liveController() ?: return@launch
            if (items.isEmpty()) {
                errorState.value = getString(R.string.nothing_to_play)
                return@launch
            }
            val key = Settings.bookKey(tree.toString(), folder.documentId)
            val abook = Settings.isAbook(this@MainActivity, key)
            // Resume an abook from its saved file + offset, rewound 15s for context; new books start at 0.
            val saved = if (abook) Settings.getBookPos(this@MainActivity, key) else null
            val savedIdx = saved?.let { (uri, _) -> items.indexOfFirst { it.mediaId == uri } } ?: -1
            val start = when {
                abook -> savedIdx.coerceAtLeast(0)
                shuffleState.value -> items.indices.random()
                else -> 0
            }
            val startPos =
                if (savedIdx >= 0) (saved!!.second - 15_000).coerceAtLeast(0L) else 0L
            // Mark this folder as playing only now that a queue is actually starting; a failed or
            // empty scan must leave playingFolderId pointing at whatever was playing before.
            playingFolderId = folder.documentId
            startQueue(controller, items, start, abook, key, startPos)
        }
    }

    /** The controller, but only while the activity is still started and the scan wasn't superseded;
     *  guards against touching a controller released in onStop. */
    private fun liveController(): MediaController? {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return null
        return controllerState.value
    }

    /** Installs and starts [items] at [startIndex]. In abook mode the queue plays sequentially with
     *  no looping and the service tracks its position under [bookFolderKey]; otherwise it follows the
     *  live shuffle/repeat settings and position tracking is off. */
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
        controller.shuffleModeEnabled = !abook && shuffleState.value
        controller.repeatMode = if (abook) Player.REPEAT_MODE_OFF else loopRepeatMode()
        controller.setMediaItems(items, startIndex, startPositionMs)
        controller.prepare()
        controller.play()
        playingFolderKeyState.value = bookFolderKey
        // Speed is an audiobook feature: books restore their saved (or default) speed; plain music
        // always plays at 1.0, never inheriting a previous book's speed or the global default.
        if (abook && bookFolderKey != null) applyFolderSpeed(controller, bookFolderKey)
        else { controller.setPlaybackSpeed(Settings.SPEED_DEFAULT); playbackSpeedState.value = Settings.SPEED_DEFAULT }
        sendBookMode(controller, if (abook) bookFolderKey else null)
    }

    /** Applies the folder's saved (or default) playback speed to [controller] and mirrors it to the UI. */
    private fun applyFolderSpeed(controller: MediaController, folderKey: String) {
        val speed = Settings.getSpeed(this, folderKey)
        controller.setPlaybackSpeed(speed)
        playbackSpeedState.value = speed
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

    private fun loopRepeatMode(): Int =
        if (Settings.isLoopEnabled(this)) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

    /** Plays the selected file first, then the rest of the folder. ExoPlayer's shuffle (toggled
     *  live) decides whether the remainder is shuffled or continues in scan order. The selection is
     *  cleared only once playback actually starts, so a failed load leaves it intact. */
    private fun playFile(folder: Node, index: Int) {
        if (controllerState.value == null) return
        val tree = treeUriState.value ?: return
        val path = pathState.value
        playbackLoadJob?.cancel()
        playbackLoadJob = lifecycleScope.launch {
            val files = try {
                withContext(Dispatchers.IO) {
                    FolderCache.children(this@MainActivity, tree, folder).second
                }
            } catch (e: ScanException) {
                if (liveController() != null) errorState.value = getString(R.string.folder_unreadable)
                return@launch
            }
            val controller = liveController() ?: return@launch
            if (index !in files.indices) {
                errorState.value = getString(R.string.nothing_to_play)
                return@launch
            }
            val items = MusicScanner.mediaItems(tree, path, files)
            val key = Settings.bookKey(tree.toString(), folder.documentId)
            val abook = Settings.isAbook(this@MainActivity, key)
            // Set only on the success path so a failed load leaves playingFolderId untouched.
            playingFolderId = folder.documentId
            startQueue(controller, items, index, abook, key)
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
                    // Drop the deleted folder's own (and descendants') cached listings, then the
                    // parent's so the now-missing folder disappears from it on re-scan.
                    FolderCache.invalidateSubtree(this@MainActivity, tree, folder.documentId)
                    FolderCache.invalidate(this@MainActivity, tree, parent.documentId)
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
        controller.pause()
        controller.clearMediaItems()
        controller.stop()
        playingFolderId = null
        playingAbookState.value = false
        playingFolderKeyState.value = null
        playingDocIdState.value = null
        clearTitleTickState.value++
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
    error: String?,
    selectedIndex: Int?,
    playingDocId: String?,
    rescanTick: Int,
    clearTitleTick: Int,
    shuffleEnabled: Boolean,
    onShuffleToggle: (Boolean) -> Unit,
    abookEnabled: Boolean,
    abookVisible: Boolean,
    playingAbook: Boolean,
    onAbookToggle: (Boolean) -> Unit,
    onEnterRoot: (Uri) -> Unit,
    onAddRoot: () -> Unit,
    onRemoveRoot: (Uri) -> Unit,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    onDescend: (Node) -> Unit,
    onUp: () -> Unit,
    onPlayFolder: (Node) -> Unit,
    onDeleteBook: (Node) -> Unit,
    onSelectFile: (Int) -> Unit,
    onPlayPause: () -> Unit,
    speed: Float,
    speedEnabled: Boolean,
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
                    canGoUp = true,
                    selectedIndex = selectedIndex,
                    playingDocId = playingDocId,
                    rescanTick = rescanTick,
                    onUp = onUp,
                    onDescend = onDescend,
                    onPlayFolder = onPlayFolder,
                    onDeleteBook = onDeleteBook,
                    onSelectFile = onSelectFile,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        NowPlaying(
            controller, error, clearTitleTick, treeUri == null,
            shuffleEnabled, onShuffleToggle,
            abookEnabled, abookVisible, onAbookToggle,
            playingAbook, onPlayPause,
            speed, speedEnabled, onSpeedChange
        )
        Spacer(Modifier.height(32.dp))
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

/** Home screen: the list of root folders on a distinct background, with add/remove. */
@Composable
private fun RootsList(
    roots: List<Uri>,
    selectedRoot: Uri?,
    onEnterRoot: (Uri) -> Unit,
    onAddRoot: () -> Unit,
    onRemoveRoot: (Uri) -> Unit,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingRemoval by remember { mutableStateOf<Uri?>(null) }
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onExit) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.exit)
                )
            }
            Text(
                text = stringResource(R.string.music_folders),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onAddRoot) {
                Icon(
                    painter = painterResource(R.drawable.ic_add_circle),
                    contentDescription = stringResource(R.string.add_folder),
                    modifier = Modifier.size(32.dp)
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings)
                )
            }
        }

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
                                .padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
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
    canGoUp: Boolean,
    selectedIndex: Int?,
    playingDocId: String?,
    rescanTick: Int,
    onUp: () -> Unit,
    onDescend: (Node) -> Unit,
    onPlayFolder: (Node) -> Unit,
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
    LaunchedEffect(current.documentId, rescanTick, retryTick) {
        loadFailed = false
        contents = null
        try {
            contents = withContext(Dispatchers.IO) { FolderCache.children(context, treeUri, current) }
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = current.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onPlayFolder(current) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.play_this_folder))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onUp, enabled = canGoUp) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_up),
                    contentDescription = stringResource(R.string.up)
                )
            }
        }
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
                    val isBook = Settings.isAbook(
                        context, Settings.bookKey(treeUri.toString(), folder.documentId)
                    )
                    Text(
                        text = "${if (isBook) "📖" else "📁"}  ${folder.name}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onDescend(folder) },
                                onLongClick = { pendingDelete = folder }
                            )
                            .padding(horizontal = 8.dp, vertical = 10.dp)
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
                        fontSize = 22.sp,
                        color = foreground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(background)
                            .clickable { onSelectFile(index) }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
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
    connectError: String?,
    clearTitleTick: Int,
    atHome: Boolean,
    shuffleEnabled: Boolean,
    onShuffleToggle: (Boolean) -> Unit,
    abookEnabled: Boolean,
    abookVisible: Boolean,
    onAbookToggle: (Boolean) -> Unit,
    playingAbook: Boolean,
    onPlayPause: () -> Unit,
    speed: Float,
    speedEnabled: Boolean,
    onSpeedChange: (Float) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
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
                title = mediaMetadata.title?.toString().orEmpty()
                path = mediaMetadata.subtitle?.toString().orEmpty()
                cleared = false
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                // Resuming re-shows the now-playing even if it was cleared while stopped/browsing.
                if (playing) {
                    title = c.mediaMetadata.title?.toString().orEmpty()
                    path = c.mediaMetadata.subtitle?.toString().orEmpty()
                    cleared = false
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playerError = null
            }

            override fun onPlayerError(error: PlaybackException) {
                playerError = "${error.errorCodeName}: ${error.message}"
            }
        }
        c.addListener(listener)
        title = c.mediaMetadata.title?.toString().orEmpty()
        path = c.mediaMetadata.subtitle?.toString().orEmpty()
        isPlaying = c.isPlaying
        onDispose { c.removeListener(listener) }
    }

    // Clear the labels and bar on request: navigating away from a stopped/paused track, or removing
    // the playing root. A playing track keeps its now-playing while browsing (no tick is sent then).
    LaunchedEffect(clearTitleTick) {
        if (clearTitleTick > 0) {
            title = ""
            path = ""
            cleared = true
        }
    }

    // Separate fixed-height slots: path (1 line) and title (room for 2). Fixed so a wrapping
    // title or a missing path never reflows the list above. Empty when nothing is playing.
    // On the roots (home) screen the track name/path are hidden, even while playback continues.
    val display = if (atHome) "" else connectError ?: playerError ?: title
    val showPath = !atHome && connectError == null && playerError == null && path.isNotEmpty()
    Spacer(Modifier.height(10.dp))
    Box(modifier = Modifier.fillMaxWidth().height(18.dp), contentAlignment = Alignment.Center) {
        if (showPath) {
            Text(
                text = path,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            text = display,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
    // Thin playback bar with elapsed/total times at the edges, draggable/tappable to seek. Fixed
    // height so showing/hiding it (no track / on the home screen) never reflows the controls below.
    val showBar = !atHome && !cleared && connectError == null && playerError == null && durationMs > 0L
    val barFraction = scrubFraction ?: (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val leftMs = scrubFraction?.let { (it * durationMs).toLong() } ?: positionMs
    Spacer(Modifier.height(8.dp))
    Box(modifier = Modifier.fillMaxWidth().height(32.dp)) {
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
                    Text(formatTime(durationMs), fontSize = 14.sp)
                }
            }
        }
    }
    // Book progress: which file of the book plus an approximate overall percent (files vary in
    // length), with a thin bar. Only shown while a book is the live queue.
    if (playingAbook && !atHome && !cleared && mediaCount > 0) {
        val fileFrac = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val bookFraction = ((mediaIndex + fileFrac) / mediaCount).coerceIn(0f, 1f)
        val percent = String.format(Locale.US, "%.1f", bookFraction * 100)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${stringResource(R.string.file_label)} ${mediaIndex + 1}/$mediaCount", fontSize = 14.sp)
            Text("$percent%", fontSize = 14.sp)
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { bookFraction },
            modifier = Modifier.fillMaxWidth().height(5.dp)
        )
    }
    Spacer(Modifier.height(8.dp))
    Box(modifier = Modifier.fillMaxWidth()) {
        // The small controls are paired in vertically-centered columns so the gap inside each pair
        // (shuffle/abook on the left, next/speed on the right) lands on the play button's mid-height.
        Column(
            modifier = Modifier.align(Alignment.CenterStart),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A book plays sequentially, so shuffle is locked while a book is the live queue.
                // Leaving the book's folder while it's stopped ends that queue (see
                // clearNowPlayingIfStopped), which frees the switch again for music.
                Switch(
                    checked = shuffleEnabled,
                    onCheckedChange = onShuffleToggle,
                    enabled = !playingAbook
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_shuffle),
                    contentDescription = stringResource(R.string.shuffle),
                    tint = if (shuffleEnabled && !playingAbook) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(12.dp))
            // The slot keeps its height even when hidden, so shuffle never shifts as abook toggles.
            Box(modifier = Modifier.height(48.dp), contentAlignment = Alignment.CenterStart) {
                if (abookVisible) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { onAbookToggle(!abookEnabled) }
                    ) {
                        // The whole row toggles; the box stays non-interactive to avoid a double event.
                        Checkbox(checked = abookEnabled, onCheckedChange = null)
                        Text(stringResource(R.string.audiobook_mode))
                    }
                }
            }
        }
        Button(
            onClick = onPlayPause,
            enabled = controller != null,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.Center).size(120.dp)
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (isPlaying) R.string.pause else R.string.play),
                modifier = Modifier.size(if (isPlaying) 48.dp else 68.dp)
            )
        }
        Column(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = { controller?.seekToNext() },
                enabled = controller != null,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.width(76.dp).height(36.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = stringResource(R.string.next)
                )
            }
            Spacer(Modifier.height(12.dp))
            // Mirrors the abook slot on the left: speed shows only with an open folder, and the slot
            // keeps its height when hidden so next never shifts.
            Box(modifier = Modifier.height(36.dp), contentAlignment = Alignment.CenterEnd) {
                if (abookVisible) {
                    // Speed is an audiobook feature; for plain music the button stays disabled (grey).
                    SpeedButton(
                        label = formatSpeed(speed),
                        enabled = speedEnabled && playingAbook && controller != null,
                        onClick = { showSpeedDialog = true }
                    )
                }
            }
        }
    }
    if (showSpeedDialog) {
        SpeedDialog(
            initial = speed,
            onPreview = { controller?.setPlaybackSpeed(it) }, // audible while dragging
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
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = modifier.width(76.dp).height(36.dp)
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
                Slider(
                    value = sliderSpeed,
                    onValueChange = {
                        sliderSpeed = snapSpeed(it)
                        onPreview(sliderSpeed)
                    },
                    valueRange = Settings.SPEED_MIN..Settings.SPEED_MAX,
                    steps = SPEED_SLIDER_STEPS
                )
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
    loopEnabled: Boolean,
    followEnabled: Boolean,
    defaultSpeed: Float,
    onThemeChange: (ThemeMode) -> Unit,
    onReplayGainChange: (Boolean) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    onFollowChange: (Boolean) -> Unit,
    onDefaultSpeedChange: (Float) -> Unit,
    onRescan: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 16.dp, top = 40.dp, end = 16.dp, bottom = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings), fontSize = 22.sp)
        }

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
            Text(stringResource(R.string.repeat))
            Spacer(Modifier.weight(1f))
            Switch(checked = loopEnabled, onCheckedChange = onLoopChange)
        }

        Spacer(Modifier.height(10.dp))
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
