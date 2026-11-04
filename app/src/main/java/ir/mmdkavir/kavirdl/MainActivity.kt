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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
                title = { Text(if (screen == 0) "KavirDL 🚀" else "تنظیمات") },
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

@OptIn(ExperimentalMaterial3Api::class)
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
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("best" to "بهترین", "1080" to "1080", "720" to "720", "480" to "480", "audio" to "فقط صدا (mp3)").forEach { (k, label) ->
                FilterChip(selected = vm.quality == k, onClick = { vm.pickQuality(k) }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(8.dp))
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
                Text("ذخیره همه")
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.items, key = { it.id }) { ItemRow(it, vm) }
        }
        if (vm.items.isEmpty() && !vm.busy) {
            Text(
                "لینک یوتیوب، اینستاگرام، تیک‌تاک، اسپاتیفای یا پینترست رو بذار. دانلود روی گیت‌هاب انجام می‌شه؛ ریپو و توکن رو توی تنظیمات وارد کن.",
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
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(item)
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        Formatter.formatShortFileSize(ctx, item.file.length()),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.saved) {
                    Icon(Icons.Default.Check, contentDescription = "ذخیره شد", tint = MaterialTheme.colorScheme.primary)
                    Text("ذخیره شد", style = MaterialTheme.typography.bodySmall)
                } else {
                    Button(onClick = { vm.save(item) }) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("ذخیره")
                    }
                }
                OutlinedButton(onClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("link", item.src))
                    Toast.makeText(ctx, "لینک کپی شد", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("کپی لینک")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.delete(item) }) {
                    Icon(Icons.Default.Delete, contentDescription = "حذف")
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
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setTree(uri)
    }
    val folderName = remember(vm.treeUri) {
        if (vm.treeUri.isBlank()) "" else (DocumentFile.fromTreeUri(ctx, Uri.parse(vm.treeUri))?.name ?: "")
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("۱) دانلود ویدیو (GitHub Actions)", style = MaterialTheme.typography.titleMedium)
        Text(
            "لینک یا اسم ریپویی که ورک‌فلوی dl.yml توشه، و توکن با دسترسی Contents و Actions روی Read and write.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = vm.ghRepo,
            onValueChange = { vm.ghRepo = it },
            label = { Text("لینک ریپوی دانلود") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vm.ghToken,
            onValueChange = { vm.ghToken = it },
            label = { Text("توکن گیت‌هاب برای دانلود") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveGh() }) { Text("ذخیره") }
            OutlinedButton(onClick = { vm.testGh() }, enabled = !vm.settingsBusy) { Text("تست اتصال") }
        }
        if (vm.dlMsg.isNotBlank()) Text(vm.dlMsg, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("۲) آپدیت برنامه", style = MaterialTheme.typography.titleMedium)
        Text(
            "لینک ریپویی که نسخه‌های برنامه توش Release می‌شه. اگه ریپو public ـه توکن نذار.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = vm.updRepo,
            onValueChange = { vm.updRepo = it },
            label = { Text("لینک ریپوی برنامه") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vm.updToken,
            onValueChange = { vm.updToken = it },
            label = { Text("توکن آپدیت (اختیاری)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Text("نسخه فعلی: ${vm.versionName}", style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { vm.checkUpdate() }, enabled = !vm.settingsBusy) { Text("بررسی آپدیت") }
        if (vm.updMsg.isNotBlank()) Text(vm.updMsg, style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
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
