package ir.mmdkavir.kavirdl

import java.io.File
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

    fun api(method: String, path: String, token: String?, body: String? = null): Pair<Int, String> {
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

    fun download(url: String, out: File, token: String?, accept: String, onProgress: (Float) -> Unit) {
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
                    val loc = c.getHeaderField("Location") ?: throw RuntimeException("redirect بدون آدرس")
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
                            if (total > 0) onProgress(read.toFloat() / total)
                        }
                    }
                }
                return
            } finally {
                c.disconnect()
            }
        }
        throw RuntimeException("تعداد redirect زیاد شد")
    }
}
