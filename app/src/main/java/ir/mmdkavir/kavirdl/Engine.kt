package ir.mmdkavir.kavirdl

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("kdl", Context.MODE_PRIVATE)
    fun config(c: Context): String = sp(c).getString("cfg", "") ?: ""
    fun setConfig(c: Context, v: String) = sp(c).edit().putString("cfg", v).apply()
    fun proxyOn(c: Context): Boolean = sp(c).getBoolean("proxy", true)
    fun setProxyOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("proxy", v).apply()
    fun cookies(c: Context): String = sp(c).getString("cookies", "") ?: ""
    fun setCookies(c: Context, v: String) = sp(c).edit().putString("cookies", v).apply()
    fun audioOnly(c: Context): Boolean = sp(c).getBoolean("audio", false)
    fun setAudioOnly(c: Context, v: Boolean) = sp(c).edit().putBoolean("audio", v).apply()
}

object Engine {
    private val mutex = Mutex()
    private var ready = false

    suspend fun init(ctx: Context) {
        mutex.withLock {
            if (!ready) {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(ctx.applicationContext)
                    FFmpeg.getInstance().init(ctx.applicationContext)
                }
                ready = true
            }
        }
    }
}

object ProxyCtl {
    private var applied: String? = null

    @Synchronized
    fun ensure(ctx: Context, cfg: String): Int {
        if (XrayManager.running() && applied == cfg) {
            Net.proxyPort = XrayManager.port
            return XrayManager.port
        }
        val port = XrayManager.start(ctx.applicationContext, cfg)
        applied = cfg
        Net.proxyPort = port
        return port
    }

    @Synchronized
    fun off() {
        XrayManager.stop()
        applied = null
    }
}

class Item(val file: File, val title: String, val mime: String) {
    val kind: Kind = Gallery.kindOf(mime)
    var saved by mutableStateOf(false)
}

class MainVM(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication<Application>().applicationContext

    val items = mutableStateListOf<Item>()
    var url by mutableStateOf("")
    var audioOnly by mutableStateOf(Prefs.audioOnly(app))
    var status by mutableStateOf("")
    var progress by mutableStateOf(-1f)
    var busy by mutableStateOf(false)

    var cfg by mutableStateOf(Prefs.config(app))
    var proxyOn by mutableStateOf(Prefs.proxyOn(app))
    var cookies by mutableStateOf(Prefs.cookies(app))
    var settingsMsg by mutableStateOf("")
    var settingsBusy by mutableStateOf(false)

    private var job: Job? = null
    private var processId: String? = null

    fun setAudio(v: Boolean) {
        audioOnly = v
        Prefs.setAudioOnly(ctx, v)
    }

    fun setProxy(v: Boolean) {
        proxyOn = v
        Prefs.setProxyOn(ctx, v)
        if (!v) {
            Net.proxyPort = 0
            ProxyCtl.off()
        }
    }

    private fun toast(s: String) {
        Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    }

    private fun nice(e: Throwable): String {
        val m = e.message ?: e.toString()
        val line = m.lineSequence().lastOrNull { it.contains("ERROR", true) } ?: m.lineSequence().firstOrNull { it.isNotBlank() } ?: m
        return line.take(220)
    }

    private fun applyProxy(): Int {
        if (!proxyOn || cfg.isBlank()) {
            Net.proxyPort = 0
            return 0
        }
        return ProxyCtl.ensure(ctx, cfg.trim())
    }

    fun cancel() {
        processId?.let {
            try {
                YoutubeDL.getInstance().destroyProcessById(it)
            } catch (_: Throwable) {
            }
        }
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
        busy = true
        progress = -1f
        status = "آماده‌سازی..."
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                run(link)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { status = "خطا: ${nice(e)}" }
            } finally {
                withContext(Dispatchers.Main) {
                    busy = false
                    progress = -1f
                }
            }
        }
    }

    private suspend fun setStatus(s: String) = withContext(Dispatchers.Main) { status = s }

    private suspend fun run(link: String) {
        Engine.init(ctx)
        if (proxyOn && cfg.isNotBlank()) setStatus("اتصال به پروکسی...")
        applyProxy()
        val dir = File(ctx.cacheDir, "dl/${System.currentTimeMillis()}").apply { mkdirs() }
        var failed = 0
        var lastError = ""

        when {
            Spotify.isSpotify(link) -> {
                setStatus("گرفتن لیست آهنگ‌ها...")
                val tracks = Spotify.resolve(link)
                tracks.forEachIndexed { i, t ->
                    setStatus("آهنگ ${i + 1} از ${tracks.size}: ${t.title}")
                    withContext(Dispatchers.Main) { progress = -1f }
                    val q = if (t.artist.isBlank()) "ytsearch1:${t.title} audio" else "ytsearch1:${t.artist} - ${t.title} audio"
                    val name = safe(if (t.artist.isBlank()) t.title else "${t.artist} - ${t.title}")
                    try {
                        ytdlp(q, dir, true, name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        failed++
                        lastError = nice(e)
                    }
                }
            }
            Pinterest.isPinterest(link) -> pinterest(link, dir)
            else -> ytdlp(link, dir, audioOnly, null)
        }

        val files = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.length() > 0 && it.extension.lowercase() !in setOf("part", "ytdl", "json", "txt", "temp") }
            .sortedBy { it.name }
        if (files.isEmpty()) throw RuntimeException(if (lastError.isNotEmpty()) lastError else "فایلی دانلود نشد")
        withContext(Dispatchers.Main) {
            files.forEach { f -> items.add(0, Item(f, f.nameWithoutExtension, Gallery.mimeOf(f))) }
            status = if (failed > 0) "${files.size} فایل دانلود شد، $failed تا ناموفق بود" else "دانلود تموم شد (${files.size} فایل)"
        }
    }

    private fun safe(s: String) = s.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().take(100)

    private suspend fun ytdlp(target: String, dir: File, audio: Boolean, name: String?) {
        val req = YoutubeDLRequest(target)
        val out = if (name != null) "${dir.absolutePath}/$name.%(ext)s" else "${dir.absolutePath}/%(title).80s.%(ext)s"
        req.addOption("-o", out)
        if (target.startsWith("ytsearch")) req.addOption("--playlist-items", "1") else req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--retries", "3")
        req.addOption("--socket-timeout", "30")
        req.addOption("--concurrent-fragments", "4")
        if (audio) {
            req.addOption("-x")
            req.addOption("--audio-format", "mp3")
            req.addOption("--audio-quality", "0")
            req.addOption("--embed-metadata")
        } else {
            req.addOption("-f", "bv*+ba/b")
            req.addOption("-S", "res:1080,vcodec:h264,acodec:m4a")
            req.addOption("--merge-output-format", "mp4")
        }
        req.addOption("--extractor-args", "youtube:player_client=android_vr,tv_simply,ios,mweb")
        if (cookies.isNotBlank()) {
            val cf = File(ctx.cacheDir, "cookies.txt")
            cf.writeText(cookies.trim() + "\n")
            req.addOption("--cookies", cf.absolutePath)
        }
        val port = Net.proxyPort
        if (port > 0) req.addOption("--proxy", "socks5h://127.0.0.1:$port")
        val pid = "kdl-${System.nanoTime()}"
        processId = pid
        try {
            YoutubeDL.getInstance().execute(req, pid) { p, _, line ->
                progress = if (p >= 0f) p / 100f else -1f
                if (line.isNotBlank() && !line.startsWith("[download]")) status = line.take(80)
            }
        } finally {
            processId = null
        }
    }

    private suspend fun pinterest(link: String, dir: File) {
        val html = try {
            Pinterest.fetchHtml(link)
        } catch (_: Throwable) {
            null
        }
        if (html == null) {
            ytdlp(link, dir, false, null)
            return
        }
        if (Pinterest.hasVideo(html)) {
            try {
                ytdlp(link, dir, false, null)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val mp4 = Pinterest.findMp4(html) ?: throw e
                setStatus("دانلود ویدیو...")
                Net.download(mp4, File(dir, "pin_${System.currentTimeMillis()}.mp4")) { progress = it / 100f }
                return
            }
        }
        val img = Pinterest.findImage(html) ?: throw RuntimeException("عکس پین پیدا نشد")
        setStatus("دانلود ${if (img.ext == "gif") "گیف" else "عکس"}...")
        val f = File(dir, "pin_${System.currentTimeMillis()}.${img.ext}")
        try {
            Net.download(img.url, f) { progress = it / 100f }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            Net.download(img.fallback, f) { progress = it / 100f }
        }
    }

    fun save(item: Item) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Gallery.save(ctx, item.file, item.mime, item.kind)
                withContext(Dispatchers.Main) {
                    item.saved = true
                    toast("توی گالری ذخیره شد")
                }
            } catch (e: Throwable) {
                withContext(Dispatchers.Main) { toast("ذخیره نشد: ${nice(e)}") }
            }
        }
    }

    fun saveAll() {
        items.filter { !it.saved }.forEach { save(it) }
    }

    fun saveAndTest() {
        if (settingsBusy) return
        val text = cfg.trim()
        Prefs.setConfig(ctx, text)
        if (text.isEmpty()) {
            ProxyCtl.off()
            settingsMsg = "کانفیگ پاک شد"
            return
        }
        settingsBusy = true
        settingsMsg = "در حال تست..."
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                ConfigParser.toXrayJson(text, 1080)
                ProxyCtl.off()
                ProxyCtl.ensure(ctx, text)
                val ms = Net.probe()
                if (!proxyOn) {
                    proxyOn = true
                    Prefs.setProxyOn(ctx, true)
                }
                "وصله ✓  (${ms}ms)"
            } catch (e: Throwable) {
                ProxyCtl.off()
                "ناموفق: ${nice(e)}"
            }
            withContext(Dispatchers.Main) {
                settingsMsg = msg
                settingsBusy = false
            }
        }
    }

    fun saveCookies() {
        Prefs.setCookies(ctx, cookies.trim())
        settingsMsg = if (cookies.isBlank()) "کوکی پاک شد" else "کوکی ذخیره شد"
    }

    fun updateYtdlp() {
        if (settingsBusy) return
        settingsBusy = true
        settingsMsg = "در حال به‌روزرسانی yt-dlp..."
        viewModelScope.launch(Dispatchers.IO) {
            val msg = try {
                Engine.init(ctx)
                val port = applyProxy()
                if (port > 0) {
                    System.setProperty("socksProxyHost", "127.0.0.1")
                    System.setProperty("socksProxyPort", port.toString())
                }
                try {
                    val r = YoutubeDL.getInstance().updateYoutubeDL(ctx)
                    "yt-dlp: $r"
                } finally {
                    System.clearProperty("socksProxyHost")
                    System.clearProperty("socksProxyPort")
                }
            } catch (e: Throwable) {
                "ناموفق: ${nice(e)}"
            }
            withContext(Dispatchers.Main) {
                settingsMsg = msg
                settingsBusy = false
            }
        }
    }

    override fun onCleared() {
        ProxyCtl.off()
        super.onCleared()
    }
}
