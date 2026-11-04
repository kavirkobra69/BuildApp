package ir.mmdkavir.kavirdl

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

object GhActions {
    private const val WORKFLOW = "YT-downloader.yml"

    fun isYoutube(url: String) = url.contains("youtube.com") || url.contains("youtu.be")

    fun supports(url: String) = isYoutube(url) || url.contains("instagram.com")

    private fun api(method: String, path: String, token: String, body: String? = null): Pair<Int, String> {
        val c = Net.open("https://api.github.com$path", "application/vnd.github+json")
        try {
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return code to text
        } finally {
            c.disconnect()
        }
    }

    private fun err(code: Int, text: String): String {
        val m = try {
            JSONObject(text).optString("message")
        } catch (_: Throwable) {
            ""
        }
        return "GitHub HTTP $code ${m.take(120)}"
    }

    private fun enc(path: String) =
        path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun listVideos(repo: String, token: String, ref: String): Map<String, JSONObject> {
        val (code, text) = api("GET", "/repos/$repo/contents/videos?ref=${URLEncoder.encode(ref, "UTF-8")}", token)
        if (code == 404) return emptyMap()
        if (code != 200) throw RuntimeException(err(code, text))
        val arr = JSONArray(text)
        val out = LinkedHashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "file") out[o.getString("name")] = o
        }
        return out
    }

    private fun runs(repo: String, token: String): List<JSONObject> {
        val (code, text) = api("GET", "/repos/$repo/actions/workflows/$WORKFLOW/runs?event=workflow_dispatch&per_page=10", token)
        if (code != 200) throw RuntimeException(err(code, text))
        val arr = JSONObject(text).getJSONArray("workflow_runs")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    suspend fun download(
        repo: String,
        token: String,
        quality: String,
        deleteAfter: Boolean,
        url: String,
        audio: Boolean,
        dir: File,
        status: suspend (String) -> Unit,
        progress: (Float) -> Unit
    ) {
        val (rc, rt) = api("GET", "/repos/$repo", token)
        if (rc != 200) throw RuntimeException("ریپو یا توکن مشکل داره: ${err(rc, rt)}")
        val branch = JSONObject(rt).getString("default_branch")

        val before = listVideos(repo, token, branch)
        val knownRuns = runs(repo, token).map { it.getLong("id") }.toSet()

        status("اجرای ورک‌فلو روی گیت‌هاب...")
        val body = JSONObject()
            .put("ref", branch)
            .put(
                "inputs",
                JSONObject()
                    .put("youtube_url", url)
                    .put("quality", if (audio) "audio" else quality)
                    .put("split_threshold_mb", "0")
                    .put("save_in_new_branch", "false")
            ).toString()
        val (dc, dt) = api("POST", "/repos/$repo/actions/workflows/$WORKFLOW/dispatches", token, body)
        if (dc != 204) throw RuntimeException("اجرای ورک‌فلو ناموفق: ${err(dc, dt)}")

        val started = System.currentTimeMillis()
        var runUrl = ""
        while (true) {
            delay(5000)
            val sec = (System.currentTimeMillis() - started) / 1000
            if (sec > 20 * 60) throw RuntimeException("زمان انتظار تموم شد")
            val run = runs(repo, token).firstOrNull { it.getLong("id") !in knownRuns }
            if (run == null) {
                status("منتظر شروع ورک‌فلو... ${sec}s")
                continue
            }
            runUrl = run.optString("html_url")
            val st = run.optString("status")
            if (st == "completed") {
                val concl = run.optString("conclusion")
                if (concl != "success") throw RuntimeException("ورک‌فلو شکست خورد ($concl) $runUrl")
                break
            }
            status("در حال دانلود روی گیت‌هاب... ${sec}s")
        }

        status("گرفتن فایل از ریپو...")
        val after = listVideos(repo, token, branch)
        val fresh = after.filter { (name, o) -> before[name]?.optString("sha") != o.optString("sha") }
        if (fresh.isEmpty()) throw RuntimeException("فایل جدیدی توی ریپو پیدا نشد $runUrl")

        for ((name, o) in fresh) {
            status("دانلود $name")
            val path = o.getString("path")
            val c = Net.open(
                "https://api.github.com/repos/$repo/contents/${enc(path)}?ref=${URLEncoder.encode(branch, "UTF-8")}",
                "application/vnd.github.raw"
            )
            try {
                c.setRequestProperty("Authorization", "Bearer $token")
                val code = c.responseCode
                if (code !in 200..299) throw RuntimeException(err(code, c.errorStream?.bufferedReader()?.readText() ?: ""))
                val total = c.contentLengthLong
                val out = File(dir, name.replace("/", "_"))
                c.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            if (total > 0) progress(read.toFloat() / total)
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
            if (deleteAfter) {
                try {
                    val del = JSONObject()
                        .put("message", "remove downloaded file [skip ci]")
                        .put("sha", o.getString("sha"))
                        .put("branch", branch)
                        .toString()
                    api("DELETE", "/repos/$repo/contents/${enc(path)}", token, del)
                } catch (_: Throwable) {
                }
            }
        }
    }
}
