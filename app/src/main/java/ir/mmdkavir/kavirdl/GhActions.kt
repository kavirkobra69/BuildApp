package ir.mmdkavir.kavirdl

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class Update(
    val label: String,
    val percent: Float = -1f,
    val done: Long = 0,
    val total: Long = 0,
    val speed: Long = 0,
    val eta: Long = -1,
    val title: String = "",
    val thumb: String = ""
)

object GhActions {
    const val WORKFLOW = "dl.yml"

    fun newJob(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + (100..999).random()

    private fun err(code: Int, text: String): String {
        val m = try {
            JSONObject(text).optString("message")
        } catch (_: Throwable) {
            ""
        }
        return "GitHub $code ${m.take(120)}"
    }

    private fun explain(code: Int, text: String): String = when (code) {
        401 -> "توکن اشتباهه یا منقضی شده (401)"
        403 -> "توکن اجازه این کار رو نداره (403). دسترسی‌های Contents، Actions و Workflows رو Read and write بذار"
        404 -> "ریپو پیدا نشد یا توکن به این ریپو دسترسی نداره (404)"
        else -> err(code, text)
    }

    private fun safeName(s: String) = s.replace(Regex("""[\\/:*?"<>|\r\n]"""), "_").trim().take(120).ifEmpty { "file" }

    fun check(repo: String, token: String): String {
        val (rc, rt) = Net.api("GET", "/repos/$repo", token)
        if (rc != 200) return explain(rc, rt)
        val canPush = try {
            JSONObject(rt).getJSONObject("permissions").optBoolean("push")
        } catch (_: Throwable) {
            true
        }
        if (!canPush) return "توکن فقط خوندن داره. باید Contents و Actions رو روی Read and write بذاری"
        val (wc, _) = Net.api("GET", "/repos/$repo/actions/workflows/$WORKFLOW", token)
        if (wc != 200) return "ورک‌فلو dl.yml توی ریپوی تو نصب نیست. «ساخت پیکربندی» رو بزن"
        return "وصله ✓ همه‌چیز آماده‌ست"
    }

    private fun findRun(repo: String, token: String, job: String): JSONObject? {
        val (c, t) = Net.api("GET", "/repos/$repo/actions/workflows/$WORKFLOW/runs?event=workflow_dispatch&per_page=30", token)
        if (c != 200) throw RuntimeException(explain(c, t))
        val arr = JSONObject(t).getJSONArray("workflow_runs")
        var found: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("display_title") == "dl $job") found = o
        }
        return found
    }

    private fun releaseOf(repo: String, token: String, job: String): JSONObject? {
        val (c, t) = Net.api("GET", "/repos/$repo/releases/tags/dl-$job", token)
        return if (c == 200) JSONObject(t) else null
    }

    private fun deleteRelease(repo: String, token: String, job: String) {
        val rel = releaseOf(repo, token, job)
        if (rel != null) Net.api("DELETE", "/repos/$repo/releases/${rel.getLong("id")}", token)
        Net.api("DELETE", "/repos/$repo/git/refs/tags/dl-$job", token)
    }

    fun cancelRemote(repo: String, token: String, job: String) {
        try {
            val r = findRun(repo, token, job)
            if (r != null && r.optString("status") != "completed") {
                Net.api("POST", "/repos/$repo/actions/runs/${r.getLong("id")}/cancel", token)
            }
        } catch (_: Throwable) {
        }
        try {
            deleteRelease(repo, token, job)
        } catch (_: Throwable) {
        }
    }

    private fun fromBody(b: JSONObject, fallback: Update): Update {
        val title = b.optString("title")
        val thumb = b.optString("thumb")
        return when (b.optString("phase")) {
            "download" -> {
                val count = b.optInt("count", 1)
                val idx = b.optInt("index", 1)
                Update(
                    label = if (count > 1) "دانلود روی گیت‌هاب ($idx از $count)" else "دانلود روی گیت‌هاب",
                    percent = b.optDouble("percent", -1.0).toFloat(),
                    done = b.optLong("downloaded"),
                    total = b.optLong("total"),
                    speed = b.optLong("speed"),
                    eta = b.optLong("eta", -1),
                    title = title,
                    thumb = thumb
                )
            }
            "uploading", "done" -> Update("آپلود نتیجه روی گیت‌هاب", -1f, title = title, thumb = thumb)
            else -> fallback.copy(title = title, thumb = thumb)
        }
    }

    private class Meter {
        private var t0 = System.nanoTime()
        private var b0 = 0L
        private var speed = 0.0

        fun update(bytes: Long): Long {
            val t = System.nanoTime()
            val dt = (t - t0) / 1e9
            if (dt >= 0.7) {
                val inst = (bytes - b0) / dt
                speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                t0 = t
                b0 = bytes
            }
            return speed.toLong()
        }
    }

    private fun failure(repo: String, token: String, job: String, run: JSONObject): RuntimeException {
        var msg = ""
        try {
            val rel = releaseOf(repo, token, job)
            if (rel != null) msg = JSONObject(rel.optString("body")).optString("message")
        } catch (_: Throwable) {
        }
        val concl = run.optString("conclusion")
        try {
            deleteRelease(repo, token, job)
        } catch (_: Throwable) {
        }
        return RuntimeException(
            when {
                concl == "cancelled" -> "اجرای گیت‌هاب لغو شد"
                msg.isNotBlank() -> msg
                else -> "دانلود روی گیت‌هاب شکست خورد ($concl)"
            }
        )
    }

    suspend fun download(
        repo: String,
        token: String,
        quality: String,
        url: String,
        job: String,
        dir: File,
        branch: String = "",
        onUpdate: (Update) -> Unit
    ): List<File> {
        val (rc, rt) = Net.api("GET", "/repos/$repo", token)
        if (rc != 200) throw RuntimeException(explain(rc, rt))
        val ref = branch.trim().ifEmpty { JSONObject(rt).getString("default_branch") }

        onUpdate(Update("بررسی درخواست..."))
        if (findRun(repo, token, job) == null) {
            onUpdate(Update("ارسال درخواست به گیت‌هاب"))
            val body = JSONObject()
                .put("ref", ref)
                .put(
                    "inputs",
                    JSONObject()
                        .put("url", url)
                        .put("quality", quality)
                        .put("audio", if (quality == "audio") "true" else "false")
                        .put("job", job)
                ).toString()
            val (dc, dt) = Net.api("POST", "/repos/$repo/actions/workflows/$WORKFLOW/dispatches", token, body)
            if (dc != 204) throw RuntimeException("اجرای ورک‌فلو ناموفق: ${explain(dc, dt)}")
        }

        val started = System.currentTimeMillis()
        var fails = 0
        var title = ""
        var thumb = ""
        while (true) {
            delay(3000)
            if (System.currentTimeMillis() - started > 40 * 60 * 1000L) throw RuntimeException("زمان انتظار تموم شد")
            try {
                val run = findRun(repo, token, job)
                var upd = Update("در صف گیت‌هاب...")
                if (run != null) {
                    val st = run.optString("status")
                    if (st == "completed") {
                        if (run.optString("conclusion") != "success") throw failure(repo, token, job, run)
                        break
                    }
                    upd = Update(if (st == "in_progress") "آماده‌سازی ران‌ر گیت‌هاب" else "در صف گیت‌هاب...")
                    val rel = releaseOf(repo, token, job)
                    if (rel != null) {
                        try {
                            upd = fromBody(JSONObject(rel.optString("body")), upd)
                        } catch (_: Throwable) {
                        }
                    }
                }
                if (upd.title.isNotBlank()) title = upd.title
                if (upd.thumb.isNotBlank()) thumb = upd.thumb
                onUpdate(upd)
                fails = 0
            } catch (e: IOException) {
                fails++
                if (fails > 10) throw RuntimeException("اینترنت قطعه")
                onUpdate(Update("اتصال اینترنت قطع شد، دوباره تلاش می‌کنم...", title = title, thumb = thumb))
            }
        }

        onUpdate(Update("آماده‌سازی دریافت...", title = title, thumb = thumb))
        val rel = releaseOf(repo, token, job) ?: throw RuntimeException("خروجی ورک‌فلو پیدا نشد")
        val assets = rel.getJSONArray("assets")
        if (assets.length() == 0) throw RuntimeException("خروجی ورک‌فلو خالی بود")
        var total = 0L
        for (i in 0 until assets.length()) total += assets.getJSONObject(i).getLong("size")
        dir.mkdirs()

        val meter = Meter()
        var base = 0L
        val raw = LinkedHashMap<String, File>()
        var meta: JSONArray? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            val size = a.getLong("size")
            val f = File(dir, name)
            val base0 = base
            Net.download(a.getString("url"), f, token, "application/octet-stream") { read, _ ->
                val cum = base0 + read
                val sp = meter.update(cum)
                val eta = if (sp > 0) (total - cum) / sp else -1L
                onUpdate(
                    Update(
                        "دریافت روی گوشی (${i + 1} از ${assets.length()})",
                        if (total > 0) cum.toFloat() / total else -1f,
                        cum, total, sp, eta, title, thumb
                    )
                )
            }
            if (f.length() != size) throw RuntimeException("حجم فایل دریافتی با فایل اصلی نمی‌خونه")
            base += size
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
        if (result.isEmpty()) throw RuntimeException("فایلی دریافت نشد")

        try {
            deleteRelease(repo, token, job)
        } catch (_: Throwable) {
        }
        return result
    }
}
