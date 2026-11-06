package ir.mmdkavir.kavirdl

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val vm: MainVM by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }
        if (savedInstanceState == null && intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.url = it }
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val c = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(c) else darkColorScheme()
            MaterialTheme(
                colorScheme = scheme,
                shapes = androidx.compose.material3.Shapes(
                    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)
                ),
                typography = androidx.compose.material3.Typography().let {
                    it.copy(
                        titleLarge = it.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        titleMedium = it.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        titleSmall = it.titleSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    )
                }
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Root(vm)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Root(vm: MainVM) {
    val rctx = LocalContext.current
    var screen by remember { mutableStateOf(if (Prefs.str(rctx, "onboarded") == "1") 0 else 3) }
    BackHandler(screen == 1 || screen == 2) { screen = if (screen == 2) 1 else 0 }
    if (screen == 3) {
        Onboarding(
            onDone = { Prefs.setStr(rctx, "onboarded", "1"); screen = 0 },
            onSettings = { Prefs.setStr(rctx, "onboarded", "1"); screen = 1 },
            onGuide = { Prefs.setStr(rctx, "onboarded", "1"); screen = 2 }
        )
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                title = { Text(when (screen) { 0 -> "KavirDL 🚀"; 1 -> "تنظیمات"; else -> "راهنما و نصب" }) },
                navigationIcon = {
                    if (screen != 0) {
                        IconButton(onClick = { screen = if (screen == 2) 1 else 0 }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                        }
                    }
                },
                actions = {
                    if (screen == 0) {
                        IconButton(onClick = { screen = 1 }) {
                            Icon(Icons.Default.Settings, contentDescription = "تنظیمات")
                        }
                    }
                }
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (screen) {
                0 -> Home(vm)
                1 -> SettingsScreen(vm, onGuide = { screen = 2 }, onIntro = { screen = 3 })
                else -> GuideScreen()
            }
        }
    }
}

private fun fmtSize(ctx: Context, b: Long) = Formatter.formatShortFileSize(ctx, b)

private fun fmtEta(sec: Long): String {
    if (sec < 0) return "—"
    val m = sec / 60
    val s = sec % 60
    return if (m > 0) "${m}:${s.toString().padStart(2, '0')}" else "${s}s"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Home(vm: MainVM) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 8.dp)) {
            OutlinedTextField(
                value = vm.url,
                onValueChange = { vm.url = it },
                label = { Text("لینک ویدیو، آهنگ یا عکس") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val t = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                        if (!t.isNullOrBlank()) vm.url = t
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "جایگذاری")
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("best" to "بهترین", "1080" to "1080", "720" to "720", "480" to "480", "audio" to "فقط صدا (mp3)").forEach { (k, label) ->
                    val sel = vm.quality == k
                    FilterChip(
                        selected = sel,
                        onClick = { vm.pickQuality(k) },
                        label = { Text(label, fontWeight = if (sel) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal) },
                        leadingIcon = if (sel) ({ Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                        shape = RoundedCornerShape(14.dp),
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.start() }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("دانلود", style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.start("image") }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("دانلود عکس")
                }
                OutlinedButton(onClick = { vm.start("gif") }, modifier = Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("دانلود GIF")
                }
            }
            if (vm.status.isNotBlank()) {
                Text(
                    vm.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 6.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (vm.tasks.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text("هنوز دانلودی نداری", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "لینک یوتیوب، اینستاگرام، تیک‌تاک، اسپاتیفای یا پینترست رو بذار. دانلود روی ریپوی گیت‌هاب خودت انجام می‌شه؛ اول توی تنظیمات وصلش کن و «ساخت پیکربندی» رو بزن.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        if (vm.tasks.isNotEmpty()) {
            Surface(
                tonalElevation = 6.dp,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(top = 10.dp)) {
                    val active = vm.tasks.count { it.state == St.QUEUED || it.state == St.RUNNING }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "دانلودها (${vm.tasks.size})" + if (active > 0) " · $active فعال" else "",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (vm.tasks.any { it.state == St.DONE }) {
                            TextOnly("پاک کردن تمام‌شده‌ها") { vm.clearFinished() }
                        }
                    }
                    LazyColumn(
                        Modifier.heightIn(max = 340.dp).padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                    ) {
                        items(vm.tasks, key = { it.id }) { TaskCard(it, vm, Modifier.animateItem()) }
                    }
                }
            }
        }
    }
}

@Composable
fun TextOnly(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

@Composable
fun TaskCard(t: Task, vm: MainVM, modifier: Modifier) {
    val ctx = LocalContext.current
    Card(
        modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(t)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title.ifBlank { t.src },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val stText = when (t.state) {
                        St.QUEUED -> "در صف"
                        St.RUNNING -> "در حال دانلود"
                        St.DONE -> "تکمیل شد"
                        St.FAILED -> "ناموفق"
                    }
                    Text(stText, style = MaterialTheme.typography.labelMedium, color = when (t.state) {
                        St.FAILED -> MaterialTheme.colorScheme.error
                        St.DONE -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    })
                }
                when (t.state) {
                    St.QUEUED, St.RUNNING -> IconButton(onClick = { vm.cancel(t) }) {
                        Icon(Icons.Default.Close, contentDescription = "لغو")
                    }
                    else -> IconButton(onClick = { vm.delete(t) }) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف")
                    }
                }
            }
            when (t.state) {
                St.QUEUED, St.RUNNING -> {
                    Spacer(Modifier.height(6.dp))
                    val known = t.state == St.RUNNING && t.percent in 0f..1f
                    val anim by animateFloatAsState(if (known) t.percent else 0f, label = "p")
                    if (known) LinearProgressIndicator(progress = { anim }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(t.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (known) "${(t.percent * 100).toInt()}٪" else "—", style = MaterialTheme.typography.titleSmall)
                    }
                    if (t.state == St.RUNNING && (t.total > 0 || t.speed > 0)) {
                        val sz = if (t.total > 0) "${fmtSize(ctx, t.done)} از ${fmtSize(ctx, t.total)}" else fmtSize(ctx, t.done)
                        val sp = if (t.speed > 0) " · ${fmtSize(ctx, t.speed)}/s" else ""
                        Text("$sz$sp · باقی‌مانده ${fmtEta(t.eta)}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                St.FAILED -> {
                    Text(t.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.retry(t) }) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("تلاش مجدد")
                        }
                        CopyLink(t.src)
                    }
                }
                St.DONE -> {
                    val size = t.doneFiles.sumOf { it.length() }
                    Text(
                        fmtSize(ctx, size) + if (t.doneFiles.size > 1) " · ${t.doneFiles.size} فایل" else "",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (t.saved.isBlank()) {
                            Button(onClick = { vm.save(t) }) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("ذخیره")
                            }
                        }
                        OutlinedButton(onClick = {
                            PlayerActivity.open(ctx, t.doneFiles.first(), t.title, t.thumb)
                        }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("پخش")
                        }
                        OutlinedButton(onClick = {
                            try {
                                Opener.share(ctx, t.doneFiles.first(), t.mime)
                            } catch (_: Throwable) {
                                Toast.makeText(ctx, "اشتراک‌گذاری ممکن نشد", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("اشتراک")
                        }
                        CopyLink(t.src)
                    }
                    Text(
                        if (t.saved.isNotBlank()) "ذخیره شد در: ${t.saved}" else "ذخیره‌شده داخل برنامه",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CopyLink(src: String) {
    val ctx = LocalContext.current
    OutlinedButton(onClick = {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("link", src))
        Toast.makeText(ctx, "لینک کپی شد", Toast.LENGTH_SHORT).show()
    }) {
        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(6.dp))
        Text("کپی لینک")
    }
}

@Composable
fun Thumb(item: Task) {
    val bmp by produceState<ImageBitmap?>(null, item.doneFiles, item.thumb) {
        value = withContext(Dispatchers.IO) { loadThumb(item) }
    }
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(
                when (item.kind) {
                    Kind.VIDEO -> Icons.Default.Movie
                    Kind.AUDIO -> Icons.Default.MusicNote
                    Kind.IMAGE -> Icons.Default.Image
                },
                contentDescription = null
            )
        }
    }
}

private fun loadThumb(item: Task): ImageBitmap? = try {
    val f = item.doneFiles.firstOrNull()
    if (f == null || !f.exists()) {
        if (item.thumb.startsWith("http")) Net.bitmap(item.thumb)?.asImageBitmap() else null
    } else when (item.kind) {
        Kind.IMAGE -> {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            var s = 1
            while (o.outWidth / s > 256) s *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = s }
            BitmapFactory.decodeFile(f.absolutePath, o2)?.asImageBitmap()
        }
        Kind.VIDEO -> {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                val fr = r.getFrameAtTime(0)
                fr?.let {
                    val w = 256
                    val h = (it.height * w / it.width.toFloat()).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(it, w, h, true).asImageBitmap()
                }
            } finally {
                r.release()
            }
        }
        Kind.AUDIO -> if (item.thumb.startsWith("http")) Net.bitmap(item.thumb)?.asImageBitmap() else null
    }
} catch (_: Throwable) {
    null
}

@Composable
fun SettingsScreen(vm: MainVM, onGuide: () -> Unit, onIntro: () -> Unit) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setTree(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.uploadPicked(uris)
    }
    if (vm.pending != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.declineOverwrite() },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("جایگزینی فایل‌های موجود؟") },
            text = { Text("این فایل‌ها توی ریپوی تو هستن و فرق دارن:\n" + vm.pendingConflicts.joinToString("\n") { it.dst }) },
            confirmButton = { TextOnly("جایگزین کن") { vm.confirmOverwrite() } },
            dismissButton = { TextOnly("نه، دست نزن") { vm.declineOverwrite() } }
        )
    }
    val folderName = remember(vm.treeUri) {
        if (vm.treeUri.isBlank()) "" else (DocumentFile.fromTreeUri(ctx, Uri.parse(vm.treeUri))?.name ?: "")
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SCard {
        Text("اتصال به گیت‌هاب شخصی", style = MaterialTheme.typography.titleMedium)
        Text(
            "هر کاربر باید حساب و ریپوی خودش رو وصل کنه و همه دانلودها فقط توی همون ریپو اجرا می‌شن. " +
                "توکن Fine-grained فقط برای همون ریپو با دسترسی Contents، Actions و Workflows روی Read and write. " +
                "برای ساخت ریپوی جدید توکن Classic با scope های repo و workflow لازمه. توکن رمزگذاری‌شده با Android Keystore نگه‌داری می‌شه.",
            style = MaterialTheme.typography.bodySmall, color = Color.White
        )
        if (vm.connRepo.isNotBlank()) {
            Text(
                "وصل به: ${vm.connLogin} / ${vm.connRepo}" + if (vm.branch.isNotBlank()) " · شاخه: ${vm.branch}" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        } else {
            Text("وصل نیست", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
        OutlinedTextField(
            value = vm.ghToken,
            onValueChange = { vm.ghToken = it },
            label = { Text(if (vm.connRepo.isNotBlank()) "توکن جدید (اختیاری، فعلی ذخیره‌ست)" else "توکن گیت‌هاب (PAT)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vm.repoInput,
            onValueChange = { vm.repoInput = it },
            label = { Text("لینک یا اسم ریپو (مثلا github.com/نام‌کاربری/ریپو)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(checked = vm.createNew, onCheckedChange = { vm.createNew = it })
            Spacer(Modifier.size(8.dp))
            Text("اگه ریپو نبود، ریپوی جدید بساز")
        }
        if (vm.createNew) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Switch(checked = vm.makePrivate, onCheckedChange = { vm.makePrivate = it })
                Spacer(Modifier.size(8.dp))
                Text("ریپوی جدید خصوصی باشه")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.connect() }, enabled = !vm.settingsBusy) { Text("ذخیره و تست اتصال") }
            Button(onClick = { vm.createConfig() }, enabled = !vm.settingsBusy) { Text("ساخت پیکربندی") }
            OutlinedButton(onClick = { vm.refreshStatus() }, enabled = !vm.settingsBusy && vm.connRepo.isNotBlank()) { Text("تازه‌سازی وضعیت") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                enabled = !vm.settingsBusy && vm.connRepo.isNotBlank()
            ) { Text("آپلود فایل راه‌اندازی Actions") }
            OutlinedButton(onClick = onGuide) { Text("راهنما و نصب") }
            OutlinedButton(onClick = onIntro) { Text("نمایش معرفی") }
            OutlinedButton(onClick = { vm.disconnect() }, enabled = !vm.settingsBusy && vm.connRepo.isNotBlank()) { Text("قطع اتصال و حذف توکن") }
        }
        if (vm.settingsBusy) {
            if (vm.setupProgress in 0f..1f) LinearProgressIndicator(progress = { vm.setupProgress }, modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        if (vm.dlMsg.isNotBlank()) Text(vm.dlMsg, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
        SCard {
        Text("آپدیت برنامه", style = MaterialTheme.typography.titleMedium)
        Text("نسخه نصب‌شده: ${vm.versionName}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        if (vm.updLatest.isNotBlank()) Text("آخرین نسخه: ${vm.updLatest}", style = MaterialTheme.typography.bodyMedium, color = Color.White)
        if (vm.updNotes.isNotBlank()) {
            Text("تغییرات:", style = MaterialTheme.typography.labelLarge)
            Text(vm.updNotes.take(1500), style = MaterialTheme.typography.bodySmall, color = Color.White)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.checkUpdate() }, enabled = !vm.settingsBusy) { Text("بررسی آپدیت") }
            if (vm.updAvailable) Button(onClick = { vm.installUpdate() }, enabled = !vm.settingsBusy) { Text("دانلود و نصب ${vm.updLatest}") }
        }
        if (vm.updProgress in 0f..1f) {
            LinearProgressIndicator(progress = { vm.updProgress }, modifier = Modifier.fillMaxWidth())
            Text("${(vm.updProgress * 100).toInt()}٪", style = MaterialTheme.typography.labelMedium)
        }
        if (vm.updMsg.isNotBlank()) Text(vm.updMsg, style = MaterialTheme.typography.bodyMedium, color = Color.White)
        }
        SCard {
        Text("محل ذخیره", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = vm.saveMode == "gallery", onClick = { vm.pickSaveMode("gallery") })
            Text("گالری (Movies، Music و Pictures داخل KavirDL)")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = vm.saveMode == "folder", onClick = {
                if (vm.treeUri.isBlank()) picker.launch(null) else vm.pickSaveMode("folder")
            })
            Text(if (folderName.isEmpty()) "پوشه دلخواه" else "پوشه: $folderName")
        }
        OutlinedButton(onClick = { picker.launch(null) }) { Text("انتخاب پوشه") }
        }
    }
}

@Composable
fun SCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
