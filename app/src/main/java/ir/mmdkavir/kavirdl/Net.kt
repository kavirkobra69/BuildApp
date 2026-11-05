package ir.mmdkavir.kavirdl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Net {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun open(url: String, accept: String = "*/*"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        return c
    }

    private fun apiOnce(method: String, path: String, token: String?, body: String?): Pair<Int, String> {
        val c = open("https://api.github.com$path", "application/vnd.github+json")
        try {
            c.instanceFollowRedirects = true
            c.requestMethod = method
            if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
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

    fun api(method: String, path: String, token: String?, body: String? = null): Pair<Int, String> {
        val attempts = if (method == "GET") 3 else 1
        var last: IOException? = null
        for (i in 1..attempts) {
            try {
                return apiOnce(method, path, token, body)
            } catch (e: IOException) {
                last = e
                if (i < attempts) Thread.sleep(1500L * i)
            }
        }
        throw last ?: IOException("network")
    }

    private fun downloadOnce(url: String, out: File, token: String?, accept: String, onProgress: (Long, Long) -> Unit) {
        var cur = url
        val firstHost = URL(url).host
        for (i in 0 until 6) {
            val c = open(cur, accept)
            try {
                if (!token.isNullOrBlank() && URL(cur).host == firstHost) {
                    c.setRequestProperty("Authorization", "Bearer $token")
                }
                val code = c.responseCode
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("redirect بدون آدرس")
                    cur = URL(URL(cur), loc).toString()
                    continue
                }
                if (code !in 200..299) throw RuntimeException("HTTP $code")
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                    }
                }
                return
            } finally {
                c.disconnect()
            }
        }
        throw IOException("تعداد redirect زیاد شد")
    }

    fun download(url: String, out: File, token: String?, accept: String, onProgress: (Long, Long) -> Unit) {
        var last: IOException? = null
        for (i in 1..3) {
            try {
                downloadOnce(url, out, token, accept, onProgress)
                return
            } catch (e: IOException) {
                last = e
                if (i < 3) Thread.sleep(2000L * i)
            }
        }
        throw last ?: IOException("network")
    }

    fun bitmap(url: String): Bitmap? = try {
        val c = open(url, "image/*")
        try {
            c.instanceFollowRedirects = true
            c.connectTimeout = 10000
            c.readTimeout = 10000
            if (c.responseCode in 200..299) BitmapFactory.decodeStream(c.inputStream) else null
        } finally {
            c.disconnect()
        }
    } catch (_: Throwable) {
        null
    }
}
