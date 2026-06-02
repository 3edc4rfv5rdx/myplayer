package com.myplayer

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controllerState = mutableStateOf<MediaController?>(null)

    /** Opens the system folder picker starting at the remembered root (if any). */
    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                // First pick becomes the remembered start folder the picker opens at next time.
                if (Prefs.getFolderUri(this) == null) Prefs.setFolderUri(this, uri.toString())
                playFolder(uri)
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()

        setContent {
            MaterialTheme {
                val controller by controllerState
                var replayGain by remember { mutableStateOf(Prefs.isReplayGainEnabled(this)) }

                PlayerScreen(
                    controller = controller,
                    onPickFolder = { folderPicker.launch(Prefs.getFolderUri(this)?.let(Uri::parse)) },
                    replayGainEnabled = replayGain,
                    onReplayGainChange = {
                        replayGain = it
                        Prefs.setReplayGainEnabled(this, it)
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlayerService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({ controllerState.value = future.get() }, MoreExecutors.directExecutor())
        controllerFuture = future
    }

    override fun onStop() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerState.value = null
        super.onStop()
    }

    private fun playFolder(treeUri: Uri) {
        val controller = controllerState.value ?: return
        val folder = MusicScanner.treeFolder(this, treeUri) ?: return
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { MusicScanner.collectAudio(folder) }
            if (items.isNotEmpty()) {
                controller.setMediaItems(items)
                controller.shuffleModeEnabled = true
                controller.repeatMode = Player.REPEAT_MODE_ALL
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
}

@Composable
private fun PlayerScreen(
    controller: MediaController?,
    onPickFolder: () -> Unit,
    replayGainEnabled: Boolean,
    onReplayGainChange: (Boolean) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                title = mediaMetadata.title?.toString().orEmpty()
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        c.addListener(listener)
        title = c.mediaMetadata.title?.toString().orEmpty()
        isPlaying = c.isPlaying
        onDispose { c.removeListener(listener) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Button(onClick = onPickFolder) {
            Text(stringResource(R.string.choose_folder))
        }

        Spacer(Modifier.height(24.dp))
        Text(
            text = title.ifEmpty { stringResource(R.string.nothing_playing) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } },
                enabled = controller != null
            ) {
                Text(stringResource(if (isPlaying) R.string.pause else R.string.play))
            }
            Spacer(Modifier.width(16.dp))
            Button(onClick = { controller?.seekToNext() }, enabled = controller != null) {
                Text(stringResource(R.string.next))
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.replaygain))
            Spacer(Modifier.width(8.dp))
            Switch(checked = replayGainEnabled, onCheckedChange = onReplayGainChange)
        }
    }
}
