package ir.mmdkavir.kavirdl

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object GhActions {
    private const val WORKFLOW = "dl.yml"

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
        if (rc != 200) throw RuntimeException("ریپو یا توکن مشکل داره: ${err(rc, rt)}")
        val branch = JSONObject(rt).getString("default_branch")
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
