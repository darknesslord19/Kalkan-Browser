package app.kalkan.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Filtre listelerini indirir, önbellekler ve motoru kurar. */
object FilterManager {

    data class FList(val id: String, val name: String, val url: String, val defaultOn: Boolean)

    val LISTS = listOf(
        FList("easylist", "EasyList (reklamlar)", "https://easylist.to/easylist/easylist.txt", true),
        FList("easyprivacy", "EasyPrivacy (izleyiciler)", "https://easylist.to/easylist/easyprivacy.txt", true),
        FList("turkish", "AdGuard Türkçe", "https://filters.adtidy.org/extension/ublock/filters/13.txt", true),
        FList("pgl", "Peter Lowe reklam sunucuları",
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext", true),
        FList("hosts", "StevenBlack hosts (sistem geneli)",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts", true),
        FList("cookies", "Çerez bildirimlerini gizle",
            "https://secure.fanboy.co.nz/fanboy-cookiemonster.txt", false),
        FList("annoyances", "Sosyal medya düğmeleri & rahatsız ediciler",
            "https://secure.fanboy.co.nz/fanboy-social.txt", false)
    )

    private const val UPDATE_INTERVAL = 3L * 24 * 60 * 60 * 1000

    @Volatile var engine: AdBlockEngine = AdBlockEngine()
        private set
    @Volatile var ready = false
        private set
    @Volatile var updating = false
        private set

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun notifyListeners() {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it() } }
    }

    fun isEnabled(id: String): Boolean =
        Store.bool("list_$id", LISTS.firstOrNull { it.id == id }?.defaultOn ?: false)

    private fun dir(ctx: Context) = File(ctx.filesDir, "filters").apply { mkdirs() }

    val lastUpdate get() = Store.prefs.getLong("filters_updated", 0)

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute {
            rebuild(app)
            if (System.currentTimeMillis() - lastUpdate > UPDATE_INTERVAL) updateNow(app)
        }
    }

    fun requestUpdate(ctx: Context, onDone: ((Int) -> Unit)? = null) {
        val app = ctx.applicationContext
        worker.execute {
            val n = updateNow(app)
            if (onDone != null) main.post { onDone(n) }
        }
    }

    fun requestRebuild(ctx: Context) {
        val app = ctx.applicationContext
        worker.execute {
            // Etkinleştirilen ama hiç indirilmemiş listeler varsa indir
            val missing = LISTS.any { isEnabled(it.id) && !File(dir(app), "${it.id}.txt").exists() }
            if (missing) updateNow(app) else rebuild(app)
        }
    }

    private fun rebuild(ctx: Context) {
        val e = AdBlockEngine()
        try {
            ctx.assets.open("builtin.txt").bufferedReader().use { e.addReader(it) }
        } catch (_: Exception) {
        }
        for (l in LISTS) {
            if (!isEnabled(l.id)) continue
            val f = File(dir(ctx), "${l.id}.txt")
            if (!f.exists()) continue
            try {
                f.bufferedReader().use { e.addReader(it) }
            } catch (_: Exception) {
            }
        }
        engine = e
        ready = true
        notifyListeners()
    }

    /** @return başarıyla indirilen liste sayısı */
    private fun updateNow(ctx: Context): Int {
        updating = true
        notifyListeners()
        var ok = 0
        for (l in LISTS) {
            if (!isEnabled(l.id)) continue
            try {
                val c = Net.open(l.url)
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", "Kalkan/1.0 (Android)")
                if (c.responseCode == 200) {
                    val tmp = File(dir(ctx), "${l.id}.tmp")
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.length() > 100) {
                        tmp.renameTo(File(dir(ctx), "${l.id}.txt"))
                        ok++
                    }
                }
                c.disconnect()
            } catch (_: Exception) {
            }
        }
        if (ok > 0) Store.prefs.edit().putLong("filters_updated", System.currentTimeMillis()).apply()
        updating = false
        rebuild(ctx)
        return ok
    }
}
