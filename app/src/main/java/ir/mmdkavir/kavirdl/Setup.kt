package ir.mmdkavir.kavirdl

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

class RepoInfo(val full: String, val owner: String, val priv: Boolean, val branch: String, val canPush: Boolean)

class SetupFile(val dst: String, val bytes: ByteArray)

class UploadResult(val uploaded: List<String>, val same: List<String>, val conflicts: List<SetupFile>)

/** Real GitHub operations that act only on the repository of the token's own account. */
object GitHubSetup {
    private val BUNDLED = listOf(
        "workflows/dl.yml" to ".github/workflows/dl.yml",
        "workflows/clean-dl.yml" to ".github/workflows/clean-dl.yml",
        "scripts/dl.py" to ".github/scripts/dl.py"
    )

    private fun msg(code: Int, text: String): String {
        val m = try {
            JSONObject(text).optString("message")
        } catch (_: Throwable) {
            ""
        }
        return when (code) {
            401 -> "توکن اشتباهه یا منقضی شده (401)"
            403 -> "توکن دسترسی کافی نداره (403) ${m.take(100)}"
            404 -> "پیدا نشد یا توکن دسترسی نداره (404)"
            422 -> "GitHub درخواست رو رد کرد (422) ${m.take(100)}"
            else -> "GitHub $code ${m.take(100)}"
        }
    }

    fun login(token: String): String {
        val (c, t) = Net.api("GET", "/user", token)
        if (c != 200) throw RuntimeException(msg(c, t))
        return JSONObject(t).getString("login")
    }

    /** Accepts a github.com URL, owner/name, or only a name (owner = the token's account). */
    fun parseRepo(input: String, login: String): Pair<String, String>? {
        var s = input.trim().removeSuffix("/").removeSuffix(".git")
        s = s.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("github.com/")
        val parts = s.split("/").filter { it.isNotBlank() }
        val (o, n) = when (parts.size) {
            1 -> login to parts[0]
            2 -> parts[0] to parts[1]
            else -> if (parts.size >= 2 && input.contains("github.com")) parts[0] to parts[1] else return null
        }
        if (!Regex("^[A-Za-z0-9-]+$").matches(o) || !Regex("^[A-Za-z0-9._-]+$").matches(n)) return null
        return o to n
    }

    private fun info(t: String): RepoInfo {
        val o = JSONObject(t)
        val perms = o.optJSONObject("permissions")
        return RepoInfo(
            o.getString("full_name"), o.getJSONObject("owner").getString("login"),
            o.optBoolean("private"), o.optString("default_branch", "main"),
            perms == null || perms.optBoolean("push") || perms.optBoolean("admin")
        )
    }

    fun repo(token: String, owner: String, name: String): RepoInfo? {
        val (c, t) = Net.api("GET", "/repos/$owner/$name", token)
        if (c == 404) return null
        if (c != 200) throw RuntimeException(msg(c, t))
        return info(t)
    }

    fun create(token: String, name: String, priv: Boolean): RepoInfo {
        val body = JSONObject().put("name", name).put("private", priv).put("auto_init", true)
            .put("description", "KavirDL downloads").toString()
        val (c, t) = Net.api("POST", "/user/repos", token, body)
        if (c == 422) throw RuntimeException("ساخت ریپو ممکن نشد. شاید این اسم قبلاً وجود داره")
        if (c == 403 || c == 404) throw RuntimeException("برای ساخت ریپوی جدید توکن Classic با دسترسی repo و workflow لازمه. یا یه ریپوی موجود بده")
        if (c != 201) throw RuntimeException(msg(c, t))
        return info(t)
    }

    /** Validates that the token's account owns the repository and can write to it. */
    fun validate(token: String, input: String): Pair<String, RepoInfo> {
        val me = login(token)
        val (o, n) = parseRepo(input, me) ?: throw RuntimeException("آدرس ریپو معتبر نیست")
        if (!o.equals(me, ignoreCase = true)) throw RuntimeException("این ریپو مال حساب $me نیست. فقط ریپوی خودت قابل استفاده‌ست")
        val r = repo(token, o, n) ?: throw RuntimeException("ریپو پیدا نشد")
        if (!r.owner.equals(me, ignoreCase = true)) throw RuntimeException("این ریپو مال حساب $me نیست")
        if (!r.canPush) throw RuntimeException("توکن اجازه نوشتن توی این ریپو رو نداره")
        return me to r
    }

    fun bundled(ctx: Context): List<SetupFile> = BUNDLED.map { (src, dst) ->
        SetupFile(dst, ctx.assets.open(src).use { it.readBytes() })
    }

    private fun gitSha(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${b.size}\u0000".toByteArray())
        md.update(b)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun enc(path: String) = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun remoteSha(token: String, repo: String, path: String, branch: String): String? {
        val (c, t) = Net.api("GET", "/repos/$repo/contents/${enc(path)}?ref=$branch", token)
        if (c == 404) return null
        if (c != 200) throw RuntimeException(msg(c, t))
        return JSONObject(t).optString("sha").ifBlank { null }
    }

    private fun put(token: String, repo: String, branch: String, path: String, bytes: ByteArray, sha: String?) {
        val body = JSONObject()
            .put("message", (if (sha == null) "Add " else "Update ") + path)
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("branch", branch)
        if (sha != null) body.put("sha", sha)
        var last = ""
        for (i in 1..4) {
            val (c, t) = Net.api("PUT", "/repos/$repo/contents/${enc(path)}", token, body.toString())
            if (c == 200 || c == 201) return
            if (c == 404 && i < 4) {
                Thread.sleep(2000)
                continue
            }
            if (c == 409) throw RuntimeException("تداخل هنگام نوشتن $path؛ دوباره تلاش کن")
            if (c == 403 || c == 404) throw RuntimeException("توکن اجازه نوشتن فایل‌های workflow رو نداره. دسترسی Workflows (یا scope به اسم workflow) لازمه")
            last = msg(c, t)
            break
        }
        throw RuntimeException(last.ifBlank { "نوشتن $path ناموفق بود" })
    }

    /** Creates missing files and skips identical ones. Different files are overwritten only if listed in [confirmed]. */
    fun upload(
        token: String, r: RepoInfo, files: List<SetupFile>, confirmed: Set<String>,
        progress: (Int, Int, String) -> Unit
    ): UploadResult {
        val up = mutableListOf<String>()
        val same = mutableListOf<String>()
        val conf = mutableListOf<SetupFile>()
        files.forEachIndexed { i, f ->
            progress(i, files.size, f.dst)
            val sha = remoteSha(token, r.full, f.dst, r.branch)
            when {
                sha == null -> {
                    put(token, r.full, r.branch, f.dst, f.bytes, null)
                    up += f.dst
                }
                sha == gitSha(f.bytes) -> same += f.dst
                f.dst in confirmed -> {
                    put(token, r.full, r.branch, f.dst, f.bytes, sha)
                    up += f.dst
                }
                else -> conf += f
            }
        }
        progress(files.size, files.size, "")
        return UploadResult(up, same, conf)
    }

    fun ensureActionsEnabled(token: String, repo: String) {
        try {
            val (c, t) = Net.api("GET", "/repos/$repo/actions/permissions", token)
            if (c == 200 && !JSONObject(t).optBoolean("enabled", true)) {
                Net.api("PUT", "/repos/$repo/actions/permissions", token, """{"enabled":true}""")
            }
        } catch (_: Throwable) {
        }
    }

    fun workflowInstalled(token: String, repo: String, tries: Int = 1): Boolean {
        for (i in 1..tries) {
            val (c, t) = Net.api("GET", "/repos/$repo/actions/workflows/${GhActions.WORKFLOW}", token)
            if (c == 200 && JSONObject(t).optString("state") == "active") return true
            if (i < tries) Thread.sleep(2500)
        }
        return false
    }

    fun recentRuns(token: String, repo: String): List<String> {
        val (c, t) = Net.api("GET", "/repos/$repo/actions/workflows/${GhActions.WORKFLOW}/runs?per_page=3", token)
        if (c != 200) return emptyList()
        val a = JSONObject(t).getJSONArray("workflow_runs")
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val res = o.optString("conclusion").ifBlank { o.optString("status") }
            "${o.optString("display_title")}: $res"
        }
    }

    /** Validates a user-picked file and maps it to its repository path. */
    fun validateUserFile(name: String, bytes: ByteArray): SetupFile {
        if (!Regex("^[A-Za-z0-9._-]{1,80}$").matches(name)) throw RuntimeException("$name: اسم فایل نامعتبره")
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("yml", "yaml", "py")) throw RuntimeException("$name: فقط فایل yml/yaml/py قابل قبوله")
        if (bytes.isEmpty() || bytes.size > 512 * 1024) throw RuntimeException("$name: حجم فایل نامعتبره")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Throwable) {
            throw RuntimeException("$name: فایل متنی UTF-8 نیست")
        }
        if (Regex("""(ghp_|gho_|ghu_|ghs_)[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}""").containsMatchIn(text)) {
            throw RuntimeException("$name: توکن داخل فایل پیدا شد؛ آپلود نمی‌شه")
        }
        if (ext != "py") {
            if (!Regex("""(?m)^("?on"?|true)\s*:""").containsMatchIn(text) || !Regex("(?m)^jobs\\s*:").containsMatchIn(text)) {
                throw RuntimeException("$name: ساختار workflow معتبر نیست (on و jobs لازمه)")
            }
            return SetupFile(".github/workflows/$name", bytes)
        }
        return SetupFile(".github/scripts/$name", bytes)
    }
}
