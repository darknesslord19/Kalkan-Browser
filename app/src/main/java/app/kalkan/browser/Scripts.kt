package app.kalkan.browser

/** Sayfalara belge başında enjekte edilen betikler. */
object Scripts {

    /**
     * Kozmetik filtreleme (tembel genel gizleme) + YouTube reklam temizleyici + arka plan oynatma.
     * window.KalkanCosmetic Java arayüzüdür.
     */
    val CONTENT = """
(function(){
  if (window.__kalkanInjected) return; window.__kalkanInjected = true;
  var K = window.KalkanCosmetic; if (!K) return;
  var host = location.hostname || '';
  var cfg = {};
  try { cfg = JSON.parse(K.config(host)); } catch(e) { return; }

  if (cfg.bg) {
    try {
      Object.defineProperty(document, 'hidden', {get: function(){ return false; }, configurable: true});
      Object.defineProperty(document, 'visibilityState', {get: function(){ return 'visible'; }, configurable: true});
      window.addEventListener('visibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
      document.addEventListener('visibilitychange', function(e){ e.stopImmediatePropagation(); }, true);
    } catch(e) {}
  }

  // Kullanıcının gizlediği öğeler (öğe silici)
  if (cfg.hide) {
    var us = document.createElement('style');
    us.id = '__kalkan_user_hide';
    us.textContent = cfg.hide;
    var attachUs = function(){ if (!us.isConnected) (document.head || document.documentElement).appendChild(us); };
    attachUs();
    document.addEventListener('DOMContentLoaded', attachUs);
    setInterval(attachUs, 2500);
  }

  // Video hızı (tüm çerçevelerde)
  if (K.rate) {
    var lastRate = 1;
    setInterval(function(){
      try {
        var r = K.rate();
        if (r === 1 && lastRate === 1) return;
        var vs = document.querySelectorAll('video,audio');
        for (var i=0;i<vs.length;i++) if (Math.abs(vs[i].playbackRate - r) > 0.01) vs[i].playbackRate = r;
        lastRate = r;
      } catch(e) {}
    }, 1000);
  }

  // Otomatik atlama: "Atla / Devam / Linke git" türü ara sayfa düğmelerine bas
  var skipSel = cfg.skipSel || [];
  if ((cfg.skip || skipSel.length) && window.top === window && K.autoClick) {
    var OK = /^\s*(skip|skip ad|skip ads|skip now|skip this ad|atla|reklam[ıi] atla|reklam[ıi] geç|geç|devam|devam et|devam etmek için tıkla(y[ıi]n)?|continue|continue to (site|link|page)|get link|go to link|go to site|visit (link|site)|open link|proceed|linke git|linki aç|bağlantıya git|siteye git|click here to continue|tıkla(y[ıi]n)? ve devam et)\s*[!.»›>→]*\s*$/i;
    var BAD = /(indir|download|install|kur\b|yükle|kayıt|register|sign ?up|log ?in|giriş|satın|buy|öde|pay|abone|subscribe|kabul|accept|allow|izin|bildirim|notification)/i;
    var done = new WeakSet(), clicks = 0;
    var usable = function(el){
      if (el.disabled || el.getAttribute('aria-disabled') === 'true') return false;
      var r = el.getBoundingClientRect();
      if (r.width < 24 || r.height < 14) return false;
      var cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) < 0.3 || cs.pointerEvents === 'none') return false;
      var f = el.closest('form');
      if (f && f.querySelector('input[type=password],input[type=email],input[type=tel],input[name*=card]')) return false;
      return true;
    };
    var trySkip = function(){
      if (clicks >= 4) return;
      for (var j=0;j<skipSel.length;j++) {
        var se = null;
        try { se = document.querySelector(skipSel[j]); } catch(x) { continue; }
        if (se && !done.has(se) && usable(se)) {
          done.add(se); clicks++;
          K.autoClick(location.href, (se.innerText || se.value || 'öğretilen düğme').replace(/\s+/g,' ').trim().slice(0,30));
          se.click();
          return;
        }
      }
      if (!cfg.skip) return;
      var els = document.querySelectorAll('a,button,input[type=submit],input[type=button],[role=button],[onclick]');
      for (var i=0;i<els.length;i++) {
        var el = els[i];
        if (done.has(el)) continue;
        var t = (el.innerText || el.value || el.getAttribute('aria-label') || '').replace(/\s+/g,' ').trim();
        if (!t || t.length > 40 || !OK.test(t) || BAD.test(t)) continue;
        if (!usable(el)) continue;
        var href = (el.tagName === 'A' && el.href && !/^javascript:/i.test(el.href)) ? el.href : '';
        if (href && K.isAd(href)) { done.add(el); continue; }
        done.add(el); clicks++;
        K.autoClick(href || location.href, t);
        el.click();
        return;
      }
    };
    setInterval(trySkip, 1000);
  }

  // Medya yakalayıcı: oynatıcıların gerçek kaynaklarını bildir
  if (K.media) {
    var seenM = new Set();
    var scanMedia = function(){
      try {
        var els = document.querySelectorAll('video, audio, video source, audio source');
        for (var i=0;i<els.length;i++) {
          var e = els[i];
          var src = e.currentSrc || e.src || '';
          if (src && /^https?:/.test(src) && !seenM.has(src)) {
            seenM.add(src);
            var tag = (e.tagName === 'SOURCE' && e.parentElement) ? e.parentElement.tagName : e.tagName;
            K.media(src, tag.toLowerCase(), location.href);
          }
        }
      } catch(e) {}
    };
    setInterval(scanMedia, 2000);
    document.addEventListener('play', scanMedia, true);
    document.addEventListener('loadedmetadata', scanMedia, true);
  }

  if (!cfg.on) return;

  var isYT = /(^|\.)youtube\.com$/.test(host) || /(^|\.)youtube-nocookie\.com$/.test(host);
  if (isYT) {
    var KEYS = ['adPlacements','playerAds','adSlots','adBreakHeartbeatParams','auxiliaryUi'];
    var strip = function(o){
      try {
        if (o && typeof o === 'object') {
          for (var i=0;i<KEYS.length;i++) if (KEYS[i] in o && KEYS[i] !== 'auxiliaryUi') delete o[KEYS[i]];
          if (o.playerResponse) strip(o.playerResponse);
          if (o.response) strip(o.response);
        }
      } catch(e) {}
      return o;
    };
    try {
      var jp = JSON.parse;
      JSON.parse = function(){ return strip(jp.apply(this, arguments)); };
      var rj = Response.prototype.json;
      Response.prototype.json = function(){ return rj.apply(this, arguments).then(strip); };
      var ipr;
      Object.defineProperty(window, 'ytInitialPlayerResponse', {configurable: true,
        get: function(){ return ipr; }, set: function(v){ ipr = strip(v); }});
    } catch(e) {}
    setInterval(function(){
      try {
        var skip = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-container button');
        if (skip) skip.click();
        var p = document.querySelector('.ad-showing, .ad-interrupting');
        if (p) {
          var v = document.querySelector('video');
          if (v && isFinite(v.duration) && v.duration > 0) { v.muted = true; v.playbackRate = 16; v.currentTime = v.duration - 0.1; }
        }
        var ov = document.querySelectorAll('.ytp-ad-overlay-close-button');
        for (var i=0;i<ov.length;i++) ov[i].click();
      } catch(e) {}
    }, 300);
  }

  var style = document.createElement('style');
  style.setAttribute('data-kalkan', '1');
  var attach = function(){
    if (!style.isConnected) (document.head || document.documentElement).appendChild(style);
  };
  var add = function(css){ if (!css) return; style.textContent += css; attach(); };
  if (isYT) add('ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,ytd-banner-promo-renderer,ytd-statement-banner-renderer,ytd-player-legacy-desktop-watch-ads-renderer,#masthead-ad,#player-ads,.ytp-ad-overlay-container,ytm-promoted-sparkles-web-renderer,ytm-promoted-video-renderer,ytm-companion-ad-renderer,ad-slot-renderer,ytm-ad-slot-renderer{display:none!important}');
  add(K.specific(host));

  var seenC = new Set(), seenI = new Set(), newC = [], newI = [], pending = false;
  var collect = function(el){
    var id = el.id;
    if (id && typeof id === 'string' && !seenI.has(id)) { seenI.add(id); newI.push(id); }
    var cl = el.classList;
    if (cl) for (var i=0;i<cl.length;i++) { var c = cl[i]; if (!seenC.has(c)) { seenC.add(c); newC.push(c); } }
  };
  var scan = function(root){
    if (!root || root.nodeType !== 1) return;
    collect(root);
    var els = root.querySelectorAll('[id],[class]');
    for (var i=0;i<els.length;i++) collect(els[i]);
  };
  var flush = function(){
    pending = false;
    if (!newC.length && !newI.length) return;
    var payload = JSON.stringify({c: newC, i: newI});
    newC = []; newI = [];
    add(K.generic(payload, host));
  };
  var schedule = function(){ if (!pending) { pending = true; setTimeout(flush, 80); } };
  var start = function(){
    scan(document.documentElement); flush();
    new MutationObserver(function(ms){
      for (var i=0;i<ms.length;i++) {
        var m = ms[i];
        if (m.type === 'attributes') collect(m.target);
        else for (var j=0;j<m.addedNodes.length;j++) scan(m.addedNodes[j]);
      }
      attach();
      schedule();
    }).observe(document.documentElement, {childList: true, subtree: true, attributes: true, attributeFilter: ['class','id']});
  };
  if (document.documentElement) start();
  else document.addEventListener('DOMContentLoaded', start);
})();
""".trimIndent()

    /** Öğe seçici (öğe gizle / atlamayı öğret). __MODE__ yerine "zap" veya "teach" gelir. */
    val PICKER = """
(function(MODE){
  if (window.__kalkanPick) window.__kalkanPick.stop();
  var K = window.KalkanCosmetic; if (!K) return;
  var brand = MODE === 'zap' ? '#E5484D' : '#F5A524';
  var box = document.createElement('div');
  box.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483646;border:2px solid ' + brand + ';background:' + brand + '33;border-radius:4px;display:none;box-sizing:border-box';
  var bar = document.createElement('div');
  bar.style.cssText = 'position:fixed;left:8px;right:8px;bottom:12px;z-index:2147483647;background:#14161C;color:#fff;border-radius:16px;padding:10px 12px;font:14px system-ui,sans-serif;display:flex;align-items:center;gap:8px;box-shadow:0 6px 24px rgba(0,0,0,.45)';
  var label = document.createElement('div');
  label.style.cssText = 'flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap';
  label.textContent = MODE === 'zap' ? 'Gizlenecek öğeye dokun' : 'Otomatik basılacak düğmeye dokun';
  bar.appendChild(label);
  var mk = function(t){ var b = document.createElement('button'); b.textContent = t; b.style.cssText = 'background:#2A2E38;color:#fff;border:0;border-radius:10px;padding:8px 13px;font:600 15px system-ui,sans-serif'; bar.appendChild(b); return b; };
  var up = mk('▲'), ok = mk('✓'), no = mk('✕');
  ok.style.background = brand; ok.style.color = '#1A1205';
  document.documentElement.appendChild(box);
  document.documentElement.appendChild(bar);
  var cur = null;
  var esc = function(s){ return (window.CSS && CSS.escape) ? CSS.escape(s) : s.replace(/[^\w-]/g, function(c){ return '\\' + c; }); };
  var sel = function(el){
    if (el.id && /^[A-Za-z][\w-]*$/.test(el.id) && document.querySelectorAll('#' + esc(el.id)).length === 1) return '#' + esc(el.id);
    var parts = [], e = el;
    while (e && e.nodeType === 1 && e !== document.body && e !== document.documentElement && parts.length < 6) {
      if (e.id && /^[A-Za-z][\w-]*$/.test(e.id)) { parts.unshift('#' + esc(e.id)); break; }
      var p = e.tagName.toLowerCase();
      var cls = Array.prototype.filter.call(e.classList, function(c){ return /^[A-Za-z_-][\w-]{1,40}$/.test(c) && !/\d{3,}/.test(c); }).slice(0, 2);
      if (cls.length) p += '.' + cls.map(esc).join('.');
      var par = e.parentElement;
      if (par) {
        var same = Array.prototype.filter.call(par.children, function(c){ return c.tagName === e.tagName; });
        if (same.length > 1) p += ':nth-of-type(' + (same.indexOf(e) + 1) + ')';
      }
      parts.unshift(p);
      var s = parts.join(' > ');
      try { if (document.querySelectorAll(s).length === 1) return s; } catch(x) {}
      e = par;
    }
    return parts.join(' > ');
  };
  var text = function(el){ return (el.innerText || el.value || el.alt || el.title || '').replace(/\s+/g, ' ').trim().slice(0, 40); };
  var show = function(el){
    cur = el;
    if (!el) { box.style.display = 'none'; return; }
    var r = el.getBoundingClientRect();
    box.style.display = 'block';
    box.style.left = r.left + 'px'; box.style.top = r.top + 'px';
    box.style.width = r.width + 'px'; box.style.height = r.height + 'px';
    var t = text(el);
    label.textContent = '<' + el.tagName.toLowerCase() + '> ' + (t || sel(el));
  };
  var inBar = function(t){ return t === bar || bar.contains(t); };
  var block = function(ev){ if (inBar(ev.target)) return; ev.preventDefault(); ev.stopPropagation(); ev.stopImmediatePropagation(); };
  var onClick = function(ev){
    if (inBar(ev.target)) return;
    block(ev);
    var el = document.elementFromPoint(ev.clientX, ev.clientY);
    if (el && el !== box && !inBar(el)) show(el);
  };
  var onScroll = function(){ if (cur) show(cur); };
  var evs = ['mousedown', 'mouseup', 'pointerup', 'auxclick', 'dblclick', 'submit'];
  evs.forEach(function(n){ window.addEventListener(n, block, true); });
  window.addEventListener('click', onClick, true);
  window.addEventListener('scroll', onScroll, true);
  var stop = function(){
    evs.forEach(function(n){ window.removeEventListener(n, block, true); });
    window.removeEventListener('click', onClick, true);
    window.removeEventListener('scroll', onScroll, true);
    box.remove(); bar.remove();
    window.__kalkanPick = null;
  };
  up.addEventListener('click', function(e){ e.stopPropagation(); if (cur && cur.parentElement && cur.parentElement !== document.body && cur.parentElement !== document.documentElement) show(cur.parentElement); });
  no.addEventListener('click', function(e){ e.stopPropagation(); stop(); });
  ok.addEventListener('click', function(e){
    e.stopPropagation();
    if (!cur) return;
    var target = cur, s = sel(cur), t = text(cur);
    stop();
    K.picked(MODE, s, t);
    if (MODE === 'zap') {
      var st = document.getElementById('__kalkan_user_hide');
      if (!st) { st = document.createElement('style'); st.id = '__kalkan_user_hide'; (document.head || document.documentElement).appendChild(st); }
      st.textContent += s + '{display:none!important}';
    } else {
      setTimeout(function(){ target.click(); }, 60);
    }
  });
  window.__kalkanPick = { stop: stop };
})('__MODE__');
""".trimIndent()

    /** Okuma modu (aç/kapat). */
    val READER = """
(function(){
  var old = document.getElementById('__kalkan_reader');
  if (old) { old.remove(); document.documentElement.style.overflow = ''; return 'off'; }
  var bad = /comment|footer|sidebar|nav|menu|related|share|social|promo|advert|ad-|banner|cookie|popup|subscribe/i;
  var scores = new Map();
  var add = function(el, v){ if (el) scores.set(el, (scores.get(el) || 0) + v); };
  document.querySelectorAll('p, pre, blockquote').forEach(function(p){
    var t = (p.innerText || '').trim();
    if (t.length < 40) return;
    var v = Math.min(t.length, 1200) + t.split(/[,.;]/).length * 8;
    add(p.parentElement, v);
    if (p.parentElement) add(p.parentElement.parentElement, v / 2);
  });
  var best = null, bs = 0;
  scores.forEach(function(v, el){
    if (bad.test((el.className || '') + ' ' + (el.id || ''))) v *= 0.3;
    if (el.tagName === 'ARTICLE' || el.getAttribute('itemprop') === 'articleBody') v *= 1.5;
    if (v > bs) { bs = v; best = el; }
  });
  if (!best || bs < 300) best = document.querySelector('article, [itemprop=articleBody], main') || best;
  if (!best) return 'none';
  var c = best.cloneNode(true);
  c.querySelectorAll('script,style,link,iframe,form,button,nav,aside,footer,noscript,input,select,textarea,object,embed,[aria-hidden=true],[hidden]').forEach(function(e){ e.remove(); });
  c.querySelectorAll('*').forEach(function(e){
    if (bad.test((e.className && e.className.baseVal === undefined ? e.className : '') + ' ' + (e.id || '')) && (e.innerText || '').length < 400) { e.remove(); return; }
    ['style','class','id','width','height','align','onclick'].forEach(function(a){ e.removeAttribute(a); });
  });
  c.querySelectorAll('img').forEach(function(i){
    var s = i.getAttribute('data-src') || i.getAttribute('data-lazy-src') || i.getAttribute('data-original');
    if (s) i.src = s;
    i.removeAttribute('loading');
  });
  var h1 = document.querySelector('h1');
  var title = (h1 && h1.innerText.trim()) || document.title;
  c.querySelectorAll('h1').forEach(function(h){ if (h.innerText.trim() === title) h.remove(); });
  var words = (c.innerText || '').trim().split(/\s+/).length;
  var dark = window.matchMedia && matchMedia('(prefers-color-scheme: dark)').matches;
  var o = document.createElement('div');
  o.id = '__kalkan_reader';
  o.style.cssText = 'position:fixed;inset:0;z-index:2147483647;overflow:auto;-webkit-overflow-scrolling:touch;background:' + (dark ? '#14161C' : '#FBF8F2');
  var sh = o.attachShadow({ mode: 'open' });
  sh.innerHTML = '<style>' +
    ':host{all:initial}' +
    '.w{max-width:720px;margin:0 auto;padding:64px 22px 80px;color:' + (dark ? '#E4E6EB' : '#222') + ';font:19px/1.75 Georgia,"Noto Serif",serif}' +
    '.bar{position:fixed;top:0;left:0;right:0;display:flex;gap:6px;justify-content:flex-end;padding:10px 12px;background:' + (dark ? '#14161Cee' : '#FBF8F2ee') + ';z-index:2}' +
    '.bar button{border:0;border-radius:10px;padding:8px 12px;font:600 14px system-ui,sans-serif;background:' + (dark ? '#2A2E38' : '#ECE6DA') + ';color:inherit}' +
    'h1{font:700 1.6em/1.25 system-ui,sans-serif;margin:0 0 8px}' +
    '.m{font:14px system-ui,sans-serif;opacity:.6;margin-bottom:28px}' +
    'img,video,figure{max-width:100%;height:auto;border-radius:8px}' +
    'a{color:#C07E0A}' +
    'pre,code{font:14px ui-monospace,monospace;white-space:pre-wrap;word-break:break-word}' +
    'blockquote{border-left:3px solid #F5A524;margin:1em 0;padding-left:16px;opacity:.85}' +
    'table{max-width:100%;display:block;overflow:auto}' +
    '</style>' +
    '<div class="bar"><button id="mn">A−</button><button id="pl">A+</button><button id="x">Kapat</button></div>' +
    '<div class="w"><h1></h1><div class="m"></div><div class="c"></div></div>';
  sh.querySelector('h1').textContent = title;
  sh.querySelector('.m').textContent = location.hostname.replace(/^www\./, '') + ' · ~' + Math.max(1, Math.round(words / 200)) + ' dk okuma';
  sh.querySelector('.c').appendChild(c);
  var size = 19, w = sh.querySelector('.w');
  sh.getElementById('pl').onclick = function(){ size = Math.min(30, size + 1); w.style.fontSize = size + 'px'; };
  sh.getElementById('mn').onclick = function(){ size = Math.max(13, size - 1); w.style.fontSize = size + 'px'; };
  sh.getElementById('x').onclick = function(){ o.remove(); document.documentElement.style.overflow = ''; };
  document.documentElement.appendChild(o);
  document.documentElement.style.overflow = 'hidden';
  return 'on';
})()
""".trimIndent()

    /** Resim içinde resim için en büyük videoyu/oynatıcıyı ekrana yayar. Oranı döndürür. */
    fun pip(on: Boolean) = """
(function(on){
  var st = document.getElementById('__kalkan_pip');
  var prev = document.querySelector('[data-kalkan-pip]');
  if (!on) { if (st) st.remove(); if (prev) prev.removeAttribute('data-kalkan-pip'); return 0; }
  var best = null, ba = 0;
  document.querySelectorAll('video, iframe').forEach(function(el){
    var r = el.getBoundingClientRect();
    if (r.width < 80 || r.height < 50) return;
    var a = r.width * r.height;
    if (el.tagName === 'VIDEO' && !el.paused) a *= 4;
    if (a > ba) { ba = a; best = el; }
  });
  if (!best) return 0;
  if (prev) prev.removeAttribute('data-kalkan-pip');
  best.setAttribute('data-kalkan-pip', '1');
  if (!st) { st = document.createElement('style'); st.id = '__kalkan_pip'; document.documentElement.appendChild(st); }
  st.textContent = '[data-kalkan-pip]{position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;transform:none!important;z-index:2147483647!important;background:#000!important;object-fit:contain!important;border:0!important}html,body{overflow:hidden!important}';
  if (best.tagName === 'VIDEO' && best.paused) { try { best.play(); } catch(e) {} }
  var ratio = (best.videoWidth && best.videoHeight) ? best.videoWidth / best.videoHeight : 16 / 9;
  return ratio;
})($on)
""".trimIndent()

    /** localStorage / sessionStorage içeriğini [[anahtar, değer], ...] olarak döndürür. */
    fun storageDump(kind: String) =
        "(function(){try{var s=window.$kind;var o=[];for(var i=0;i<s.length;i++){var k=s.key(i);o.push([k,String(s.getItem(k))]);}return o;}catch(e){return 'ERR:'+e.message;}})()"

    /** Kaynak zamanlama bilgileri (ağ kaydı). */
    val PERF = """
(function(){
  try {
    var out = [];
    var nav = performance.getEntriesByType('navigation')[0];
    if (nav) out.push({n: nav.name, t: 'document', d: nav.duration, s: nav.responseStatus || 0, z: nav.transferSize || 0, b: nav.encodedBodySize || 0, st: 0, p: nav.nextHopProtocol || ''});
    performance.getEntriesByType('resource').forEach(function(e){
      out.push({n: e.name, t: e.initiatorType, d: e.duration, s: e.responseStatus || 0, z: e.transferSize || 0, b: e.encodedBodySize || 0, st: e.startTime, p: e.nextHopProtocol || ''});
    });
    return out;
  } catch(e) { return []; }
})()
""".trimIndent()
}
