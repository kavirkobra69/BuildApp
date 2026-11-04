package ir.mmdkavir.kavirdl

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("kdl", Context.MODE_PRIVATE)
    fun str(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
}

class Item(
    val id: String,
    val file: File,
    val title: String,
    val mime: String,
    val src: String,
    saved: Boolean,
    val time: Long
) {
    val kind: Kind = Gallery.kindOf(mime)
    var saved by mutableStateOf(saved)
}

object History {
    private fun store(ctx: Context) = File(ctx.filesDir, "history.json")

    fun dir(ctx: Context): File = File(ctx.filesDir, "dl").apply { mkdirs() }

    fun load(ctx: Context): List<Item> {
        val f = store(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<Item>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val file = File(dir(ctx), o.getString("file"))
                if (file.exists()) {
                    out += Item(
                        o.getString("id"), file, o.getString("title"), o.getString("mime"),
                        o.optString("src"), o.optBoolean("saved"), o.optLong("time")
                    )
                }
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun write(ctx: Context, items: List<Item>) {
        val arr = JSONArray()
        for (it in items) {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("file", it.file.name)
                    .put("title", it.title)
                    .put("mime", it.mime)
                    .put("src", it.src)
                    .put("saved", it.saved)
                    .put("time", it.time)
            )
        }
        store(ctx).writeText(arr.toString())
    }
}

class MainVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>().applicationContext

    val items = mutableStateListOf<Item>().also { it.addAll(History.load(app)) }
    var url by mutableStateOf("")
    var quality by mutableStateOf(Prefs.str(app, "quality", "720"))
    var status by mutableStateOf("")
    var progress by mutableStateOf(-1f)
    var busy by mutableStateOf(false)

    var ghRepo by mutableStateOf(Prefs.str(app, "ghRepo", "kavirkobra69/BuildApp"))
    var ghToken by mutableStateOf(Prefs.str(app, "ghToken"))
    var updRepo by mutableStateOf(Prefs.str(app, "updRepo", Updater.APP_REPO))
    var updToken by mutableStateOf(Prefs.str(app, "updToken"))
    var dlMsg by mutableStateOf("")
    var updMsg by mutableStateOf("")
    var saveMode by mutableStateOf(Prefs.str(app, "saveMode", "gallery"))
    var treeUri by mutableStateOf(Prefs.str(app, "treeUri"))
    var settingsMsg by mutableStateOf("")
    var settingsBusy by mutableStateOf(false)
    val versionName: String = Updater.currentVersion(app)

    private var job: Job? = null

    fun pickQuality(k: String) {
        quality = k
        Prefs.setStr(ctx, "quality", k)
    }

    private fun nice(e: Throwable): String {
        val m = e.message ?: e.toString()
        return m.lineSequence().firstOrNull { it.isNotBlank() }?.take(220) ?: m
    }

    private suspend fun setStatus(s: String) = withContext(Dispatchers.Main) { status = s }

    fun cancel() {
        job?.cancel()
        busy = false
        status = "لغو شد"
        progress = -1f
    }

    fun start() {
        if (busy) return
        val link = Regex("""https?://[^\s]+""").find(url)?.value?.trimEnd('.', ',', ')', '،')
        if (link == null) {
            status = "لینک معتبر پیدا نشد"
            return
        }
        if (ghToken.isBlank() || !ghRepo.contains("/")) {
            status = "اول توی تنظیمات، ریپو و توکن گیت‌هاب رو وارد کن"
            return
        }
        busy = true
        progress = -1f
        status = "شروع..."
        job = viewModelScope.launch(Dispatchers.IO) {
            val tmp = File(ctx.cacheDir, "dl/${System.currentTimeMillis()}")
            try {
                val files = GhActions.download(
                    GhActions.normRepo(ghRepo), ghToken.trim(), quality, link, tmp,
                    { setStatus(it) }, { progress = it }
                )
                val stamp = System.currentTimeMillis()
                val added = mutableListOf<Item>()
                files.forEachIndexed { i, f ->
                    val id = "${stamp}_$i"
                    val dest = File(History.dir(ctx), "${id}_${f.name}")
                    f.copyTo(dest, overwrite = true)
                    added += Item(id, dest, f.nameWithoutExtension, Gallery.mimeOf(f), link, false, stamp)
                }
                withContext(Dispatchers.Main) {
                    added.reversed().forEach { items.add(0, it) }
                    History.write(ctx, items.toList())
                    status = "تموم شد (${added.size} فایل)"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { status = "خطا: ${nice(e)}" }
            } finally {
                tmp.deleteRecursively()
                withContext(NonCancellable + Dispatchers.Main) {
                    busy = false
                    progress = -1f
                }
            }
        }
    }

    fun save(item: Item) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (saveMode == "folder" && treeUri.isNotBlank()) {
                    Gallery.saveToTree(ctx, item.file, item.mime, Uri.parse(treeUri))
                } else {
                    Gallery.save(ctx, item.file, item.mime, item.kind)
                }
                withContext(Dispatchers.Main) {
                    item.saved = true
                    History.write(ctx, items.toList())
                    status = "ذخیره شد"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { status = "ذخیره نشد: ${nice(e)}" }
            }
        }
    }

    fun saveAll() {
        items.filter { !it.saved }.forEach { save(it) }
    }

    fun delete(item: Item) {
        items.remove(item)
        item.file.delete()
        History.write(ctx, items.toList())
    }

    fun saveGh() {
        ghRepo = GhActions.normRepo(ghRepo)
        Prefs.setStr(ctx, "ghRepo", ghRepo)
        Prefs.setStr(ctx, "ghToken", ghToken.trim())
        dlMsg = "ذخیره شد"
    }

    fun testGh() {
        if (settingsBusy) return
        saveGh()
        settingsBusy = true
        dlMsg = "در حال تست..."
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                GhActions.check(ghRepo, ghToken.trim())
            } catch (e: Throwable) {
                "ناموفق: ${nice(e)}"
            }
            withContext(Dispatchers.Main) {
                dlMsg = msg
                settingsBusy = false
            }
        }
    }

    fun saveUpd() {
        updRepo = GhActions.normRepo(updRepo)
        Prefs.setStr(ctx, "updRepo", updRepo)
        Prefs.setStr(ctx, "updToken", updToken.trim())
        updMsg = "ذخیره شد"
    }

    fun pickSaveMode(mode: String) {
        saveMode = mode
        Prefs.setStr(ctx, "saveMode", mode)
    }

    fun setTree(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Throwable) {
        }
        treeUri = uri.toString()
        Prefs.setStr(ctx, "treeUri", treeUri)
        pickSaveMode("folder")
    }

    fun checkUpdate() {
        if (settingsBusy) return
        settingsBusy = true
        saveUpd()
        updMsg = "در حال بررسی..."
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                val token = updToken.trim().ifEmpty { null }
                val info = Updater.latest(updRepo, token)
                val cur = Updater.currentVersion(ctx)
                if (!Updater.isNewer(info.tag, cur)) {
                    "آخرین نسخه رو داری ($cur)"
                } else {
                    withContext(Dispatchers.Main) { updMsg = "دانلود نسخه ${info.tag}..." }
                    val apk = File(ctx.cacheDir, "update/KavirDL.apk")
                    apk.parentFile?.mkdirs()
                    if (token != null) {
                        Net.download(info.assetUrl, apk, token, "application/octet-stream") { }
                    } else {
                        Net.download(info.publicUrl, apk, null, "application/octet-stream") { }
                    }
                    if (Updater.install(ctx, apk)) "صفحه نصب باز شد" else "اجازه نصب برنامه‌ها رو برای KavirDL روشن کن و دوباره بزن"
                }
            } catch (e: Throwable) {
                "ناموفق: ${nice(e)}"
            }
            withContext(Dispatchers.Main) {
                updMsg = msg
                settingsBusy = false
            }
        }
    }
}
