package ir.mmdkavir.kavirdl

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

object GhActions {
    private const val WORKFLOW = "dl.yml"
    private const val LEGACY = "YT-downloader.yml"

    fun normRepo(s: String): String {
        val t = s.trim()
            .removePrefix("https://").removePrefix("http://")
            .removePrefix("www.github.com/").removePrefix("github.com/")
            .removeSuffix(".git").trim('/')
        val p = t.split("/")
        return if (p.size >= 2) "${p[0]}/${p[1]}" else t
    }

    private fun explain(code: Int, text: String): String = when (code) {
        401 -> "توکن اشتباهه یا منقضی شده (401)"
        403 -> "توکن اجازه این کار رو نداره (403)"
        404 -> "ریپو پیدا نشد یا توکن به این ریپو دسترسی نداره (404). اسم ریپو رو چک کن و توی توکن همین ریپو رو انتخاب کن"
        else -> err(code, text)
    }

    fun check(repo: String, token: String): String {
        if (!repo.contains("/")) return "آدرس ریپو درست نیست. مثل owner/name بنویس"
        val (rc, rt) = Net.api("GET", "/repos/$repo", token)
        if (rc != 200) return explain(rc, rt)
        val canPush = try {
            JSONObject(rt).getJSONObject("permissions").optBoolean("push")
        } catch (_: Throwable) {
            true
        }
        if (!canPush) return "توکن فقط خوندن داره. باید Contents و Actions رو روی Read and write بذاری"
        if (hasWorkflow(repo, token, WORKFLOW)) return "وصله ✓ ورک‌فلو dl.yml (همه سایت‌ها)"
        if (hasWorkflow(repo, token, LEGACY)) return "وصله ✓ ورک‌فلو YT-downloader (یوتیوب، اینستاگرام، تیک‌تاک)"
        return "اتصال درسته ولی نه dl.yml توی این ریپو هست نه YT-downloader.yml"
    }

    private fun hasWorkflow(repo: String, token: String, file: String): Boolean =
        Net.api("GET", "/repos/$repo/actions/workflows/$file", token).first == 200

    private fun enc(path: String) =
        path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun listVideos(repo: String, token: String, ref: String): Map<String, JSONObject> {
        val (code, text) = Net.api("GET", "/repos/$repo/contents/videos?ref=${URLEncoder.encode(ref, "UTF-8")}", token)
        if (code == 404) return emptyMap()
        if (code != 200) throw RuntimeException(explain(code, text))
        val arr = JSONArray(text)
        val out = LinkedHashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "file") out[o.getString("name")] = o
        }
        return out
    }

    private fun legacyRuns(repo: String, token: String): List<JSONObject> {
        val (code, text) = Net.api("GET", "/repos/$repo/actions/workflows/$LEGACY/runs?event=workflow_dispatch&per_page=10", token)
        if (code != 200) throw RuntimeException(explain(code, text))
        val arr = JSONObject(text).getJSONArray("workflow_runs")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private suspend fun legacy(
        repo: String,
        token: String,
        branch: String,
        quality: String,
        url: String,
        dir: File,
        status: suspend (String) -> Unit,
        progress: (Float) -> Unit
    ): List<File> {
        val low = url.lowercase()
        if (low.contains("spotify") || low.contains("pinterest") || low.contains("pin.it")) {
            throw RuntimeException("ورک‌فلوی YT-downloader فقط یوتیوب، اینستاگرام و تیک‌تاک رو پشتیبانی می‌کنه. برای اسپاتیفای و پینترست باید dl.yml توی ریپو باشه")
        }
        val before = listVideos(repo, token, branch)
        val known = legacyRuns(repo, token).map { it.getLong("id") }.toSet()

        status("ارسال درخواست به گیت‌هاب...")
        val body = JSONObject()
            .put("ref", branch)
            .put(
                "inputs",
                JSONObject()
                    .put("youtube_url", url)
                    .put("quality", quality)
                    .put("split_threshold_mb", "0")
                    .put("save_in_new_branch", "false")
            ).toString()
        val (dc, dt) = Net.api("POST", "/repos/$repo/actions/workflows/$LEGACY/dispatches", token, body)
        if (dc != 204) throw RuntimeException("اجرای ورک‌فلو ناموفق: ${err(dc, dt)}")

        val started = System.currentTimeMillis()
        while (true) {
            delay(4000)
            val sec = (System.currentTimeMillis() - started) / 1000
            if (sec > 30 * 60) throw RuntimeException("زمان انتظار تموم شد")
            val run = legacyRuns(repo, token).firstOrNull { it.getLong("id") !in known }
            if (run == null) {
                status("منتظر شروع ورک‌فلو... ${sec}s")
                continue
            }
            if (run.optString("status") == "completed") {
                val concl = run.optString("conclusion")
                if (concl != "success") throw RuntimeException("دانلود روی گیت‌هاب شکست خورد ($concl) ${run.optString("html_url")}")
                break
            }
            status("در حال دانلود روی گیت‌هاب... ${sec}s")
        }

        status("گرفتن فایل از ریپو...")
        val after = listVideos(repo, token, branch)
        val fresh = after.filter { (name, o) -> before[name]?.optString("sha") != o.optString("sha") }
        if (fresh.isEmpty()) throw RuntimeException("فایل جدیدی توی ریپو پیدا نشد")
        dir.mkdirs()
        val result = mutableListOf<File>()
        for ((name, o) in fresh) {
            status("دانلود $name")
            val path = o.getString("path")
            val out = File(dir, safeName(name))
            Net.download(
                "https://api.github.com/repos/$repo/contents/${enc(path)}?ref=${URLEncoder.encode(branch, "UTF-8")}",
                out, token, "application/vnd.github.raw", progress
            )
            result += out
            try {
                val del = JSONObject()
                    .put("message", "remove downloaded file [skip ci]")
                    .put("sha", o.getString("sha"))
                    .put("branch", branch)
                    .toString()
                Net.api("DELETE", "/repos/$repo/contents/${enc(path)}", token, del)
            } catch (_: Throwable) {
            }
        }
        return result
    }

    private fun err(code: Int, text: String): String {
        val m = try {
            JSONObject(text).optString("message")
        } catch (_: Throwable) {
            ""
        }
        return "GitHub $code ${m.take(120)}"
    }

    private fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().take(120).ifEmpty { "file" }

    suspend fun download(
        repo: String,
        token: String,
        quality: String,
        url: String,
        dir: File,
        status: suspend (String) -> Unit,
        progress: (Float) -> Unit
    ): List<File> {
        val (rc, rt) = Net.api("GET", "/repos/$repo", token)
        if (rc != 200) throw RuntimeException(explain(rc, rt))
        val branch = JSONObject(rt).getString("default_branch")
        if (!hasWorkflow(repo, token, WORKFLOW)) {
            if (hasWorkflow(repo, token, LEGACY)) return legacy(repo, token, branch, quality, url, dir, status, progress)
            throw RuntimeException("توی این ریپو نه dl.yml هست نه YT-downloader.yml")
        }
        val job = java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()

        status("ارسال درخواست به گیت‌هاب...")
        val body = JSONObject()
            .put("ref", branch)
            .put(
                "inputs",
                JSONObject()
                    .put("url", url)
                    .put("quality", quality)
                    .put("audio", if (quality == "audio") "true" else "false")
                    .put("job", job)
            ).toString()
        val (dc, dt) = Net.api("POST", "/repos/$repo/actions/workflows/$WORKFLOW/dispatches", token, body)
        if (dc != 204) throw RuntimeException("اجرای ورک‌فلو ناموفق: ${err(dc, dt)}")

        val started = System.currentTimeMillis()
        while (true) {
            delay(4000)
            val sec = (System.currentTimeMillis() - started) / 1000
            if (sec > 30 * 60) throw RuntimeException("زمان انتظار تموم شد")
            val (c, t) = Net.api("GET", "/repos/$repo/actions/workflows/$WORKFLOW/runs?event=workflow_dispatch&per_page=20", token)
            if (c != 200) throw RuntimeException(err(c, t))
            val arr = JSONObject(t).getJSONArray("workflow_runs")
            var run: JSONObject? = null
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.optString("display_title") == "dl $job") {
                    run = o
                    break
                }
            }
            if (run == null) {
                status("منتظر شروع ورک‌فلو... ${sec}s")
                continue
            }
            if (run.optString("status") == "completed") {
                val concl = run.optString("conclusion")
                if (concl != "success") throw RuntimeException("دانلود روی گیت‌هاب شکست خورد ($concl) ${run.optString("html_url")}")
                break
            }
            status("در حال دانلود روی گیت‌هاب... ${sec}s")
        }

        status("گرفتن فایل‌ها...")
        val (c1, t1) = Net.api("GET", "/repos/$repo/releases/tags/dl-$job", token)
        if (c1 != 200) throw RuntimeException("خروجی ورک‌فلو پیدا نشد: ${err(c1, t1)}")
        val rel = JSONObject(t1)
        val relId = rel.getLong("id")
        val assets = rel.getJSONArray("assets")
        dir.mkdirs()

        val raw = LinkedHashMap<String, File>()
        var meta: JSONArray? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            status("دانلود $name")
            val f = File(dir, name)
            Net.download(a.getString("url"), f, token, "application/octet-stream", progress)
            if (name == "meta.json") {
                meta = try {
                    JSONArray(f.readText())
                } catch (_: Throwable) {
                    null
                }
                f.delete()
            } else {
                raw[name] = f
            }
        }

        val result = mutableListOf<File>()
        for ((assetName, f) in raw) {
            var original = assetName
            val m = meta
            if (m != null) {
                for (i in 0 until m.length()) {
                    val o = m.getJSONObject(i)
                    if (o.optString("asset") == assetName) original = o.optString("name", assetName)
                }
            }
            var dest = File(dir, safeName(original))
            var n = 1
            while (dest.exists() && dest.absolutePath != f.absolutePath) {
                dest = File(dir, "${n}_${safeName(original)}")
                n++
            }
            if (dest.absolutePath != f.absolutePath) f.renameTo(dest)
            result += if (dest.exists()) dest else f
        }

        try {
            Net.api("DELETE", "/repos/$repo/releases/$relId", token)
            Net.api("DELETE", "/repos/$repo/git/refs/tags/dl-$job", token)
        } catch (_: Throwable) {
        }
        if (result.isEmpty()) throw RuntimeException("فایلی دریافت نشد")
        return result
    }
}
