package ir.mmdkavir.kavirdl

import org.json.JSONObject

data class SpotifyTrack(val title: String, val artist: String)

object Spotify {
    private val idRe = Regex("""open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?(track|album|playlist)/([A-Za-z0-9]+)""")

    fun isSpotify(url: String) = url.contains("spotify.com") || url.contains("spotify.link")

    fun resolve(input: String): List<SpotifyTrack> {
        var url = input
        if (!idRe.containsMatchIn(url)) url = Net.finalUrl(url)
        val m = idRe.find(url) ?: throw IllegalArgumentException("لینک اسپاتیفای شناخته نشد")
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        val (html, _) = Net.text("https://open.spotify.com/embed/$type/$id")
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
        if (json != null) {
            val entity = try {
                JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
                    .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
            } catch (_: Throwable) {
                null
            }
            if (entity != null) {
                val out = mutableListOf<SpotifyTrack>()
                if (type == "track") {
                    out += one(entity)
                } else {
                    val list = entity.optJSONArray("trackList")
                    if (list != null) {
                        for (i in 0 until list.length()) {
                            val t = list.optJSONObject(i) ?: continue
                            out += one(t)
                        }
                    }
                }
                val clean = out.filter { it.title.isNotBlank() }
                if (clean.isNotEmpty()) return clean.take(100)
            }
        }
        val (oe, _) = Net.text("https://open.spotify.com/oembed?url=https://open.spotify.com/$type/$id")
        val title = JSONObject(oe).optString("title")
        if (title.isBlank()) throw RuntimeException("اطلاعات آهنگ گرفته نشد")
        return listOf(SpotifyTrack(title, ""))
    }

    private fun one(o: JSONObject): SpotifyTrack {
        val title = o.optString("name").ifEmpty { o.optString("title") }
        var artist = ""
        val arr = o.optJSONArray("artists")
        if (arr != null) {
            val names = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.optString("name")?.takeIf { it.isNotEmpty() }?.let { names += it }
            }
            artist = names.joinToString(", ")
        }
        if (artist.isEmpty()) artist = o.optString("subtitle")
        return SpotifyTrack(title.trim(), artist.trim())
    }
}

data class PinResult(val url: String, val ext: String, val fallback: String)

object Pinterest {
    fun isPinterest(url: String) = url.contains("pinterest.") || url.contains("pin.it")

    fun hasVideo(html: String) =
        html.contains("\"video_list\"") || html.contains("\"V_720P\"") || html.contains("og:video") ||
            html.contains("\"V_HLSV3") || html.contains("\"V_EXP")

    fun fetchHtml(url: String): String = Net.text(url).first

    fun findMp4(html: String): String? =
        Regex("""https:\\?/\\?/v\d*\.pinimg\.com[^"\\]*?\.mp4""")
            .findAll(html).map { it.value.replace("\\/", "/") }.firstOrNull()

    fun findImage(html: String): PinResult? {
        val og = Regex("""<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']""").find(html)?.groupValues?.get(1)
            ?: return null
        val clean = og.replace("&amp;", "&")
        val ext = clean.substringAfterLast('.').substringBefore('?').lowercase().let {
            if (it in listOf("gif", "png", "webp", "jpg", "jpeg")) it else "jpg"
        }
        val upgraded = clean.replace(Regex("""pinimg\.com/\d+x/"""), "pinimg.com/originals/")
        return PinResult(upgraded, ext, clean)
    }
}
