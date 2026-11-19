package @@PKG@@

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * @@SITE@@ için CloudStream sağlayıcısı.
 * Kalkan Tarayıcı ile @@DATE@@ tarihinde oluşturuldu.
 *
 * Seçiciler Kalkan'ın sayfa analizinden dolduruldu. Analiz yapılmayan kısımlar için
 * genel yedekler kullanılır; çalışmayan bir yer olursa aşağıdaki seçici sabitlerini düzelt.
 * Kalkan'ın bulduğu uçlar: @@NAME@@Endpoints.kt ve ENDPOINTS.md
 *
 * Analiz özeti:
@@ANALYSIS_NOTE@@
 */
class @@NAME@@Provider : MainAPI() {
    override var mainUrl = "@@MAIN_URL@@"
    override var name = "@@SITE@@"
    override var lang = "@@LANG@@"
    override val hasMainPage = true
    override val supportedTypes = setOf(@@TVTYPES_KT@@)

    // Ana sayfa bölümleri: adres to başlık. Adreste {page} varsa sayfa numarası yazılır,
    // yoksa 2. sayfadan itibaren /page/N/ eklenir (WordPress düzeni).
    override val mainPage = mainPageOf(
@@MAIN_PAGES@@
    )

    // ---- Seçiciler (Kalkan analizi) ----
    private val cardSelector = "@@CARD_SEL@@"
    private val posterAttr = "@@POSTER_ATTR@@"
    private val episodeSelector = "@@EPISODE_SEL@@"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.contains("{page}") -> request.data.replace("{page}", page.toString())
            page > 1 -> request.data.trimEnd('/') + "/page/$page/"
            else -> request.data
        }
        val document = app.get(url, referer = mainUrl).document
        val items = findCards(document)
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    private val badPath = Regex("(?i)/(oyuncu|actor|cast|yonetmen|director|etiket|tag|author|yazar|user|uye|login|register|iletisim|contact|category|kategori|yil|ulke|page)(/|$)")

    /** Seçici işe yaramazsa ya da oyuncu/etiket gibi yanlış bağlantılar dönerse, görselli film bağlantılarını kendisi bulur. */
    private fun findCards(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val host = try { java.net.URI(mainUrl).host.removePrefix("www.") } catch (_: Exception) { "" }
        fun ok(r: SearchResponse): Boolean = try {
            val u = java.net.URI(r.url); u.host.removePrefix("www.") == host && u.path.length > 2 && !badPath.containsMatchIn(u.path)
        } catch (_: Exception) { false }
        val primary = doc.select(cardSelector).mapNotNull { it.toSearchResult() }.distinctBy { it.url }.filter { ok(it) }
        if (primary.size >= 3) return primary
        val generic = doc.select("a[href]:has(img)").mapNotNull { it.toSearchResult() }.distinctBy { it.url }.filter { ok(it) }
        return if (generic.size > primary.size) generic else primary
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a") ?: return null
        val title = (@@TITLE_EXPR@@)?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr(posterAttr)?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")
        )
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    // Birincil arama boş dönerse sırayla denenen yaygın arama adresleri (%s = sorgu)
    private val searchFallbacks = listOf(
        "/?s=%s", "/search?q=%s", "/search/?q=%s", "/arama?q=%s", "/arama/?q=%s", "/ara?q=%s",
        "/index.php?do=search&subaction=search&story=%s", "/search/%s", "/arama/%s", "/ara/%s",
        "/?q=%s", "/?search=%s", "/search?query=%s", "/api/search?q=%s", "/ajax/search?q=%s"
    )

    private fun jsonStrings(node: Any?, out: MutableList<String>) {
        when (node) {
            is org.json.JSONObject -> node.keys().forEach { jsonStrings(node.opt(it), out) }
            is org.json.JSONArray -> for (i in 0 until node.length()) jsonStrings(node.opt(i), out)
            is String -> out.add(node)
        }
    }

    /** Arama yanıtını (HTML ya da JSON içinde HTML/nesne listesi) sonuç listesine çevirir. */
    private fun parseSearch(body: String): List<SearchResponse> {
        val t = body.trim()
        if (t.isEmpty()) return emptyList()
        val docs = ArrayList<org.jsoup.nodes.Document>()
        if (t.startsWith("{") || t.startsWith("[")) {
            val strs = ArrayList<String>()
            try { jsonStrings(if (t.startsWith("[")) org.json.JSONArray(t) else org.json.JSONObject(t), strs) } catch (_: Exception) {}
            val html = strs.filter { it.contains("<") && it.contains(">") }.joinToString("\n")
            if (html.isNotBlank()) docs.add(org.jsoup.Jsoup.parse(html, mainUrl))
            // Nesne listesi: {title/name, url/link/slug, poster/image}
            try {
                val arr = ArrayList<org.json.JSONObject>()
                fun walk(n: Any?) {
                    when (n) {
                        is org.json.JSONObject -> { if ((n.has("title") || n.has("name")) && (n.has("url") || n.has("link") || n.has("slug") || n.has("uri"))) arr.add(n) else n.keys().forEach { walk(n.opt(it)) } }
                        is org.json.JSONArray -> for (i in 0 until n.length()) walk(n.opt(i))
                    }
                }
                walk(if (t.startsWith("[")) org.json.JSONArray(t) else org.json.JSONObject(t))
                val res = arr.mapNotNull { o ->
                    val ttl = o.optString("title").ifBlank { o.optString("name") }.trim()
                    val raw = listOf("url", "link", "uri", "slug").map { o.optString(it) }.firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                    val href = fixUrlNull(raw) ?: return@mapNotNull null
                    val img = listOf("poster", "image", "img", "thumb", "thumbnail", "cover").map { o.optString(it) }.firstOrNull { it.isNotBlank() }
                    if (ttl.isBlank()) null else newMovieSearchResponse(ttl, href, TvType.Movie) { this.posterUrl = fixUrlNull(img) }
                }
                if (res.isNotEmpty()) return res.distinctBy { it.url }
            } catch (_: Exception) {}
        } else docs.add(org.jsoup.Jsoup.parse(t, mainUrl))
        for (d in docs) {
            val r = findCards(d)
            if (r.isNotEmpty()) return r
        }
        return emptyList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Arama ucu: @@SEARCH_NOTE@@
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val xhr = mapOf("X-Requested-With" to "XMLHttpRequest")
        val primaryBody = try {
            @@SEARCH_CALL@@
            response.text
        } catch (_: Exception) { "" }
        var results = parseSearch(primaryBody)
        if (results.isEmpty()) {
            for (p in searchFallbacks) {
                val u = mainUrl + p.replace("%s", encoded)
                for (h in listOf(emptyMap(), xhr)) {
                    val body = try { app.get(u, referer = mainUrl, headers = h).text } catch (_: Exception) { "" }
                    results = parseSearch(body)
                    if (results.isNotEmpty()) return results
                }
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("(19|20)\\d{2}")
            .find(document.selectFirst("[class*=year], [class*=yil], [class*=release]")?.text() ?: "")?.value?.toIntOrNull()

        val episodes = if (episodeSelector.isBlank()) emptyList() else
            document.select(episodeSelector).mapIndexedNotNull { i, ep ->
                val href = fixUrlNull(ep.attr("href")) ?: return@mapIndexedNotNull null
                newEpisode(url = href, initializer = {
                    this.name = ep.text().trim().ifBlank { "Bölüm ${i + 1}" }
                    this.episode = i + 1
                })
            }.distinctBy { it.data }

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    // ---- Video çözümleme (sırayla): bilinen barındırıcılar, iç içe iframe'ler (2 seviye),
    // ---- packed JS, CryptoJS AES blokları, base64 gömülü linkler, doğrudan m3u8/mp4
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = app.get(data, referer = mainUrl)
        return scanSource(page.text, page.document, data, 0, subtitleCallback, callback)
    }

    private suspend fun scanSource(
        raw: String,
        doc: org.jsoup.nodes.Document?,
        referer: String,
        depth: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        var text = raw.replace("\\/", "/")

        // packed JS (eval(function(p,a,c,k,e,d)...)
        if (text.contains("eval(function(p,a,c,k,e,d)")) {
            runCatching { JsUnpacker(text).unpack() }.getOrNull()?.let { text += "\n" + it.replace("\\/", "/") }
        }
@@CRYPTO_CALL@@
        if (emitStreams(text, referer, callback)) found = true

        // base64 içinde saklanan linkler
        Regex("[\"']([A-Za-z0-9+/=]{40,})[\"']").findAll(text).forEach { m ->
            val dec = runCatching { String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT)) }.getOrNull()
            if (dec != null && dec.startsWith("http")) {
                if (loadExtractor(dec, referer, subtitleCallback, callback) || emitStreams(dec, referer, callback)) found = true
            }
        }

        // iframe / data-* gömülü oynatıcılar
        if (depth < 2) {
            val d = doc ?: org.jsoup.Jsoup.parse(text)
            val attrs = listOf("src", "data-src", "data-embed", "data-video", "data-player", "data-url")
            val frames = d.select("iframe, [data-embed], [data-video], [data-player]")
                .mapNotNull { el -> attrs.firstNotNullOfOrNull { k -> el.attr(k).takeIf { it.isNotBlank() } } }
                .map { fixUrl(it) }.distinct()
            for (frame in frames) {
                if (loadExtractor(frame, referer, subtitleCallback, callback)) {
                    found = true
                } else {
                    val inner = runCatching { app.get(frame, referer = referer).text }.getOrNull() ?: continue
                    if (scanSource(inner, null, frame, depth + 1, subtitleCallback, callback)) found = true
                }
            }
        }
        return found
    }

    // HLS: AES-128 (açık anahtarlı) şifrelemeyi oynatıcı kendisi çözer; burada yalnızca
    // doğru Referer/Origin başlıklarını veriyoruz. DRM (Widevine/FairPlay) desteklenmez.
    private suspend fun emitStreams(src: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var ok = false
        val origin = runCatching { java.net.URI(referer).let { "${it.scheme}://${it.host}" } }.getOrDefault(mainUrl)
        val hdr = mapOf("Referer" to referer, "Origin" to origin)
        Regex("https?:[^\"'\\s<>\\\\]+\\.m3u8[^\"'\\s<>\\\\]*").findAll(src).map { it.value }.distinct().forEach { u ->
            M3u8Helper.generateM3u8(name, u, referer, headers = hdr).forEach(callback)
            ok = true
        }
        Regex("https?:[^\"'\\s<>\\\\]+\\.mp4[^\"'\\s<>\\\\]*").findAll(src).map { it.value }.distinct().forEach { u ->
            callback(newExtractorLink(name, name, u, ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.headers = hdr
                this.quality = Qualities.Unknown.value
            })
            ok = true
        }
        return ok
    }
@@CRYPTO_FUN@@}
