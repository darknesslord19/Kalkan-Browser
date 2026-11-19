package @@PKG@@

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * @@SITE@@ için "API modu" sağlayıcısı (Kalkan Tarayıcı @@DATE@@).
 * Site HTML kazımak yerine TMDB biçimli bir JSON uçtan beslendiği için katalog, arama ve detay
 * doğrudan JSON'dan okunur: @@TMDB_BASE@@
 * Video akışı sitenin kendi izleme sayfasından WebView ile yakalanır (siteye özel şifreli uçlar
 * otomatik çözülemez; ENDPOINTS.md'ye bak).
 */
class @@NAME@@Provider : MainAPI() {
    override var mainUrl = "@@MAIN_URL@@"
    override var name = "@@SITE@@"
    override var lang = "@@LANG@@"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val api = "@@TMDB_BASE@@"
    private val origin = "@@MAIN_URL@@"
    private val img = "https://image.tmdb.org/t/p"
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val hdr = mapOf("User-Agent" to ua, "Origin" to origin, "Referer" to "$origin/", "Accept" to "application/json")

    override val mainPage = mainPageOf(
        "movie/popular" to "Filmler",
        "trending/movie/week" to "Trend Filmler",
        "tv/popular" to "Diziler",
        "trending/tv/week" to "Trend Diziler",
        "movie/top_rated" to "En Beğenilen Filmler",
        "tv/top_rated" to "En Beğenilen Diziler"
    )

    private suspend fun get(path: String, query: String = ""): JSONObject {
        val sep = if (path.contains('?')) "&" else "?"
        val url = "$api/$path${sep}language=tr-TR${if (query.isNotEmpty()) "&$query" else ""}"
        return JSONObject(app.get(url, headers = hdr).text)
    }

    private fun pic(p: String?, size: String = "w500") = p?.takeIf { it.startsWith("/") }?.let { "$img/$size$it" }

    private fun toResult(o: JSONObject, forced: String? = null): SearchResponse? {
        val type = forced ?: o.optString("media_type")
        if (type != "movie" && type != "tv") return null
        val id = o.optInt("id").takeIf { it > 0 } ?: return null
        val title = o.optString(if (type == "tv") "name" else "title").ifBlank { o.optString(if (type == "tv") "original_name" else "original_title") }
        if (title.isBlank()) return null
        val year = o.optString(if (type == "tv") "first_air_date" else "release_date").take(4).toIntOrNull()
        val url = "$mainUrl/#$type/$id"
        return if (type == "tv") newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = pic(o.optString("poster_path")); this.year = year }
        else newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = pic(o.optString("poster_path")); this.year = year }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val type = if (request.data.contains("tv")) "tv" else "movie"
        val j = get(request.data, "page=$page")
        val arr = j.optJSONArray("results") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { toResult(arr.getJSONObject(it), type) }
        return newHomePageResponse(request.name, items, hasNext = page < j.optInt("total_pages", 1))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val out = ArrayList<SearchResponse>()
        for (page in 1..2) {
            val j = get("search/multi", "query=$q&page=$page&include_adult=false")
            val arr = j.optJSONArray("results") ?: break
            for (i in 0 until arr.length()) toResult(arr.getJSONObject(i))?.let { out.add(it) }
            if (page >= j.optInt("total_pages", 1)) break
        }
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        val m = Regex("#(movie|tv)/(\\d+)").find(url) ?: return null
        val type = m.groupValues[1]; val id = m.groupValues[2].toInt()
        val d = get("$type/$id", "append_to_response=external_ids,credits,recommendations")
        val title = d.optString(if (type == "tv") "name" else "title").ifBlank { d.optString(if (type == "tv") "original_name" else "original_title") }
        val year = d.optString(if (type == "tv") "first_air_date" else "release_date").take(4).toIntOrNull()
        val imdb = d.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") } ?: d.optString("imdb_id").takeIf { it.startsWith("tt") }
        val tags = d.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getJSONObject(it).optString("name") } }
        val actors = d.optJSONObject("credits")?.optJSONArray("cast")?.let { c -> (0 until minOf(c.length(), 15)).map { c.getJSONObject(it).optString("name") }.filter { it.isNotBlank() } }
        val recs = d.optJSONObject("recommendations")?.optJSONArray("results")?.let { r -> (0 until r.length()).mapNotNull { toResult(r.getJSONObject(it), type) } }
        fun data(s: Int, e: Int) = JSONObject().put("type", type).put("id", id).put("s", s).put("e", e).toString()

        if (type == "movie") {
            return newMovieLoadResponse(title, url, TvType.Movie, data(1, 1)) {
                posterUrl = pic(d.optString("poster_path")); backgroundPosterUrl = pic(d.optString("backdrop_path"), "original")
                this.year = year; plot = d.optString("overview").ifBlank { null }; this.tags = tags
                duration = d.optInt("runtime").takeIf { it > 0 }; recommendations = recs
                score = d.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { Score.from(it, 10) }
                if (!actors.isNullOrEmpty()) addActors(actors)
                addImdbId(imdb); addTMDbId(id.toString())
            }
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val seasons = d.optJSONArray("seasons")?.let { s -> (0 until s.length()).map { s.getJSONObject(it).optInt("season_number") }.filter { it > 0 } } ?: emptyList()
        val episodes = seasons.amap { sn ->
            val season = runCatching { get("tv/$id/season/$sn") }.getOrNull() ?: return@amap emptyList<Episode>()
            val eps = season.optJSONArray("episodes") ?: return@amap emptyList<Episode>()
            (0 until eps.length()).mapNotNull { i ->
                val e = eps.getJSONObject(i)
                val no = e.optInt("episode_number"); val air = e.optString("air_date")
                if (no <= 0 || air.isBlank() || air > today) return@mapNotNull null
                newEpisode(data(sn, no)) {
                    this.name = e.optString("name").ifBlank { "Bölüm $no" }; this.season = sn; this.episode = no
                    this.posterUrl = pic(e.optString("still_path"), "w300"); this.description = e.optString("overview").ifBlank { null }
                }
            }
        }.flatten().sortedWith(compareBy({ it.season }, { it.episode }))
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = pic(d.optString("poster_path")); backgroundPosterUrl = pic(d.optString("backdrop_path"), "original")
            this.year = year; plot = d.optString("overview").ifBlank { null }; this.tags = tags; recommendations = recs
            score = d.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { Score.from(it, 10) }
            if (!actors.isNullOrEmpty()) addActors(actors)
            addImdbId(imdb); addTMDbId(id.toString())
        }
    }

    // Akış: sitenin izleme sayfasını gerçek bir WebView'da açıp ağdaki m3u8/mp4 isteğini yakalar.
    private val watchRe = Regex("(?i)^(?!.*(?:doubleclick|googlesyndication|/ads?/|preroll|trailer|fragman|teaser)).*(?:\\.m3u8|\\.mp4|/master\\.txt)")

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val o = runCatching { JSONObject(data) }.getOrNull() ?: return false
        val type = o.optString("type", "movie"); val id = o.optInt("id"); val s = o.optInt("s", 1); val e = o.optInt("e", 1)
        val pages = listOf(
            "$origin/watch-now?type=$type&id=$id" + (if (type == "tv") "&season=$s&episode=$e" else ""),
            if (type == "tv") "$origin/tv/$id/$s/$e" else "$origin/movie/$id",
            "$origin/watch/$type/$id" + (if (type == "tv") "/$s/$e" else ""),
            "$origin/embed/$type/$id" + (if (type == "tv") "/$s/$e" else "")
        )
        for (p in pages) {
            val got = runCatching { app.get(p, referer = "$origin/", interceptor = WebViewResolver(watchRe)).url }.getOrNull() ?: continue
            if (!watchRe.containsMatchIn(got)) continue
            callback(newExtractorLink(name, name, got, if (got.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.headers = mapOf("Origin" to origin, "Referer" to "$origin/", "User-Agent" to ua)
            })
            return true
        }
        return false
    }
}
