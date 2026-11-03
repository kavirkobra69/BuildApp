package ir.mmdkavir.kavirdl

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

object ConfigParser {

    fun toXrayJson(raw: String, port: Int): String {
        val text = raw.trim()
        if (text.isEmpty()) throw IllegalArgumentException("کانفیگ خالیه")
        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", port)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            .put("sniffing", JSONObject().put("enabled", false))
        if (text.startsWith("{")) {
            val obj = JSONObject(text)
            obj.put("inbounds", JSONArray().put(inbound))
            return obj.toString()
        }
        val outbound = when {
            text.startsWith("vless://", true) -> vless(text)
            text.startsWith("vmess://", true) -> vmess(text)
            text.startsWith("trojan://", true) -> trojan(text)
            else -> throw IllegalArgumentException("فقط vless:// و vmess:// و trojan:// یا JSON پشتیبانی میشه")
        }
        val root = JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("inbounds", JSONArray().put(inbound))
            .put(
                "outbounds",
                JSONArray()
                    .put(outbound.put("tag", "proxy"))
                    .put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
            )
        return root.toString()
    }

    private fun hostOf(u: Uri): String = (u.host ?: throw IllegalArgumentException("آدرس سرور پیدا نشد")).trim('[', ']')

    private fun portOf(u: Uri): Int = if (u.port > 0) u.port else 443

    private fun vless(link: String): JSONObject {
        val u = Uri.parse(link)
        val id = u.userInfo ?: throw IllegalArgumentException("uuid پیدا نشد")
        val host = hostOf(u)
        val user = JSONObject().put("id", id).put("encryption", u.getQueryParameter("encryption") ?: "none")
        u.getQueryParameter("flow")?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }
        val settings = JSONObject().put(
            "vnext",
            JSONArray().put(
                JSONObject().put("address", host).put("port", portOf(u)).put("users", JSONArray().put(user))
            )
        )
        return JSONObject()
            .put("protocol", "vless")
            .put("settings", settings)
            .put("streamSettings", stream(host) { u.getQueryParameter(it) })
    }

    private fun trojan(link: String): JSONObject {
        val u = Uri.parse(link)
        val pass = u.userInfo ?: throw IllegalArgumentException("پسورد پیدا نشد")
        val host = hostOf(u)
        val settings = JSONObject().put(
            "servers",
            JSONArray().put(JSONObject().put("address", host).put("port", portOf(u)).put("password", pass))
        )
        return JSONObject()
            .put("protocol", "trojan")
            .put("settings", settings)
            .put("streamSettings", stream(host, defaultSecurity = "tls") { u.getQueryParameter(it) })
    }

    private fun vmess(link: String): JSONObject {
        val b64 = link.substring(8).substringBefore('#').trim()
        val decoded = String(Base64.decode(b64, Base64.DEFAULT or Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        val j = JSONObject(decoded)
        val host = j.getString("add")
        val port = j.get("port").toString().toInt()
        val user = JSONObject()
            .put("id", j.getString("id"))
            .put("alterId", j.optString("aid", "0").toIntOrNull() ?: 0)
            .put("security", j.optString("scy", "auto").ifEmpty { "auto" })
        val settings = JSONObject().put(
            "vnext",
            JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user)))
        )
        val q: (String) -> String? = { key ->
            when (key) {
                "type" -> j.optString("net", "tcp")
                "headerType" -> j.optString("type", "none")
                "security" -> if (j.optString("tls") == "tls") "tls" else "none"
                "serviceName" -> j.optString("path")
                else -> j.optString(key).takeIf { it.isNotEmpty() }
            }
        }
        return JSONObject()
            .put("protocol", "vmess")
            .put("settings", settings)
            .put("streamSettings", stream(host, q = q))
    }

    private fun stream(address: String, defaultSecurity: String = "none", q: (String) -> String?): JSONObject {
        var net = (q("type") ?: "tcp").lowercase()
        net = when (net) {
            "h2" -> "http"
            "splithttp" -> "xhttp"
            else -> net
        }
        val security = (q("security") ?: defaultSecurity).lowercase()
        val hostHeader = q("host")
        val path = q("path") ?: "/"
        val s = JSONObject().put("network", net).put("security", security)

        if (security == "tls") {
            val tls = JSONObject()
                .put("serverName", q("sni") ?: hostHeader ?: address)
                .put("fingerprint", q("fp") ?: "chrome")
            q("alpn")?.takeIf { it.isNotEmpty() }?.let { a ->
                val arr = JSONArray()
                a.split(",").forEach { arr.put(it.trim()) }
                tls.put("alpn", arr)
            }
            val insecure = q("allowInsecure") ?: q("insecure")
            if (insecure == "1" || insecure == "true") tls.put("allowInsecure", true)
            s.put("tlsSettings", tls)
        } else if (security == "reality") {
            val r = JSONObject()
                .put("serverName", q("sni") ?: address)
                .put("fingerprint", q("fp") ?: "chrome")
                .put("publicKey", q("pbk") ?: "")
                .put("shortId", q("sid") ?: "")
                .put("spiderX", q("spx") ?: "")
            s.put("realitySettings", r)
        }

        when (net) {
            "ws" -> {
                val ws = JSONObject().put("path", path)
                (hostHeader ?: q("sni"))?.let { ws.put("headers", JSONObject().put("Host", it)) }
                s.put("wsSettings", ws)
            }
            "grpc" -> {
                val g = JSONObject().put("serviceName", q("serviceName") ?: path.trim('/'))
                if (q("mode") == "multi") g.put("multiMode", true)
                s.put("grpcSettings", g)
            }
            "httpupgrade" -> {
                val h = JSONObject().put("path", path)
                hostHeader?.let { h.put("host", it) }
                s.put("httpupgradeSettings", h)
            }
            "xhttp" -> {
                val x = JSONObject().put("path", path)
                hostHeader?.let { x.put("host", it) }
                q("mode")?.let { x.put("mode", it) }
                s.put("xhttpSettings", x)
            }
            "http" -> {
                val h = JSONObject().put("path", path)
                hostHeader?.let { h.put("host", JSONArray().put(it)) }
                s.put("httpSettings", h)
            }
            "tcp" -> {
                if (q("headerType") == "http") {
                    val req = JSONObject()
                        .put("path", JSONArray().put(path))
                        .put("headers", JSONObject().put("Host", JSONArray().put(hostHeader ?: address)))
                    s.put(
                        "tcpSettings",
                        JSONObject().put("header", JSONObject().put("type", "http").put("request", req))
                    )
                }
            }
        }
        return s
    }
}

object XrayManager {
    private var process: Process? = null
    private val log = StringBuilder()

    @Volatile
    var port: Int = 0
        private set

    fun running(): Boolean = process?.isAlive == true && port > 0

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Synchronized
    fun start(ctx: Context, raw: String): Int {
        stop()
        val p = freePort()
        val json = ConfigParser.toXrayJson(raw, p)
        val dir = ctx.filesDir
        val cfg = File(dir, "xray.json")
        cfg.writeText(json)
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) throw IllegalStateException("فایل xray داخل برنامه نیست")
        val pb = ProcessBuilder(bin.absolutePath, "run", "-c", cfg.absolutePath)
        pb.directory(dir)
        pb.environment()["XRAY_LOCATION_ASSET"] = dir.absolutePath
        pb.redirectErrorStream(true)
        val proc = pb.start()
        process = proc
        log.setLength(0)
        Thread {
            try {
                proc.inputStream.bufferedReader().forEachLine {
                    synchronized(log) {
                        log.append(it).append('\n')
                        if (log.length > 4000) log.delete(0, log.length - 4000)
                    }
                }
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true }.start()

        val deadline = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) {
                val out = synchronized(log) { log.toString() }
                process = null
                throw IllegalStateException("xray بسته شد\n$out")
            }
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 200) }
                port = p
                return p
            } catch (_: Throwable) {
                Thread.sleep(150)
            }
        }
        stop()
        throw IllegalStateException("xray بالا نیومد")
    }

    @Synchronized
    fun stop() {
        process?.let {
            try {
                it.destroy()
                Thread.sleep(100)
                if (it.isAlive) it.destroyForcibly()
            } catch (_: Throwable) {
            }
        }
        process = null
        port = 0
        Net.proxyPort = 0
    }
}
