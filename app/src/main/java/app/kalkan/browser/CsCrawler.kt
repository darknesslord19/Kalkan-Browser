package app.kalkan.browser

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Arka plan gezgini: gizli bir WebView ile siteyi kendisi dolaşır (ana sayfa → ilk film/dizi →
 * ilk bölüm → arama kutusuna gerçekten yazıp arama), bulduklarını CsHints'e birleştirir ve
 * her adım için denetim satırı üretir. Eksikler eklentiye çalışma anı yedekleriyle telafi edilir.
 */
internal object CsCrawler {
    private val H = Handler(Looper.getMainLooper())

    val SEARCH_JS = """
(function(phrase){try{
window.__kq=[];
var of=window.fetch;if(of)window.fetch=function(u,o){try{window.__kq.push([(o&&o.method)||'GET',(typeof u==='string'?u:(u&&u.url)||''),(o&&o.body)?String(o.body):'']);}catch(e){}return of.apply(this,arguments);};
var ox=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.open=function(m,u){this.__m=m;this.__u=u;return ox.apply(this,arguments);};
XMLHttpRequest.prototype.send=function(b){try{window.__kq.push([this.__m||'GET',this.__u||'',b?String(b):'']);}catch(e){}return os.apply(this,arguments);};
var sel='input[type=search],input[name=s],input[name=q],input[name=query],input[name=search],input[name=keyword],input[name=story],input[name=term],input[name=arama],input[placeholder*="ara" i],input[placeholder*="search" i],input[id*="search" i],input[class*="search" i],input[aria-label*="ara" i],input[aria-label*="search" i]';
var si=document.querySelector(sel);
if(!si){var SR=/(search|arama|\bara\b|find|query)/i;
  si=Array.prototype.filter.call(document.querySelectorAll('input[type=text],input:not([type])'),function(e){
    var par=e.closest('[class*=search i],[id*=search i],[role=search],form');
    return SR.test((e.id||'')+' '+(e.className||'')+' '+(e.name||'')+' '+(e.placeholder||'')+' '+(par?(par.className||'')+' '+(par.id||''):''));})[0]||null;}
if(!si)return JSON.stringify({err:'kutu yok'});
var tg=document.querySelector('[class*=search-toggle i],[class*=search-icon i],[class*=search-btn i],a[href="#search"],button[aria-label*=search i]');
try{if(tg&&tg.click)tg.click();}catch(e){}
try{si.focus();}catch(e){}
var setter=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;setter.call(si,phrase);
['input','change','keyup'].forEach(function(t){si.dispatchEvent(new Event(t,{bubbles:true}));});
si.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
si.dispatchEvent(new KeyboardEvent('keypress',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
si.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));
var f=si.form;if(f){try{if(f.requestSubmit)f.requestSubmit();else f.submit();}catch(e){}}
return JSON.stringify({ok:1});
}catch(e){return JSON.stringify({err:String(e)});}})
"""

    /** Sayfa yüklendi + oturması için bekleme; yönlendirmelerde son yükleme kazanır. */
    private fun load(wv: WebView, url: String, settleMs: Long, timeoutMs: Long, cb: () -> Unit) {
        val seq = AtomicInteger(0)
        var fired = false
        fun fire() { if (!fired) { fired = true; cb() } }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String?) {
                val my = seq.incrementAndGet()
                H.postDelayed({ if (seq.get() == my) fire() }, settleMs)
            }
        }
        H.postDelayed({ fire() }, timeoutMs)
        wv.loadUrl(url)
    }

    private fun evalJson(wv: WebView, js: String, cb: (JSONObject?) -> Unit) {
        wv.evaluateJavascript(js) { r ->
            val o = try {
                if (r.isNullOrBlank() || r == "null") null else {
                    val v = JSONTokener(r).nextValue()
                    when (v) {
                        is JSONObject -> v
                        is String -> JSONObject(v)
                        else -> null
                    }
                }
            } catch (_: Exception) { null }
            cb(o)
        }
    }

    private fun analyze(wv: WebView, host: String, cb: () -> Unit) {
        evalJson(wv, CsAnalyzer.JS) { o ->
            if (o != null && !o.has("error")) CsHints.merge(host, o)
            cb()
        }
    }

    /** Arama için sitede yazılan sorgudan adres kalıbı çıkarır (%s = sorgu). */
    private fun derive(origin: String, startUrl: String, href: String, kq: JSONArray?, phrase: String): JSONObject? {
        val enc = URLEncoder.encode(phrase, "UTF-8")
        val variants = listOf(enc, enc.replace("+", "%20"), phrase, phrase.replace(" ", "+"), phrase.replace(" ", "-").lowercase())
            .distinct()
        val host = Uri.parse(origin).host?.removePrefix("www.")
        fun rel(u: String): String? = try {
            val a = Uri.parse(java.net.URL(java.net.URL(origin), u).toString())
            if (a.host?.removePrefix("www.") != host) null else (a.encodedPath ?: "/") + (a.encodedQuery?.let { "?$it" } ?: "")
        } catch (_: Exception) { null }
        fun repl(s: String): String? {
            for (v in variants) {
                val i = s.indexOf(v, ignoreCase = true)
                if (i >= 0) return s.substring(0, i) + "%s" + s.substring(i + v.length)
            }
            return null
        }
        // 1) adres çubuğu değişti mi
        if (href != startUrl) rel(href)?.let { r -> repl(r)?.let { return JSONObject().put("pattern", it).put("how", "form") } }
        // 2) sayfa içi AJAX / fetch
        if (kq != null) for (i in 0 until kq.length()) {
            val e = kq.optJSONArray(i) ?: continue
            val method = e.optString(0).uppercase()
            val u = e.optString(1)
            val body = e.optString(2)
            val r = rel(u) ?: continue
            if (method == "POST" && body.isNotEmpty()) {
                val params = LinkedHashMap<String, String>()
                var field: String? = null
                for (kv in body.split('&')) {
                    val k = kv.substringBefore('=')
                    val v = try { java.net.URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8") } catch (_: Exception) { "" }
                    if (v.equals(phrase, ignoreCase = true)) field = k else if (k.isNotEmpty()) params[k] = v
                }
                if (field != null) {
                    val ex = JSONObject(); params.forEach { (k, v) -> ex.put(k, v) }
                    return JSONObject().put("method", "post").put("path", r.substringBefore('?')).put("param", field).put("params", ex).put("how", "ajax")
                }
            } else {
                repl(r)?.let { return JSONObject().put("pattern", it).put("how", "ajax") }
            }
        }
        return null
    }

    private fun hasSane(l: JSONObject?): Boolean = l != null && l.optInt("matches") in 3..400

    /** Satırları ipuçlarından hesaplar. */
    fun evalRows(rows: List<AuditRow>, h: JSONObject, scanned: Set<String>) {
        fun row(k: String) = rows.first { it.key == k }
        fun set(k: String, s: Int, n: String) { row(k).state = s; row(k).note = n }
        val l = h.optJSONObject("list")
        val d = h.optJSONObject("detail")
        val p = h.optJSONObject("player")
        if (l == null) set("home", St.AUTO, "Kart seçici bulunamadı. Eklenti çalışma anında görselli film bağlantılarını kendisi bulacak.")
        else if (!hasSane(l)) set("home", St.AUTO, "${l.optInt("count")} kart ama seçici güvenilmez (${l.optString("card")}). Yedek kart bulucu devrede.")
        else set("home", St.OK, "${l.optInt("count")} kart · ${l.optString("card")}")
        if (l == null) set("poster", St.WAIT, "")
        else if (l.optString("posterAttr") == "src") set("poster", St.AUTO, "Afişler gecikmeli yükleniyor olabilir. data-src → data-original → src sırası denenecek.")
        else set("poster", St.OK, "Afiş alanı: ${l.optString("posterAttr")}")
        if (d != null && (d.optString("title").isNotEmpty() || d.optBoolean("ogTitle"))) {
            set("detail", St.OK, listOfNotNull(
                d.optString("title").ifEmpty { null }?.let { "başlık $it" },
                if (d.optBoolean("ogImage")) "afiş meta" else null,
                if (d.optString("plot").isNotEmpty() || d.optBoolean("ogDesc")) "özet" else null
            ).joinToString(" · "))
        } else if ("detail" in scanned) set("detail", St.WARN, "Detay sayfasında başlık bulunamadı; h1 ve og:title yedekleri kullanılacak.")
        else set("detail", St.WARN, "Detay sayfası taranamadı (ana sayfada kart bulunamadı). Bir film sayfasında “CS analizi” çalıştır.")
        val epCount = d?.optInt("episodeCount") ?: 0
        if (d != null && d.optString("episodes").isNotEmpty()) {
            if (epCount in 2..400) set("episodes", St.OK, "$epCount bölüm · ${d.optString("episodes")}")
            else set("episodes", St.WARN, "Bölüm sayısı şüpheli ($epCount). Eklenti yalnızca “Bölüm N” kalıbına uyan bağlantıları alır.")
        } else if ("detail" in scanned) set("episodes", St.OK, "Bölüm listesi yok — film sitesi gibi görünüyor (bölümler film olarak ele alınır).")
        else set("episodes", St.WARN, "Bölüm listesi için bir dizi detay sayfasında “CS analizi” çalıştır.")
        if (p != null) {
            val parts = ArrayList<String>()
            p.optJSONArray("iframes")?.length()?.takeIf { it > 0 }?.let { parts += "$it iframe" }
            if (p.optBoolean("video")) parts += "video etiketi"
            p.optJSONArray("m3u8")?.length()?.takeIf { it > 0 }?.let { parts += "$it m3u8" }
            if (p.optBoolean("packed")) parts += "packed JS (otomatik çözülecek)"
            if (p.optBoolean("cryptojs") || p.optBoolean("aes")) parts += "AES/CryptoJS (otomatik çözülecek)"
            set("player", St.OK, parts.joinToString(" · "))
        } else if ("detail" in scanned) set("player", St.WARN, "Oynatıcı sayfada görünmedi (tıklayınca yükleniyor olabilir). Eklenti çalışma anında iframe, m3u8, packed JS ve CryptoJS zincirini dener.")
        else set("player", St.WARN, "Oynatıcı taranamadı.")
        val s = h.optJSONObject("search")
        if (s != null && s.optString("pattern").isNotEmpty()) {
            if (s.optBoolean("verified")) set("search", St.OK, "Sitede gerçek arama yapılıp doğrulandı · ${s.optString("pattern")}")
            else set("search", St.AUTO, "Adres bulundu (${s.optString("pattern")}) ama canlı doğrulanamadı. Yedek arama zinciri devrede.")
        } else if (s != null && s.optString("param").isNotEmpty()) {
            set("search", St.OK, "Form: ${s.optString("method").uppercase()} ${s.optString("path")} ?${s.optString("param")}=")
        } else set("search", St.AUTO, "Arama adresi kesinleşmedi. Eklenti çalışma anında 15 yaygın adresi sırayla deneyecek (JSON ve AJAX dahil).")
        val m = h.optJSONObject("misc")
        val notes = ArrayList<String>()
        var st = St.OK
        if (m?.optBoolean("cloudflare") == true) { notes += "Cloudflare koruması var → CloudflareKiller otomatik devrede"; st = St.AUTO }
        if (m != null && m.optString("router").isNotEmpty()) { notes += "İçerik JSON uç noktasından da geliyor (?${m.optString("router")}) → yedek olarak kullanılacak"; st = St.AUTO }
        if (m?.optBoolean("unreachable") == true) { notes += "Siteye telefondan ulaşılamadı"; st = St.WARN }
        set("net", st, notes.joinToString("\n").ifEmpty { "Engel yok, düz istekler yeterli." })
    }

    fun score(rows: List<AuditRow>): Int {
        var t = 0.0
        for (r in rows) t += when (r.state) { St.OK -> 1.0; St.AUTO -> 0.85; St.WARN -> 0.5; else -> 0.0 }
        return (t / rows.size * 100).toInt()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun run(act: MainActivity, host: String, origin: String, ua: String, rows: List<AuditRow>, onUpdate: () -> Unit, onDone: () -> Unit) {
        val wv = WebView(act)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString = ua
        val scanned = HashSet<String>()
        var closed = false
        fun row(k: String) = rows.first { it.key == k }
        fun begin(k: String, note: String) { row(k).state = St.RUN; row(k).note = note; onUpdate() }
        fun refresh() { evalRows(rows, CsHints.load(host), scanned); onUpdate() }
        fun finish() {
            if (closed) return
            closed = true
            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
            refresh()
            onDone()
        }
        fun bg(work: () -> Unit, then: () -> Unit) = Thread {
            try { work() } catch (_: Exception) {}
            H.post(then)
        }.start()

        // ---- 5) ağ & koruma kontrolü, ardından bitiş
        fun netStage() {
            begin("net", "Koruma ve JSON uçları kontrol ediliyor…")
            bg({
                val misc = JSONObject()
                try {
                    val r = ApiFinder.fetchText("$origin/", ua, null, 1 shl 17)
                    val t = r.second.second
                    if (r.first == 403 || r.first == 503 || t.contains("Just a moment", true) || t.contains("cf-browser-verification", true))
                        misc.put("cloudflare", true)
                    if (r.first == 0 || r.first >= 500 && r.first != 503) misc.put("unreachable", true)
                } catch (_: Exception) { misc.put("unreachable", true) }
                for (q in listOf("router=1", "json=1", "ajax=1", "format=json")) {
                    try {
                        val r = ApiFinder.fetchText("$origin/?$q", ua, origin, 1 shl 16, mapOf("X-Requested-With" to "XMLHttpRequest", "Accept" to "application/json,*/*"))
                        val b = r.second.second.trimStart()
                        if (r.first == 200 && (b.startsWith("{") || b.startsWith("[")) && b.length > 40) { misc.put("router", q); break }
                    } catch (_: Exception) { }
                }
                CsHints.merge(host, JSONObject().put("misc", misc))
            }, { finish() })
        }

        // ---- 4) arama
        fun searchStage() {
            val h0 = CsHints.load(host)
            val titles = h0.optJSONObject("list")?.optJSONArray("titles")
            val phrase = (0 until (titles?.length() ?: 0)).map { titles!!.optString(it) }
                .firstOrNull { it.length >= 4 }?.filter { it.isLetterOrDigit() || it == ' ' }?.trim()?.take(20)?.trim().orEmpty()
            if (phrase.length < 4) {
                begin("search", "Örnek film adı yok, adres yoklanıyor…")
                bg({
                    // başlık yoksa doğrulama yapılamaz; yalnızca mevcut form bilgisi kalır
                }, { netStage() })
                return
            }
            begin("search", "“$phrase” sitede aranıyor…")
            load(wv, "$origin/", 1500, 20000) {
                evalJson(wv, "(" + SEARCH_JS + ")(" + JSONObject.quote(phrase) + ")") { _ ->
                    H.postDelayed({
                        evalJson(wv, "JSON.stringify({href:location.href,q:(window.__kq||[]).slice(0,60)})") { o ->
                            val href = o?.optString("href").orEmpty()
                            val kq = o?.optJSONArray("q")
                            val found = derive(origin, "$origin/", href, kq, phrase)
                            row("search").note = "Doğrulanıyor…"; onUpdate()
                            bg({
                                val tl = listOf(phrase)
                                var res: JSONObject? = found
                                if (res != null && res.optString("pattern").isNotEmpty()) {
                                    res.put("verified", CsSearch.check(origin, res.optString("pattern"), tl, ua))
                                }
                                if (res == null || (res.optString("pattern").isNotEmpty() && !res.optBoolean("verified"))) {
                                    // Gerçek yazma işe yaramadıysa ya da doğrulanamadıysa yaygın adresleri yokla
                                    val p = CsSearch.probe(origin, tl, ua)
                                    if (p != null) res = JSONObject().put("pattern", p).put("verified", true).put("how", "probe")
                                }
                                if (res != null) CsHints.merge(host, JSONObject().put("search", res))
                            }, { refresh(); netStage() })
                        }
                    }, 4000)
                }
            }
        }

        // ---- 3) bölüm / oynatıcı
        fun episodeStage() {
            val d = CsHints.load(host).optJSONObject("detail")
            val ep = d?.optJSONArray("epLinks")?.optString(0).orEmpty()
            if (ep.isEmpty()) { searchStage(); return }
            begin("player", "İlk bölüm açılıyor…")
            load(wv, ep, 2500, 22000) {
                analyze(wv, host) { scanned.add("episode"); refresh(); searchStage() }
            }
        }

        // ---- 2) ilk film / dizi detayı
        fun detailStage() {
            val l = CsHints.load(host).optJSONObject("list")
            val link = l?.optJSONArray("links")?.optString(0).orEmpty()
            if (link.isEmpty()) { refresh(); searchStage(); return }
            begin("detail", "İlk film/dizi sayfası açılıyor…")
            load(wv, link, 2500, 22000) {
                analyze(wv, host) {
                    scanned.add("detail")
                    // Aynı sayfada tıklanmadan oynatıcı yoksa bile devam
                    refresh(); episodeStage()
                }
            }
        }

        // ---- 1) ana sayfa
        begin("home", "Ana sayfa açılıyor…")
        load(wv, "$origin/", 2200, 25000) {
            analyze(wv, host) { scanned.add("home"); refresh(); detailStage() }
        }
    }
}

/** Tarama penceresi: gezgini çalıştırır, canlı rapor gösterir; bitince devam/yeniden tara sunar. */
internal fun MainActivity.csScanPanel(t: MainActivity.Tab, continueLabel: String, onContinue: () -> Unit) {
    val origin = Uri.parse(t.url).let { "${it.scheme}://${it.authority}" }
    val host = t.pageHost
    val ua = t.webView.settings.userAgentString ?: "Mozilla/5.0"
    val rows = listOf(
        AuditRow("home", "Ana sayfa kartları"),
        AuditRow("poster", "Afişler"),
        AuditRow("detail", "Film / dizi detayı"),
        AuditRow("episodes", "Bölüm listesi"),
        AuditRow("player", "Oynatıcı ve video kaynağı"),
        AuditRow("search", "Arama"),
        AuditRow("net", "Koruma ve JSON uçları")
    )
    val panel = CsPanel(this, "Eklenti hazırlığı", host)
    var done = false
    fun paint() {
        val doneN = rows.count { it.state != St.WAIT && it.state != St.RUN }
        panel.setProgress(doneN / rows.size.toFloat(), "${doneN}/${rows.size} adım")
        panel.render(rows)
    }
    fun start() {
        rows.forEach { it.state = St.WAIT; it.note = "" }
        done = false
        panel.clearButtons()
        panel.setFooter("Site arka planda gezilir: ana sayfa, ilk film/dizi, ilk bölüm ve gerçek bir arama. Bu birkaç on saniye sürebilir.")
        panel.addButton("Taramayı atla") { panel.dismiss(); onContinue() }
        paint()
        CsCrawler.run(this, host, origin, ua, rows, { runOnUiThread { paint() } }) {
            runOnUiThread {
                done = true
                paint()
                val sc = CsCrawler.score(rows)
                val col = when { sc >= 85 -> 0xFF2E9E5B.toInt(); sc >= 60 -> 0xFFF59E0B.toInt(); else -> 0xFFE5484D.toInt() }
                panel.setChip("Hazırlık %$sc", col)
                panel.setTitle(if (sc >= 85) "Eklenti bilgileri toplandı" else "Eklenti bilgileri kısmen toplandı", host)
                val manual = rows.filter { it.state == St.WARN || it.state == St.FAIL }
                val auto = rows.count { it.state == St.AUTO }
                panel.setFooter(
                    (if (auto > 0) "✦ $auto eksik otomatik telafi edildi (eklenti çalışma anında yedek yöntemleri kullanır).\n" else "") +
                        (if (manual.isNotEmpty()) "! ${manual.size} madde elle tamamlanabilir: " + manual.joinToString(", ") { it.title } +
                            ". İstersen o sayfaya gidip Araçlar → CS analizi çalıştır; ya da böyle devam et, eklenti yine yedeklerle dener."
                        else "Eksik görünmüyor; derlemeye geçebilirsin.")
                )
                panel.clearButtons()
                panel.addButton(continueLabel, primary = true) { panel.dismiss(); onContinue() }
                panel.addButton("Yeniden tara") { start() }
                panel.addButton("Verileri sıfırla") { CsHints.clear(host); toast("Analiz verisi silindi"); start() }
                panel.addButton("Kapat") { panel.dismiss() }
            }
        }
    }
    panel.show()
    start()
}
