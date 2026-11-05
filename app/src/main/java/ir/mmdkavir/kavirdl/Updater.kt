package ir.mmdkavir.kavirdl

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

data class UpdateInfo(val tag: String, val assetUrl: String, val publicUrl: String, val notes: String = "")

object Updater {
    const val APP_REPO = "kavirkobra69/BuildApp"

    fun currentVersion(ctx: Context): String =
        try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"
        } catch (_: Throwable) {
            "0"
        }

    private fun parse(v: String) = v.trim().removePrefix("v").substringBefore('-').split(".").map { it.toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parse(remote)
        val b = parse(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    fun latest(repo: String, token: String?): UpdateInfo {
        val (code, text) = Net.api("GET", "/repos/$repo/releases/latest", token)
        if (code == 404) throw RuntimeException("ریلیزی توی این ریپو پیدا نشد (404)")
        if (code == 401) throw RuntimeException("توکن اشتباهه (401). خالی بذارش اگه ریپو public ـه")
        if (code != 200) throw RuntimeException("GitHub $code")
        val o = JSONObject(text)
        val assets = o.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                return UpdateInfo(o.getString("tag_name"), a.getString("url"), a.getString("browser_download_url"), o.optString("body"))
            }
        }
        throw RuntimeException("فایل APK توی ریلیز نبود")
    }

    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        return true
    }
}
