package app.kalkan.browser

import android.net.Uri

/** Akış adaylarını ölçer: süre/boyuta göre gerçek filmi seçer, reklam ve kısa klipleri eler; sonucu CsHints'e yazar. */
internal object CsMedia {
    fun analyze(list: List<String>, ua: String, origin: String, host: String) {
        class M(val url: String, val secs: Double, val mb: Double, val kind: String)
        fun mediaOf(u: String): M? {
            try {
                val isMp4 = Regex("(?i)\\.mp4(\\?|$)").containsMatchIn(u)
                if (isMp4) {
                    val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                    c.connectTimeout = 8000; c.readTimeout = 8000
                    c.setRequestProperty("Range", "bytes=0-1"); c.setRequestProperty("User-Agent", ua); c.setRequestProperty("Referer", "$origin/")
                    val total = c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: c.contentLengthLong
                    c.disconnect()
                    return M(u, 0.0, if (total > 0) total / 1048576.0 else 0.0, "mp4")
                }
                val r = ApiFinder.fetchText(u, ua, "$origin/", 1 shl 17)
                var body = r.second.second
                if (r.first != 200 || !body.trimStart().startsWith("#EXTM3U")) return null
                if (body.contains("#EXT-X-STREAM-INF")) {
                    val v = body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return M(u, 99999.0, 0.0, "m3u8")
                    val vu = if (v.startsWith("http")) v else java.net.URI(u).resolve(v).toString()
                    body = ApiFinder.fetchText(vu, ua, "$origin/", 1 shl 17).second.second
                }
                if (!body.contains("#EXT-X-ENDLIST")) return M(u, 99999.0, 0.0, "m3u8")
                val secs = Regex("#EXTINF:([0-9.]+)").findAll(body).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
                return M(u, secs, 0.0, "m3u8")
            } catch (_: Exception) { return null }
        }
        val ms = list.take(12).mapNotNull { mediaOf(it) }
        val long = ms.filter { it.secs >= 1200.0 || it.mb >= 80.0 }
        val best = long.maxByOrNull { if (it.secs > 0) it.secs else it.mb * 10 }
        val shorts = ms.filter { it !in long }
        val bestHost = best?.let { Uri.parse(it.url).host.orEmpty() }
        val bad = shorts.map { Uri.parse(it.url).host.orEmpty() }.filter { it.isNotEmpty() && it != bestHost }.distinct()
        if (bad.isNotEmpty()) CsHints.set(host, "player", "adRe", bad.joinToString("|") { it.replace(".", "\\.") })
        CsHints.set(host, "player", "shortCount", shorts.size)
        if (best != null) {
            val dk = if (best.secs in 1.0..90000.0) " · ${(best.secs / 60).toInt()} dk" else if (best.mb > 0) " · ${best.mb.toInt()} MB" else ""
            CsHints.set(host, "player", "stream", Uri.parse(best.url).host + " · " + best.kind + dk)
            CsHints.set(host, "player", "shortOnly", false)
            if (!Regex("(?i)\\.(m3u8|mp4)(\\?|$)|/master\\.txt").containsMatchIn(best.url)) {
                val gh = Uri.parse(best.url).host.orEmpty()
                val segs = LinkedHashSet<String>()
                for (c in list) { val uu = Uri.parse(c); if (uu.host == gh) uu.pathSegments.firstOrNull()?.let { segs.add(Regex.escape(it)) } }
                CsHints.set(host, "player", "streamRe", gh.replace(".", "\\.") + "/(?:" + segs.joinToString("|") + ")/")
            }
        } else if (shorts.isNotEmpty()) {
            val mx = shorts.maxOf { it.secs }
            CsHints.set(host, "player", "shortOnly", true)
            CsHints.set(host, "player", "stream", "")
            CsHints.set(host, "player", "shortMax", mx.toInt())
        }

    }
}
