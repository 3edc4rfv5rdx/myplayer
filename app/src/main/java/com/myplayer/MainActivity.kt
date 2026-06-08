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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

    // documentId of the folder the current playback was started from.
    private var playingFolderId: String? = null

    // Cached so onMediaItemTransition needn't read the DB on every track change.
    private var followEnabled = true

    // The in-flight folder/file scan; cancelled when a newer Play request supersedes it so the last
    // request wins (not the last scan to finish).
    private var playbackLoadJob: Job? = null

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
                    var replayGain by remember { mutableStateOf(Settings.isReplayGainEnabled(this)) }
                    var loop by remember { mutableStateOf(Settings.isLoopEnabled(this)) }
                    var follow by remember { mutableStateOf(Settings.isFollowEnabled(this)) }

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
                            onRescan = {
                                FolderCache.clear(this)
                                rescanTickState.value++
                                screenState.value = Screen.Browser
                            },
                            onBack = { screenState.value = Screen.Browser }
                        )

                        Screen.Browser -> PlayerScreen(
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
                            onSelectFile = { selectedIndexState.value = it },
                            onPlayPause = { togglePlay() }
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
                })
                // The service keeps playing across activity recreation; recover which folder the
                // live playlist was started from so Play in the same folder resumes, not restarts.
                playingFolderId = playFolderIdOf(c.currentMediaItem)
                followPlayingTrack(c.currentMediaItem)
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

    /** Enters [treeUri] from the roots list, showing its top folder and marking it selected. */
    private fun enterRoot(treeUri: Uri) {
        selectedRootState.value = treeUri
        treeUriState.value = treeUri
        pathState.value = listOf(MusicScanner.rootNode(this, treeUri))
        selectedIndexState.value = null
    }

    /** Returns to the roots list (the home screen). */
    private fun goHome() {
        treeUriState.value = null
        pathState.value = emptyList()
        selectedIndexState.value = null
        clearTitleTickState.value++
    }

    /** Up one level; from a root's top folder this returns to the roots list. */
    private fun goUp() {
        val path = pathState.value
        if (path.size > 1) {
            pathState.value = path.dropLast(1)
            selectedIndexState.value = null
            clearTitleTickState.value++
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
            controller.mediaItemCount > 0 -> controller.play()
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
        playingFolderId = folder.documentId
        playbackLoadJob?.cancel()
        playbackLoadJob = lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { MusicScanner.collectAudio(this@MainActivity, tree, path) }
            val controller = liveController() ?: return@launch
            if (items.isEmpty()) {
                errorState.value = getString(R.string.nothing_to_play)
                return@launch
            }
            val start = if (shuffleState.value) items.indices.random() else 0
            startQueue(controller, items, start)
        }
    }

    /** The controller, but only while the activity is still started and the scan wasn't superseded;
     *  guards against touching a controller released in onStop. */
    private fun liveController(): MediaController? {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return null
        return controllerState.value
    }

    /** Installs and starts [items] at [startIndex] with the live shuffle/repeat settings. */
    private fun startQueue(controller: MediaController, items: List<MediaItem>, startIndex: Int) {
        errorState.value = null
        controller.shuffleModeEnabled = shuffleState.value
        controller.repeatMode = loopRepeatMode()
        controller.setMediaItems(items, startIndex, 0L)
        controller.prepare()
        controller.play()
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
        playingFolderId = folder.documentId
        playbackLoadJob?.cancel()
        playbackLoadJob = lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching { FolderCache.children(this@MainActivity, tree, folder).second }
                    .getOrDefault(emptyList())
            }
            val controller = liveController() ?: return@launch
            if (index !in files.indices) {
                errorState.value = getString(R.string.nothing_to_play)
                return@launch
            }
            val items = MusicScanner.mediaItems(tree, path, files)
            startQueue(controller, items, index)
            selectedIndexState.value = null
        }
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
    onEnterRoot: (Uri) -> Unit,
    onAddRoot: () -> Unit,
    onRemoveRoot: (Uri) -> Unit,
    onExit: () -> Unit,
    onOpenSettings: () -> Unit,
    onDescend: (Node) -> Unit,
    onUp: () -> Unit,
    onPlayFolder: (Node) -> Unit,
    onSelectFile: (Int) -> Unit,
    onPlayPause: () -> Unit
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
                    onSelectFile = onSelectFile,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        NowPlaying(
            controller, error, clearTitleTick, treeUri == null,
            shuffleEnabled, onShuffleToggle, onPlayPause
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
                    TextButton(onClick = {
                        onRemoveRoot(uri)
                        pendingRemoval = null
                    }) { Text(stringResource(R.string.remove)) }
                },
                dismissButton = {
                    TextButton(onClick = { pendingRemoval = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

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
    onSelectFile: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    Text(
                        text = "📁  ${folder.name}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDescend(folder) }
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
    onPlayPause: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var durationMs by remember { mutableStateOf(0L) }

    // Poll the controller for the playback position (Media3 has no position-changed callback);
    // a light 500 ms tick is enough for a thin progress bar and the edge timestamps.
    LaunchedEffect(controller) {
        while (controller != null) {
            positionMs = controller.currentPosition.coerceAtLeast(0L)
            durationMs = controller.duration.takeIf { it > 0L } ?: 0L
            delay(500)
        }
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                title = mediaMetadata.title?.toString().orEmpty()
                path = mediaMetadata.subtitle?.toString().orEmpty()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
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

    // Clear the now-playing labels when navigating Up.
    LaunchedEffect(clearTitleTick) {
        if (clearTitleTick > 0) {
            title = ""
            path = ""
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
    // Thin playback bar with elapsed/total times at the edges. Fixed height so showing/hiding it
    // (no track / on the home screen) never reflows the transport controls below.
    val showBar = !atHome && connectError == null && playerError == null && durationMs > 0L
    Spacer(Modifier.height(8.dp))
    Box(modifier = Modifier.fillMaxWidth().height(32.dp)) {
        if (showBar) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                )
                Spacer(Modifier.height(3.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(positionMs), fontSize = 14.sp)
                    Text(formatTime(durationMs), fontSize = 14.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Switch(checked = shuffleEnabled, onCheckedChange = onShuffleToggle)
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(R.drawable.ic_shuffle),
                contentDescription = stringResource(R.string.shuffle),
                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
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
        Button(
            onClick = { controller?.seekToNext() },
            enabled = controller != null,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_skip_next),
                contentDescription = stringResource(R.string.next)
            )
        }
    }
}

@Composable
private fun SettingsScreen(
    version: String,
    build: Long,
    themeMode: ThemeMode,
    replayGainEnabled: Boolean,
    loopEnabled: Boolean,
    followEnabled: Boolean,
    onThemeChange: (ThemeMode) -> Unit,
    onReplayGainChange: (Boolean) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    onFollowChange: (Boolean) -> Unit,
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
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}
