package app.kalkan.browser

import java.io.BufferedReader

/**
 * EasyList / AdGuard / hosts uyumlu reklam engelleme motoru.
 * - Ağ kuralları: ||alan.adi^ (hızlı küme), yol/opsiyonlu kurallar, istisnalar (@@)
 * - Kozmetik kurallar: ##seçici, alan.com##seçici, #@# istisnaları
 */
class AdBlockEngine {

    companion object {
        const val T_SCRIPT = 1
        const val T_IMAGE = 2
        const val T_CSS = 4
        const val T_XHR = 8
        const val T_SUBDOC = 16
        const val T_MEDIA = 32
        const val T_FONT = 64
        const val T_OTHER = 128
        const val T_OBJECT = 256
        const val T_PING = 512
        const val T_WS = 1024

        private val TYPE_NAMES = mapOf(
            "script" to T_SCRIPT, "image" to T_IMAGE, "stylesheet" to T_CSS, "css" to T_CSS,
            "xmlhttprequest" to T_XHR, "xhr" to T_XHR, "subdocument" to T_SUBDOC, "frame" to T_SUBDOC,
            "media" to T_MEDIA, "font" to T_FONT, "other" to T_OTHER, "object" to T_OBJECT,
            "ping" to T_PING, "beacon" to T_PING, "websocket" to T_WS
        )

        private val SLDS = setOf(
            "co", "com", "org", "net", "gov", "edu", "ac", "gen", "web", "bel", "k12", "av",
            "bbs", "biz", "info", "tv", "pol", "tsk", "name", "dr", "or", "ne", "go", "mil"
        )

        private val COMMON_TOKENS = setOf("http", "https", "www", "com", "net", "org", "html", "php")

        private val BAD_SELECTOR = listOf(
            ":-abp-", ":has-text(", ":upward(", ":remove(", ":style(", ":matches-css", ":xpath(",
            ":min-text-length", ":watch-attr", ":others(", ":matches-path(", ":contains(",
            ":matches-attr(", ":matches-prop(", ":remove-attr(", ":remove-class(", ":if(", ":if-not("
        )

        private val SIMPLE_KEY = Regex("^([.#])([A-Za-z0-9_-]+)")
        private val COSMETIC_DOMAINS = Regex("^[a-z0-9.,~*_-]*$")

        fun baseDomain(host: String): String {
            if (host.isEmpty()) return host
            if (host[0].isDigit() && host.all { it.isDigit() || it == '.' }) return host
            val parts = host.split('.')
            if (parts.size <= 2) return host
            val tld = parts[parts.size - 1]
            val sld = parts[parts.size - 2]
            return if (tld.length == 2 && sld in SLDS) parts.takeLast(3).joinToString(".")
            else parts.takeLast(2).joinToString(".")
        }

        fun domainMatch(host: String, d: String): Boolean {
            if (host.isEmpty()) return false
            if (d.endsWith(".*")) {
                val base = d.substring(0, d.length - 2)
                return host.startsWith("$base.") || host.contains(".$base.")
            }
            return host == d || host.endsWith(".$d")
        }

        /** a.b.c.com -> [a.b.c.com, b.c.com, c.com, com] */
        inline fun forEachSuffix(host: String, fn: (String) -> Boolean): Boolean {
            var h = host
            while (true) {
                if (fn(h)) return true
                val i = h.indexOf('.')
                if (i < 0) return false
                h = h.substring(i + 1)
            }
        }

        fun toRegex(p: String): String {
            val sb = StringBuilder()
            var s = p
            if (s.startsWith("||")) {
                sb.append("^[a-z][a-z0-9+.-]*://([^/?#]*\\.)?")
                s = s.substring(2)
            } else if (s.startsWith("|")) {
                sb.append("^")
                s = s.substring(1)
            }
            var end = ""
            if (s.endsWith("|")) {
                end = "$"
                s = s.dropLast(1)
            }
            for (c in s) {
                when (c) {
                    '*' -> sb.append(".*")
                    '^' -> sb.append("(?:[^a-z0-9_.%-]|$)")
                    '\\', '.', '+', '?', '(', ')', '[', ']', '{', '}', '$', '|' -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
            }
            sb.append(end)
            return sb.toString()
        }

        private fun isTokenChar(c: Char) = (c in 'a'..'z') || (c in '0'..'9') || c == '%'

        fun bestToken(pattern: String): String? {
            var p = pattern
            var leftAnchored = false
            var rightAnchored = false
            if (p.startsWith("||")) { p = p.substring(2); leftAnchored = true }
            else if (p.startsWith("|")) { p = p.substring(1); leftAnchored = true }
            if (p.endsWith("|")) { p = p.dropLast(1); rightAnchored = true }
            var best: String? = null
            var i = 0
            val n = p.length
            while (i < n) {
                if (!isTokenChar(p[i])) { i++; continue }
                val start = i
                while (i < n && isTokenChar(p[i])) i++
                val end = i
                if (end - start < 3) continue
                val okLeft = if (start == 0) leftAnchored else p[start - 1] != '*'
                val okRight = if (end == n) rightAnchored else p[end] != '*'
                if (!okLeft || !okRight) continue
                val tok = p.substring(start, end)
                if (tok in COMMON_TOKENS) continue
                if (best == null || tok.length > best.length) best = tok
            }
            return best
        }

        fun urlTokens(lurl: String): List<String> {
            val out = ArrayList<String>(24)
            var i = 0
            val n = lurl.length
            while (i < n) {
                if (!isTokenChar(lurl[i])) { i++; continue }
                val start = i
                while (i < n && isTokenChar(lurl[i])) i++
                if (i - start >= 3) out.add(lurl.substring(start, i))
            }
            return out
        }
    }

    class Opts {
        var third: Boolean? = null
        var types = 0
        var notTypes = 0
        var domains: List<String>? = null
        var notDomains: List<String>? = null
        var important = false
        var hide: String? = null
        var document = false
        fun isPlain() = third == null && types == 0 && notTypes == 0 && domains == null &&
            notDomains == null && !important
    }

    class NetRule(
        private val pattern: String?,
        val third: Boolean?,
        val types: Int,
        val notTypes: Int,
        val domains: List<String>?,
        val notDomains: List<String>?,
        val important: Boolean
    ) {
        @Volatile private var re: Regex? = null
        @Volatile private var broken = false

        private fun regex(): Regex? {
            if (broken) return null
            re?.let { return it }
            return try {
                Regex(pattern!!).also { re = it }
            } catch (e: Exception) {
                broken = true; null
            }
        }

        fun matches(lurl: String, ctx: Ctx): Boolean {
            if (third != null && third != ctx.third) return false
            if (ctx.type != 0) {
                if (types != 0 && (types and ctx.type) == 0) return false
                if ((notTypes and ctx.type) != 0) return false
            }
            domains?.let { list -> if (list.none { domainMatch(ctx.pageHost, it) }) return false }
            notDomains?.let { list -> if (list.any { domainMatch(ctx.pageHost, it) }) return false }
            if (pattern != null) {
                val r = regex() ?: return false
                if (!r.containsMatchIn(lurl)) return false
            }
            return true
        }
    }

    class Ctx(val type: Int, val third: Boolean, val pageHost: String)

    // Ağ kuralları
    val blockDomains = HashSet<String>(1 shl 17)
    private val allowDomains = HashSet<String>()
    private val hostRules = HashMap<String, MutableList<NetRule>>()
    private val allowHostRules = HashMap<String, MutableList<NetRule>>()
    private val tokenRules = HashMap<String, MutableList<NetRule>>()
    private val allowTokenRules = HashMap<String, MutableList<NetRule>>()
    private val docAllow = HashSet<String>()

    // Kozmetik kurallar
    private val genericByKey = HashMap<String, MutableList<String>>() // ".cls" / "#id" -> seçiciler
    private val genericComplex = ArrayList<String>()
    private val genericExcl = ArrayList<Pair<String, List<String>>>()
    private val genericExceptions = HashSet<String>()
    private val specificHide = HashMap<String, MutableList<String>>()
    private val specificExceptions = HashMap<String, MutableSet<String>>()
    private val noCosmetic = HashSet<String>()
    private val noGeneric = HashSet<String>()

    var networkRuleCount = 0
        private set
    var cosmeticRuleCount = 0
        private set

    private val specificCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 64
    }

    // ---------------- Ayrıştırma ----------------

    fun addReader(reader: BufferedReader) {
        reader.useLines { lines -> lines.forEach { addLine(it.trim()) } }
    }

    fun addText(text: String) {
        text.lineSequence().forEach { addLine(it.trim()) }
    }

    fun addLine(line: String) {
        if (line.isEmpty() || line[0] == '!' || line[0] == '[') return
        if (line[0] == '#' && !line.startsWith("##") && !line.startsWith("#@#")) return // hosts yorumları

        val hash = line.indexOf('#')
        if (hash >= 0) {
            val prefix = line.substring(0, hash)
            if (COSMETIC_DOMAINS.matches(prefix.lowercase())) {
                val rest = line.substring(hash)
                when {
                    rest.startsWith("##") || rest.startsWith("#@#") -> { addCosmetic(prefix, rest); return }
                    rest.startsWith("#?#") || rest.startsWith("#$#") || rest.startsWith("#@?#") ||
                        rest.startsWith("#@$#") || rest.startsWith("#%#") || rest.startsWith("#@%#") -> return
                }
            }
        }

        // hosts dosyası biçimi
        if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
            val d = line.substringAfter(' ').trim().substringBefore(' ').substringBefore('#').trim().lowercase()
            if (d.isNotEmpty() && d != "localhost" && d != "0.0.0.0" && d.contains('.')) {
                blockDomains.add(d); networkRuleCount++
            }
            return
        }
        addNetwork(line)
    }

    private fun addCosmetic(domainsPart: String, rest: String) {
        val exception = rest.startsWith("#@#")
        val sel = rest.substring(if (exception) 3 else 2).trim()
        if (sel.isEmpty() || sel.startsWith("+js(") || sel.startsWith("^") || sel.startsWith("%")) return
        if (BAD_SELECTOR.any { sel.contains(it) }) return
        val domains = domainsPart.lowercase().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val pos = domains.filter { !it.startsWith("~") }
        if (exception) {
            if (pos.isEmpty()) genericExceptions.add(sel)
            else pos.forEach { specificExceptions.getOrPut(it) { HashSet() }.add(sel) }
            return
        }
        cosmeticRuleCount++
        if (pos.isEmpty()) {
            if (domains.isEmpty()) addGeneric(sel)
            else genericExcl.add(sel to domains.map { it.removePrefix("~") })
            return
        }
        pos.forEach { specificHide.getOrPut(it) { ArrayList(2) }.add(sel) }
    }

    private fun addGeneric(sel: String) {
        val m = SIMPLE_KEY.find(sel)
        if (m != null && !sel.contains(',')) {
            val key = m.groupValues[1] + m.groupValues[2]
            genericByKey.getOrPut(key) { ArrayList(1) }.add(sel)
        } else {
            genericComplex.add(sel)
        }
    }

    private fun parseOpts(s: String): Opts? {
        val o = Opts()
        for (raw in s.split(',')) {
            var opt = raw.trim()
            if (opt.isEmpty()) continue
            var neg = false
            if (opt.startsWith("~")) { neg = true; opt = opt.substring(1) }
            val lower = opt.lowercase()
            when {
                lower == "third-party" || lower == "3p" -> o.third = !neg
                lower == "first-party" || lower == "1p" -> o.third = neg
                TYPE_NAMES.containsKey(lower) -> {
                    val bit = TYPE_NAMES.getValue(lower)
                    if (neg) o.notTypes = o.notTypes or bit else o.types = o.types or bit
                }
                lower.startsWith("domain=") || lower.startsWith("from=") -> {
                    val pos = ArrayList<String>()
                    val negs = ArrayList<String>()
                    lower.substringAfter('=').split('|').forEach { d ->
                        if (d.isEmpty()) return@forEach
                        if (d.startsWith("~")) negs.add(d.substring(1)) else pos.add(d)
                    }
                    if (pos.isNotEmpty()) o.domains = pos
                    if (negs.isNotEmpty()) o.notDomains = negs
                }
                lower == "important" -> o.important = true
                lower == "match-case" || lower == "all" -> {}
                lower == "elemhide" || lower == "ehide" -> o.hide = "elem"
                lower == "generichide" || lower == "ghide" -> o.hide = "generic"
                lower == "document" || lower == "doc" -> o.document = true
                else -> return null // desteklenmeyen seçenek -> kuralı atla
            }
        }
        return o
    }

    private fun hostOf(pattern: String): String? {
        if (!pattern.startsWith("||")) return null
        val body = pattern.substring(2)
        val host = body.takeWhile { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
        if (host.isEmpty() || !host.contains('.') || host.endsWith(".")) return null
        return host
    }

    private fun addNetwork(raw: String) {
        var line = raw
        var allow = false
        if (line.startsWith("@@")) { allow = true; line = line.substring(2) }
        if (line.startsWith("/") && (line.endsWith("/") || line.contains("/$"))) return // regex kuralları
        var pattern = line
        var opts = Opts()
        val d = line.lastIndexOf('$')
        if (d > 0) {
            opts = parseOpts(line.substring(d + 1)) ?: return
            pattern = line.substring(0, d)
        }
        pattern = pattern.lowercase()
        if (pattern.isEmpty() || pattern == "*" || pattern == "|" || pattern == "||") return

        if (opts.hide != null) {
            if (allow) {
                val h = hostOf(pattern) ?: return
                if (opts.hide == "elem") noCosmetic.add(h) else noGeneric.add(h)
            }
            return
        }
        if (opts.document) {
            if (allow) hostOf(pattern)?.let { docAllow.add(it) }
            return
        }

        networkRuleCount++
        val host = hostOf(pattern)
        if (host != null) {
            val after = pattern.substring(2 + host.length)
            val pure = after.isEmpty() || after == "^" || after == "^|"
            if (pure && opts.isPlain()) {
                (if (allow) allowDomains else blockDomains).add(host)
                return
            }
            if (after.isEmpty() || after[0] == '^' || after[0] == '/' || after[0] == ':') {
                val rule = NetRule(
                    if (pure) null else toRegex(pattern), opts.third, opts.types, opts.notTypes,
                    opts.domains, opts.notDomains, opts.important
                )
                (if (allow) allowHostRules else hostRules).getOrPut(host) { ArrayList(1) }.add(rule)
                return
            }
        }
        val token = bestToken(pattern) ?: return
        val rule = NetRule(
            toRegex(pattern), opts.third, opts.types, opts.notTypes,
            opts.domains, opts.notDomains, opts.important
        )
        (if (allow) allowTokenRules else tokenRules).getOrPut(token) { ArrayList(2) }.add(rule)
    }

    // ---------------- Eşleştirme ----------------

    /** Sadece alan adı listesi (DNS koruması için). */
    fun isDomainBlocked(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (forEachSuffix(h) { allowDomains.contains(it) }) return false
        return forEachSuffix(h) { blockDomains.contains(it) }
    }

    fun shouldBlock(url: String, host: String, pageHost: String, type: Int): Boolean {
        if (host.isEmpty()) return false
        if (pageHost.isNotEmpty() && forEachSuffix(pageHost) { docAllow.contains(it) }) return false
        val third = pageHost.isNotEmpty() && baseDomain(host) != baseDomain(pageHost)
        val ctx = Ctx(type, third, pageHost)
        val lurl = url.lowercase()

        var blocked = false
        var important = false
        if (forEachSuffix(host) { blockDomains.contains(it) }) blocked = true
        if (!blocked || !important) {
            forEachSuffix(host) { h ->
                hostRules[h]?.forEach { r ->
                    if (r.matches(lurl, ctx)) {
                        blocked = true
                        if (r.important) important = true
                    }
                }
                important
            }
        }
        var tokens: List<String>? = null
        if (!blocked) {
            tokens = urlTokens(lurl)
            loop@ for (t in tokens) {
                val list = tokenRules[t] ?: continue
                for (r in list) {
                    if (r.matches(lurl, ctx)) {
                        blocked = true
                        if (r.important) important = true
                        break@loop
                    }
                }
            }
        }
        if (!blocked) return false
        if (important) return true

        // İstisnalar
        if (forEachSuffix(host) { allowDomains.contains(it) }) return false
        if (forEachSuffix(host) { h -> allowHostRules[h]?.any { it.matches(lurl, ctx) } == true }) return false
        val toks = tokens ?: urlTokens(lurl)
        for (t in toks) {
            val list = allowTokenRules[t] ?: continue
            if (list.any { it.matches(lurl, ctx) }) return false
        }
        return true
    }

    private fun hostExceptions(host: String): Set<String> {
        val out = HashSet<String>(genericExceptions)
        forEachSuffix(host) { h -> specificExceptions[h]?.let { out.addAll(it) }; false }
        return out
    }

    fun cosmeticAllowed(host: String): Boolean = !forEachSuffix(host) { noCosmetic.contains(it) }

    private fun genericAllowed(host: String): Boolean = !forEachSuffix(host) { noGeneric.contains(it) }

    private fun css(selectors: Collection<String>): String {
        if (selectors.isEmpty()) return ""
        val sb = StringBuilder(selectors.size * 40)
        for (s in selectors) sb.append(s).append("{display:none!important}\n")
        return sb.toString()
    }

    /** Siteye özel + karmaşık genel seçiciler. */
    fun specificCss(host: String): String {
        if (!cosmeticAllowed(host)) return ""
        synchronized(specificCache) { specificCache[host]?.let { return it } }
        val ex = hostExceptions(host)
        val sel = LinkedHashSet<String>()
        forEachSuffix(host) { h -> specificHide[h]?.let { sel.addAll(it) }; false }
        if (genericAllowed(host)) {
            sel.addAll(genericComplex)
            for ((s, excluded) in genericExcl) if (excluded.none { domainMatch(host, it) }) sel.add(s)
        }
        sel.removeAll(ex)
        val out = css(sel)
        synchronized(specificCache) { specificCache[host] = out }
        return out
    }

    /** Sayfada görülen sınıf ve kimliklere göre genel gizleme seçicileri. */
    fun genericCss(classes: List<String>, ids: List<String>, host: String): String {
        if (!cosmeticAllowed(host) || !genericAllowed(host)) return ""
        val out = ArrayList<String>()
        for (c in classes) genericByKey[".$c"]?.let { out.addAll(it) }
        for (i in ids) genericByKey["#$i"]?.let { out.addAll(it) }
        if (out.isEmpty()) return ""
        val ex = hostExceptions(host)
        return css(out.filter { it !in ex })
    }
}
