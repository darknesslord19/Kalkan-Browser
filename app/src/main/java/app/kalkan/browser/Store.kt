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
}
