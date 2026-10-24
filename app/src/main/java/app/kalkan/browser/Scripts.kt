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
}
