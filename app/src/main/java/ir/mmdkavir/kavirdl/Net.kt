package ir.mmdkavir.kavirdl

import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

object Net {
    const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    @Volatile
    var proxyPort: Int = 0

    private fun proxy(): Proxy =
        if (proxyPort > 0) Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", proxyPort))
        else Proxy.NO_PROXY

    fun open(url: String, accept: String = "*/*"): HttpURLConnection {
        val c = URL(url).openConnection(proxy()) as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        return c
    }

    fun text(url: String): Pair<String, String> {
        val c = open(url, "text/html,application/json,*/*")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            return body to c.url.toString()
        } finally {
            c.disconnect()
        }
    }

    fun finalUrl(url: String): String {
        val c = open(url)
        try {
            c.requestMethod = "GET"
            c.responseCode
            return c.url.toString()
        } finally {
            c.disconnect()
        }
    }

    fun download(url: String, out: File, onProgress: (Float) -> Unit) {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Long = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) onProgress(read * 100f / total)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    fun probe(): Long {
        val t = System.currentTimeMillis()
        val c = open("https://cp.cloudflare.com/generate_204")
        try {
            val code = c.responseCode
            if (code != 204 && code != 200) throw RuntimeException("HTTP $code")
        } finally {
            c.disconnect()
        }
        return System.currentTimeMillis() - t
    }
}
