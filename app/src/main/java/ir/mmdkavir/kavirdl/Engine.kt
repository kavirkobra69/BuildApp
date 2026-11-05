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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("kdl", Context.MODE_PRIVATE)
    fun str(c: Context, k: String, d: String = ""): String = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()
}

enum class St { QUEUED, RUNNING, DONE, FAILED }

class Task(
    val id: String,
    val src: String,
    val quality: String,
    val time: Long,
    state: St = St.QUEUED,
    title: String = "",
    thumb: String = "",
    error: String = "",
    saved: String = "",
    val files: List<File> = emptyList()
) {
    var state by mutableStateOf(state)
    var title by mutableStateOf(title)
    var thumb by mutableStateOf(thumb)
    var error by mutableStateOf(error)
    var saved by mutableStateOf(saved)
    var label by mutableStateOf("در صف")
    var percent by mutableStateOf(-1f)
    var done by mutableStateOf(0L)
    var total by mutableStateOf(0L)
    var speed by mutableStateOf(0L)
    var eta by mutableStateOf(-1L)
    var doneFiles by mutableStateOf(files)
    val mime: String get() = doneFiles.firstOrNull()?.let { Gallery.mimeOf(it) } ?: "video/mp4"
    val kind: Kind get() = Gallery.kindOf(mime)
}

object Store {
    private fun store(ctx: Context) = File(ctx.filesDir, "tasks.json")
    fun root(ctx: Context): File = File(ctx.filesDir, "dl").apply { mkdirs() }

    fun load(ctx: Context): List<Task> {
        val f = store(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<Task>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                var st = try {
                    St.valueOf(o.getString("state"))
                } catch (_: Throwable) {
                    St.FAILED
                }
                val names = o.optJSONArray("files") ?: JSONArray()
                val files = (0 until names.length()).map { File(File(root(ctx), id), names.getString(it)) }
                var err = o.optString("error")
                if (st == St.DONE && (files.isEmpty() || files.any { !it.exists() })) {
                    st = St.FAILED
                    err = "فایل دانلودشده پیدا نشد"
                }
                out += Task(
                    id, o.getString("src"), o.optString("quality", "720"), o.optLong("time"),
                    st, o.optString("title"), o.optString("thumb"), err, o.optString("saved"),
                    if (st == St.DONE) files else emptyList()
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    @Synchronized
    fun write(ctx: Context, tasks: List<Task>) {
        val arr = JSONArray()
        for (t in tasks) {
            arr.put(
                JSONObject()
                    .put("id", t.id).put("src", t.src).put("quality", t.quality).put("time", t.time)
                    .put("state", t.state.name).put("title", t.title).put("thumb", t.thumb)
                    .put("error", t.error).put("saved", t.saved)
                    .put("files", JSONArray(t.doneFiles.map { it.name }))
            )
        }
        val tmp = File(ctx.filesDir, "tasks.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(store(ctx))
    }
}

class MainVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>().applicationContext

    val tasks = mutableStateListOf<Task>()
    var url by mutableStateOf("")
    var quality by mutableStateOf(Prefs.str(app, "quality", "720"))
    var status by mutableStateOf("")
    var ghToken by mutableStateOf(Prefs.str(app, "ghToken"))
    var dlMsg by mutableStateOf("")
    var updMsg by mutableStateOf("")
    var saveMode by mutableStateOf(Prefs.str(app, "saveMode", "gallery"))
    var treeUri by mutableStateOf(Prefs.str(app, "treeUri"))
    var settingsBusy by mutableStateOf(false)
    val versionName: String = Updater.currentVersion(app)

    private val jobs = HashMap<String, Job>()
    private val gate = Semaphore(3)

    init {
        tasks.addAll(Store.load(app))
        tasks.filter { it.state == St.QUEUED || it.state == St.RUNNING }.forEach {
            it.state = St.QUEUED
            launchTask(it)
        }
        persist()
    }

    private fun persist() {
        val snap = tasks.toList()
        viewModelScope.launch(Dispatchers.IO) { Store.write(ctx, snap) }
    }

    fun pickQuality(k: String) {
        quality = k
        Prefs.setStr(ctx, "quality", k)
    }

    private fun nice(e: Throwable): String {
        val m = e.message ?: e.toString()
        return m.lineSequence().firstOrNull { it.isNotBlank() }?.take(220) ?: m
    }

    fun start() {
        val link = Regex("""https?://[^\s]+""").find(url)?.value?.trimEnd('.', ',', ')', '،')
        if (link == null) {
            status = "لینک معتبر پیدا نشد"
            return
        }
        if (ghToken.isBlank()) {
            status = "اول توی تنظیمات، توکن گیت‌هاب رو وارد کن"
            return
        }
        if (tasks.any { it.src == link && it.quality == quality && (it.state == St.QUEUED || it.state == St.RUNNING) }) {
            status = "این لینک همین الان در حال دانلوده"
            return
        }
        val t = Task(GhActions.newJob(), link, quality, System.currentTimeMillis())
        tasks.add(0, t)
        url = ""
        status = ""
        persist()
        launchTask(t)
    }

    private fun launchTask(t: Task) {
        jobs[t.id] = viewModelScope.launch(Dispatchers.IO) {
            val tmp = File(ctx.cacheDir, "dl/${t.id}")
            try {
                withContext(Dispatchers.Main) { t.label = "در صف"; t.percent = -1f }
                gate.withPermit {
                    withContext(Dispatchers.Main) { t.state = St.RUNNING; t.error = "" }
                    var lastWrite = 0L
                    val files = GhActions.download(ghToken.trim(), t.quality, t.src, t.id, tmp) { u ->
                        viewModelScope.launch(Dispatchers.Main) {
                            t.label = u.label
                            t.percent = u.percent
                            t.done = u.done
                            t.total = u.total
                            t.speed = u.speed
                            t.eta = u.eta
                            if (u.title.isNotBlank() && t.title != u.title) {
                                t.title = u.title
                            }
                            if (u.thumb.isNotBlank() && t.thumb != u.thumb) t.thumb = u.thumb
                            val now = System.currentTimeMillis()
                            if (now - lastWrite > 5000) {
                                lastWrite = now
                                persist()
                            }
                        }
                    }
                    val dest = File(Store.root(ctx), t.id).apply { mkdirs() }
                    val finals = files.map { f ->
                        val d = File(dest, f.name)
                        f.copyTo(d, overwrite = true)
                        if (d.length() != f.length()) throw RuntimeException("کپی فایل ناقص بود")
                        d
                    }
                    withContext(Dispatchers.Main) {
                        t.doneFiles = finals
                        if (t.title.isBlank()) t.title = finals.first().nameWithoutExtension
                        t.percent = 1f
                        t.state = St.DONE
                        persist()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) {
                    t.state = St.FAILED
                    t.error = nice(e)
                    persist()
                }
            } finally {
                tmp.deleteRecursively()
                jobs.remove(t.id)
            }
        }
    }

    fun cancel(t: Task) {
        jobs.remove(t.id)?.cancel()
        t.state = St.FAILED
        t.error = "لغو شد"
        persist()
        val token = ghToken.trim()
        viewModelScope.launch(NonCancellable + Dispatchers.IO) { GhActions.cancelRemote(token, t.id) }
    }

    fun retry(t: Task) {
        if (t.state != St.FAILED) return
        val i = tasks.indexOf(t)
        if (i < 0) return
        val n = Task(GhActions.newJob(), t.src, t.quality, System.currentTimeMillis(), title = t.title, thumb = t.thumb)
        tasks[i] = n
        persist()
        launchTask(n)
    }

    fun save(t: Task) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var where = ""
                for (f in t.doneFiles) {
                    val mime = Gallery.mimeOf(f)
                    if (saveMode == "folder" && treeUri.isNotBlank()) {
                        Gallery.saveToTree(ctx, f, mime, Uri.parse(treeUri))
                        where = "پوشه انتخابی"
                    } else {
                        Gallery.save(ctx, f, mime, Gallery.kindOf(mime))
                        where = when (Gallery.kindOf(mime)) {
                            Kind.VIDEO -> "Movies/KavirDL"
                            Kind.AUDIO -> "Music/KavirDL"
                            Kind.IMAGE -> "Pictures/KavirDL"
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    t.saved = where
                    persist()
                    status = "ذخیره شد"
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { status = "ذخیره نشد: ${nice(e)}" }
            }
        }
    }

    fun delete(t: Task) {
        jobs.remove(t.id)?.cancel()
        tasks.remove(t)
        File(Store.root(ctx), t.id).deleteRecursively()
        persist()
    }

    fun clearFinished() {
        tasks.filter { it.state == St.DONE }.toList().forEach { delete(it) }
    }

    fun saveGh() {
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
                GhActions.check(ghToken.trim())
            } catch (e: Throwable) {
                "ناموفق: ${nice(e)}"
            }
            withContext(Dispatchers.Main) {
                dlMsg = msg
                settingsBusy = false
            }
        }
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
        saveGh()
        updMsg = "در حال بررسی..."
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                val token = ghToken.trim().ifEmpty { null }
                val info = Updater.latest(Updater.APP_REPO, token)
                val cur = Updater.currentVersion(ctx)
                if (!Updater.isNewer(info.tag, cur)) {
                    "آخرین نسخه رو داری ($cur)"
                } else {
                    withContext(Dispatchers.Main) { updMsg = "دانلود نسخه ${info.tag}..." }
                    val apk = File(ctx.cacheDir, "update/KavirDL.apk")
                    apk.parentFile?.mkdirs()
                    if (token != null) {
                        Net.download(info.assetUrl, apk, token, "application/octet-stream") { _, _ -> }
                    } else {
                        Net.download(info.publicUrl, apk, null, "application/octet-stream") { _, _ -> }
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
