package app.kalkan.browser

import android.net.Uri
import android.widget.Toast
import android.text.InputType
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Bir sekmede bulunan tüm API uçları (canlı + ağ + kaynak taraması + belgeler). */
internal fun MainActivity.collectApis(t: MainActivity.Tab): List<ApiEp> {
    val m = LinkedHashMap<String, ApiEp>()
    t.apiDocs?.forEach { m[ApiFinder.key(it.method, it.url)] = it }
    t.apiScan?.forEach { m[ApiFinder.key(it.method, it.url)] = it }
    synchronized(t.res) { t.res.values.toList() }.forEach { r ->
        if (r.category == MediaSniffer.CAT_OTHER && !r.blocked && ApiFinder.isApiLike(r.url, "", "")) {
            val k = ApiFinder.key(r.method, r.url)
            if (!m.containsKey(k)) m[k] = ApiEp(r.method, r.url, ApiFinder.SRC_NET).apply { time = r.time }
        }
    }
    synchronized(t.apis) { t.apis.values.toList() }.forEach { e ->
        m.remove(ApiFinder.key("", e.url))
        m[ApiFinder.key(e.method, e.url)] = e
    }
    return m.values.toList()
}

private object CsGen {
    val TYPES = linkedMapOf(
        "Movie" to "Film",
        "TvSeries" to "Dizi",
        "Anime" to "Anime",
        "AsianDrama" to "Asya dizisi",
        "Cartoon" to "Çizgi film",
        "Documentary" to "Belgesel",
        "Live" to "Canlı yayın",
        "Others" to "Diğer"
    )

    private val QUERY_PARAMS = listOf("q", "s", "query", "keyword", "keywords", "term", "search", "k", "ara", "arama", "searchword", "kelime", "text", "title")
    private val SEARCH_WORD = Regex("(?i)(search|arama|\\bara\\b|find|query|keyword|suche|buscar)")

    fun className(host: String): String {
        val base = host.removePrefix("www.").removePrefix("m.").substringBefore('.')
        val clean = base.split('-', '_').joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
            .filter { it.isLetterOrDigit() }
        val name = clean.ifEmpty { "Site" }
        return if (name.first().isDigit()) "S$name" else name
    }

    fun sanitizeClass(s: String): String {
        val c = s.split(' ', '-', '_', '.').joinToString("") { p -> p.replaceFirstChar { it.uppercase() } }
            .filter { it.isLetterOrDigit() && it.code < 128 }
        return when {
            c.isEmpty() -> "Site"
            c.first().isDigit() -> "S$c"
            else -> c
        }
    }

    fun pkgPart(s: String): String {
        val p = s.lowercase(Locale.US).filter { (it in 'a'..'z') || it.isDigit() }
        return when {
            p.isEmpty() -> "kalkan"
            p.first().isDigit() -> "p$p"
            else -> p
        }
    }

    /** Kotlin dize sabiti içine güvenle koymak için kaçışlar. */
    fun ks(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

    /** Arama için Kotlin ifadesi ve açıklama üretir. */
    fun searchExpr(eps: List<ApiEp>, origin: String, host: String): Pair<String, String> {
        val enc = "\${java.net.URLEncoder.encode(query, \"UTF-8\")}"
        fun prefix(url: String): String {
            val u = Uri.parse(url)
            val same = u.host?.removePrefix("www.") == host.removePrefix("www.")
            return if (same) "\$mainUrl" else ks("${u.scheme}://${u.authority}")
        }
        val candidates = eps.filter { e ->
            val u = Uri.parse(e.url)
            (e.method.isEmpty() || e.method == "GET") && e.url.startsWith("http") &&
                (SEARCH_WORD.containsMatchIn(u.path ?: "") || u.queryParameterNames.any { it.lowercase() in QUERY_PARAMS })
        }.sortedWith(compareBy({ if (it.live) 0 else 1 }, { if (Uri.parse(it.url).host?.endsWith(host.removePrefix("www.")) == true) 0 else 1 }))

        for (e in candidates) {
            val u = Uri.parse(e.url)
            val param = u.queryParameterNames.firstOrNull { it.lowercase() in QUERY_PARAMS }
            if (param != null) {
                val sb = StringBuilder(prefix(e.url)).append(ks(u.encodedPath ?: "")).append('?')
                val parts = u.queryParameterNames.map { n ->
                    if (n == param) "${ks(n)}=$enc" else "${ks(n)}=${ks(Uri.encode(u.getQueryParameter(n) ?: ""))}"
                }
                sb.append(parts.joinToString("&"))
                return Pair("\"$sb\"", "${e.source} kaynağından bulundu: ${e.method.ifEmpty { "GET" }} ${e.url}")
            }
            val segs = u.pathSegments ?: emptyList()
            val idx = segs.indexOfLast { SEARCH_WORD.containsMatchIn(it) }
            if (idx >= 0) {
                val path = segs.take(idx + 1).joinToString("/") { ks(it) }
                return Pair("\"${prefix(e.url)}/$path/$enc\"", "${e.source} kaynağından tahmin edildi: ${e.url}")
            }
        }
        return Pair("\"\$mainUrl/?s=$enc\"", "uç bulunamadı, WordPress varsayılanı ?s= tahmin edildi — kontrol et")
    }

    fun constName(e: ApiEp, used: MutableSet<String>): String {
        val u = Uri.parse(e.url)
        val segs = (u.pathSegments ?: emptyList()).map { ApiFinder.normalize("x://h/$it").substringAfter("x://h/") }
            .filter { it.isNotEmpty() && !it.startsWith("{") }
        var base = segs.takeLast(4).joinToString("_")
            .replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').uppercase(Locale.US)
        if (base.isEmpty()) base = (u.host ?: "ROOT").replace(Regex("[^A-Za-z0-9]+"), "_").uppercase(Locale.US)
        if (e.method.isNotEmpty() && e.method != "GET") base = "${e.method}_$base"
        if (base.first().isDigit()) base = "U_$base"
        if (base.length > 60) base = base.take(60).trimEnd('_')
        var name = base
        var i = 2
        while (!used.add(name)) name = "${base}_${i++}"
        return name
    }
}

/** CloudStream eklenti iskeleti üretip ZIP olarak kaydeder. */
internal fun MainActivity.exportCloudStream() {
    val t = pageTab() ?: return
    // Bilgi bankasında bu site için çalışan referans eklenti varsa tarama gerekmez: doğrudan forma geç
    if (try { CsBrain.hasRef(this, t.pageHost) } catch (_: Exception) { false }) {
        Toast.makeText(this, "Bilgi bankasında çalışan ${CsBrain.siteName(this, t.pageHost)} eklentisi var — tarama atlandı", Toast.LENGTH_LONG).show()
        exportCloudStreamForm(t, null)
        return
    }
    // Önce bu sayfayı analiz et (seçiciler site başına birikir), sonra formu aç
    analyzeCsPage(t) { _ ->
        csScanPanel(t, "Devam · eklenti ayarları") {
            exportCloudStreamForm(t, CsHints.load(t.pageHost).takeIf { it.length() > 0 })
        }
    }
}

/** Geçerli sayfayı analiz edip sonucu site için kaydeder. */
internal fun MainActivity.analyzeCsPage(tab: MainActivity.Tab, done: (JSONObject?) -> Unit) {
    tab.webView.evaluateJavascript(CsAnalyzer.JS) { r ->
        val fresh = try {
            if (r.isNullOrBlank() || r == "null") null else JSONObject(r).takeIf { !it.has("error") }
        } catch (_: Exception) { null }
        done(if (fresh == null) CsHints.load(tab.pageHost).takeIf { it.length() > 0 } else CsHints.merge(tab.pageHost, fresh))
    }
}

/** Araçlar menüsü: yalnızca sayfayı analiz et, durumu göster. */
internal fun MainActivity.analyzeCsPageAndShow() {
    val t = pageTab() ?: return
    if (try { CsBrain.hasRef(this, t.pageHost) } catch (_: Exception) { false }) {
        Toast.makeText(this, "Bu site bilgi bankasında hazır (${CsBrain.siteName(this, t.pageHost)}) — tarama gerekmiyor", Toast.LENGTH_LONG).show()
        return
    }
    analyzeCsPage(t) { _ -> csScanPanel(t, "Tamam") {} }
}

private fun MainActivity.exportCloudStreamForm(t: MainActivity.Tab, hints: JSONObject?) {
    val origin = Uri.parse(t.url).let { "${it.scheme}://${it.authority}" }
    val host = t.pageHost
    val eps = collectApis(t)

    val nameF = field("Eklenti adı (ör. OrnekSite)", CsGen.className(host))
    val siteF = field("Görünen ad", CsGen.className(host))
    val authorF = field("Yazar", Store.str("cs_author", ""))
    val langF = field("Dil kodu (tr, en…)", Store.str("cs_lang", "tr"))
    val descF = field("Açıklama", "", multi = false)
    val info = small(
        "${eps.size} uç bulundu (${eps.count { it.apiLike }} API benzeri).\n\n" +
            "Sayfa analizi:\n" + CsHints.summary(hints) + "\n\n" +
            "✗ olanlar için o sayfaya gidip Araçlar → \u201CCS analizi\u201D çalıştır, sonra tekrar dışa aktar.\n" +
            "Arama ✗ ise sitede bir film adı arayıp sonuç sayfasında dışa aktarmak en kesin yoldur."
    )
    MaterialAlertDialogBuilder(this)
        .setTitle("CloudStream iskeleti")
        .setView(formBox(nameF, siteF, authorF, langF, descF, info))
        .setPositiveButton("Devam") { _, _ ->
            val types = CsGen.TYPES.keys.toList()
            val checked = BooleanArray(types.size) { it < 2 }
            MaterialAlertDialogBuilder(this)
                .setTitle("İçerik türleri")
                .setMultiChoiceItems(CsGen.TYPES.values.toTypedArray(), checked) { _, w, c -> checked[w] = c }
                .setPositiveButton("ZIP oluştur") { _, _ ->
                    val sel = types.filterIndexed { i, _ -> checked[i] }.ifEmpty { listOf("Movie") }
                    Store.prefs.edit().putString("cs_author", authorF.text.toString().trim())
                        .putString("cs_lang", langF.text.toString().trim()).apply()
                    buildCsZip(
                        t, origin, host, eps,
                        CsGen.sanitizeClass(nameF.text.toString().trim().ifEmpty { CsGen.className(host) }),
                        siteF.text.toString().trim().ifEmpty { host },
                        authorF.text.toString().trim().ifEmpty { "Darkness" },
                        langF.text.toString().trim().lowercase(Locale.US).take(5).ifEmpty { "tr" },
                        descF.text.toString().trim().ifEmpty { "$host için CloudStream eklentisi" },
                        sel
                    )
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        .setNegativeButton("Vazgeç", null)
        .show()
    langF.inputType = InputType.TYPE_CLASS_TEXT
}

private fun MainActivity.buildCsZip(
    t: MainActivity.Tab, origin: String, host: String, eps: List<ApiEp>,
    name: String, site: String, author: String, lang: String, desc: String, types: List<String>
) {
    ensureStorage {
        toast("Eklenti iskeleti hazırlanıyor…")
        val ua = t.webView.settings.userAgentString ?: "Mozilla/5.0"
        Thread {
            var zipBytes: ByteArray? = null
            var zipFileName = ""
            val msg = try {
                val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("tr")).format(Date())
                val pkg = "com." + CsGen.pkgPart(author) + "." + CsGen.pkgPart(name)
                val (searchUrl, searchNote) = CsGen.searchExpr(eps, origin, host)
                val apiEps = eps.filter { it.apiLike && (it.url.startsWith("http") || it.url.startsWith("ws")) }
                    .sortedWith(compareBy({ it.group }, { it.url }))
                val tokens = linkedMapOf(
                    "@@NAME@@" to name,
                    "@@PKG@@" to pkg,
                    "@@SITE@@" to CsGen.ks(site),
                    "@@MAIN_URL@@" to CsGen.ks(origin),
                    "@@HOST@@" to host.removePrefix("www."),
                    "@@LANG@@" to CsGen.ks(lang),
                    "@@AUTHOR@@" to CsGen.ks(author),
                    "@@DESCRIPTION@@" to CsGen.ks(desc),
                    "@@TVTYPES_GRADLE@@" to types.joinToString(", ") { "\"$it\"" },
                    "@@TVTYPES_KT@@" to types.joinToString(", ") { "TvType.$it" },
                    "@@SEARCH_URL@@" to searchUrl,
                    "@@SEARCH_NOTE@@" to searchNote.replace("*/", "* /"),
                    "@@DATE@@" to date,
                    "@@COUNT@@" to apiEps.size.toString()
                )
                // ---- Sayfa analizi ve video raporu ile seçicileri doldur
                // Canlı yakalanan akış adaylarını (kullanıcı videoyu oynatırken) ölç: gerçek filmi seç, reklam/kısa klipleri ele
                val hostBase = host.removePrefix("www.")
                val mediaCand = eps.map { it.url }.filter { u ->
                    if (!u.startsWith("http")) return@filter false
                    val up = Uri.parse(u)
                    val path = up.path.orEmpty()
                    Regex("(?i)\\.(m3u8|mp4)(\\?|$)|/master\\.txt").containsMatchIn(u) ||
                        (path.length > 40 && Regex("(?i)/(m3u8?|hls|list|playlist|stream|manifest|master|process)/").containsMatchIn(path) &&
                            up.host?.removePrefix("www.")?.endsWith(hostBase) != true)
                }.distinct().take(24)
                if (mediaCand.isNotEmpty()) try { CsMedia.analyze(mediaCand, ua, origin, host) } catch (_: Exception) { }
                val brainNote = try { CsBrain.apply(this, host) } catch (_: Exception) { null }
                val hints = CsHints.load(host)
                val report = CsVideo.analyze(t, hints, ua)
                val list = hints.optJSONObject("list")
                val det = hints.optJSONObject("detail")
                val titleSel = list?.optString("title").orEmpty()
                tokens["@@CARD_SEL@@"] = CsGen.ks(if (list?.optBoolean("bad") == true) "a:has(img)" else list?.optString("card").orEmpty().ifBlank { "a:has(img)" })
                tokens["@@POSTER_ATTR@@"] = CsGen.ks(list?.optString("posterAttr").orEmpty().ifBlank { "data-src" })
                tokens["@@EPISODE_SEL@@"] = CsGen.ks(det?.optString("episodes").orEmpty())
                tokens["@@PLOT_SEL@@"] = CsGen.ks(det?.optString("plot").orEmpty())
                tokens["@@POSTER_SEL@@"] = CsGen.ks(det?.optString("poster").orEmpty())
                tokens["@@YEAR_SEL@@"] = CsGen.ks(det?.optString("year").orEmpty())
                tokens["@@TITLE_EXPR@@"] =
                    if (titleSel.isNotBlank()) "selectFirst(\"${CsGen.ks(titleSel)}\")?.text()" else "null as String?"
                val notes = ArrayList<String>()
                notes += if (list != null) "Kartlar: ${list.optInt("count")} adet, seçici ${list.optString("card")}" else "Kartlar: bulunamadı, genel yedek seçici (a:has(img)) kullanılıyor"
                notes += if (det?.optString("episodes").orEmpty().isNotEmpty()) "Bölümler: ${det!!.optInt("episodeCount")} adet, seçici ${det.optString("episodes")}" else "Bölümler: bulunamadı (film olarak ele alınır)"
                notes += report.lines
                brainNote?.let { notes += it }
                tokens["@@ANALYSIS_NOTE@@"] = notes.joinToString("\n") { " *  - " + it.replace("*/", "* /").replace("\n", " ") }
                val nav = hints.optJSONArray("nav")
                val pages = StringBuilder("        \"\$mainUrl/\" to \"Ana sayfa\",")
                if (nav != null) for (i in 0 until minOf(nav.length(), 8)) {
                    val o = nav.optJSONObject(i) ?: continue
                    if (Regex("(?i)^(en iyiler|listeler|kategoriler|türler|turler|menü|menu)$").matches(o.optString("name").trim())) continue
                    pages.append("\n        \"\$mainUrl").append(CsGen.ks(o.optString("path")).trimEnd('/')).append("/\" to \"")
                        .append(CsGen.ks(o.optString("name"))).append("\",")
                }
                // Taramada DOĞRULANMIŞ kategoriler (her birinde kart bulundu) menü tahmininin yerini alır
                val vcats = hints.optJSONObject("cats")?.optJSONArray("list")
                if (vcats != null && vcats.length() >= 2) {
                    pages.setLength(0)
                    pages.append("        \"\$mainUrl/\" to \"Ana sayfa\",")
                    val seenV = HashSet<String>()
                    for (i in 0 until minOf(vcats.length(), 12)) {
                        val o = vcats.optJSONObject(i) ?: continue
                        val u = o.optString("u")
                        val path = if (u.startsWith(origin)) u.substring(origin.length) else u
                        if (path.isBlank() || !seenV.add(path)) continue
                        val addr = if (path.startsWith("http")) CsGen.ks(path) else "\$mainUrl" + CsGen.ks(path)
                        pages.append("\n        \"").append(addr).append("\" to \"").append(CsGen.ks(o.optString("n"))).append("\",")
                    }
                }
                tokens["@@PAGE_PAT@@"] = CsGen.ks(hints.optJSONObject("misc")?.optString("pagePat").orEmpty())
                // Bilgi bankasındaki gerçek kategori listesi (varsa) menüden gelenlerin önüne geçer
                val kbPages = try { CsBrain.mainPages(this, host) } catch (_: Exception) { emptyList() }
                if (kbPages.size >= 3) {
                    val seenP = HashSet<String>()
                    pages.setLength(0)
                    pages.append("        \"\$mainUrl/\" to \"Ana sayfa\",")
                    for ((path, title) in kbPages.take(30)) {
                        if (!seenP.add(path)) continue
                        val addr = if (path.startsWith("http")) CsGen.ks(path) else "\$mainUrl" + CsGen.ks(path)
                        pages.append("\n        \"").append(addr).append("\" to \"").append(CsGen.ks(title)).append("\",")
                    }
                }
                tokens["@@KB_KEYS@@"] = try { CsBrain.aesKeys(this).joinToString(", ") { "\"${CsGen.ks(it)}\"" } } catch (_: Exception) { "" }
                tokens["@@MAIN_PAGES@@"] = pages.toString()
                // Arama: 1) canlı yakalanan uç  2) sayfadaki form  3) adres yoklama  4) WordPress varsayılanı
                val encExpr = "\${java.net.URLEncoder.encode(query, \"UTF-8\")}"
                val sf = hints.optJSONObject("search")
                var searchCall: String? = null
                val titles = ArrayList<String>()
                list?.optJSONArray("titles")?.let { a -> for (i in 0 until a.length()) titles.add(a.optString(i)) }
                val pat0 = sf?.optString("pattern").orEmpty()
                if (searchNote.startsWith("uç bulunamadı")) {
                    if (pat0.startsWith("/") && pat0.contains("%s")) {
                        tokens["@@SEARCH_URL@@"] = "\"\$mainUrl${CsGen.ks(pat0).replace("%s", encExpr)}\""
                        tokens["@@SEARCH_NOTE@@"] = "Darkness taramasında sitede gerçek arama yapılarak bulundu ($pat0)" + if (sf?.optBoolean("verified") == true) " · doğrulandı" else ""
                    } else if (sf != null && sf.optString("param").isNotEmpty()) {
                        val param = sf.optString("param")
                        val path = sf.optString("path").ifBlank { "/" }
                        val extra = sf.optJSONObject("params")
                        if (sf.optString("method") == "post") {
                            val pairs = ArrayList<String>()
                            if (extra != null) for (k in extra.keys()) pairs.add("\"${CsGen.ks(k)}\" to \"${CsGen.ks(extra.optString(k))}\"")
                            pairs.add("\"${CsGen.ks(param)}\" to query")
                            searchCall = "val response = app.post(\"\$mainUrl${CsGen.ks(path)}\", data = mapOf(${pairs.joinToString(", ")}), referer = mainUrl)"
                            tokens["@@SEARCH_NOTE@@"] = "sayfadaki arama formundan alındı (POST $path, alan: $param)"
                        } else {
                            val qs = ArrayList<String>()
                            if (extra != null) for (k in extra.keys()) qs.add("${Uri.encode(k)}=${Uri.encode(extra.optString(k))}")
                            qs.add("${Uri.encode(param)}=")
                            tokens["@@SEARCH_URL@@"] = "\"\$mainUrl${CsGen.ks(path)}?${CsGen.ks(qs.joinToString("&"))}$encExpr\""
                            tokens["@@SEARCH_NOTE@@"] = "sayfadaki arama formundan alındı (GET $path, alan: $param)"
                        }
                    } else {
                        CsSearch.probe(origin, titles, ua)?.let { pat ->
                            tokens["@@SEARCH_URL@@"] = "\"\$mainUrl${CsGen.ks(pat).replace("%s", encExpr)}\""
                            tokens["@@SEARCH_NOTE@@"] = "adres yoklamasıyla bulundu ($pat)"
                        }
                    }
                }
                // Seçilen GET adresi (yakalanan uç, form ya da varsayılan) gerçekten arama yapıyor mu? Değilse yokla.
                if (searchCall == null && titles.isNotEmpty()) {
                    val pat = tokens["@@SEARCH_URL@@"].orEmpty().removeSurrounding("\"")
                        .removePrefix("\$mainUrl").replace(encExpr, "%s")
                    if (!pat.contains('\\') && pat.contains("%s")) {
                        if (CsSearch.check(origin, pat, titles, ua)) {
                            tokens["@@SEARCH_NOTE@@"] = tokens["@@SEARCH_NOTE@@"] + " · canlı sitede doğrulandı"
                        } else {
                            val fixed = CsSearch.probe(origin, titles, ua)
                            if (fixed != null) {
                                tokens["@@SEARCH_URL@@"] = "\"\$mainUrl${CsGen.ks(fixed).replace("%s", encExpr)}\""
                                tokens["@@SEARCH_NOTE@@"] = "önceki tahmin doğrulanamadı, yoklamayla bulundu ($fixed)"
                            } else {
                                tokens["@@SEARCH_NOTE@@"] = tokens["@@SEARCH_NOTE@@"] + " · UYARI: doğrulanamadı, arama çalışmayabilir"
                            }
                        }
                    }
                }
                tokens["@@STREAM_RE@@"] = CsGen.ks(hints.optJSONObject("player")?.optString("streamRe").orEmpty())
                tokens["@@AD_RE@@"] = CsGen.ks(hints.optJSONObject("player")?.optString("adRe").orEmpty())
                tokens["@@XRW@@"] = CsGen.ks(hints.optJSONObject("misc")?.optString("xrw").orEmpty().ifEmpty { "XMLHttpRequest" })
                val tmdbBase = hints.optJSONObject("misc")?.optString("tmdb").orEmpty()
                tokens["@@TMDB_BASE@@"] = CsGen.ks(tmdbBase)
                tokens["@@ROUTER@@"] = CsGen.ks(hints.optJSONObject("misc")?.optString("router").orEmpty())
                tokens["@@SEARCH_CALL@@"] = searchCall
                    ?: ("val url = " + tokens["@@SEARCH_URL@@"] + "\n        val response = fetch(url)")
                tokens["@@CRYPTO_PASS@@"] = report.passes.joinToString(", ") { "\"${CsGen.ks(it)}\"" }

                fun tpl(file: String): String {
                    var s = assets.open("cs3/$file").bufferedReader().use { it.readText() }
                    tokens.forEach { (k, v) -> s = s.replace(k, v) }
                    return s
                }

                // Uç sabitleri
                val used = HashSet<String>()
                val ek = StringBuilder()
                ek.append("package $pkg\n\n")
                ek.append("/**\n * Darkness Tarayıcı'nın ${host} üzerinde bulduğu API uçları ($date).\n")
                ek.append(" * Aynı sitedeki uçlar mainUrl'e göre göreli yazılmıştır: \"\$mainUrl\" + SABIT\n */\n")
                ek.append("@Suppress(\"unused\")\nobject ${name}Endpoints {\n")
                for (e in apiEps.take(200)) {
                    val u = Uri.parse(e.url)
                    val same = u.host == Uri.parse(origin).host
                    val value = if (same) (u.encodedPath ?: "/") + (u.encodedQuery?.let { "?$it" } ?: "") else e.url
                    val meta = listOfNotNull(
                        e.method.ifEmpty { "?" }, e.source,
                        e.status.takeIf { it > 0 }?.toString(), e.ct.ifEmpty { null }, e.note.ifEmpty { null }
                    ).joinToString(" · ").replace("*/", "* /")
                    ek.append("    /** $meta */\n")
                    ek.append("    const val ${CsGen.constName(e, used)} = \"${CsGen.ks(value)}\"\n")
                    if (e.body.isNotEmpty() && !e.body.startsWith("[")) {
                        ek.append("    // Örnek gövde: ${e.body.take(160).replace("\n", " ").replace("*/", "* /")}\n")
                    }
                }
                if (apiEps.isEmpty()) ek.append("    // Henüz uç bulunamadı. Araçlar → API uçları ile sayfada gezinip tekrar dışa aktar.\n")
                ek.append("}\n")

                // ENDPOINTS.md
                val md = StringBuilder("# $site — bulunan uçlar\n\nDarkness Tarayıcı · $date · $origin\n\n")
                md.append("| Yöntem | Adres | Kaynak | Durum | Not |\n|---|---|---|---|---|\n")
                for (e in eps.sortedWith(compareBy({ !it.apiLike }, { it.group }, { it.url }))) {
                    fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
                    md.append("| ${e.method.ifEmpty { "—" }} | `${cell(e.url)}` | ${cell(e.source)} | ${if (e.status > 0) e.status else ""} | ${cell(listOfNotNull(e.ct.ifEmpty { null }, e.note.ifEmpty { null }, if (e.count > 1) "×${e.count}" else null).joinToString(", "))} |\n")
                }
                val samples = eps.filter { it.body.isNotEmpty() && !it.body.startsWith("[") }
                if (samples.isNotEmpty()) {
                    md.append("\n## Örnek istek gövdeleri\n")
                    samples.take(40).forEach { e -> md.append("\n**${e.method} ${e.url}**\n\n```\n${e.body}\n```\n") }
                }

                tokens["@@CRYPTO_CALL@@"] = if (report.passes.isNotEmpty()) tpl("crypto_call.txt") else ""
                tokens["@@CRYPTO_FUN@@"] = if (report.passes.isNotEmpty()) tpl("crypto_fun.txt") else ""

                // Hazır Türk oynatıcı çözücüleri (API modu dışında): dosyaları ekle, Plugin'e kaydet
                val extPack = if (tmdbBase.isEmpty()) try { CsBrain.extPack(this, pkg) } catch (_: Exception) { null } else null
                tokens["@@EXTRACTORS@@"] = extPack?.second?.joinToString("") { "        registerExtractorAPI($it())\n" }.orEmpty()
                val root = "$name-cloudstream"
                val kt = "$root/$name/src/main/kotlin/" + pkg.replace('.', '/')
                val files = linkedMapOf<String, String>(
                    "$root/build.gradle.kts" to tpl("root_build.gradle.kts"),
                    "$root/settings.gradle.kts" to tpl("settings.gradle.kts"),
                    "$root/gradle.properties" to tpl("gradle.properties"),
                    "$root/.gitignore" to tpl("gitignore"),
                    "$root/README.md" to tpl("README.md"),
                    "$root/.github/workflows/build.yml" to tpl("workflow.yml"),
                    "$root/$name/build.gradle.kts" to tpl("module_build.gradle.kts"),
                    "$root/$name/src/main/AndroidManifest.xml" to tpl("AndroidManifest.xml"),
                    "$kt/${name}Plugin.kt" to tpl("Plugin.kt"),
                    "$kt/${name}Provider.kt" to tpl(if (tmdbBase.isNotEmpty()) "ProviderTmdb.kt" else "Provider.kt"),
                    "$kt/${name}Endpoints.kt" to ek.toString(),
                    "$root/$name/ENDPOINTS.md" to md.toString()
                )
                extPack?.first?.forEach { (fn, content) -> files["$kt/ext/$fn"] = content }
                // Bilgi bankasında bu site için ÇALIŞAN referans eklenti varsa üretilen sağlayıcının yerine onu koy
                try {
                    CsBrain.refFiles(this, host, origin)?.let { ref ->
                        files.keys.removeAll { it.startsWith("$root/$name/src/main/") || it == "$root/$name/build.gradle.kts" }
                        for ((rel, content) in ref) files["$root/$name/$rel"] = content
                        files["$root/$name/ENDPOINTS.md"] = "Bu eklenti Darkness bilgi bankasındaki çalışan referans kaynaktan üretildi ($host).\n"
                    }
                } catch (_: Exception) { }
                val bos = ByteArrayOutputStream()
                ZipOutputStream(bos).use { z ->
                    for ((path, content) in files) {
                        z.putNextEntry(ZipEntry(path))
                        z.write(content.toByteArray(Charsets.UTF_8))
                        z.closeEntry()
                    }
                }
                val zipName = "$root.zip"
                Saver.save(this, zipName, "application/zip") { it.write(bos.toByteArray()) }
                zipBytes = bos.toByteArray()
                zipFileName = zipName
                "Kaydedildi: İndirilenler/Kalkan/$zipName · ${apiEps.size} uç eklendi"
            } catch (e: Exception) {
                "İskelet oluşturulamadı: ${e.message}"
            }
            runOnUiThread {
                MaterialAlertDialogBuilder(this)
                    .setTitle("CloudStream iskeleti")
                    .setMessage(msg + if (msg.startsWith("Kaydedildi"))
                        "\n\n\u201CDerle ve .cs3 indir\u201D ile Darkness ZIP'i GitHub'a yükleyip derletir ve .cs3 dosyasını otomatik indirir."
                    else "")
                    .setPositiveButton("Tamam", null)
                    .setNeutralButton("İndirilenler") { _, _ -> openSystemDownloads() }
                    .apply {
                        val zb = zipBytes
                        if (zb != null) setNegativeButton("Derle ve .cs3 indir") { _, _ -> ghBuildZip(zb, name, zipFileName) }
                    }
                    .show()
            }
        }.start()
    }
}
