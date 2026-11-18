package @@PKG@@

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * @@SITE@@ için CloudStream sağlayıcı iskeleti.
 * Kalkan Tarayıcı ile @@DATE@@ tarihinde oluşturuldu.
 *
 * Bu dosya olduğu gibi derlenir, ancak "TODO" ile işaretli seçiciler (CSS selector)
 * sitenin HTML yapısına göre doldurulana kadar sonuç döndürmez.
 * Kalkan'ın bulduğu uçlar: @@NAME@@Endpoints.kt ve ENDPOINTS.md
 *
 * İpucu: Kalkan'da Araçlar → Öğe gizle / Kaynak kodu ile sayfadaki öğelerin
 * sınıf adlarını bulup aşağıdaki seçicilere yazabilirsin.
 */
class @@NAME@@Provider : MainAPI() {
    override var mainUrl = "@@MAIN_URL@@"
    override var name = "@@SITE@@"
    override var lang = "@@LANG@@"
    override val hasMainPage = true
    override val supportedTypes = setOf(@@TVTYPES_KT@@)

    // Ana sayfa bölümleri: adres to başlık. Sayfa numarası için adreste {page} kullan.
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Ana sayfa",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.replace("{page}", page.toString())
        val document = app.get(url, referer = mainUrl).document
        // TODO: Ana sayfadaki her içerik kartını seçen seçici
        val items = document.select("TODO-kart").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request, items, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // TODO: Kartın içindeki başlık, bağlantı ve afiş seçicileri
        val title = selectFirst("TODO-baslik")?.text()?.trim()
            ?: selectFirst("a")?.attr("title")?.trim()?.ifBlank { null }
            ?: return null
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        })
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
        return response.document.select("TODO-kart").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = mainUrl).document

        // TODO: Detay sayfası seçicileri
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")
        val year = Regex("(19|20)\\d{2}")
            .find(document.selectFirst("TODO-yil")?.text() ?: "")?.value?.toIntOrNull()

        // TODO: Dizi ise bölüm bağlantılarını seçen seçici
        val episodes = document.select("TODO-bolum").mapNotNull { ep ->
            val href = fixUrlNull(ep.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            newEpisode(url = href, initializer = { this.name = ep.text().trim() })
        }

        return if (episodes.isNotEmpty()) {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = mainUrl).document
        var found = false

        // Sayfadaki gömülü oynatıcıları CloudStream'in hazır çözücülerine gönderir
        document.select("iframe[src], iframe[data-src]").forEach { frame ->
            val src = fixUrlNull(frame.attr("src").ifBlank { frame.attr("data-src") }) ?: return@forEach
            if (loadExtractor(src, data, subtitleCallback, callback)) found = true
        }

        // TODO: Oynatıcı farklı yükleniyorsa (API, JSON vb.) burada ele al.
        // Kalkan'ın canlı yakaladığı uçlar için bkz. @@NAME@@Endpoints
        return found
    }
}
