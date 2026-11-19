package app.kalkan.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kalkan'ın bilgi bankası (assets/cs3/kb.json): çalışan eklentilerden (ATEST, HDFilmCehennemi, Donkey ...)
 * öğrenilen site profilleri (kategori listesi, arama kalıbı, X-Requested-With, TMDB ucu, oynatıcı ailesi, AES anahtarları)
 * ve oynatıcı aileleri (bePlayer, ContentX, getVideo, Rapid/CloseLoad ...).
 * Tarama ve üretim sırasında bilinen siteler için eksik bilgiler buradan tamamlanır.
 */
internal object CsBrain {
    @Volatile private var cache: JSONObject? = null

    private fun kb(ctx: Context): JSONObject {
        cache?.let { return it }
        val o = try {
            JSONObject(ctx.assets.open("cs3/kb.json").bufferedReader().use { it.readText() })
        } catch (_: Exception) { JSONObject() }
        cache = o
        return o
    }

    private fun brand(host: String): String =
        host.lowercase().removePrefix("www.").substringBefore('.').filter { !it.isDigit() }

    /** Alan adına (dizipal1820 ≈ dizipal2000 gibi rakam farklarını yok sayarak) uyan site profili. */
    fun site(ctx: Context, host: String): JSONObject? {
        val sites = kb(ctx).optJSONObject("sites") ?: return null
        val h = host.lowercase().removePrefix("www.")
        sites.optJSONObject(h)?.let { return it }
        val b = brand(h)
        if (b.length < 4) return null
        val it = sites.keys()
        while (it.hasNext()) { val k = it.next(); if (brand(k) == b) return sites.optJSONObject(k) }
        return null
    }

    /** Bilinen sitede eksik ipuçlarını (arama kalıbı, XRW, TMDB ucu) doldurur; canlı tarama sonradan üzerine yazar. */
    fun apply(ctx: Context, host: String): String? {
        val s = site(ctx, host) ?: return null
        val cur = CsHints.load(host)
        val parts = ArrayList<String>()
        val sp = s.optString("searchPattern")
        if (sp.isNotEmpty() && cur.optJSONObject("search")?.optString("pattern").orEmpty().isEmpty()) {
            CsHints.merge(host, JSONObject().put("search", JSONObject().put("pattern", sp).put("how", "kb")))
            parts += "arama $sp"
        }
        val misc = JSONObject()
        val curMisc = cur.optJSONObject("misc")
        s.optString("xrw").takeIf { it.isNotEmpty() && curMisc?.optString("xrw").orEmpty().isEmpty() }?.let { misc.put("xrw", it); parts += "XRW $it" }
        s.optString("tmdb").takeIf { it.isNotEmpty() && curMisc?.optString("tmdb").orEmpty().isEmpty() }?.let { misc.put("tmdb", it); parts += "TMDB API" }
        if (misc.length() > 0) CsHints.merge(host, JSONObject().put("misc", misc))
        val pg = s.optJSONArray("mainPages")?.length() ?: 0
        if (pg > 0) parts += "$pg kategori"
        val pl = s.optJSONArray("players")
        if (pl != null && pl.length() > 0) parts += "oynatıcı: " + (0 until minOf(pl.length(), 3)).joinToString(", ") { pl.getJSONObject(it).optString("class") }
        val note = s.optString("note")
        return "Bilgi bankası: ${s.optString("name")} profili tanındı (" + parts.joinToString(" · ") + ")" + (if (note.isNotEmpty()) " — $note" else "")
    }

    /** Bilinen siteden ana sayfa bölümleri: [yol-ya-da-adres, başlık]. */
    fun mainPages(ctx: Context, host: String): List<Pair<String, String>> {
        val a: JSONArray = site(ctx, host)?.optJSONArray("mainPages") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { it.optString(0) to it.optString(1) } }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
    }

    /** Tüm bilinen AES anahtarları (eklentinin anahtar havuzuna tohum olarak verilir). */
    fun aesKeys(ctx: Context): List<String> {
        val out = LinkedHashSet<String>()
        val sites = kb(ctx).optJSONObject("sites") ?: return emptyList()
        val it = sites.keys()
        while (it.hasNext()) { val k = sites.optJSONObject(it.next())?.optJSONArray("aesKeys") ?: continue; for (i in 0 until k.length()) out.add(k.optString(i)) }
        return out.filter { it.length in 8..64 }
    }

    /** Oynatıcı ailesi adı: sayfa metni/adresiyle eşleşen ilk aile(ler). */
    fun families(ctx: Context, text: String): List<String> {
        val f = kb(ctx).optJSONObject("families") ?: return emptyList()
        val out = ArrayList<String>()
        val it = f.keys()
        while (it.hasNext()) {
            val k = it.next()
            val re = f.optJSONObject(k)?.optString("detect").orEmpty()
            if (re.isNotEmpty() && runCatching { Regex(re, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)) out += k
        }
        return out
    }

    /**
     * Bilgi bankasında bu site için çalışan referans eklenti kaynağı varsa (assets/ref/<Ad>.zip) dosyalarını döndürür.
     * Ana sınıftaki alan adı kullanıcının güncel adresine çevrilir. Anahtar: modül içi yol.
     */
    fun refFiles(ctx: Context, host: String, origin: String): Map<String, String>? {
        val dir = site(ctx, host)?.optString("ref").orEmpty().ifEmpty { return null }
        val out = LinkedHashMap<String, String>()
        try {
            java.util.zip.ZipInputStream(ctx.assets.open("ref/$dir.zip")).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    if (e.isDirectory) continue
                    out[e.name.replace('\\', '/')] = z.readBytes().toString(Charsets.UTF_8)
                }
            }
        } catch (_: Exception) { return null }
        val newHost = origin.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
        for ((k, v) in out.entries.toList()) {
            if (!k.endsWith(".kt") || !Regex(":\\s*MainAPI\\(\\)").containsMatchIn(v)) continue
            val m = Regex("override\\s+var\\s+mainUrl\\s*=\\s*\"(https?://[^\"]+)\"").find(v) ?: continue
            val oldUrl = m.groupValues[1]
            val oldHost = oldUrl.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
            var t = v.replace(oldUrl, origin.trimEnd('/'))
            if (oldHost.isNotEmpty() && oldHost != newHost) t = t.replace(oldHost, newHost)
            out[k] = t
        }
        return out
    }

    /** Bu site için bankada çalışan referans eklenti kaynağı var mı? (varsa uzun tarama atlanır) */
    fun hasRef(ctx: Context, host: String): Boolean = site(ctx, host)?.optString("ref").orEmpty().isNotEmpty()

    fun siteName(ctx: Context, host: String): String = site(ctx, host)?.optString("name").orEmpty()
}
