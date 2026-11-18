package app.kalkan.browser

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Bulunan bir API ucu. */
class ApiEp(val method: String, val url: String, val source: String) {
    @Volatile var count = 1
    @Volatile var status = 0
    @Volatile var ct = ""
    @Volatile var body = ""
    @Volatile var kind = ""
    @Volatile var note = ""
    @Volatile var time = System.currentTimeMillis()

    val live get() = source == ApiFinder.SRC_LIVE || source == ApiFinder.SRC_NET
    val group: Int
        get() = when (source) {
            ApiFinder.SRC_LIVE, ApiFinder.SRC_NET -> 1
            ApiFinder.SRC_PROBE, ApiFinder.SRC_OPENAPI, ApiFinder.SRC_WP, ApiFinder.SRC_ROBOTS, ApiFinder.SRC_OIDC -> 3
            else -> 2
        }
    val apiLike: Boolean get() = ApiFinder.isApiLike(url, ct, kind) || group == 3
}

object ApiFinder {
    const val SRC_LIVE = "Canlı"
    const val SRC_NET = "Ağ isteği"
    const val SRC_HTML = "Sayfa HTML"
    const val SRC_PROBE = "Bilinen yol"
    const val SRC_OPENAPI = "OpenAPI"
    const val SRC_WP = "WordPress"
    const val SRC_ROBOTS = "robots.txt"
    const val SRC_OIDC = "OpenID"

    private val API_RE = Regex(
        "(?i)(/api(/|\\b|$)|/v[0-9]+(\\.[0-9]+)?(/|$)|graphql|/rest/|/ajax|/rpc|/services?/|/endpoints?|\\.json(\\?|$)|" +
            "/auth|/oauth|/token|/login|/logout|/signin|/signup|/register|/session|/users?(/|\\?|$)|/account|/me(/|\\?|$)|" +
            "/search|/query|wp-json|/admin|/backend|/gateway|/webhook|/socket|/hub|/stream|/upload|/graphql|/feed|" +
            "\\.php(\\?|$)|\\.ashx|\\.asmx|\\.aspx|/cgi-bin/|/ws(/|$)|/data/|/config|/collect|/track|/event)"
    )

    private val STATIC_EXT = setOf(
        "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp", "css", "woff", "woff2", "ttf", "otf", "eot",
        "mp4", "webm", "mp3", "m4a", "ogg", "wav", "map", "ts", "m4s", "m3u8", "mpd", "js", "mjs", "jsx", "tsx",
        "scss", "less", "md", "txt", "pdf", "zip", "gz"
    )

    private val NOISE_HOSTS = setOf(
        "www.w3.org", "w3.org", "schema.org", "developer.mozilla.org", "reactjs.org", "react.dev", "fb.me",
        "github.com", "jquery.com", "momentjs.com", "tc39.es", "goo.gl", "bit.ly", "example.com", "www.example.com",
        "json-schema.org", "creativecommons.org", "opensource.org", "npmjs.com", "www.npmjs.com", "unpkg.com",
        "vuejs.org", "angular.io", "nextjs.org", "lodash.com", "underscorejs.org", "feross.org", "mths.be",
        "stackoverflow.com", "en.wikipedia.org", "ns.adobe.com", "purl.org", "xmlns.com", "www.apple.com",
        "polyfill.io", "babeljs.io", "webpack.js.org", "localhost", "127.0.0.1"
    )

    private val CALL_RE = Regex(
        "(?:fetch|axios(?:\\.(get|post|put|patch|delete|head))?|\\$\\.(get|post|ajax|getJSON)|" +
            "\\.(get|post|put|patch|delete|request)|\\.open)\\s*\\(\\s*" +
            "(?:[\"'](GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)[\"']\\s*,\\s*)?" +
            "[\"'`]([^\"'`\\s]{2,300})[\"'`]"
    )
    private val URL_RE = Regex("(?:https?|wss?)://[A-Za-z0-9.\\-]+(?::\\d+)?(?:/[^\\s\"'`<>\\\\()\\[\\]|^,;]*)?")
    private val PATH_RE = Regex("[\"'`](/[A-Za-z0-9_\\-.~/{}:@%+=&?!]{1,250})[\"'`]")
    private val TEMPLATE_RE = Regex("\\$\\{[^}]{0,80}\\}")
    private val SCRIPT_SRC_RE = Regex("<script[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)

    private val SEG_NUM = Regex("^\\d+$")
    private val SEG_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val SEG_HEX = Regex("^[0-9a-fA-F]{16,}$")
    private val SEG_TOKEN = Regex("^[A-Za-z0-9_\\-]{28,}$")

    val PROBE_PATHS = listOf(
        "/robots.txt", "/sitemap.xml", "/.well-known/security.txt", "/.well-known/openid-configuration",
        "/.well-known/assetlinks.json", "/.well-known/apple-app-site-association", "/manifest.json",
        "/swagger.json", "/swagger/v1/swagger.json", "/openapi.json", "/api-docs", "/v2/api-docs", "/v3/api-docs",
        "/api/swagger.json", "/api/openapi.json", "/api", "/api/v1", "/graphql?query=%7B__typename%7D",
        "/wp-json/", "/humans.txt", "/crossdomain.xml"
    )

    private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options")

    fun isApiLike(url: String, ct: String, kind: String): Boolean {
        if (kind in setOf("fetch", "xhr", "ws", "sse", "beacon")) return !isStatic(url)
        if (ct.contains("json", true) || ct.contains("xml", true) || ct.contains("graphql", true) ||
            ct.contains("protobuf", true) || ct.contains("event-stream", true)
        ) return true
        if (url.startsWith("ws")) return true
        val path = try { Uri.parse(url).path ?: "" } catch (_: Exception) { "" }
        val q = try { Uri.parse(url).query ?: "" } catch (_: Exception) { "" }
        return API_RE.containsMatchIn(path) || (q.isNotEmpty() && API_RE.containsMatchIn("$path?$q"))
    }

    private fun isStatic(url: String): Boolean {
        val seg = try { Uri.parse(url).lastPathSegment ?: "" } catch (_: Exception) { "" }
        val ext = seg.substringAfterLast('.', "").lowercase()
        return ext in STATIC_EXT
    }

    /** Sayısal / uuid parçaları {id} gibi yer tutuculara çevirerek gruplar. */
    fun normalize(url: String): String = try {
        val u = Uri.parse(url)
        val segs = (u.pathSegments ?: emptyList()).joinToString("/") { s ->
            when {
                SEG_NUM.matches(s) -> "{id}"
                SEG_UUID.matches(s) -> "{uuid}"
                SEG_HEX.matches(s) -> "{hash}"
                SEG_TOKEN.matches(s) && s.any { it.isDigit() } -> "{token}"
                else -> s
            }
        }
        "${u.scheme}://${u.authority}/$segs"
    } catch (_: Exception) { url }

    fun key(method: String, url: String) = method + " " + normalize(url)

    /** Ham adayı mutlak, temiz bir adrese çevirir; uygun değilse null. */
    private fun accept(raw: String, base: String, fromCall: Boolean): String? {
        var u = raw.trim().trimEnd('.', ',', ';', ')', '\\', ':')
        if (u.length < 2) return null
        if (u.startsWith("data:") || u.startsWith("blob:") || u.startsWith("javascript:") || u.startsWith("mailto:")) return null
        if (u.contains('\\') || u.contains('*') || u.contains("..../")) return null
        if (u.startsWith("//")) u = "https:$u"
        val looksRelative = u.startsWith("/") || u.startsWith("./") || u.startsWith("../")
        val absolute = u.startsWith("http://") || u.startsWith("https://") || u.startsWith("ws://") || u.startsWith("wss://")
        if (!absolute && !looksRelative) {
            if (!fromCall || !u.contains('/') || !Regex("^[A-Za-z0-9_\\-./{}?=&%]+$").matches(u)) return null
        }
        val full = try { if (absolute) u else URL(URL(base), u).toString() } catch (_: Exception) { return null }
        val p = try { Uri.parse(full) } catch (_: Exception) { return null }
        val host = p.host?.lowercase() ?: return null
        if (host in NOISE_HOSTS || !host.contains('.')) return null
        val path = p.path ?: ""
        if ((path.isEmpty() || path == "/") && p.query.isNullOrEmpty()) return null
        if (path.contains("//") || path.count { it == '/' } > 15) return null
        if (!path.any { it.isLetter() }) return null
        if (isStatic(full)) return null
        return full
    }

    /** Metindeki API adreslerini çıkarır. */
    fun extract(text: String, base: String, source: String, out: MutableMap<String, ApiEp>) {
        val t = TEMPLATE_RE.replace(text, "{param}")
        fun add(method: String, url: String) {
            val k = key(method, url)
            if (method.isNotEmpty()) out.remove(key("", url))
            else if (out.keys.any { it.endsWith(" " + normalize(url)) }) return
            if (!out.containsKey(k)) out[k] = ApiEp(method, url, source)
        }
        for (m in CALL_RE.findAll(t)) {
            val url = accept(m.groupValues[5], base, true) ?: continue
            val method = listOf(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4])
                .firstOrNull { it.isNotEmpty() }?.uppercase()?.let {
                    when (it) { "GETJSON" -> "GET"; "AJAX", "REQUEST" -> ""; else -> it }
                } ?: ""
            add(if (m.value.startsWith("fetch") && method.isEmpty()) "" else method, url)
        }
        for (m in URL_RE.findAll(t)) {
            val url = accept(m.value, base, false) ?: continue
            add("", url)
        }
        for (m in PATH_RE.findAll(t)) {
            val url = accept(m.groupValues[1], base, false) ?: continue
            add("", url)
        }
    }

    fun scriptSrcs(html: String, base: String): List<String> =
        SCRIPT_SRC_RE.findAll(html).mapNotNull {
            try { URL(URL(base), it.groupValues[1].replace("&amp;", "&")).toString() } catch (_: Exception) { null }
        }.toList()

    fun fetchText(url: String, ua: String, referer: String?, limit: Int = 4 shl 20): Pair<Int, Pair<String, String>> {
        val c = Net.open(url)
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        if (!referer.isNullOrEmpty()) c.setRequestProperty("Referer", referer)
        try {
            CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
        } catch (_: Exception) {
        }
        try {
            val code = c.responseCode
            val ct = c.contentType ?: ""
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val bytes = stream?.use { s ->
                val buf = java.io.ByteArrayOutputStream()
                val b = ByteArray(1 shl 15)
                while (buf.size() < limit) {
                    val n = s.read(b)
                    if (n <= 0) break
                    buf.write(b, 0, n)
                }
                buf.toByteArray()
            } ?: ByteArray(0)
            return Pair(code, Pair(ct, String(bytes, Charsets.UTF_8)))
        } finally {
            c.disconnect()
        }
    }

    // ---------- Belge ayrıştırıcıları ----------

    fun parseOpenApi(j: JSONObject, docUrl: String, origin: String, out: MutableMap<String, ApiEp>): Int {
        val paths = j.optJSONObject("paths") ?: return 0
        val base = try {
            if (j.has("openapi")) {
                val s0 = j.optJSONArray("servers")?.optJSONObject(0)?.optString("url").orEmpty()
                if (s0.isEmpty()) origin else URL(URL(docUrl), s0).toString().trimEnd('/')
            } else {
                val o = URL(origin)
                val host = j.optString("host").ifEmpty { o.authority }
                val scheme = j.optJSONArray("schemes")?.optString(0)?.ifEmpty { null } ?: o.protocol
                "$scheme://$host" + j.optString("basePath", "").trimEnd('/')
            }
        } catch (_: Exception) { origin }
        var n = 0
        for (p in paths.keys()) {
            val ops = paths.optJSONObject(p) ?: continue
            for (m in ops.keys()) {
                if (m.lowercase() !in HTTP_METHODS) continue
                val ep = ApiEp(m.uppercase(), base + p, SRC_OPENAPI)
                ops.optJSONObject(m)?.let { op ->
                    ep.note = op.optString("summary").ifEmpty { op.optString("operationId") }
                }
                out[key(ep.method, ep.url)] = ep
                n++
            }
        }
        return n
    }

    fun parseWordPress(j: JSONObject, origin: String, out: MutableMap<String, ApiEp>): Int {
        val routes = j.optJSONObject("routes") ?: return 0
        var n = 0
        for (r in routes.keys()) {
            if (n > 1500) break
            val o = routes.optJSONObject(r) ?: continue
            val methods = o.optJSONArray("methods")
            val list = if (methods != null) (0 until methods.length()).map { methods.getString(it) } else listOf("GET")
            for (m in list.distinct()) {
                val ep = ApiEp(m.uppercase(), "$origin/wp-json$r", SRC_WP)
                ep.note = o.optString("namespace")
                out[key(ep.method, ep.url)] = ep
                n++
            }
        }
        return n
    }

    fun parseRobots(text: String, origin: String, out: MutableMap<String, ApiEp>): Int {
        var n = 0
        for (line in text.lines()) {
            val l = line.substringBefore('#').trim()
            val k = l.substringBefore(':').trim().lowercase()
            val v = l.substringAfter(':', "").trim()
            if (v.isEmpty()) continue
            val url = when (k) {
                "disallow", "allow" -> if (v.startsWith("/") && v.length > 1) origin + v.replace("*", "{*}").replace("$", "") else null
                "sitemap" -> v
                else -> null
            } ?: continue
            val ep = ApiEp("", url, SRC_ROBOTS)
            ep.note = if (k == "sitemap") "Site haritası" else k.replaceFirstChar { it.uppercase() }
            out.putIfAbsent(key("", url), ep)
            n++
        }
        return n
    }

    fun parseOidc(j: JSONObject, out: MutableMap<String, ApiEp>): Int {
        var n = 0
        for (k in j.keys()) {
            if (!(k.endsWith("_endpoint") || k == "jwks_uri")) continue
            val v = j.optString(k)
            if (!v.startsWith("http")) continue
            val ep = ApiEp(if (k == "token_endpoint") "POST" else "GET", v, SRC_OIDC)
            ep.note = k
            out[key(ep.method, ep.url)] = ep
            n++
        }
        return n
    }
}

// =====================================================================
// Canlı yakalama (JS köprüsünden)
// =====================================================================

private var apiRefresh: (() -> Unit)? = null
private var apiRefreshPosted = false

internal fun MainActivity.addApi(tab: MainActivity.Tab, o: JSONObject) {
    val url = o.optString("u")
    if (!url.startsWith("http") && !url.startsWith("ws")) return
    val method = o.optString("m", "GET").uppercase()
    val k = ApiFinder.key(method, url)
    synchronized(tab.apis) {
        val e = tab.apis[k]
        val ep = if (e != null) {
            e.count++
            e.time = System.currentTimeMillis()
            e
        } else {
            if (tab.apis.size >= 2000) tab.apis.remove(tab.apis.keys.first())
            ApiEp(method, url, ApiFinder.SRC_LIVE).also { tab.apis[k] = it }
        }
        if (o.has("s")) ep.status = o.optInt("s")
        o.optString("ct").takeIf { it.isNotEmpty() }?.let { ep.ct = it.substringBefore(';') }
        o.optString("b").takeIf { it.isNotEmpty() }?.let { ep.body = it }
        ep.kind = o.optString("k")
    }
    if (tab === current) postApiRefresh()
}

private fun MainActivity.postApiRefresh() {
    if (apiRefresh == null || apiRefreshPosted) return
    apiRefreshPosted = true
    ui.postDelayed({ apiRefreshPosted = false; apiRefresh?.invoke() }, 500)
}

/** Sayfa başka bir siteye geçince o sekmenin API listesini sıfırlar. */
internal fun MainActivity.resetApisIfHostChanged(tab: MainActivity.Tab, url: String) {
    val h = hostOf(url)
    if (h.isEmpty() || h == MainActivity.ASSET_HOST || h == tab.apiHost) return
    tab.apiHost = h
    synchronized(tab.apis) { tab.apis.clear() }
    tab.apiScan = null
    tab.apiDocs = null
}

// =====================================================================
// API uçları ekranı
// =====================================================================

private val scanPool = Executors.newFixedThreadPool(4)

internal fun MainActivity.showApis() {
    val t = pageTab() ?: return
    resetApisIfHostChanged(t, t.url)
    val d = sheet(0.92f)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val head = sheetHeader("API uçları", t.pageHost)
    root.addView(head)
    @Suppress("UNCHECKED_CAST")
    val subtitle = (head.getChildAt(1) as? TextView)

    var group = 0 // 0 tümü, 1 canlı, 2 kaynak, 3 belgeler
    var onlyApi = true
    var query = ""
    var scanning = false
    var probing = false
    var items: List<ApiEp> = emptyList()

    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), 0, dp(8), 0) }
    val actionScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(actions) }
    root.addView(actionScroll)

    val chips = ChipGroup(this).apply { isSingleLine = true; setPadding(dp(16), 0, dp(16), 0) }
    root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })

    val search = EditText(this).apply {
        hint = "Ara (ör. /api, graphql, login, POST)"
        setSingleLine()
        textSize = 14f
        setBackgroundResource(R.drawable.bg_url)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setTextColor(textColor())
        setHintTextColor(mutedColor())
    }
    root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(16); marginEnd = dp(16); topMargin = dp(6)
    })
    val status = small("").apply { setPadding(dp(20), dp(8), dp(20), 0) }
    root.addView(status)
    val empty = small("").apply {
        gravity = android.view.Gravity.CENTER
        setPadding(dp(28), dp(36), dp(28), dp(36))
        visibility = View.GONE
    }
    root.addView(empty)

    lateinit var rv: RecyclerView
    lateinit var refresh: () -> Unit

    fun all(): List<ApiEp> = collectApis(t)

    fun methodColor(m: String) = Color.parseColor(
        when (m) {
            "GET" -> "#30A46C"
            "POST" -> "#3E8EF7"
            "PUT", "PATCH" -> "#E2A336"
            "DELETE" -> "#E5484D"
            "WS", "SSE" -> "#8E4EC6"
            else -> "#7A7F8C"
        }
    )

    fun statusColor(s: Int) = when {
        s >= 400 -> Color.parseColor("#E5484D")
        s in 300..399 -> Color.parseColor("#3E8EF7")
        s in 100..299 -> Color.parseColor("#30A46C")
        else -> mutedColor()
    }

    rv = simpleList({ items.size }) { i, t1, t2, row ->
        val e = items[i]
        val sp = SpannableStringBuilder()
        val m = e.method.ifEmpty { "URL" }
        sp.append(m)
        sp.setSpan(ForegroundColorSpan(methodColor(e.method)), 0, m.length, 0)
        sp.setSpan(StyleSpan(Typeface.BOLD), 0, m.length, 0)
        if (e.status > 0) {
            val st = "  ${e.status}"
            val s0 = sp.length
            sp.append(st)
            sp.setSpan(ForegroundColorSpan(statusColor(e.status)), s0, sp.length, 0)
            sp.setSpan(StyleSpan(Typeface.BOLD), s0, sp.length, 0)
        }
        sp.append("  ·  ").append(e.source)
        if (e.count > 1) sp.append("  ·  ×${e.count}")
        if (e.ct.isNotEmpty()) sp.append("  ·  ").append(e.ct.substringAfter('/').take(18))
        t1.text = sp
        val u = e.url
        val hs = u.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val he = u.indexOf('/', hs).let { if (it < 0) u.length else it }
        val sp2 = SpannableStringBuilder(u)
        sp2.setSpan(ForegroundColorSpan(mutedColor()), 0, u.length, 0)
        if (he > hs) sp2.setSpan(ForegroundColorSpan(textColor()), he, u.length, 0)
        t2.text = sp2
        t2.typeface = Typeface.MONOSPACE
        t2.maxLines = 3
        row.setOnClickListener { apiDetails(t, e) }
        row.setOnLongClickListener { copy(e.url); true }
    }
    rv.setPadding(dp(14), dp(6), dp(14), dp(20))
    rv.clipToPadding = false
    root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55).toInt()))

    refresh = {
        val list = all()
        val counts = IntArray(4)
        list.filter { !onlyApi || it.apiLike }.forEach { counts[0]++; counts[it.group]++ }
        chips.removeAllViews()
        listOf("Tümü", "Canlı", "Kaynak kodu", "Belgeler").forEachIndexed { gi, label ->
            chips.addView(Chip(this).apply {
                text = "$label ${counts[gi]}"
                isCheckable = true
                isChecked = gi == group
                setOnClickListener { group = gi; refresh() }
            })
        }
        chips.addView(Chip(this).apply {
            text = "Sadece API benzeri"
            isCheckable = true
            isChecked = onlyApi
            setOnClickListener { onlyApi = !onlyApi; refresh() }
        })
        val q = query.lowercase()
        items = list.filter { e ->
            (group == 0 || e.group == group) && (!onlyApi || e.apiLike) &&
                (q.isEmpty() || e.url.lowercase().contains(q) || e.method.lowercase() == q || e.source.lowercase().contains(q) || e.note.lowercase().contains(q))
        }.sortedWith(
            if (group == 1) compareByDescending<ApiEp> { it.time }
            else compareBy<ApiEp>({ it.group }, { if (hostOf(it.url) == t.pageHost) 0 else 1 }, { Uri.parse(it.url).path ?: it.url })
        )
        subtitle?.text = "${t.pageHost} · ${counts[0]} uç" +
            (if (t.apiScan != null) " · ${t.apiScanFiles} dosya tarandı" else "")
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = when {
            scanning && list.isEmpty() -> "Taranıyor…"
            group == 1 -> "Henüz canlı istek yakalanmadı.\nSayfada gezin, düğmelere bas, arama yap — sayfanın yaptığı API çağrıları burada belirir."
            group == 3 && t.apiDocs == null -> "\u201CBelgeleri tara\u201D ile sitenin herkese açık API belgelerini (OpenAPI/Swagger, WordPress REST, robots.txt) ara."
            onlyApi -> "API benzeri uç bulunamadı. \u201CSadece API benzeri\u201D filtresini kapatıp tüm adreslere bakabilirsin."
            else -> "Sonuç yok."
        }
        rv.adapter?.notifyDataSetChanged()
    }

    fun runScan() {
        if (scanning) return
        scanning = true
        status.text = "Sayfa kaynağı okunuyor…"
        val ua = currentUA()
        val pageUrl = t.url
        fetchLiveHtml(t) { html ->
            val scripts = LinkedHashSet<String>()
            synchronized(t.res) { t.res.values.toList() }
                .filter { it.category == MediaSniffer.CAT_SCRIPT && !it.blocked }
                .forEach { scripts.add(it.url) }
            if (html != null) scripts.addAll(ApiFinder.scriptSrcs(html, pageUrl))
            val list = scripts.filter { it.startsWith("http") }.take(60)
            val out = java.util.concurrent.ConcurrentHashMap<String, ApiEp>()
            val done = AtomicInteger(0)
            status.text = "Taranıyor: 0 / ${list.size} betik dosyası…"
            scanPool.execute {
                val local = LinkedHashMap<String, ApiEp>()
                if (html != null) ApiFinder.extract(html, pageUrl, ApiFinder.SRC_HTML, local)
                synchronized(out) { local.forEach { (k, v) -> out.putIfAbsent(k, v) } }
                val latch = java.util.concurrent.CountDownLatch(list.size)
                for (s in list) {
                    scanPool.execute {
                        try {
                            val (code, r) = ApiFinder.fetchText(s, ua, pageUrl)
                            if (code in 200..299) {
                                val m = LinkedHashMap<String, ApiEp>()
                                val name = Uri.parse(s).lastPathSegment?.take(40) ?: "betik"
                                ApiFinder.extract(r.second, s, name, m)
                                synchronized(out) {
                                    m.forEach { (k, v) ->
                                        if (v.method.isNotEmpty()) out.remove(ApiFinder.key("", v.url))
                                        out.putIfAbsent(k, v)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            val n = done.incrementAndGet()
                            ui.post { if (scanning) status.text = "Taranıyor: $n / ${list.size} betik dosyası…" }
                            latch.countDown()
                        }
                    }
                }
                try { latch.await(90, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
                val result = synchronized(out) { out.values.toList() }
                ui.post {
                    t.apiScan = result
                    t.apiScanFiles = list.size + (if (html != null) 1 else 0)
                    scanning = false
                    status.text = "Kaynak taraması bitti · ${result.size} adres bulundu"
                    refresh()
                }
            }
        }
    }

    fun runProbe() {
        if (probing) return
        probing = true
        val origin = try { Uri.parse(t.url).let { "${it.scheme}://${it.authority}" } } catch (_: Exception) { return }
        val ua = currentUA()
        val pageUrl = t.url
        status.text = "Bilinen yollar deneniyor (${ApiFinder.PROBE_PATHS.size})…"
        scanPool.execute {
            val out = LinkedHashMap<String, ApiEp>()
            val found = AtomicInteger(0)
            val latch = java.util.concurrent.CountDownLatch(ApiFinder.PROBE_PATHS.size)
            for (p in ApiFinder.PROBE_PATHS) {
                scanPool.execute {
                    try {
                        val url = origin + p
                        val (code, r) = ApiFinder.fetchText(url, ua, pageUrl, 3 shl 20)
                        val (ct, body) = r
                        if (code in 200..403 && code != 404) {
                            val ep = ApiEp("GET", url, ApiFinder.SRC_PROBE)
                            ep.status = code
                            ep.ct = ct.substringBefore(';')
                            found.incrementAndGet()
                            val parsed = LinkedHashMap<String, ApiEp>()
                            if (code in 200..299) {
                                val trimmed = body.trimStart()
                                when {
                                    p == "/robots.txt" && !trimmed.startsWith("<") -> ep.note = "${ApiFinder.parseRobots(body, origin, parsed)} kural"
                                    trimmed.startsWith("{") -> try {
                                        val j = JSONObject(trimmed)
                                        val n = when {
                                            j.has("paths") && (j.has("openapi") || j.has("swagger")) -> ApiFinder.parseOpenApi(j, url, origin, parsed).also { if (it > 0) ep.note = "OpenAPI belgesi · $it uç" }
                                            j.has("routes") -> ApiFinder.parseWordPress(j, origin, parsed).also { if (it > 0) ep.note = "WordPress REST · $it uç" }
                                            j.has("issuer") -> ApiFinder.parseOidc(j, parsed).also { if (it > 0) ep.note = "OpenID yapılandırması" }
                                            else -> 0
                                        }
                                        if (n == 0 && ep.note.isEmpty()) ep.note = "JSON yanıt"
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                            synchronized(out) {
                                out[ApiFinder.key("GET", url)] = ep
                                parsed.forEach { (k, v) -> out.putIfAbsent(k, v) }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        latch.countDown()
                    }
                }
            }
            try { latch.await(60, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            val result = synchronized(out) { out.values.toList() }
            ui.post {
                t.apiDocs = result
                probing = false
                status.text = "${found.get()} bilinen yol yanıt verdi · belgelerden ${result.size - found.get()} uç çıkarıldı"
                group = 3
                refresh()
            }
        }
    }

    actions.addView(textBtn("Kaynağı yeniden tara") { t.apiScan = null; runScan() })
    actions.addView(textBtn("Belgeleri tara") { runProbe() })
    actions.addView(textBtn("Dışa aktar") { exportApis(t, items.ifEmpty { all() }) })
    actions.addView(textBtn("CloudStream iskeleti") { exportCloudStream() })
    actions.addView(textBtn("Canlıyı temizle") {
        synchronized(t.apis) { t.apis.clear() }
        refresh()
    })

    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; refresh() }
    })

    apiRefresh = { refresh() }
    d.setOnDismissListener { apiRefresh = null }
    refresh()
    if (t.apiScan == null) runScan() else status.text = "Canlı istekler sayfada gezindikçe eklenir"
    d.setContentView(root)
    d.show()
}

private fun curl(e: ApiEp): String {
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    val sb = StringBuilder("curl ")
    val m = e.method.ifEmpty { "GET" }
    if (m != "GET" && m != "WS" && m != "SSE") sb.append("-X $m ")
    sb.append(q(e.url))
    if (e.body.isNotEmpty() && !e.body.startsWith("[") && !e.body.startsWith("FormData:")) {
        sb.append(" \\\n  --data ").append(q(e.body))
    }
    return sb.toString()
}

private fun MainActivity.apiDetails(t: MainActivity.Tab, e: ApiEp) {
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    val sb = StringBuilder()
    sb.append(e.url).append("\n\n")
    sb.append("Yöntem: ${e.method.ifEmpty { "bilinmiyor" }}\n")
    sb.append("Kaynak: ${e.source}\n")
    if (e.kind.isNotEmpty()) sb.append("Çağrı türü: ${e.kind}\n")
    if (e.status > 0) sb.append("Durum: ${e.status}\n")
    if (e.ct.isNotEmpty()) sb.append("İçerik türü: ${e.ct}\n")
    if (e.count > 1) sb.append("Çağrı sayısı: ${e.count}\n")
    if (e.live) sb.append("Son çağrı: ${fmt.format(Date(e.time))}\n")
    if (e.note.isNotEmpty()) sb.append("Not: ${e.note}\n")
    val n = ApiFinder.normalize(e.url)
    if (n != e.url.substringBefore('?')) sb.append("Kalıp: $n\n")
    if (e.body.isNotEmpty()) sb.append("\nİstek gövdesi:\n${e.body}\n")
    val tv = TextView(this).apply {
        text = sb
        textSize = 12.5f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setTextColor(textColor())
        setPadding(dp(22), dp(8), dp(22), 0)
    }
    val b = MaterialAlertDialogBuilder(this)
        .setTitle(e.method.ifEmpty { "Adres" } + " · " + (Uri.parse(e.url).path ?: "").takeLast(40))
        .setView(ScrollView(this).apply { addView(tv) })
        .setPositiveButton("cURL kopyala") { _, _ -> copy(curl(e)) }
        .setNegativeButton("Adresi kopyala") { _, _ -> copy(e.url) }
    val getLike = e.method.isEmpty() || e.method == "GET"
    if (getLike && e.url.startsWith("http") && !e.url.contains("{")) {
        b.setNeutralButton("Yanıtı gör") { _, _ ->
            startActivity(Intent(this, SourceActivity::class.java)
                .putExtra(SourceActivity.EXTRA_URL, e.url)
                .putExtra(SourceActivity.EXTRA_UA, currentUA())
                .putExtra(SourceActivity.EXTRA_FETCH, true))
        }
    }
    b.show()
}

private fun MainActivity.exportApis(t: MainActivity.Tab, list: List<ApiEp>) {
    if (list.isEmpty()) { toast("Dışa aktarılacak uç yok"); return }
    val host = t.pageHost.removePrefix("www.")
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    fun text(): String {
        val sb = StringBuilder("# $host API uçları (${list.size}) — Kalkan, $stamp\n")
        list.groupBy { it.source }.forEach { (src, l) ->
            sb.append("\n## $src (${l.size})\n")
            l.sortedBy { it.url }.forEach { e ->
                sb.append(e.method.ifEmpty { "URL" }.padEnd(7)).append(e.url)
                if (e.status > 0) sb.append("  [${e.status}]")
                if (e.note.isNotEmpty()) sb.append("  — ${e.note}")
                sb.append('\n')
            }
        }
        return sb.toString()
    }
    fun json(): String {
        val a = JSONArray()
        list.forEach { e ->
            a.put(JSONObject().put("method", e.method).put("url", e.url).put("pattern", ApiFinder.normalize(e.url))
                .put("source", e.source).put("status", e.status).put("contentType", e.ct).put("count", e.count)
                .put("body", e.body).put("note", e.note))
        }
        return JSONObject().put("site", host).put("created", stamp).put("endpoints", a).toString(2)
    }
    fun postman(): String {
        val items = JSONArray()
        list.filter { it.url.startsWith("http") }.forEach { e ->
            val req = JSONObject().put("method", e.method.ifEmpty { "GET" }.let { if (it == "WS" || it == "SSE") "GET" else it })
                .put("header", JSONArray()).put("url", JSONObject().put("raw", e.url))
            if (e.body.isNotEmpty() && !e.body.startsWith("[")) req.put("body", JSONObject().put("mode", "raw").put("raw", e.body))
            items.put(JSONObject().put("name", (e.method.ifEmpty { "GET" }) + " " + (Uri.parse(e.url).path ?: e.url)).put("request", req))
        }
        return JSONObject()
            .put("info", JSONObject().put("name", "$host (Kalkan)")
                .put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"))
            .put("item", items).toString(2)
    }
    fun save(name: String, mime: String, content: () -> String) {
        ensureStorage {
            Thread {
                val msg = try {
                    val c = content()
                    Saver.save(this, name, mime) { it.write(c.toByteArray()) }
                    "Kaydedildi: İndirilenler/Kalkan/$name"
                } catch (ex: Exception) {
                    "Kaydedilemedi: ${ex.message}"
                }
                runOnUiThread { toast(msg) }
            }.start()
        }
    }
    val base = "kalkan-api-${Saver.sanitize(host)}-$stamp"
    MaterialAlertDialogBuilder(this)
        .setTitle("${list.size} ucu dışa aktar")
        .setItems(arrayOf("Metin olarak kopyala", "Metin dosyası (.txt)", "JSON dosyası", "Postman koleksiyonu")) { _, w ->
            when (w) {
                0 -> copy(text())
                1 -> save("$base.txt", "text/plain") { text() }
                2 -> save("$base.json", "application/json") { json() }
                3 -> save("$base.postman_collection.json", "application/json") { postman() }
            }
        }.show()
}
