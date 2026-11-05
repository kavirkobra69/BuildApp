package ir.mmdkavir.kavirdl

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Full-screen in-app player for downloaded video and audio files. */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    companion object {
        private const val X_PATH = "path"
        private const val X_TITLE = "title"
        private const val X_THUMB = "thumb"

        fun open(ctx: Context, file: File, title: String, thumb: String) {
            val i = Intent(ctx, PlayerActivity::class.java)
                .putExtra(X_PATH, file.absolutePath)
                .putExtra(X_TITLE, title)
                .putExtra(X_THUMB, thumb)
            ctx.startActivity(i)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = File(intent.getStringExtra(X_PATH) ?: "")
        val title = intent.getStringExtra(X_TITLE).orEmpty()
        val thumb = intent.getStringExtra(X_THUMB).orEmpty()
        val isVideo = Gallery.kindOf(Gallery.mimeOf(file)) == Kind.VIDEO
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = ExoPlayer.Builder(this).build()
        player = p
        if (file.exists()) {
            p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            p.prepare()
            p.playWhenReady = true
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(p, file, title, thumb, isVideo) { exit() }
            }
        }
    }

    private fun exit() {
        releasePlayer()
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun releasePlayer() {
        player?.let {
            it.stop()
            it.release()
        }
        player = null
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }
}

private fun fmt(ms: Long): String {
    val s = (if (ms < 0) 0 else ms) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private class Meta(val title: String, val artist: String, val art: Bitmap?)

private fun readMeta(file: File, title: String, thumb: String): Meta {
    var t = ""
    var a = ""
    var art: Bitmap? = null
    try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            t = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            a = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty()
            r.embeddedPicture?.let { art = BitmapFactory.decodeByteArray(it, 0, it.size) }
        } finally {
            r.release()
        }
    } catch (_: Throwable) {
    }
    if (art == null && thumb.startsWith("http")) art = Net.bitmap(thumb)
    return Meta(t.ifBlank { title.ifBlank { file.nameWithoutExtension } }, a, art)
}

@Composable
private fun PlayerScreen(p: ExoPlayer, file: File, title: String, thumb: String, isVideo: Boolean, onExit: () -> Unit) {
    var playing by remember { mutableStateOf(p.isPlaying) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableFloatStateOf(p.volume) }
    var muted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(if (file.exists()) "" else "File not found") }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }

    androidx.compose.runtime.DisposableEffect(p) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "This file cannot be played (unsupported or corrupted)"
            }

            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                if (v.width > 0 && v.height > 0) ratio = v.width * v.pixelWidthHeightRatio / v.height
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playing = false
            }
        }
        p.addListener(l)
        onDispose { p.removeListener(l) }
    }
    LaunchedEffect(p) {
        while (true) {
            if (!dragging) pos = p.currentPosition
            dur = if (p.duration > 0) p.duration else 0L
            delay(250)
        }
    }
    BackHandler { onExit() }

    val meta by produceState<Meta?>(null) {
        value = if (isVideo) null else withContext(Dispatchers.IO) { readMeta(file, title, thumb) }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E0E12)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isVideo) title.ifBlank { file.nameWithoutExtension } else "Now playing",
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = onExit) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Exit Player")
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (error.isNotEmpty()) {
                Text(error, color = Color(0xFFFF8A80), textAlign = TextAlign.Center)
            } else if (isVideo) {
                AndroidView(
                    factory = { c ->
                        PlayerView(c).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            this.player = p
                        }
                    },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(12.dp)).background(Color.Black)
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(220.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF1E1E26)),
                        contentAlignment = Alignment.Center
                    ) {
                        val art = meta?.art
                        if (art != null) {
                            Image(art.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(80.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        meta?.title ?: title.ifBlank { file.nameWithoutExtension },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    val artist = meta?.artist.orEmpty()
                    if (artist.isNotBlank()) Text(artist, color = Color(0xFFB0B0BC), textAlign = TextAlign.Center)
                }
            }
        }
        Slider(
            value = if (dragging) dragPos else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
            onValueChange = {
                dragging = true
                dragPos = it
            },
            onValueChangeFinished = {
                if (dur > 0) {
                    p.seekTo((dragPos * dur).toLong())
                    pos = (dragPos * dur).toLong()
                }
                dragging = false
            },
            enabled = dur > 0 && error.isEmpty()
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt(if (dragging) (dragPos * dur).toLong() else pos), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(fmt(dur), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)) }, enabled = error.isEmpty()) {
                Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp))
            }
            IconButton(
                onClick = {
                    if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                    if (p.isPlaying) p.pause() else p.play()
                },
                enabled = error.isEmpty(),
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(52.dp)
                )
            }
            IconButton(
                onClick = {
                    val target = p.currentPosition + 10_000
                    p.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
                },
                enabled = error.isEmpty()
            ) {
                Icon(Icons.Default.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                muted = !muted
                p.volume = if (muted) 0f else volume
            }) {
                Icon(
                    if (muted || volume == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    tint = Color.White
                )
            }
            Slider(
                value = if (muted) 0f else volume,
                onValueChange = {
                    volume = it
                    muted = false
                    p.volume = it
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
