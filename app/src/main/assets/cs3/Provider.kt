package @@PKG@@

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

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
        val resp = fetch(url)
        var items = findCards(resp.document)
        if (items.isEmpty()) items = routerCards(url)
        if (items.isEmpty() && page <= 1) throw ErrorLoadingException("Kart bulunamadı (HTTP ${resp.code}, ${resp.text.length} bayt): ${resp.text.take(90).replace('\n', ' ')}")
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

    private val cfKiller by lazy { com.lagradost.cloudstream3.network.CloudflareKiller() }

    /** Önce düz istek; Cloudflare/403/503 engeli görürse CloudflareKiller ile yeniden dener. */
    private suspend fun fetch(url: String): com.lagradost.nicehttp.NiceResponse {
        val hdr = mapOf("Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8")
        var r = app.get(url, referer = mainUrl, headers = hdr)
        val blocked = r.code == 403 || r.code == 503 || r.text.contains("Just a moment", true) || r.text.contains("cf-browser-verification", true)
        if (blocked) r = app.get(url, referer = mainUrl, headers = hdr, interceptor = cfKiller)
        followDomain(r.url)
        return r
    }

    /** Site alan adı değiştirip yönlendiriyorsa (ör. .nl → .mobi) mainUrl'i yeni adrese çeker. */
    private fun followDomain(finalUrl: String) {
        try {
            val h = java.net.URI(finalUrl).host ?: return
            val cur = java.net.URI(mainUrl).host ?: return
            val brand = cur.removePrefix("www.").substringBefore('.').take(8)
            if (h != cur && h.contains(brand, true)) mainUrl = "https://$h"
        } catch (_: Exception) { }
    }

    private val xrwValue = "@@XRW@@"
    private val xhrHeaders: List<Map<String, String>> =
        listOf(emptyMap<String, String>()) + listOf(xrwValue, "XMLHttpRequest", "fetch").distinct().map { mapOf("X-Requested-With" to it) }
    private val streamRegex = Regex("(?i)\\.(m3u8|mp4)(\\?|\\$)|/master\\.txt")
    private val streamHint = "@@STREAM_RE@@"
    private val sniffRegex = Regex("(?i)https?://[^\\s\"'<>\\\\]+/(?:m3u8?|hls|playlist|manifest|master|list)/[^\\s\"'<>\\\\]{20,}" + (if (streamHint.isNotBlank()) "|(?:https?://)?" + streamHint + "[^\\s\"'<>\\\\]*" else ""))
    private val watchRegex = Regex("(?i)^(?!.*(?:doubleclick|googlesyndication|/ads?/|[._-]ads?[._/-]|preroll|promo|trailer|fragman|teaser|twimg|fbcdn)).*(?:\\.(m3u8|mp4)(\\?|\\$)|/master\\.txt" + (if (streamHint.isNotBlank()) "|" + streamHint else "") + ")")
    private val notMedia = Regex("(?i)\\.(js|css|png|jpe?g|webp|gif|svg|vtt|srt|json|woff2?)(\\?|\\$)")

    /** Uzantısız HLS (JW Player vb.): adresi indirip içeriği (#EXTM3U) doğrular, öyleyse oynatma listesi olarak yayar. */
    private suspend fun sniffHls(u: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        val origin = try { java.net.URI(referer).let { it.scheme + "://" + it.host } } catch (_: Exception) { mainUrl }
        val hdr = mapOf("Referer" to referer, "Origin" to origin)
        val body = runCatching { app.get(u, referer = referer, headers = hdr).text }.getOrNull() ?: return false
        if (!body.trimStart().startsWith("#EXTM3U")) return false
        if (!worthIt(u, referer, true)) return false
        M3u8Helper.generateM3u8(name, u, referer, headers = hdr).forEach(callback)
        return true
    }

    private val routerParam = "@@ROUTER@@"
    private val epRegex = Regex("(?i)(\\d+\\s*\\.?\\s*(bölüm|bolum|sezon|episode|season|ep)\\b|\\b(bölüm|bolum|episode|ep|sezon|season)[\\s-]*\\d+|\\d+\\s*x\\s*\\d+)")

    /** İçeriği JSON uç noktasından da veren (SPA) siteler için: adrese router parametresi ekleyip yanıttan HTML çıkarır. */
    private suspend fun routerBody(url: String): String {
        if (routerParam.isBlank()) return ""
        val u = url + (if (url.contains("?")) "&" else "?") + routerParam
        return try { app.get(u, referer = mainUrl, headers = mapOf("X-Requested-With" to xrwValue)).text } catch (_: Exception) { "" }
    }

    private suspend fun routerCards(url: String): List<SearchResponse> = parseSearch(routerBody(url))

    private suspend fun routerDoc(url: String): org.jsoup.nodes.Document? {
        val t = routerBody(url).trim()
        if (t.isEmpty()) return null
        if (!(t.startsWith("{") || t.startsWith("["))) return org.jsoup.Jsoup.parse(t, mainUrl)
        val strs = ArrayList<String>()
        try { jsonStrings(if (t.startsWith("[")) org.json.JSONArray(t) else org.json.JSONObject(t), strs) } catch (_: Exception) { return null }
        val html = strs.filter { it.contains("<") && it.contains(">") }.joinToString("\n")
        return if (html.isBlank()) null else org.jsoup.Jsoup.parse(html, mainUrl)
    }

    private val junkText = Regex("(?i)^(izle|film izle|devamı|detay|incele|oynat|play|tıkla|hd|dublaj|altyazı)$")
    private val junkImg = Regex("(?i)(logo|icon|dublaj|altyazi|flag|badge|avatar|blank|placeholder|loading|spinner|\\.svg|\\.gif)")

    /** Aynı adrese giden bağlantılar arasından (poster bağlantısında yazı olmayabilir) en uygun başlık metnini bulur. */
    private fun sameHrefText(a: Element): String? {
        val raw = a.attr("href")
        return a.ownerDocument()?.select("a[href]")?.filter { it.attr("href") == raw }
            ?.map { it.text().trim() }?.filter { it.length in 3..100 && !junkText.matches(it) }?.maxByOrNull { it.length }
    }

    /** Son çare başlık: adres sonundaki slug'dan ("odyssey-izle" → "Odyssey"). Geçerli adresli kart asla düşmez. */
    private fun slugTitle(href: String): String? {
        val slug = href.trimEnd('/').substringAfterLast('/').substringBefore('?').removeSuffix(".html")
        val t = slug.replace(Regex("(?i)-(izle|full|hd|film|filmi|izle-hd)$"), "").replace('-', ' ').trim()
        return t.takeIf { it.length >= 2 }?.replaceFirstChar { it.uppercase() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (tagName() == "a") this else selectFirst("a") ?: return null
        val title = (@@TITLE_EXPR@@)?.trim()?.takeIf { it.isNotBlank() }
            ?: sameHrefText(anchor)
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: slugTitle(anchor.attr("href"))
            ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null
        val img = select("img").firstOrNull { im -> !junkImg.containsMatchIn(im.attr("data-src") + im.attr("data-original") + im.attr("src")) } ?: selectFirst("img")
        val poster = fixUrlNull(
            listOf("data-src", "data-original", "data-lazy-src", "data-lazy", posterAttr, "src")
                .mapNotNull { k -> img?.attr(k)?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("data:") && !junkImg.containsMatchIn(it) } }
                .firstOrNull()
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
        val primaryBody = try {
            @@SEARCH_CALL@@
            response.text
        } catch (_: Exception) { "" }
        var results = parseSearch(primaryBody)
        if (results.isEmpty()) {
            for (p in searchFallbacks) {
                val u = mainUrl + p.replace("%s", encoded)
                for (h in xhrHeaders) {
                    val body = try { app.get(u, referer = mainUrl, headers = h).text } catch (_: Exception) { "" }
                    results = parseSearch(body)
                    if (results.isNotEmpty()) return results
                }
            }
        }
        return results
    }

    private val plotSel = "@@PLOT_SEL@@"
    private val posterSel = "@@POSTER_SEL@@"
    private val yearSel = "@@YEAR_SEL@@"
    private val seasonRe = Regex("(?i)(\\d+)\\s*\\.?\\s*(sezon|season)|(?:sezon|season)[\\s-]*(\\d+)")
    private val epNumRe = Regex("(?i)(\\d+)\\s*\\.?\\s*(b[öo]l[üu]m|episode|ep)\\b|(?:b[öo]l[üu]m|episode|ep)[\\s-]*(\\d+)")

    private fun cleanTitle(t: String): String {
        var x = t.trim()
        x = x.replace(Regex("\\s*[|\\-–—]\\s*[^|\\-–—]{2,40}$"), "")
        x = x.replace(Regex("(?i)\\s*(full\\s*hd\\s*)?(film(i)?\\s*)?(t[üu]rk[çc]e\\s*(dublaj|altyaz[ıi]l[ıi]?)?\\s*)?(izle|izleyin|izle\\s*hd).*$"), "")
        return x.trim().ifBlank { t.trim() }
    }

    private fun yearOf(d: org.jsoup.nodes.Document, titleText: String): Int? {
        val cands = listOfNotNull(
            if (yearSel.isNotBlank()) d.selectFirst(yearSel)?.text() else null,
            d.selectFirst("a[href*=/yil/], a[href*=/year/], a[href*=/release-year/]")?.text(),
            d.selectFirst("[class*=year], [class*=yil], [class*=release]")?.text()?.takeIf { it.length < 60 },
            titleText
        )
        for (c in cands) Regex("\\b(19[3-9]\\d|20[0-3]\\d)\\b").find(c)?.value?.toIntOrNull()?.let { return it }
        return null
    }

    private fun durationOf(d: org.jsoup.nodes.Document): Int? {
        val t = d.select("[class*=duration], [class*=sure], [class*=runtime], li, span, div")
            .map { it.ownText().trim() }
            .firstOrNull { it.length < 40 && Regex("(?i)\\b\\d{2,3}\\s*(dk|dakika|min)").containsMatchIn(it) } ?: return null
        return Regex("(\\d{2,3})").find(t)?.value?.toIntOrNull()
    }

    private fun scoreOf(d: org.jsoup.nodes.Document): Double? {
        val t = d.selectFirst("[class*=imdb], [class*=rating], [class*=puan], [class*=score]")?.text() ?: return null
        val v = Regex("\\b(10(?:[.,]0)?|\\d[.,]\\d)\\b").find(t)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null
        return v.takeIf { it in 0.1..10.0 }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = fetch(url).document.let { d -> if (d.selectFirst("h1") == null) (routerDoc(url) ?: d) else d }

        val rawTitle = document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: document.title().takeIf { it.isNotBlank() }
            ?: return null
        val title = cleanTitle(rawTitle)
        fun imgOf(e: Element) = listOf("data-src", "data-original", "src").map { e.attr(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
        val poster = fixUrlNull(
            (if (posterSel.isNotBlank()) document.selectFirst(posterSel)?.let { imgOf(it) } else null)
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("[class*=poster] img, [class*=cover] img")?.let { imgOf(it) }
        )
        val plot = (if (plotSel.isNotBlank()) document.selectFirst(plotSel)?.text()?.trim() else null)?.takeIf { it.length > 20 }
            ?: document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        val year = yearOf(document, rawTitle)
        val tags = document.select("a[href*=/tur/], a[href*=/turu/], a[href*=/genre], a[href*=/kategori/], a[href*=/category/]")
            .map { it.text().trim() }.filter { it.length in 2..28 }.distinct().take(8)
        val actors = document.select("a[href*=/oyuncu], a[href*=/actor], a[href*=/cast/], a[href*=/stars/]")
            .map { it.text().trim() }.filter { it.length in 3..40 }.distinct().take(12)
        val recs = document.select("[class*=related] a:has(img), [class*=benzer] a:has(img), [class*=similar] a:has(img), [class*=recommend] a:has(img)")
            .mapNotNull { it.toSearchResult() }.distinctBy { it.url }.filter { it.url != url }.take(12)
        val trailer = document.selectFirst("iframe[src*=youtube], iframe[data-src*=youtube], a[href*=youtube.com/watch], [data-trailer], [data-youtube]")
            ?.let { e -> listOf("src", "data-src", "href", "data-trailer", "data-youtube").map { e.attr(it) }.firstOrNull { it.isNotBlank() } }
            ?.let { fixUrlNull(it) }
        val dur = durationOf(document)
        val sc = scoreOf(document)

        val episodes = if (episodeSelector.isBlank()) emptyList() else
            document.select(episodeSelector).filter { epRegex.containsMatchIn(it.text() + " " + it.attr("href")) }.mapIndexedNotNull { i, ep ->
                val href = fixUrlNull(ep.attr("href")) ?: return@mapIndexedNotNull null
                val tx = ep.text().trim() + " " + ep.attr("href")
                val sn = seasonRe.find(tx)?.let { it.groupValues[1].ifBlank { it.groupValues[3] } }?.toIntOrNull()
                val en = epNumRe.find(tx)?.let { it.groupValues[1].ifBlank { it.groupValues[3] } }?.toIntOrNull()
                newEpisode(url = href, initializer = {
                    this.name = ep.text().trim().ifBlank { "Bölüm ${i + 1}" }
                    this.season = sn ?: 1
                    this.episode = en ?: (i + 1)
                })
            }.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

        return if (episodes.size > 1) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.tags = tags; this.duration = dur; this.recommendations = recs
                sc?.let { this.score = Score.from(it, 10) }
                if (actors.isNotEmpty()) addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.plot = plot; this.year = year
                this.tags = tags; this.duration = dur; this.recommendations = recs
                sc?.let { this.score = Score.from(it, 10) }
                if (actors.isNotEmpty()) addActors(actors)
                trailer?.let { addTrailer(it) }
            }
        }
    }

    /** Sayfa betiklerini Rhino ile çalıştırıp (sahte DOM ile) oluşan değişkenlerden / jwplayer.setup'tan akış adreslerini toplar. */
    @Suppress("DEPRECATION")
    private fun rhinoDecode(doc: org.jsoup.nodes.Document, pageUrl: String): List<String> {
        val host = runCatching { java.net.URI(pageUrl).host }.getOrNull() ?: "localhost"
        val polyfill = """
            var window = globalThis || this; var global = window; var __jw = [];
            var document = { getElementById: function() { return { style: {}, addEventListener: function() {} }; },
                querySelector: function() { return { style: {}, addEventListener: function() {} }; }, querySelectorAll: function() { return []; },
                createElement: function() { return { style: {}, setAttribute: function() {}, addEventListener: function() {} }; }, cookie: "",
                addEventListener: function() {}, removeEventListener: function() {},
                body: { appendChild: function() {}, removeChild: function() {} }, head: { appendChild: function() {}, removeChild: function() {} },
                location: { href: "$pageUrl", hostname: "$host" } };
            var location = document.location;
            var navigator = { userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36", language: "tr-TR" };
            var screen = { width: 1920, height: 1080 };
            var ${'$'} = function() { return { ready: function(){}, prepend: function(){}, append: function(){}, on: function(){}, css: function(){}, attr: function(){}, hide: function(){}, show: function(){} }; };
            ${'$'}.ajax = function(){};
            var jwplayer = function() { return { setup: function(o){ try { __jw.push(JSON.stringify(o)); } catch(e){} }, on: function(){}, once: function(){}, addButton: function(){} }; };
            jwplayer.key = "";
            var console = { log: function() {}, error: function() {}, warn: function() {} };
            var _b = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=';
            if (typeof btoa === 'undefined') { btoa = function(input) { var str = String(input); var output = '';
                for (var block = 0, charCode, idx = 0, map = _b; str.charAt(idx | 0) || (map = '=', idx % 1); output += map.charAt(63 & block >> 8 - idx % 1 * 8)) {
                    charCode = str.charCodeAt(idx += 3/4); if (charCode > 0xFF) throw new Error('btoa'); block = block << 8 | charCode; } return output; }; }
            if (typeof atob === 'undefined') { atob = function(input) { var str = String(input).replace(/[=]+${'$'}/, ''); if (str.length % 4 == 1) throw new Error('atob'); var output = '';
                for (var bc = 0, bs = 0, buffer, idx = 0; buffer = str.charAt(idx++); ~(buffer = _b.indexOf(buffer)) && (bs = bc % 4 ? bs * 64 + buffer : buffer, bc++ % 4) ? output += String.fromCharCode(255 & bs >> (-2 * bc & 6)) : 0) {} return output; }; }
        """.trimIndent()
        val collector = """
            var __l = [];
            for (var k in this) { try { var v = this[k]; if (typeof v === 'string' && v.indexOf('http') === 0 && /m3u8|\.txt|master|\/hls\/|\.mp4|\/m3u/.test(v) && __l.indexOf(v) === -1) __l.push(v); } catch(e){} }
            __l.join('|||') + '|||' + __jw.join('|||');
        """.trimIndent()
        // Rhino derleme sırasında yok; çalışma anında uygulamadan yansıma ile kullanılır
        val ctxCls = runCatching { Class.forName("org.mozilla.javascript.Context") }.getOrNull() ?: return emptyList()
        val scopeCls = Class.forName("org.mozilla.javascript.Scriptable")
        val cx = ctxCls.getMethod("enter").invoke(null)
        val evalM = ctxCls.getMethod("evaluateString", scopeCls, String::class.java, String::class.java, Int::class.javaPrimitiveType, Any::class.java)
        fun ev(scope: Any, code: String, n: String): Any? = evalM.invoke(cx, scope, code, n, 1, null)
        return try {
            ctxCls.getMethod("setOptimizationLevel", Int::class.javaPrimitiveType).invoke(cx, -1)
            val scope = ctxCls.getMethod("initStandardObjects").invoke(cx)!!
            ev(scope, polyfill, "polyfill")
            doc.select("script").forEach { s ->
                val code = s.data().trim()
                if (code.isNotBlank() && !Regex("google|analytics|cast_sender|ld\\+json|gtag").containsMatchIn(code.take(300))) {
                    try { ev(scope, code, "script") } catch (_: Throwable) {}
                }
            }
            val res = ev(scope, collector, "collector")?.toString().orEmpty().replace("\\/", "/")
            Regex("https?://[^\\s\"'<>\\\\|]+").findAll(res).map { it.value }
                .filter { Regex("(?i)m3u8|\\.txt|master|/hls/|\\.mp4|/m3u").containsMatchIn(it) && !notMedia.containsMatchIn(it) }.distinct().toList()
        } catch (_: Throwable) { emptyList() } finally { runCatching { ctxCls.getMethod("exit").invoke(null) } }
    }

    /** Oynatıcı sayfasındaki "hash" değerini /video/ah/ benzeri uca POST edip dönen akış adreslerini toplar. */
    private suspend fun ajaxHashStreams(text: String, pageUrl: String): List<String> {
        val hash = Regex("\"hash\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            ?: Regex("hash\\s*:\\s*['\"]([A-Za-z0-9]{24,64})['\"]").find(text)?.groupValues?.get(1) ?: return emptyList()
        val path = Regex("\"url\"\\s*:\\s*\"([^\"]+ah/)\"").find(text)?.groupValues?.get(1)
            ?: Regex("url\\s*:\\s*['\"]([^'\"]+ah/)['\"]").find(text)?.groupValues?.get(1) ?: "/video/ah/"
        val origin = Regex("(https?://[^/]+)").find(pageUrl)?.groupValues?.get(1) ?: return emptyList()
        val api = if (path.startsWith("http")) path else origin + "/" + path.trimStart('/')
        val body = runCatching {
            app.post(api, data = mapOf("hash" to hash), referer = pageUrl,
                headers = mapOf("Origin" to origin, "X-Requested-With" to "XMLHttpRequest")).text
        }.getOrNull()?.replace("\\/", "/") ?: return emptyList()
        return Regex("https?://[^\\s\"'<>\\\\]+").findAll(body).map { it.value }
            .filter { Regex("(?i)m3u8|\\.txt|master|/hls/|\\.mp4|/m3u").containsMatchIn(it) && !notMedia.containsMatchIn(it) }.distinct().toList()
    }

    private fun decodeLayers(text: String): String {
        val extra = StringBuilder()
        fun accept(s: String?) {
            if (s != null && s.contains("http") && Regex("(?i)m3u8|\\.mp4|/m3u|master|iframe").containsMatchIn(s)) extra.append('\n').append(s.replace("\\/", "/"))
        }
        fun b64(s: String) = runCatching { String(android.util.Base64.decode(s, android.util.Base64.DEFAULT)) }.getOrNull()
        Regex("[\"']([A-Za-z0-9+/=%_\\-]{40,})[\"']").findAll(text).take(60).forEach { m ->
            val v = m.groupValues[1]
            accept(b64(v)); accept(b64(v.reversed())); accept(b64(v.replace('-', '+').replace('_', '/')))
            if (v.length % 2 == 0 && v.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' })
                accept(runCatching { String(v.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) }.getOrNull())
            accept(v.map { c -> when (c) { in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26; in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26; else -> c } }.joinToString(""))
            if (v.contains('%')) accept(runCatching { java.net.URLDecoder.decode(v, "UTF-8") }.getOrNull())
        }
        Regex("String\\.fromCharCode\\(([0-9, ]{20,})\\)").findAll(text).take(20).forEach { m ->
            accept(runCatching { m.groupValues[1].split(',').map { it.trim().toInt().toChar() }.joinToString("") }.getOrNull())
        }
        return if (extra.isEmpty()) text else text + extra
    }

    // ---- Video çözümleme (sırayla): bilinen barındırıcılar, iç içe iframe'ler (2 seviye),
    // ---- packed JS, CryptoJS AES blokları, base64 gömülü linkler, doğrudan m3u8/mp4
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val page = fetch(data)
        var ok = scanSource(page.text, page.document, data, 0, subtitleCallback, callback)
        if (!ok) routerDoc(data)?.let { ok = scanSource(it.outerHtml(), it, data, 0, subtitleCallback, callback) }
        if (!ok) {
            // Son çare: sayfayı gerçek bir WebView'da oynatıp ağdaki akış isteğini yakala
            val got = runCatching {
                app.get(data, referer = mainUrl, interceptor = com.lagradost.cloudstream3.network.WebViewResolver(watchRegex)).url
            }.getOrNull()
            if (got != null && watchRegex.containsMatchIn(got) && (emitStreams(got, data, callback) || sniffHls(got, data, callback))) ok = true
        }
        return ok
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
        text = decodeLayers(text)
        // Altyazılar: .vtt / .srt adresleri
        Regex("https?:[^\"'\\s<>\\\\]+\\.(?:vtt|srt)[^\"'\\s<>\\\\]*").findAll(text).map { it.value }.distinct().take(10).forEach { u ->
            val lang = if (Regex("(?i)(^|[^a-z])(tr|tur|turk|turkce)([^a-z]|$)").containsMatchIn(u.substringAfterLast('/'))) "Türkçe" else "Altyazı"
            subtitleCallback(SubtitleFile(lang, u))
        }
        if (emitStreams(text, referer, callback)) found = true

        if (!found) {
            val viaJs = rhinoDecode(doc ?: org.jsoup.Jsoup.parse(text), referer) + ajaxHashStreams(text, referer)
            for (c in viaJs.distinct().take(8)) if (emitStreams(c, referer, callback) || sniffHls(c, referer, callback)) { found = true; break }
        }

        // Uzantısız HLS adayları: JW Player file/src alanları ve /m3u/, /list/, /hls/ gibi yollar
        if (!found) {
            val jw = Regex("[\"']?(?:file|src|source)[\"']?\\s*:\\s*[\"'](https?://[^\"']+)[\"']").findAll(text).map { it.groupValues[1] }
            val cands = (sniffRegex.findAll(text).map { it.value } + jw).distinct()
                .map { if (it.startsWith("http")) it else "https://$it" }
                .filter { !notMedia.containsMatchIn(it) }.take(8).toList()
            for (c in cands) if (sniffHls(c, referer, callback)) { found = true; break }
        }

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
            val frames = d.select("iframe, [data-embed], [data-video], [data-player], nav.card-nav a, a.card-nav-link, button.card-nav-link, [data-url]")
                .mapNotNull { el -> attrs.firstNotNullOfOrNull { k -> el.attr(k).takeIf { it.isNotBlank() } } }
                .map { fixUrl(it) }.distinct()
            for (frame in frames) {
                if (loadExtractor(frame, referer, subtitleCallback, callback)) { found = true; continue }
                val inner = runCatching { app.get(frame, referer = referer).text }.getOrNull()
                if (inner != null && scanSource(inner, null, frame, depth + 1, subtitleCallback, callback)) { found = true; continue }
                // Son çare: oynatıcıyı gerçek bir WebView'da çalıştırıp ağdaki m3u8/mp4 isteğini yakala
                // (karmaşık/gizlenmiş oynatıcı betiklerini kendimiz çözmek yerine tarayıcıya çözdürür)
                if (depth == 0 && !found) {
                    val got = runCatching {
                        app.get(frame, referer = referer, interceptor = com.lagradost.cloudstream3.network.WebViewResolver(watchRegex)).url
                    }.getOrNull()
                    if (got != null && watchRegex.containsMatchIn(got) && (emitStreams(got, frame, callback) || sniffHls(got, frame, callback))) found = true
                }
            }
        }
        return found
    }

    // HLS: AES-128 (açık anahtarlı) şifrelemeyi oynatıcı kendisi çözer; burada yalnızca
    // doğru Referer/Origin başlıklarını veriyoruz. DRM (Widevine/FairPlay) desteklenmez.
    private val adHint = "@@AD_RE@@"
    private val adRegex = Regex("(?i)(doubleclick|googlesyndication|adservice|adnxs|/ads?/|[._-]ads?[._/-]|preroll|promo|popunder|banner|video\\.twimg|fbcdn|cdninstagram|tiktok|fragman|trailer|teaser)" + (if (adHint.isNotBlank()) "|$adHint" else ""))

    /** Reklam / kısa klip / fragman elemesi: HLS toplam süresine ya da mp4 boyutuna bakar; ölçülemezse geçirir. */
    private suspend fun worthIt(u: String, referer: String, hls: Boolean): Boolean {
        if (adRegex.containsMatchIn(u)) return false
        val hdr = mapOf("Referer" to referer)
        return try {
            if (hls) {
                var body = app.get(u, referer = referer, headers = hdr).text
                if (body.contains("#EXT-X-STREAM-INF")) {
                    val v = body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return true
                    val vu = if (v.startsWith("http")) v else java.net.URI(u).resolve(v).toString()
                    body = app.get(vu, referer = referer, headers = hdr).text
                }
                if (!body.contains("#EXT-X-ENDLIST")) return true
                val secs = Regex("#EXTINF:([0-9.]+)").findAll(body).sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
                secs <= 0.0 || secs >= 600.0
            } else {
                val r = app.get(u, referer = referer, headers = hdr + mapOf("Range" to "bytes=0-1"))
                val total = r.headers["Content-Range"]?.substringAfter('/')?.toLongOrNull()
                total == null || total >= 40_000_000L
            }
        } catch (_: Exception) { true }
    }

    private suspend fun emitStreams(src: String, referer: String, callback: (ExtractorLink) -> Unit): Boolean {
        var ok = false
        val origin = runCatching { java.net.URI(referer).let { "${it.scheme}://${it.host}" } }.getOrDefault(mainUrl)
        val hdr = mapOf("Referer" to referer, "Origin" to origin)
        Regex("https?:[^\"'\\s<>\\\\]+\\.m3u8[^\"'\\s<>\\\\]*").findAll(src).map { it.value }.distinct().forEach { u ->
            if (worthIt(u, referer, true)) {
                M3u8Helper.generateM3u8(name, u, referer, headers = hdr).forEach(callback)
                ok = true
            }
        }
        Regex("https?:[^\"'\\s<>\\\\]+\\.mp4[^\"'\\s<>\\\\]*").findAll(src).map { it.value }.distinct().forEach { u ->
            if (worthIt(u, referer, false)) {
                callback(newExtractorLink(name, name, u, ExtractorLinkType.VIDEO) {
                    this.referer = referer
                    this.headers = hdr
                    this.quality = Qualities.Unknown.value
                })
                ok = true
            }
        }
        Regex("https?:[^\"'\\s<>\\\\]+/master\\.txt[^\"'\\s<>\\\\]*").findAll(src).map { it.value }.distinct().forEach { u ->
            if (worthIt(u, referer, true)) {
                M3u8Helper.generateM3u8(name, u, referer, headers = hdr).forEach(callback)
                ok = true
            }
        }
        return ok
    }
@@CRYPTO_FUN@@}
