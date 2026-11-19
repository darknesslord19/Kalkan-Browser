package app.kalkan.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * CloudStream iskeleti için sayfa analizi.
 *
 * Akış: sayfada JS çalışır (kart / başlık / afiş / bölüm / oynatıcı / arama formu bulur),
 * sonuç site başına kaydedilir ve farklı sayfalarda (ana sayfa, detay, bölüm) yapılan
 * analizler birleşir. Dışa aktarırken Provider.kt'deki seçiciler bu bilgilerle dolar.
 */
object CsAnalyzer {

    /** Sayfada çalışan analiz betiği. Bir nesne döndürür (evaluateJavascript JSON'a çevirir). */
    val JS = """
(function(){
try{
var out={host:location.hostname,url:location.href};
function cls(el){var r=[],l=el.classList||[];for(var i=0;i<l.length;i++){var c=l[i];
  if(c.length<30&&!/\d/.test(c)&&/^[A-Za-z_][\w-]*${'$'}/.test(c)&&
     !/^(active|current|first|last|odd|even|hover|selected|show|hide|lazy|lazyload|lazyloaded|loaded|open|focus|visible|clearfix)${'$'}/i.test(c))r.push(c);}return r;}
function sig(el){var c=cls(el).slice(0,2);return el.tagName.toLowerCase()+(c.length?'.'+c.join('.'):'');}
function cnt(s){try{return document.querySelectorAll(s).length;}catch(e){return 0;}}
function same(a){try{return new URL(a.href,location.href).host===location.host;}catch(e){return false;}}
function abs(u){try{return new URL(u,location.href).href;}catch(e){return u;}}
function skip(a){return !!a.closest('nav,header,footer,[role=navigation]');}
function each(sel,fn){Array.prototype.forEach.call(document.querySelectorAll(sel),fn);}

// ---- 1) Kart listesi: resim içeren, tekrar eden bağlantılar
function cardSel(h){var s=sig(h);
  if(s.indexOf('.')<0&&h.parentElement){var ps=sig(h.parentElement);if(ps.indexOf('.')>=0)s=ps+' > '+s;}
  return s;}
var groups={};
each('a[href]',function(a){
  var hr=a.getAttribute('href')||'';
  if(!same(a)||/^(#|javascript)/.test(hr)||skip(a)||!a.querySelector('img'))return;
  var cands=[['self',a],['parent',a.parentElement],['parent2',a.parentElement&&a.parentElement.parentElement]];
  cands.forEach(function(p){var h=p[1];if(!h||h===document.body||h===document.documentElement)return;
    var sel=cardSel(h);var k=p[0]+'|'+sel;
    var g=groups[k]||(groups[k]={lvl:p[0],sel:sel,items:[],hrefs:{}});
    g.items.push({h:h,a:a});g.hrefs[a.href]=1;});
});
var best=null,bs=-1;
Object.keys(groups).forEach(function(k){var g=groups[k];var n=Object.keys(g.hrefs).length;if(n<4)return;
  var m=cnt(g.sel);var sc=n-Math.abs(m-n)*0.5+(g.sel.indexOf('.')>=0?0.5:0);
  if(sc>bs){bs=sc;best=g;best.n=n;best.m=m;}});
if(best){
  var h=best.items[0].h,a=best.items[0].a,img=a.querySelector('img');
  var ts='';var hs=['h1','h2','h3','h4','h5','h6'];
  for(var i=0;i<hs.length&&!ts;i++){if(h.querySelector(hs[i]))ts=hs[i];}
  if(!ts){var all=h.querySelectorAll('*');for(var j=0;j<all.length&&!ts;j++){var e=all[j];
    if(e.tagName!=='IMG'&&/(title|name|baslik|isim)/i.test(e.getAttribute('class')||'')&&e.textContent.trim().length>1)ts=sig(e);}}
  var pa='src',pas=['data-src','data-lazy-src','data-original','data-lazy','src'];
  for(var q=0;q<pas.length;q++){var v=img&&img.getAttribute(pas[q]);if(v&&v.indexOf('data:')!==0){pa=pas[q];break;}}
  out.list={card:best.sel,title:ts,posterAttr:pa,count:best.n,matches:best.m,
    sample:best.items.slice(0,3).map(function(x){return (x.h.textContent||'').trim().replace(/\s+/g,' ').slice(0,50);})};
}

// ---- 2) Detay sayfası
var d={};
var h1=document.querySelectorAll('h1');if(h1.length)d.title=h1.length===1?'h1':sig(h1[0]);
function meta(p){var m=document.querySelector('meta[property="'+p+'"],meta[name="'+p+'"]');return m?m.content:'';}
d.ogTitle=!!meta('og:title');d.ogImage=!!meta('og:image');d.ogDesc=!!(meta('og:description')||meta('description'));
function pick(re,pred){var els=document.body?document.body.querySelectorAll('[class],[id]'):[];
  for(var i=0;i<els.length&&i<4000;i++){var e=els[i];var c=(e.getAttribute('class')||'')+' '+(e.id||'');
    if(re.test(c)&&pred(e)){var s=cls(e).length?sig(e):(e.id?'#'+e.id:'');if(s)return {s:s,e:e};}}
  return null;}
var pl=pick(/(description|summary|plot|synopsis|ozet|konu|aciklama|overview|storyline)/i,function(e){return e.textContent.trim().length>40&&e.textContent.length<2500;});
if(pl)d.plot=pl.s;
var po=pick(/(poster|cover|afis|thumb)/i,function(e){return e.tagName==='IMG'||!!e.querySelector('img');});
if(po)d.poster=po.e.tagName==='IMG'?po.s:po.s+' img';
var yr=pick(/(year|yil|release|date)/i,function(e){return /\b(19|20)\d{2}\b/.test(e.textContent)&&e.textContent.length<40;});
if(yr)d.year=yr.s;
var epRe=/(b[öo]l[üu]m|episode|\bep\b|sezon|season|\d+\s*x\s*\d+)/i,eg={};
each('a[href]',function(a){if(!same(a)||skip(a))return;
  if(!epRe.test(a.textContent+' '+(a.getAttribute('href')||'')))return;
  var c=a.parentElement,n=0;while(c&&n<4&&cls(c).length===0){c=c.parentElement;n++;}
  if(!c||c===document.body)return;
  var k=sig(c);(eg[k]=eg[k]||{h:{}}).h[a.href]=1;});
var eb=null,en=1;Object.keys(eg).forEach(function(k){var n=Object.keys(eg[k].h).length;if(n>en){en=n;eb=k;}});
if(eb){d.episodes=eb+' a';d.episodeCount=en;}
if(Object.keys(d).length>3||d.episodes)out.detail=d;

// ---- 3) Oynatıcı ve şifre izleri
var p={};var fr=[];
each('iframe,[data-embed],[data-video],[data-player]',function(e){
  var u=e.getAttribute('src')||e.getAttribute('data-src')||e.getAttribute('data-embed')||e.getAttribute('data-video')||e.getAttribute('data-player')||'';
  if(u&&!/^(about:|javascript:)/.test(u))fr.push(abs(u));});
p.iframes=fr.slice(0,6);p.video=!!document.querySelector('video');
var inl=[];Array.prototype.forEach.call(document.scripts,function(s){if(!s.src&&s.textContent)inl.push(s.textContent.slice(0,200000));});
var T=inl.join('\n').slice(0,600000);
p.cryptojs=/CryptoJS|crypto-js/.test(T);
p.aes=/AES\.decrypt|aesDecrypt|createDecipheriv|subtle\.decrypt/.test(T);
p.packed=T.indexOf('eval(function(p,a,c,k,e,d)')>=0;
p.atob=T.indexOf('atob(')>=0;
var pw={},m,r1=/decrypt\(\s*[^,()]+,\s*["']([^"']{3,64})["']/g;
while((m=r1.exec(T)))pw[m[1]]=1;
var r2=/(?:pass(?:word|phrase)?|secret|aeskey|key)\w*\s*[:=]\s*["']([A-Za-z0-9_\-+\/=!@#%^&*.]{6,64})["']/ig;
while((m=r2.exec(T)))pw[m[1]]=1;
p.pass=(p.cryptojs||p.aes)?Object.keys(pw).slice(0,8):[];
p.m3u8=(T.match(/https?:[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*/g)||[]).slice(0,3);
var ss=[];Array.prototype.forEach.call(document.scripts,function(s){
  if(s.src&&!/(jquery|bootstrap|analytics|gtag|googletagmanager|adsbygoogle|recaptcha|cloudflare)/i.test(s.src))ss.push(s.src);});
p.scripts=ss.slice(0,10);
if(p.iframes.length||p.video||p.cryptojs||p.aes||p.packed||p.m3u8.length)out.player=p;

// ---- 4) Arama formu ve menü
var sf=null;
Array.prototype.forEach.call(document.forms,function(f){if(sf)return;
  var i=f.querySelector('input[name=s],input[name=q],input[name=query],input[name=search],input[name=keyword],input[name=k],input[name=term],input[name=arama]');
  if(i){try{sf={param:i.name,path:new URL(f.getAttribute('action')||location.pathname,location.href).pathname};}catch(e){}}});
if(sf)out.search=sf;
var nav=[],seen={};
each('nav a[href],header a[href]',function(a){var t=(a.textContent||'').trim().replace(/\s+/g,' ');
  if(!t||t.length>30||!same(a))return;var pth=new URL(a.href,location.href).pathname;
  if(pth==='/'||seen[pth]||/^#/.test(a.getAttribute('href')))return;seen[pth]=1;nav.push({name:t,path:pth});});
if(nav.length)out.nav=nav.slice(0,12);
return out;
}catch(e){return {error:String(e)};}
})()
"""
}

/** Site başına birikimli analiz sonuçları. */
object CsHints {
    private fun key(host: String) = "cs_hints_" + host.removePrefix("www.")

    fun load(host: String): JSONObject =
        try { JSONObject(Store.str(key(host), "{}")) } catch (_: Exception) { JSONObject() }

    private fun empty(v: Any?) = v == null || v == JSONObject.NULL ||
        (v is JSONObject && v.length() == 0) || (v is JSONArray && v.length() == 0) ||
        (v is String && v.isEmpty()) || (v is Int && v == 0)

    /** Yeni sonucu, mevcut bilgiyle alt alan düzeyinde birleştirir (boşlar eskiyi silmez). */
    fun merge(host: String, fresh: JSONObject): JSONObject {
        val cur = load(host)
        for (k in listOf("list", "detail", "player", "search", "nav")) {
            val v = fresh.opt(k)
            if (empty(v)) continue
            if (v is JSONObject) {
                val dst = cur.optJSONObject(k) ?: JSONObject().also { cur.put(k, it) }
                for (sk in v.keys()) {
                    val sv = v.opt(sk)
                    if (!empty(sv) && sv != false) dst.put(sk, sv)
                }
            } else cur.put(k, v)
        }
        cur.put("updated", System.currentTimeMillis())
        Store.prefs.edit().putString(key(host), cur.toString()).apply()
        return cur
    }

    fun clear(host: String) { Store.prefs.edit().remove(key(host)).apply() }

    /** Kullanıcıya gösterilecek kısa durum özeti. */
    fun summary(h: JSONObject?): String {
        if (h == null) return "Bu sayfa analiz edilemedi."
        fun ok(b: Boolean) = if (b) "✓" else "✗"
        val l = h.optJSONObject("list")
        val d = h.optJSONObject("detail")
        val p = h.optJSONObject("player")
        return buildString {
            append("${ok(l != null)} Ana sayfa kartları" + (l?.let { " (${it.optInt("count")} kart: ${it.optString("card")})" } ?: " — ana sayfada veya kategori sayfasında analiz et"))
            append("\n${ok(d?.optString("episodes").orEmpty().isNotEmpty())} Bölüm listesi" + (d?.optInt("episodeCount")?.takeIf { it > 0 }?.let { " ($it bölüm)" } ?: " — dizi detay sayfasında analiz et"))
            append("\n${ok(d != null && d.optBoolean("ogTitle") || d?.optString("title").orEmpty().isNotEmpty())} Detay sayfası")
            append("\n${ok(p != null)} Oynatıcı" + (p?.optJSONArray("iframes")?.length()?.takeIf { it > 0 }?.let { " ($it iframe)" } ?: " — bölüm/film sayfasında analiz et"))
            append("\n${ok(h.optJSONObject("search") != null)} Arama formu")
        }
    }
}

/** Video akışı raporu: HLS şifreleme/anahtar, Referer gereksinimi, şifre izleri. Ağ kullanır: arka plan iş parçacığında çağır. */
object CsVideo {
    class Report(val lines: List<String>, val passes: List<String>)

    private val KNOWN = Regex("(?i)(vidmoly|filemoon|streamtape|doodstream|dood\\.|mixdrop|voe\\.|uqload|vidhide|rapidvid|sibnet|ok\\.ru|fembed|streamwish|upstream|vtube|closeload|turbovid)")
    private val R1 = Regex("""decrypt\(\s*[^,()]+,\s*["']([^"']{3,64})["']""")
    private val R2 = Regex("""(?i)(?:pass(?:word|phrase)?|secret|aeskey|key)\w*\s*[:=]\s*["']([A-Za-z0-9_\-+/=!@#%^&*.]{6,64})["']""")

    fun analyze(tab: MainActivity.Tab, hints: JSONObject, ua: String): Report {
        val lines = ArrayList<String>()
        val player = hints.optJSONObject("player") ?: JSONObject()

        // --- HLS
        val cap = synchronized(tab.res) { tab.res.values.toList() }.firstOrNull { it.url.substringBefore('?').endsWith(".m3u8") }
        val m3 = cap?.url ?: player.optJSONArray("m3u8")?.optString(0)?.takeIf { it.isNotBlank() }
        if (m3 != null) lines += hls(m3, cap?.referer ?: tab.url, ua)
        else lines += "HLS: bu sayfada m3u8 yakalanmadı (videoyu oynatıp tekrar dışa aktar)"

        // --- Oynatıcı türü
        val frames = player.optJSONArray("iframes")
        val hosts = HashSet<String>()
        if (frames != null) for (i in 0 until frames.length()) KNOWN.find(frames.optString(i))?.let { hosts.add(it.groupValues[1]) }
        if (hosts.isNotEmpty()) lines += "Oynatıcı: bilinen barındırıcılar (${hosts.joinToString()}) CloudStream çıkarıcılarıyla çözülür"

        // --- Şifre izleri: sayfa betikleri + harici JS dosyaları
        var cryptoJs = player.optBoolean("cryptojs")
        var aes = player.optBoolean("aes")
        var packed = player.optBoolean("packed")
        val passes = LinkedHashSet<String>()
        player.optJSONArray("pass")?.let { a -> for (i in 0 until a.length()) passes.add(a.optString(i)) }
        val srcs = player.optJSONArray("scripts")
        if (srcs != null) for (i in 0 until minOf(srcs.length(), 8)) {
            val text = try { ApiFinder.fetchText(srcs.optString(i), ua, tab.url, 1_500_000).second.second } catch (_: Exception) { continue }
            if (Regex("CryptoJS|crypto-js").containsMatchIn(text)) cryptoJs = true
            if (Regex("AES\\.decrypt|aesDecrypt|createDecipheriv|subtle\\.decrypt").containsMatchIn(text)) aes = true
            if (text.contains("eval(function(p,a,c,k,e,d)")) packed = true
            if (cryptoJs || aes) {
                R1.findAll(text).forEach { passes.add(it.groupValues[1]) }
                R2.findAll(text).forEach { passes.add(it.groupValues[1]) }
            }
        }
        val flags = listOfNotNull(
            "CryptoJS".takeIf { cryptoJs }, "AES.decrypt".takeIf { aes },
            "packed JS".takeIf { packed }, "atob".takeIf { player.optBoolean("atob") }
        )
        if (flags.isNotEmpty()) lines += "Şifre/gizleme izleri: ${flags.joinToString()} (üretilen kod bunları çalışırken çözer)"
        val usable = if (cryptoJs || aes) passes.take(8) else emptyList()
        if (usable.isNotEmpty()) lines += "Anahtar adayı: ${usable.size} adet (kodda cryptoPass listesine eklendi)"
        return Report(lines, usable)
    }

    private fun hls(url: String, ref: String, ua: String): List<String> {
        val out = ArrayList<String>()
        val r = try { ApiFinder.fetchText(url, ua, ref, 1 shl 19) } catch (e: Exception) {
            return listOf("HLS: m3u8 alınamadı (${e.message})")
        }
        if (r.first >= 400) return listOf("HLS: m3u8 erişilemedi (HTTP ${r.first})")
        var needsRef = false
        try { needsRef = ApiFinder.fetchText(url, ua, null, 1 shl 16).first >= 400 } catch (_: Exception) {}

        val master = r.second.second
        var media = master
        var mediaUrl = url
        val variants = ArrayList<String>()
        val ml = master.lines()
        for (i in ml.indices) if (ml[i].startsWith("#EXT-X-STREAM-INF") && i + 1 < ml.size) {
            val res = Regex("RESOLUTION=(\\d+x\\d+)").find(ml[i])?.groupValues?.get(1) ?: "?"
            variants.add(res)
            if (variants.size == 1) {
                mediaUrl = try { java.net.URL(java.net.URL(url), ml[i + 1].trim()).toString() } catch (_: Exception) { url }
                media = try { ApiFinder.fetchText(mediaUrl, ua, ref, 1 shl 19).second.second } catch (_: Exception) { master }
            }
        }

        var enc = "yok"
        var keyUri: String? = null
        for (m in Regex("#EXT-X-(?:SESSION-)?KEY:(.*)").findAll(master + "\n" + media)) {
            val a = m.groupValues[1]
            val method = Regex("METHOD=([\\w-]+)").find(a)?.groupValues?.get(1) ?: ""
            val fmt = Regex("KEYFORMAT=\"([^\"]+)\"").find(a)?.groupValues?.get(1) ?: ""
            if (method == "NONE") continue
            if (method == "SAMPLE-AES" || Regex("(?i)(widevine|fairplay|playready|streamingkeydelivery)").containsMatchIn(fmt)) { enc = "DRM"; break }
            enc = "AES-128"
            keyUri = Regex("URI=\"([^\"]+)\"").find(a)?.groupValues?.get(1)
        }
        val head = StringBuilder("HLS: ")
        if (variants.isNotEmpty()) head.append("${variants.size} kalite (${variants.take(4).joinToString()}) · ")
        when (enc) {
            "DRM" -> head.append("DRM korumalı (Widevine/FairPlay/SAMPLE-AES): CloudStream oynatamaz, aşılmaya çalışılmaz")
            "AES-128" -> {
                head.append("AES-128 şifreli (oynatıcı çözer)")
                val ku = keyUri?.let { try { java.net.URL(java.net.URL(mediaUrl), it).toString() } catch (_: Exception) { null } }
                if (ku != null) {
                    val ok = try { ApiFinder.fetchText(ku, ua, ref, 64).let { it.first == 200 && it.second.second.isNotEmpty() } } catch (_: Exception) { false }
                    head.append(if (ok) " · anahtar erişilebilir" else " · anahtar alınamadı (Referer/çerez gerekebilir)")
                }
            }
            else -> head.append("şifresiz")
        }
        if (needsRef) head.append(" · Referer gerekli (kod otomatik gönderir)")
        out.add(head.toString())
        return out
    }
}
