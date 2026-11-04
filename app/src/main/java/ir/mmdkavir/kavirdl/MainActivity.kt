package ir.mmdkavir.kavirdl

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
            val scheme = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(c)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(c)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) {
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
    var screen by remember { mutableStateOf(0) }
    BackHandler(screen == 1) { screen = 0 }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (screen == 0) "KavirDL" else "تنظیمات") },
                navigationIcon = {
                    if (screen == 1) {
                        IconButton(onClick = { screen = 0 }) {
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
            if (screen == 0) Home(vm) else SettingsScreen(vm)
        }
    }
}

@Composable
fun Home(vm: MainVM) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(checked = vm.audioOnly, onCheckedChange = { vm.setAudio(it) })
            Spacer(Modifier.size(8.dp))
            Text("فقط صدا (mp3)")
        }
        Button(
            onClick = { if (vm.busy) vm.cancel() else vm.start() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (vm.busy) "لغو" else "دانلود")
        }
        if (vm.busy) {
            Spacer(Modifier.height(8.dp))
            if (vm.progress in 0f..1f) {
                LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (vm.status.isNotBlank()) {
            Text(
                vm.status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (vm.items.count { !it.saved } > 1) {
            OutlinedButton(onClick = { vm.saveAll() }, modifier = Modifier.fillMaxWidth()) {
                Text("ذخیره همه در گالری")
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.items, key = { it.file.absolutePath }) { ItemRow(it, vm) }
        }
        if (vm.items.isEmpty() && !vm.busy) {
            Text(
                "یوتیوب، اینستاگرام، تیک‌تاک، اسپاتیفای و پینترست پشتیبانی میشه. اگه سایتی باز نمیشه، توی تنظیمات کانفیگ v2ray بذار.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }
    }
}

@Composable
fun ItemRow(item: Item, vm: MainVM) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(item)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    Formatter.formatShortFileSize(ctx, item.file.length()),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.size(8.dp))
            if (item.saved) {
                Icon(Icons.Default.Check, contentDescription = "ذخیره شد", tint = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = { vm.save(item) }) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("ذخیره")
                }
            }
        }
    }
}

@Composable
fun Thumb(item: Item) {
    val bmp by produceState<ImageBitmap?>(null, item.file) {
        value = withContext(Dispatchers.IO) { loadThumb(item) }
    }
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
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

private fun loadThumb(item: Item): ImageBitmap? = try {
    when (item.kind) {
        Kind.IMAGE -> {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(item.file.absolutePath, o)
            var s = 1
            while (o.outWidth / s > 256) s *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = s }
            BitmapFactory.decodeFile(item.file.absolutePath, o2)?.asImageBitmap()
        }
        Kind.VIDEO -> {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(item.file.absolutePath)
                val f = r.getFrameAtTime(0)
                f?.let {
                    val w = 256
                    val h = (it.height * w / it.width.toFloat()).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(it, w, h, true).asImageBitmap()
                }
            } finally {
                r.release()
            }
        }
        Kind.AUDIO -> null
    }
} catch (_: Throwable) {
    null
}

@Composable
fun SettingsScreen(vm: MainVM) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("V2Ray داخلی (بدون VPN)", style = MaterialTheme.typography.titleMedium)
        Text(
            "لینک vless:// (یا vmess:// / trojan:// / کانفیگ JSON) رو اینجا بذار. فقط خود برنامه از این پروکسی استفاده می‌کنه و بقیه گوشی تغییری نمی‌کنه.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = vm.proxyOn, onCheckedChange = { vm.setProxy(it) })
            Spacer(Modifier.size(8.dp))
            Text("استفاده از پروکسی")
        }
        OutlinedTextField(
            value = vm.cfg,
            onValueChange = { vm.cfg = it },
            label = { Text("vless://...") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveAndTest() }, enabled = !vm.settingsBusy) { Text("ذخیره و تست اتصال") }
            OutlinedButton(onClick = { vm.cfg = ""; vm.saveAndTest() }, enabled = !vm.settingsBusy) { Text("پاک کردن") }
        }
        if (vm.settingsMsg.isNotBlank()) Text(vm.settingsMsg, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("دانلود یوتیوب با GitHub Actions", style = MaterialTheme.typography.titleMedium)
        Text(
            "ورک‌فلوی YT-downloader توی ریپوی خودت روی گیت‌هاب اجرا می‌شه، فایل رو دانلود می‌کنه و برنامه از ریپو برمی‌داردش. توکن (PAT) باید دسترسی Contents و Actions با سطح write داشته باشه.",
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = vm.ghOn, onCheckedChange = { vm.ghOn = it })
            Spacer(Modifier.size(8.dp))
            Text("استفاده برای یوتیوب و اینستاگرام (و جایگزین وقتی دانلود مستقیم نشد)")
        }
        OutlinedTextField(
            value = vm.ghRepo,
            onValueChange = { vm.ghRepo = it },
            label = { Text("ریپو (owner/name)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vm.ghToken,
            onValueChange = { vm.ghToken = it },
            label = { Text("توکن گیت‌هاب") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vm.ghQuality,
            onValueChange = { vm.ghQuality = it },
            label = { Text("کیفیت (best, 1080, 720, 480)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = vm.ghDelete, onCheckedChange = { vm.ghDelete = it })
            Spacer(Modifier.size(8.dp))
            Text("حذف فایل از ریپو بعد از دانلود")
        }
        Button(onClick = { vm.saveGh() }) { Text("ذخیره تنظیمات گیت‌هاب") }
        HorizontalDivider()
        Text("کوکی یوتیوب (اختیاری)", style = MaterialTheme.typography.titleMedium)
        Text(
            "اگه خطای Sign in to confirm you're not a bot گرفتی، فایل cookies.txt (فرمت Netscape) مرورگرت رو اینجا paste کن.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = vm.cookies,
            onValueChange = { vm.cookies = it },
            label = { Text("cookies.txt") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { vm.saveCookies() }) { Text("ذخیره کوکی") }
        HorizontalDivider()
        Text("موتور دانلود", style = MaterialTheme.typography.titleMedium)
        Text(
            "اگه یوتیوب یا اینستا یهو کار نکرد، yt-dlp رو به‌روزرسانی کن.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedButton(onClick = { vm.updateYtdlp() }, enabled = !vm.settingsBusy) { Text("به‌روزرسانی yt-dlp") }
    }
}
