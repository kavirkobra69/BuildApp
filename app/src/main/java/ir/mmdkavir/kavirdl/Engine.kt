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
    fun remove(c: Context, k: String) = sp(c).edit().remove(k).apply()
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
    val files: List<File> = emptyList(),
    val repo: String = ""
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
                val repo = o.optString("repo")
                if ((st == St.QUEUED || st == St.RUNNING) && repo.isBlank()) {
                    st = St.FAILED
                    err = "این دانلود قبل از اتصال حساب شخصی بود؛ دوباره دانلودش کن"
                }
                if (st == St.DONE && (files.isEmpty() || files.any { !it.exists() })) {
                    st = St.FAILED
                    err = "فایل دانلودشده پیدا نشد"
                }
                out += Task(
                    id, o.getString("src"), o.optString("quality", "720"), o.optLong("time"),
                    st, o.optString("title"), o.optString("thumb"), err, o.optString("saved"),
                    if (st == St.DONE) files else emptyList(), repo
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
                    .put("error", t.error).put("saved", t.saved).put("repo", t.repo)
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
    var ghToken by mutableStateOf("")
    var repoInput by mutableStateOf(Prefs.str(app, "connRepo"))
    var makePrivate by mutableStateOf(true)
    var username by mutableStateOf(Prefs.str(app, "username"))
    var branch by mutableStateOf(Prefs.str(app, "branch"))
    var updLatest by mutableStateOf("")
    var updNotes by mutableStateOf("")
    var updAvailable by mutableStateOf(false)
    var updProgress by mutableStateOf(-1f)
    private var updInfo: UpdateInfo? = null
    var createNew by mutableStateOf(true)
    var connLogin by mutableStateOf(Prefs.str(app, "connLogin"))
    var connRepo by mutableStateOf(Prefs.str(app, "connRepo"))
    var connInfo by mutableStateOf("")
    var dlMsg by mutableStateOf("")
    var updMsg by mutableStateOf("")
    var setupProgress by mutableStateOf(-1f)
    var pending by mutableStateOf<Pair<RepoInfo, List<SetupFile>>?>(null)
    var pendingConflicts by mutableStateOf<List<SetupFile>>(emptyList())
    var saveMode by mutableStateOf(Prefs.str(app, "saveMode", "gallery"))
    var treeUri by mutableStateOf(Prefs.str(app, "treeUri"))
    var settingsBusy by mutableStateOf(false)
    val versionName: String = Updater.currentVersion(app)

    private val jobs = HashMap<String, Job>()
    private val gate = Semaphore(3)

    private fun stored(): String = Secure.get(ctx, "ghToken")

    private fun clean(m: String): String {
        var r = m
        for (t in listOf(stored(), ghToken.trim())) if (t.length > 8) r = r.replace(t, "***")
        return r
    }

    init {
        Prefs.remove(app, "ghToken")
        Prefs.remove(app, "ghRepo")
        Prefs.remove(app, "updToken")
        Prefs.remove(app, "updRepo")
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
        return clean(m.lineSequence().firstOrNull { it.isNotBlank() }?.take(220) ?: m)
    }

    fun start() {
        val link = Regex("""https?://[^\s]+""").find(url)?.value?.trimEnd('.', ',', ')', '،')
        if (link == null) {
            status = "لینک معتبر پیدا نشد"
            return
        }
        if (connRepo.isBlank() || stored().isBlank()) {
            status = "اول توی تنظیمات، حساب گیت‌هاب خودت رو وصل کن"
            return
        }
        if (tasks.any { it.src == link && it.quality == quality && (it.state == St.QUEUED || it.state == St.RUNNING) }) {
            status = "این لینک همین الان در حال دانلوده"
            return
        }
        val t = Task(GhActions.newJob(), link, quality, System.currentTimeMillis(), repo = connRepo)
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
                    val files = GhActions.download(t.repo, stored(), t.quality, t.src, t.id, tmp, Prefs.str(ctx, "branch")) { u ->
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
        val token = stored()
        val repo = t.repo
        viewModelScope.launch(NonCancellable + Dispatchers.IO) { if (repo.isNotBlank()) GhActions.cancelRemote(repo, token, t.id) }
    }

    fun retry(t: Task) {
        if (t.state != St.FAILED) return
        val i = tasks.indexOf(t)
        if (i < 0) return
        val n = Task(GhActions.newJob(), t.src, t.quality, System.currentTimeMillis(), title = t.title, thumb = t.thumb, repo = connRepo)
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

    private fun run(block: () -> String) {
        if (settingsBusy) return
        settingsBusy = true
        setupProgress = -1f
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                block()
            } catch (e: Throwable) {
                "ناموفق: ${nice(e)}"
            }
            withContext(Dispatchers.Main) {
                dlMsg = msg
                settingsBusy = false
                setupProgress = -1f
            }
        }
    }

    private fun cancelActive() {
        tasks.filter { it.state == St.QUEUED || it.state == St.RUNNING }.toList().forEach { cancel(it) }
    }

    private fun main(f: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { f() }
    }

    private fun tokenToUse(): String = ghToken.trim().ifEmpty { stored() }.also {
        if (it.isBlank()) throw RuntimeException("توکن رو وارد کن")
    }

    private fun adopt(token: String, me: String, r: RepoInfo) {
        val changed = connRepo != r.full || stored() != token
        if (changed) cancelActive()
        Secure.put(ctx, "ghToken", token)
        Prefs.setStr(ctx, "connLogin", me)
        Prefs.setStr(ctx, "connRepo", r.full)
        main {
            connLogin = me
            connRepo = r.full
            repoInput = r.full
            ghToken = ""
        }
    }

    private fun describe(token: String, me: String, r: RepoInfo): String {
        val wf = GitHubSetup.workflowInstalled(token, r.full)
        val runs = if (wf) GitHubSetup.recentRuns(token, r.full) else emptyList()
        val s = "حساب: $me\nریپو: ${r.full} (${if (r.priv) "خصوصی" else "عمومی"})\nورک‌فلو: ${if (wf) "نصب شده ✓" else "نصب نیست؛ «ساخت پیکربندی» رو بزن"}"
        return if (runs.isEmpty()) s else s + "\nآخرین اجراها:\n" + runs.joinToString("\n")
    }

    fun connect() = run {
        dlMsg = "در حال بررسی..."
        val token = tokenToUse()
        val (me, r) = GitHubSetup.validate(token, repoInput, username, branch)
        Prefs.setStr(ctx, "username", username.trim())
        Prefs.setStr(ctx, "branch", branch.trim())
        adopt(token, me, r)
        "وصل شد ✓\n" + describe(token, me, r)
    }

    fun refreshStatus() = run {
        val token = stored()
        if (token.isBlank() || connRepo.isBlank()) throw RuntimeException("هنوز وصل نشدی")
        val (me, r) = GitHubSetup.validate(token, connRepo, username, branch)
        describe(token, me, r)
    }

    fun disconnect() {
        if (settingsBusy) return
        cancelActive()
        Secure.remove(ctx, "ghToken")
        Prefs.remove(ctx, "connLogin")
        Prefs.remove(ctx, "connRepo")
        ghToken = ""
        repoInput = ""
        connLogin = ""
        connRepo = ""
        pending = null
        dlMsg = "قطع شد و توکن از گوشی پاک شد"
    }

    private fun finishUpload(token: String, r: RepoInfo, files: List<SetupFile>, confirmed: Set<String>): String {
        val res = GitHubSetup.upload(token, r, files, confirmed) { i, n, name ->
            main { setupProgress = if (n == 0) -1f else i.toFloat() / n; if (name.isNotBlank()) dlMsg = "آپلود (${i + 1} از $n): $name" }
        }
        if (res.conflicts.isNotEmpty()) {
            main {
                pending = r to files
                pendingConflicts = res.conflicts
            }
            return "این فایل‌ها از قبل هستن و فرق دارن؛ منتظر تأیید جایگزینی"
        }
        GitHubSetup.ensureActionsEnabled(token, r.full)
        val ok = GitHubSetup.workflowInstalled(token, r.full, 8)
        val summary = "آپلود شد: ${res.uploaded.size}، بدون تغییر: ${res.same.size}"
        return if (ok) "$summary\nورک‌فلو dl.yml نصب و فعاله ✓" else "$summary\nفایل‌ها آپلود شدن ولی ورک‌فلو هنوز فعال نشد؛ چند ثانیه بعد «تازه‌سازی وضعیت» رو بزن"
    }

    fun createConfig() = run {
        dlMsg = "در حال بررسی..."
        val token = tokenToUse()
        val me = GitHubSetup.login(token)
        val (o, n) = GitHubSetup.parseRepo(repoInput, username.trim().ifEmpty { me }) ?: throw RuntimeException("آدرس یا اسم ریپو معتبر نیست")
        if (!o.equals(me, ignoreCase = true)) throw RuntimeException("این ریپو مال حساب $me نیست")
        var r = GitHubSetup.repo(token, o, n)
        var created = false
        if (r == null) {
            if (!createNew) throw RuntimeException("ریپو پیدا نشد و ساخت ریپوی جدید خاموشه")
            r = GitHubSetup.create(token, n, makePrivate)
            created = true
        }
        if (!r.owner.equals(me, ignoreCase = true)) throw RuntimeException("این ریپو مال حساب $me نیست")
        if (!r.canPush) throw RuntimeException("توکن اجازه نوشتن توی این ریپو رو نداره")
        if (!created) r = GitHubSetup.withBranch(token, r, branch)
        Prefs.setStr(ctx, "username", username.trim())
        Prefs.setStr(ctx, "branch", if (created) "" else branch.trim())
        adopt(token, me, r)
        val out = finishUpload(token, r, GitHubSetup.bundled(ctx), emptySet())
        (if (created) "ریپوی جدید ساخته شد: ${r.full}\n" else "") + out
    }

    fun uploadPicked(uris: List<Uri>) = run {
        val token = stored()
        if (token.isBlank() || connRepo.isBlank()) throw RuntimeException("اول حساب گیت‌هاب رو وصل کن")
        val (_, r) = GitHubSetup.validate(token, connRepo, username, branch)
        val files = uris.map { u ->
            val name = androidx.documentfile.provider.DocumentFile.fromSingleUri(ctx, u)?.name ?: ""
            val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: throw RuntimeException("$name: خوندن فایل ممکن نشد")
            GitHubSetup.validateUserFile(name, bytes)
        }
        finishUpload(token, r, files, emptySet())
    }

    fun confirmOverwrite() {
        val p = pending ?: return
        val allow = pendingConflicts.map { it.dst }.toSet()
        pending = null
        run { finishUpload(stored(), p.first, p.second, allow) }
    }

    fun declineOverwrite() {
        pending = null
        dlMsg = "جایگزینی لغو شد؛ فایل‌های موجود دست نخورد"
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
        updMsg = "در حال بررسی..."
        updAvailable = false
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                val info = Updater.latest(Updater.APP_REPO, null)
                val cur = Updater.currentVersion(ctx)
                val newer = Updater.isNewer(info.tag, cur)
                withContext(Dispatchers.Main) {
                    updLatest = info.tag
                    updNotes = info.notes
                    updAvailable = newer
                    updInfo = info
                }
                if (newer) "نسخه جدید آماده‌ست" else "آخرین نسخه رو داری ($cur)"
            } catch (e: Throwable) {
                "بررسی ناموفق: ${nice(e)}"
            }
            withContext(Dispatchers.Main) {
                updMsg = msg
                settingsBusy = false
            }
        }
    }

    fun installUpdate() {
        val info = updInfo ?: return
        if (settingsBusy) return
        settingsBusy = true
        updProgress = 0f
        updMsg = "در حال دانلود ${info.tag}..."
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                val apk = File(ctx.cacheDir, "update/KavirDL.apk")
                apk.parentFile?.mkdirs()
                var last = 0L
                Net.download(info.publicUrl, apk, null, "application/octet-stream") { read, total ->
                    val now = System.currentTimeMillis()
                    if (total > 0 && now - last > 250) {
                        last = now
                        updProgress = read.toFloat() / total
                    }
                }
                if (apk.length() < 100_000) throw RuntimeException("فایل دانلودشده ناقصه")
                if (Updater.install(ctx, apk)) "صفحه نصب باز شد" else "اجازه نصب برنامه‌ها رو برای KavirDL روشن کن و دوباره بزن"
            } catch (e: Throwable) {
                "دانلود یا نصب ناموفق: ${nice(e)}"
            }
            withContext(Dispatchers.Main) {
                updMsg = msg
                updProgress = -1f
                settingsBusy = false
            }
        }
    }
}
