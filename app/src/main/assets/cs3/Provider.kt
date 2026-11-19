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
        val items = document.select(cardSelector).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
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

    override suspend fun search(query: String): List<SearchResponse> {
        // Arama ucu: @@SEARCH_NOTE@@
        val url = @@SEARCH_URL@@
        val response = app.get(url, referer = mainUrl)
        // Yanıt JSON ise bir data class tanımlayıp şöyle okuyabilirsin:
        // val json = response.parsedSafe<AramaYaniti>()
        return response.document.select(cardSelector).mapNotNull { it.toSearchResult() }.distinctBy { it.url }
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
