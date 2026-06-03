package com.myplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Screen { Browser, Settings }

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controllerState = mutableStateOf<MediaController?>(null)
    private val errorState = mutableStateOf<String?>(null)

    private val treeUriState = mutableStateOf<Uri?>(null)
    private val pathState = mutableStateOf<List<Node>>(emptyList())
    private val selectedIndexState = mutableStateOf<Int?>(null)
    private val screenState = mutableStateOf(Screen.Browser)
    private val rescanTickState = mutableStateOf(0)
    private val clearTitleTickState = mutableStateOf(0)
    private val themeState = mutableStateOf(ThemeMode.System)

    // Random playback order, on by default; intentionally not persisted (resets each launch).
    private val shuffleState = mutableStateOf(true)

    // documentId of the folder the current playback was started from.
    private var playingFolderId: String? = null

    /** Picks (or changes) the root folder. This is the only place a SAF permission is requested. */
    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                Settings.setFolderUri(this, uri.toString())
                FolderCache.clear(this)
                openRoot(uri)
                screenState.value = Screen.Browser
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        themeState.value = Settings.getThemeMode(this)
        Settings.getFolderUri(this)?.let { openRoot(Uri.parse(it)) }

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
                    val treeUri by treeUriState
                    val path by pathState
                    val error by errorState
                    val selectedIndex by selectedIndexState
                    val screen by screenState
                    val rescanTick by rescanTickState
                    val clearTitleTick by clearTitleTickState
                    val shuffle by shuffleState
                    var replayGain by remember { mutableStateOf(Settings.isReplayGainEnabled(this)) }
                    var loop by remember { mutableStateOf(Settings.isLoopEnabled(this)) }

                    BackHandler(enabled = screen == Screen.Settings || path.size > 1) {
                        if (screen == Screen.Settings) screenState.value = Screen.Browser else goUp()
                    }

                    when (screen) {
                        Screen.Settings -> SettingsScreen(
                            version = appVersionName(),
                            build = appVersionCode(),
                            themeMode = theme,
                            replayGainEnabled = replayGain,
                            loopEnabled = loop,
                            onThemeChange = {
                                themeState.value = it
                                Settings.setThemeMode(this, it)
                            },
                            onReplayGainChange = {
                                replayGain = it
                                Settings.setReplayGainEnabled(this, it)
                            },
                            onLoopChange = {
                                loop = it
                                Settings.setLoopEnabled(this, it)
                                controllerState.value?.repeatMode =
                                    if (it) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
                            },
                            onChangeRoot = {
                                folderPicker.launch(Settings.getFolderUri(this)?.let(Uri::parse))
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
                            treeUri = treeUri,
                            path = path,
                            error = error,
                            selectedIndex = selectedIndex,
                            rescanTick = rescanTick,
                            clearTitleTick = clearTitleTick,
                            shuffleEnabled = shuffle,
                            onShuffleToggle = {
                                shuffleState.value = it
                                controllerState.value?.shuffleModeEnabled = it
                            },
                            onPickRoot = {
                                folderPicker.launch(Settings.getFolderUri(this)?.let(Uri::parse))
                            },
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
                controllerState.value = future.get()
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

    private fun openRoot(treeUri: Uri) {
        treeUriState.value = treeUri
        pathState.value = listOf(MusicScanner.rootNode(this, treeUri))
        selectedIndexState.value = null
    }

    private fun goUp() {
        val path = pathState.value
        if (path.size > 1) pathState.value = path.dropLast(1)
        selectedIndexState.value = null
        clearTitleTickState.value++
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
            index != null && dir != null -> {
                playFile(dir, index)
                selectedIndexState.value = null
            }
            dir != null && dir.documentId != playingFolderId -> playFolder(dir)
            controller.mediaItemCount > 0 -> controller.play()
            dir != null -> playFolder(dir)
        }
    }

    /** Plays everything under [folder] recursively. ExoPlayer's shuffle (toggled live) handles the
     *  order; with shuffle on we start on a random track, off starts from the top. */
    private fun playFolder(folder: Node) {
        val controller = controllerState.value ?: return
        val tree = treeUriState.value ?: return
        val shuffle = shuffleState.value
        playingFolderId = folder.documentId
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { MusicScanner.collectAudio(this@MainActivity, tree, folder) }
            if (items.isNotEmpty()) {
                controller.shuffleModeEnabled = shuffle
                controller.repeatMode = loopRepeatMode()
                controller.setMediaItems(items, if (shuffle) items.indices.random() else 0, 0L)
                controller.prepare()
                controller.play()
            }
        }
    }

    private fun loopRepeatMode(): Int =
        if (Settings.isLoopEnabled(this)) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF

    /** Plays the selected file first, then the rest of the folder. ExoPlayer's shuffle (toggled
     *  live) decides whether the remainder is shuffled or continues in scan order. */
    private fun playFile(folder: Node, index: Int) {
        val controller = controllerState.value ?: return
        val tree = treeUriState.value ?: return
        val shuffle = shuffleState.value
        playingFolderId = folder.documentId
        lifecycleScope.launch {
            val files =
                withContext(Dispatchers.IO) { FolderCache.children(this@MainActivity, tree, folder).second }
            if (index in files.indices) {
                val items = MusicScanner.mediaItems(tree, files)
                controller.shuffleModeEnabled = shuffle
                controller.repeatMode = loopRepeatMode()
                controller.setMediaItems(items, index, 0L)
                controller.prepare()
                controller.play()
            }
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
    treeUri: Uri?,
    path: List<Node>,
    error: String?,
    selectedIndex: Int?,
    rescanTick: Int,
    clearTitleTick: Int,
    shuffleEnabled: Boolean,
    onShuffleToggle: (Boolean) -> Unit,
    onPickRoot: () -> Unit,
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
        // Lift the list's bottom edge by ~1cm (160dp = 1in) above the now-playing controls.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(bottom = 63.dp)) {
            if (current == null || treeUri == null) {
                Button(onClick = onPickRoot) { Text(stringResource(R.string.choose_folder)) }
            } else {
                FolderBrowser(
                    treeUri = treeUri,
                    current = current,
                    canGoUp = path.size > 1,
                    selectedIndex = selectedIndex,
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

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        NowPlaying(controller, error, clearTitleTick, shuffleEnabled, onShuffleToggle, onPlayPause)
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FolderBrowser(
    treeUri: Uri,
    current: Node,
    canGoUp: Boolean,
    selectedIndex: Int?,
    rescanTick: Int,
    onUp: () -> Unit,
    onDescend: (Node) -> Unit,
    onPlayFolder: (Node) -> Unit,
    onSelectFile: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var contents by remember(current.documentId) {
        mutableStateOf<Pair<List<Node>, List<Node>>?>(null)
    }
    LaunchedEffect(current.documentId, rescanTick) {
        contents = withContext(Dispatchers.IO) { FolderCache.children(context, treeUri, current) }
    }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = current.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onOpenSettings) { Text(stringResource(R.string.settings)) }
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onPlayFolder(current) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.play_this_folder))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onUp, enabled = canGoUp) {
                Text(stringResource(R.string.up))
            }
        }
        Spacer(Modifier.height(8.dp))

        val c = contents
        if (c != null && c.first.isEmpty() && c.second.isEmpty()) {
            Text(stringResource(R.string.empty_folder))
        } else if (c != null) {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(c.first, key = { it.documentId }) { folder ->
                    Text(
                        text = "📁  ${folder.name}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDescend(folder) }
                            .padding(vertical = 10.dp)
                    )
                }
                itemsIndexed(c.second, key = { _, file -> file.documentId }) { index, file ->
                    val selected = index == selectedIndex
                    Text(
                        text = "🎵  ${file.name}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 22.sp,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary
                        else Color.Unspecified,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { onSelectFile(index) }
                            .padding(vertical = 10.dp)
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
    shuffleEnabled: Boolean,
    onShuffleToggle: (Boolean) -> Unit,
    onPlayPause: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }

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

    val display =
        connectError ?: playerError ?: title.ifEmpty { stringResource(R.string.nothing_playing) }
    if (connectError == null && playerError == null && path.isNotEmpty()) {
        Text(
            text = path,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Text(
        text = display,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(16.dp))
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
            Text(
                text = stringResource(if (isPlaying) R.string.pause else R.string.play),
                fontSize = 20.sp
            )
        }
        Button(
            onClick = { controller?.seekToNext() },
            enabled = controller != null,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Text(stringResource(R.string.next))
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
    onThemeChange: (ThemeMode) -> Unit,
    onReplayGainChange: (Boolean) -> Unit,
    onLoopChange: (Boolean) -> Unit,
    onChangeRoot: () -> Unit,
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
        Button(onClick = onChangeRoot, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.change_folder))
        }
        Spacer(Modifier.height(12.dp))
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

        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.replaygain))
            Spacer(Modifier.weight(1f))
            Switch(checked = replayGainEnabled, onCheckedChange = onReplayGainChange)
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
