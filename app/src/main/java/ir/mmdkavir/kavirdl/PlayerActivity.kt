package ir.mmdkavir.kavirdl

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ScreenRotationAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import android.view.TextureView
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
    private var file: File = File("")
    private var fullscreen by mutableStateOf(false)
    private var landscape by mutableStateOf(false)
    private var baseOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

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

        fun posKey(f: File) = "pos_" + f.absolutePath.hashCode()
        fun rotKey(f: File) = "rot_" + f.absolutePath.hashCode()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(intent.getStringExtra(X_PATH) ?: "")
        val title = intent.getStringExtra(X_TITLE).orEmpty()
        val thumb = intent.getStringExtra(X_THUMB).orEmpty()
        val isVideo = Gallery.kindOf(Gallery.mimeOf(file)) == Kind.VIDEO
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        baseOrientation = when (Prefs.str(this, "pl_orient", "auto")) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        requestedOrientation = baseOrientation
        landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        applyBars()
        val p = ExoPlayer.Builder(this).build()
        player = p
        if (file.exists()) {
            p.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            if (isVideo) p.setPlaybackSpeed(Prefs.str(this, "pl_speed", "1.0").toFloatOrNull() ?: 1f)
            val saved = Prefs.str(this, posKey(file), "0").toLongOrNull() ?: 0L
            if (Prefs.str(this, "pl_remember", "1") == "1" && saved > 3000) p.seekTo(saved)
            p.prepare()
            p.playWhenReady = true
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (isVideo) {
                    VideoScreen(p, file, title, fullscreen || landscape, { toggleFullscreen() }) { exit() }
                } else {
                    PlayerScreen(p, file, title, thumb, false) { exit() }
                }
            }
        }
    }

    private fun applyBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen || landscape) {
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            c.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun toggleFullscreen() {
        if (!fullscreen && !landscape) {
            fullscreen = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            fullscreen = false
            requestedOrientation = baseOrientation
        }
        applyBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!landscape && fullscreen && requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE) fullscreen = false
        applyBars()
    }

    private fun savePosition() {
        val p = player ?: return
        if (!file.exists()) return
        val d = p.duration
        val pos = p.currentPosition
        val v = if (d > 0 && pos > d - 3000) 0L else pos
        Prefs.setStr(this, posKey(file), v.toString())
    }

    private fun exit() {
        savePosition()
        releasePlayer()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        savePosition()
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

@Composable
private fun VideoScreen(p: ExoPlayer, file: File, title: String, isFull: Boolean, onFullscreen: () -> Unit, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val hideMs = (Prefs.str(ctx, "pl_hide", "4").toLongOrNull() ?: 4L) * 1000
    var playing by remember { mutableStateOf(p.isPlaying) }
    var buffering by remember { mutableStateOf(p.playbackState == Player.STATE_BUFFERING) }
    var pos by remember { mutableLongStateOf(p.currentPosition) }
    var dur by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    var volume by remember { mutableFloatStateOf(p.volume) }
    var muted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(if (file.exists()) "" else "File not found") }
    var ratio by remember { mutableFloatStateOf(if (p.videoSize.height > 0) p.videoSize.width * p.videoSize.pixelWidthHeightRatio / p.videoSize.height else 16f / 9f) }
    var rot by remember { mutableIntStateOf((Prefs.str(ctx, PlayerActivity.rotKey(file), "0").toIntOrNull() ?: 0) % 360) }
    var visible by remember { mutableStateOf(true) }
    var touch by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(p.playbackParameters.speed) }
    var speedMenu by remember { mutableStateOf(false) }
    var rotMenu by remember { mutableStateOf(false) }
    var seekHint by remember { mutableStateOf("") }

    fun setRot(r: Int) {
        rot = ((r % 360) + 360) % 360
        Prefs.setStr(ctx, PlayerActivity.rotKey(file), rot.toString())
        touch++
    }

    androidx.compose.runtime.DisposableEffect(p) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "This video cannot be played (unsupported or corrupted file)"
            }

            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                if (v.width > 0 && v.height > 0) ratio = v.width * v.pixelWidthHeightRatio / v.height
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    playing = false
                    visible = true
                }
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
    LaunchedEffect(visible, playing, touch, speedMenu, rotMenu, dragging) {
        if (visible && playing && !speedMenu && !rotMenu && !dragging) {
            delay(hideMs)
            visible = false
        }
    }
    LaunchedEffect(seekHint) {
        if (seekHint.isNotEmpty()) {
            delay(700)
            seekHint = ""
        }
    }
    BackHandler { if (isFull) onFullscreen() else onExit() }

    val bar = Color.Black.copy(alpha = 0.55f)
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val sideways = rot == 90 || rot == 270
        val eff = if (sideways) 1f / ratio else ratio
        val bw = maxWidth.value
        val bh = maxHeight.value
        val dw = if (bw / bh > eff) bh * eff else bw
        val dh = if (bw / bh > eff) bh else bw / eff
        Box(Modifier.align(Alignment.Center).size(dw.dp, dh.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.requiredSize((if (sideways) dh else dw).dp, (if (sideways) dw else dh).dp)
                    .graphicsLayer { rotationZ = rot.toFloat() }
            ) {
                AndroidView(
                    factory = { c -> TextureView(c).also { p.setVideoTextureView(it) } },
                    onRelease = { p.clearVideoTextureView(it) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { visible = !visible; touch++ },
                    onDoubleTap = { o ->
                        if (error.isEmpty()) {
                            val back = o.x < size.width / 2
                            val t = p.currentPosition + if (back) -10_000 else 10_000
                            p.seekTo(if (dur > 0) t.coerceIn(0, dur) else t.coerceAtLeast(0))
                            seekHint = if (back) "-10s" else "+10s"
                            touch++
                        }
                    }
                )
            }
        )
        if (buffering && error.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        if (seekHint.isNotEmpty()) {
            Text(
                seekHint, color = Color.White, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.Center).background(bar, RoundedCornerShape(20.dp)).padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
        if (error.isNotEmpty()) {
            Text(
                error, color = Color(0xFFFF8A80), textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp).background(bar, RoundedCornerShape(16.dp)).padding(16.dp)
            )
        }
        AnimatedVisibility(visible || error.isNotEmpty(), Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(bar).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title.ifBlank { file.nameWithoutExtension }, color = Color.White, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                Box {
                    IconButton(onClick = { rotMenu = true; touch++ }) {
                        Icon(Icons.Default.ScreenRotationAlt, contentDescription = "Rotate video", tint = Color.White)
                    }
                    DropdownMenu(rotMenu, { rotMenu = false }) {
                        DropdownMenuItem(text = { Text("Rotate 90° clockwise") }, onClick = { setRot(rot + 90); rotMenu = false })
                        DropdownMenuItem(text = { Text("Rotate 90° counterclockwise") }, onClick = { setRot(rot - 90); rotMenu = false })
                        DropdownMenuItem(text = { Text("Rotate 180°") }, onClick = { setRot(rot + 180); rotMenu = false })
                        DropdownMenuItem(text = { Text("Reset rotation (0°)") }, onClick = { setRot(0); rotMenu = false })
                    }
                }
                Box {
                    IconButton(onClick = { speedMenu = true; touch++ }) {
                        Icon(Icons.Default.Speed, contentDescription = "Playback speed", tint = Color.White)
                    }
                    DropdownMenu(speedMenu, { speedMenu = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { sp ->
                            DropdownMenuItem(
                                text = { Text((if (sp == speed) "● " else "") + "${sp}x") },
                                onClick = { p.setPlaybackSpeed(sp); speed = sp; speedMenu = false }
                            )
                        }
                    }
                }
                IconButton(onClick = onFullscreen) {
                    Icon(if (isFull) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, contentDescription = "Fullscreen", tint = Color.White)
                }
                Button(onClick = onExit) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Exit Player")
                }
            }
        }
        AnimatedVisibility(visible || error.isNotEmpty(), Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxWidth().background(bar).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(if (dragging) (dragPos * dur).toLong() else pos), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = if (dragging) dragPos else if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = true; dragPos = it },
                        onValueChangeFinished = {
                            if (dur > 0) {
                                p.seekTo((dragPos * dur).toLong())
                                pos = (dragPos * dur).toLong()
                            }
                            dragging = false
                        },
                        enabled = dur > 0 && error.isEmpty(),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )
                    Text(fmt(dur), color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        muted = !muted
                        p.volume = if (muted) 0f else volume
                        touch++
                    }) {
                        Icon(
                            if (muted || volume == 0f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                            contentDescription = if (muted) "Unmute" else "Mute", tint = Color.White
                        )
                    }
                    Slider(
                        value = if (muted) 0f else volume,
                        onValueChange = { volume = it; muted = false; p.volume = it; touch++ },
                        modifier = Modifier.weight(0.6f)
                    )
                    Spacer(Modifier.weight(0.1f))
                    IconButton(onClick = { p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0)); touch++ }, enabled = error.isEmpty()) {
                        Icon(Icons.Default.Replay10, contentDescription = "Back 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    IconButton(
                        onClick = {
                            if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                            if (p.isPlaying) p.pause() else p.play()
                            touch++
                        },
                        enabled = error.isEmpty(), modifier = Modifier.size(60.dp)
                    ) {
                        Icon(
                            if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(44.dp)
                        )
                    }
                    IconButton(
                        onClick = { val t = p.currentPosition + 10_000; p.seekTo(if (dur > 0) t.coerceAtMost(dur) else t); touch++ },
                        enabled = error.isEmpty()
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.weight(0.7f))
                }
            }
        }
    }
}

@Composable
fun PlayerSettings() {
    val ctx = LocalContext.current
    var speed by remember { mutableStateOf(Prefs.str(ctx, "pl_speed", "1.0")) }
    var hide by remember { mutableStateOf(Prefs.str(ctx, "pl_hide", "4")) }
    var orient by remember { mutableStateOf(Prefs.str(ctx, "pl_orient", "auto")) }
    var remember by remember { mutableStateOf(Prefs.str(ctx, "pl_remember", "1")) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("تنظیمات پخش‌کننده", style = MaterialTheme.typography.titleMedium)
        Text("سرعت پیش‌فرض ویدیو", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("0.5", "0.75", "1.0", "1.25", "1.5", "2.0").forEach {
                FilterChip(selected = speed == it, onClick = { speed = it; Prefs.setStr(ctx, "pl_speed", it) }, label = { Text("${it}x") })
            }
        }
        Text("مخفی شدن خودکار کنترل‌ها (ثانیه)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("2", "4", "6", "8").forEach {
                FilterChip(selected = hide == it, onClick = { hide = it; Prefs.setStr(ctx, "pl_hide", it) }, label = { Text(it) })
            }
        }
        Text("جهت صفحه هنگام پخش", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "خودکار", "portrait" to "عمودی", "landscape" to "افقی").forEach { (k, l) ->
                FilterChip(selected = orient == k, onClick = { orient = k; Prefs.setStr(ctx, "pl_orient", k) }, label = { Text(l) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(checked = remember == "1", onCheckedChange = { remember = if (it) "1" else "0"; Prefs.setStr(ctx, "pl_remember", remember) })
            Spacer(Modifier.size(8.dp))
            Text("یادآوری آخرین نقطه پخش")
        }
    }
}
