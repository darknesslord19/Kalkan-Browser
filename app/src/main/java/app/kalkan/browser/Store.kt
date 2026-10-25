package app.kalkan.browser

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class Entry(val url: String, var title: String, var time: Long)

data class UserScript(val id: Long, var name: String, var match: String, var code: String, var on: Boolean)

/** Geçmiş, yer imleri, ayarlar ve sayaçlar. */
object Store {
    private lateinit var app: Context
    lateinit var prefs: SharedPreferences
        private set
    private val io = Executors.newSingleThreadExecutor()

    private val history = ArrayList<Entry>()
    private val bookmarks = ArrayList<Entry>()
    val totalBlocked = AtomicLong(0)

    val ENGINES = linkedMapOf(
        "google" to Pair("Google", "https://www.google.com/search?q=%s"),
        "duckduckgo" to Pair("DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        "bing" to Pair("Bing", "https://www.bing.com/search?q=%s"),
        "yandex" to Pair("Yandex", "https://yandex.com.tr/search/?text=%s"),
        "brave" to Pair("Brave Search", "https://search.brave.com/search?q=%s"),
        "startpage" to Pair("Startpage", "https://www.startpage.com/do/search?q=%s")
    )

    val DEFAULT_SITES = listOf(
        Entry("https://www.google.com", "Google", 0),
        Entry("https://m.youtube.com", "YouTube", 0),
        Entry("https://tr.wikipedia.org", "Vikipedi", 0),
        Entry("https://github.com", "GitHub", 0),
        Entry("https://www.reddit.com", "Reddit", 0),
        Entry("https://x.com", "X", 0),
        Entry("https://www.instagram.com", "Instagram", 0),
        Entry("https://eksisozluk.com", "Ekşi Sözlük", 0)
    )

    fun init(ctx: Context) {
        app = ctx.applicationContext
        prefs = PreferenceManager.getDefaultSharedPreferences(app)
        totalBlocked.set(prefs.getLong("total_blocked", 0))
        load("history.json", history)
        load("bookmarks.json", bookmarks)
        loadScripts()
    }

    private fun load(name: String, into: MutableList<Entry>) {
        try {
            val f = File(app.filesDir, name)
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                into.add(Entry(o.getString("u"), o.optString("t"), o.optLong("d")))
            }
        } catch (_: Exception) {
        }
    }

    private fun save(name: String, list: List<Entry>) {
        val snapshot = synchronized(list) { list.map { it.copy() } }
        io.execute {
            try {
                val arr = JSONArray()
                snapshot.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.time)) }
                val f = File(app.filesDir, "$name.tmp")
                f.writeText(arr.toString())
                f.renameTo(File(app.filesDir, name))
            } catch (_: Exception) {
            }
        }
    }

    // ---------- Ayarlar ----------
    fun bool(key: String, def: Boolean) = prefs.getBoolean(key, def)
    fun str(key: String, def: String) = prefs.getString(key, def) ?: def

    val adblock get() = bool("adblock", true)
    val engineKey get() = str("search_engine", "google")
    val searchTemplate get() = (ENGINES[engineKey] ?: ENGINES.getValue("google")).second
    val engineName get() = (ENGINES[engineKey] ?: ENGINES.getValue("google")).first

    fun searchUrl(q: String): String =
        searchTemplate.replace("%s", java.net.URLEncoder.encode(q, "UTF-8"))

    fun allowlist(): Set<String> = prefs.getStringSet("allowlist", emptySet()) ?: emptySet()

    fun isAllowlisted(host: String): Boolean {
        if (host.isEmpty()) return false
        val set = allowlist()
        if (set.isEmpty()) return false
        return AdBlockEngine.forEachSuffix(host) { set.contains(it) }
    }

    fun toggleAllowlist(host: String): Boolean {
        val set = HashSet(allowlist())
        val h = host.removePrefix("www.")
        val now = if (set.contains(h)) { set.remove(h); false } else { set.add(h); true }
        prefs.edit().putStringSet("allowlist", set).apply()
        return now
    }

    fun addBlocked(n: Long = 1) {
        val v = totalBlocked.addAndGet(n)
        if (v % 50 == 0L) persistCounters()
    }

    fun persistCounters() {
        prefs.edit().putLong("total_blocked", totalBlocked.get()).apply()
    }

    // ---------- Geçmiş ----------
    fun addHistory(url: String, title: String) {
        if (url.startsWith(MainActivity.HOME_URL) || url.startsWith("about:") || url.startsWith("data:")) return
        synchronized(history) {
            val last = history.lastOrNull()
            if (last != null && last.url == url) {
                last.title = title.ifEmpty { last.title }
                last.time = System.currentTimeMillis()
            } else {
                history.add(Entry(url, title, System.currentTimeMillis()))
                if (history.size > 3000) history.subList(0, history.size - 3000).clear()
            }
        }
        save("history.json", history)
    }

    fun historyList(q: String = ""): List<Entry> = synchronized(history) {
        val l = history.asReversed().filter {
            q.isEmpty() || it.url.contains(q, true) || it.title.contains(q, true)
        }
        l.take(500)
    }

    fun removeHistory(url: String) {
        synchronized(history) { history.removeAll { it.url == url } }
        save("history.json", history)
    }

    fun clearHistory() {
        synchronized(history) { history.clear() }
        save("history.json", history)
    }

    // ---------- Yer imleri ----------
    fun bookmarkList(q: String = ""): List<Entry> = synchronized(bookmarks) {
        bookmarks.asReversed().filter { q.isEmpty() || it.url.contains(q, true) || it.title.contains(q, true) }
    }

    fun isBookmarked(url: String) = synchronized(bookmarks) { bookmarks.any { it.url == url } }

    /** @return yeni durum (true = eklendi) */
    fun toggleBookmark(url: String, title: String): Boolean {
        val added = synchronized(bookmarks) {
            if (bookmarks.removeAll { it.url == url }) false
            else { bookmarks.add(Entry(url, title, System.currentTimeMillis())); true }
        }
        save("bookmarks.json", bookmarks)
        return added
    }

    fun removeBookmark(url: String) {
        synchronized(bookmarks) { bookmarks.removeAll { it.url == url } }
        save("bookmarks.json", bookmarks)
    }

    // ---------- Öneriler & hızlı erişim ----------
    fun suggest(query: String): List<Entry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val scores = HashMap<String, Pair<Entry, Int>>()
        fun consider(e: Entry, bonus: Int) {
            val u = e.url.lowercase()
            val stripped = u.removePrefix("https://").removePrefix("http://").removePrefix("www.")
            val hit = stripped.startsWith(q) || e.title.lowercase().contains(q) || u.contains(q)
            if (!hit) return
            var s = bonus + (if (stripped.startsWith(q)) 40 else 0)
            val prev = scores[e.url]
            if (prev != null) s += prev.second
            scores[e.url] = Pair(e, s)
        }
        synchronized(bookmarks) { bookmarks.forEach { consider(it, 60) } }
        synchronized(history) { history.forEach { consider(it, 5) } }
        return scores.values.sortedByDescending { it.second }.take(6).map { it.first }
    }

    fun topSites(): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        synchronized(bookmarks) { bookmarks.asReversed().take(8).forEach { out[hostKey(it.url)] = it } }
        if (out.size < 8) {
            val counts = HashMap<String, Pair<Entry, Int>>()
            synchronized(history) {
                history.forEach {
                    val k = hostKey(it.url)
                    val p = counts[k]
                    counts[k] = Pair(p?.first ?: Entry(rootOf(it.url), it.title, 0), (p?.second ?: 0) + 1)
                }
            }
            counts.values.filter { it.second >= 2 }.sortedByDescending { it.second }.forEach {
                if (out.size < 8 && !out.containsKey(hostKey(it.first.url))) out[hostKey(it.first.url)] = it.first
            }
        }
        for (d in DEFAULT_SITES) {
            if (out.size >= 8) break
            if (!out.containsKey(hostKey(d.url))) out[hostKey(d.url)] = d
        }
        return out.values.toList()
    }

    private fun hostKey(url: String) = try {
        (android.net.Uri.parse(url).host ?: url).removePrefix("www.").removePrefix("m.")
    } catch (_: Exception) { url }

    private fun rootOf(url: String) = try {
        val u = android.net.Uri.parse(url)
        "${u.scheme}://${u.host}"
    } catch (_: Exception) { url }

    // ---------- Site kuralları (öğe gizleme / otomatik basma) ----------
    private fun rules(kind: String): JSONObject = try {
        JSONObject(prefs.getString("rules_$kind", "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    fun rulesFor(kind: String, host: String): List<String> {
        val o = rules(kind)
        val out = ArrayList<String>()
        AdBlockEngine.forEachSuffix(host.lowercase().removePrefix("www.")) { h ->
            o.optJSONArray(h)?.let { a -> for (i in 0 until a.length()) out.add(a.getString(i)) }
            false
        }
        return out
    }

    fun addRule(kind: String, host: String, sel: String) {
        val o = rules(kind)
        val h = host.lowercase().removePrefix("www.")
        val a = o.optJSONArray(h) ?: JSONArray()
        for (i in 0 until a.length()) if (a.getString(i) == sel) return
        a.put(sel)
        o.put(h, a)
        prefs.edit().putString("rules_$kind", o.toString()).apply()
    }

    fun removeRule(kind: String, host: String, sel: String) {
        val o = rules(kind)
        val a = o.optJSONArray(host) ?: return
        val n = JSONArray()
        for (i in 0 until a.length()) if (a.getString(i) != sel) n.put(a.getString(i))
        if (n.length() == 0) o.remove(host) else o.put(host, n)
        prefs.edit().putString("rules_$kind", o.toString()).apply()
    }

    /** (site, seçici) çiftleri */
    fun allRules(kind: String): List<Pair<String, String>> {
        val o = rules(kind)
        val out = ArrayList<Pair<String, String>>()
        o.keys().forEach { h ->
            val a = o.optJSONArray(h) ?: return@forEach
            for (i in 0 until a.length()) out.add(Pair(h, a.getString(i)))
        }
        return out.sortedBy { it.first }
    }

    // ---------- Kullanıcı betikleri ----------
    private val scripts = ArrayList<UserScript>()

    private fun loadScripts() {
        try {
            val f = File(app.filesDir, "scripts.json")
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                scripts.add(UserScript(o.getLong("id"), o.optString("name"), o.optString("match", "*"),
                    o.optString("code"), o.optBoolean("on", true)))
            }
        } catch (_: Exception) {
        }
    }

    private fun saveScripts() {
        val arr = JSONArray()
        synchronized(scripts) {
            scripts.forEach {
                arr.put(JSONObject().put("id", it.id).put("name", it.name).put("match", it.match)
                    .put("code", it.code).put("on", it.on))
            }
        }
        val text = arr.toString()
        io.execute {
            try { File(app.filesDir, "scripts.json").writeText(text) } catch (_: Exception) {}
        }
    }

    fun scriptList(): List<UserScript> = synchronized(scripts) { scripts.toList() }

    fun saveScript(sc: UserScript) {
        synchronized(scripts) {
            val i = scripts.indexOfFirst { it.id == sc.id }
            if (i >= 0) scripts[i] = sc else scripts.add(sc)
        }
        saveScripts()
    }

    fun deleteScript(id: Long) {
        synchronized(scripts) { scripts.removeAll { it.id == id } }
        saveScripts()
    }

    fun scriptMatches(sc: UserScript, host: String): Boolean {
        val h = host.lowercase()
        return sc.match.split(',', ' ', '\n').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.any { p ->
            when {
                p == "*" -> true
                p.startsWith("*.") -> { val b = p.substring(2); h == b || h.endsWith(".$b") }
                else -> { val b = p.removePrefix("www."); h == b || h.endsWith(".$b") }
            }
        }
    }

    fun scriptsFor(host: String): List<UserScript> = scriptList().filter { it.on && scriptMatches(it, host) }

    // ---------- Yedekleme ----------
    private val NO_BACKUP = setOf("session", "session_index", "wg_config", "wg_name", "dns_vpn_on")

    fun exportJson(): String {
        val p = JSONObject()
        for ((k, v) in prefs.all) {
            if (k in NO_BACKUP) continue
            val o = JSONObject()
            when (v) {
                is Boolean -> o.put("t", "b").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is String -> o.put("t", "s").put("v", v)
                is Set<*> -> o.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            p.put(k, o)
        }
        fun list(l: List<Entry>) = JSONArray().apply {
            l.forEach { put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.time)) }
        }
        val sc = JSONArray()
        scriptList().forEach {
            sc.put(JSONObject().put("id", it.id).put("name", it.name).put("match", it.match).put("code", it.code).put("on", it.on))
        }
        return JSONObject()
            .put("app", "Kalkan")
            .put("version", 1)
            .put("created", System.currentTimeMillis())
            .put("prefs", p)
            .put("bookmarks", list(synchronized(bookmarks) { bookmarks.toList() }))
            .put("history", list(synchronized(history) { history.toList() }))
            .put("scripts", sc)
            .toString(2)
    }

    /** @return içe aktarılan öğe sayısı */
    fun importJson(text: String): Int {
        val root = JSONObject(text)
        if (root.optString("app") != "Kalkan") throw IllegalArgumentException("Bu bir Kalkan yedeği değil")
        var n = 0
        root.optJSONObject("prefs")?.let { p ->
            val e = prefs.edit()
            p.keys().forEach { k ->
                if (k in NO_BACKUP) return@forEach
                val o = p.getJSONObject(k)
                when (o.optString("t")) {
                    "b" -> e.putBoolean(k, o.getBoolean("v"))
                    "i" -> e.putInt(k, o.getInt("v"))
                    "l" -> e.putLong(k, o.getLong("v"))
                    "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> e.putString(k, o.getString("v"))
                    "set" -> {
                        val a = o.getJSONArray("v")
                        e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                    }
                }
                n++
            }
            e.apply()
            totalBlocked.set(prefs.getLong("total_blocked", totalBlocked.get()))
        }
        fun merge(key: String, into: MutableList<Entry>, file: String) {
            val a = root.optJSONArray(key) ?: return
            synchronized(into) {
                val have = into.map { it.url }.toHashSet()
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val u = o.getString("u")
                    if (u in have) continue
                    into.add(Entry(u, o.optString("t"), o.optLong("d")))
                    have.add(u)
                    n++
                }
                into.sortBy { it.time }
            }
            save(file, into)
        }
        merge("bookmarks", bookmarks, "bookmarks.json")
        merge("history", history, "history.json")
        root.optJSONArray("scripts")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                saveScript(UserScript(o.getLong("id"), o.optString("name"), o.optString("match", "*"),
                    o.optString("code"), o.optBoolean("on", true)))
                n++
            }
        }
        return n
    }

    fun clearSession() {
        prefs.edit().putString("session", "[]").putInt("session_index", 0).apply()
    }
}
