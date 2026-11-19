#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Darkness — Otomatik CS3 eklenti üretici
=====================================
Kullanım:
    python darkness_uretici.py https://siteadi.com
    python darkness_uretici.py https://siteadi.com --ad SiteAdi
    python darkness_uretici.py https://siteadi.com --har site.har     (site Python'u engelliyorsa)

Ne yapar:
  1) Siteyi açar, film/dizi KARTLARINI bulur (seçici, başlık, afiş özelliği).
  2) Menüden kategorileri bulur, her birinde kart var mı dener (ana sayfa listeleri).
  3) Sayfalama biçimini bulur (/page/2/, ?page=2, /sayfa/2 ...) ve 2. sayfayı dener.
  4) Arama adresini bulur ve gerçek bir kelimeyle dener.
  5) Bir film ve bir dizi sayfası açar: bölüm linkleri, oynatıcı (iframe, DooPlay, m3u8...).
  6) Repo düzeninde zip üretir:
        cikti/<Ad>.zip
          <Ad>/build.gradle.kts
          <Ad>/src/main/kotlin/com/darkness/<Ad>.kt
          <Ad>/src/main/kotlin/com/darkness/<Ad>Plugin.kt
        cikti/<Ad>_ANALIZ.txt   (ne bulundu, ne bulunamadı, elle bakılacaklar)

Not: Oynatıcısı JavaScript/şifreli API ile yüklenen sitelerde iskelet (liste, arama, detay,
bölümler) yine çalışır; oynatma kısmı için rapordaki "ELLE BAKILACAK" maddelerine bak
ve o sitenin HAR'ını gönder.
"""
import argparse
import datetime
import io
import json
import os
import re
import subprocess
import sys
import zipfile
from collections import Counter, OrderedDict, defaultdict
from urllib.parse import urljoin, urlparse, quote_plus


def _need(pkgs):
    missing = []
    for mod, pip_name in pkgs:
        try:
            __import__(mod)
        except ImportError:
            missing.append(pip_name)
    if missing:
        print(f"[kurulum] Eksik paketler kuruluyor: {' '.join(missing)}")
        subprocess.call([sys.executable, "-m", "pip", "install", "--user", *missing])


_need([("requests", "requests"), ("bs4", "beautifulsoup4")])
import requests  # noqa: E402
from bs4 import BeautifulSoup  # noqa: E402

try:  # Windows konsolunda Türkçe karakterler
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36")

JUNK_PATH = re.compile(
    r"(/wp-|/feed|/tag/|/author/|/iletisim|/contact|/hakkimizda|/about|/dmca|/gizlilik|/privacy|"
    r"/login|/giris|/kayit|/register|/uye|/profil|/hesap|/sepet|/cart|\.(css|js|png|jpe?g|webp|gif|svg|ico|xml)$|"
    r"/sitemap|/rss|javascript:|mailto:|#)", re.I)
CATEGORY_WORDS = re.compile(
    r"(film|dizi|movie|series|tv|anime|kategori|category|tur|genre|platform|netflix|exxen|blutv|disney|"
    r"amazon|prime|yerli|yabanci|aksiyon|komedi|korku|dram|bilim|animasyon|belgesel|gerilim|romantik|"
    r"populer|imdb|yeni|son-eklenen|trend|yil|year|ulke|country)", re.I)
MOVIE_SEG = re.compile(r"^(film|filmler|movie|movies|izle|watch)$", re.I)
SERIES_SEG = re.compile(r"^(dizi|diziler|series|tv|tvshows|tv-series|show|shows|anime|animeler)$", re.I)
EP_TOKENS = ["/bolum/", "-bolum", "/episode/", "-episode", "/episodes/", "-sezon-", "/sezon-", "/season/",
             "-season-", "/bolumler/"]
PLAYER_HOST_HINTS = ["embed", "player", "/e/", "/v/", "iframe", "video", "stream", "watch", "vid", "play"]
IFRAME_JUNK = ["googletagmanager", "google.com/recaptcha", "facebook.com", "disqus", "twitter.com",
               "doubleclick", "youtube.com/embed", "youtube-nocookie", "about:blank", "instagram.com"]


# ----------------------------------------------------------------------------- yardımcılar
def host(u):
    return urlparse(u).netloc.lower().replace("www.", "")


def path_of(u):
    return urlparse(u).path or "/"


def norm(u):
    p = urlparse(u)
    return f"{p.scheme}://{p.netloc}{p.path}".rstrip("/") + ("?" + p.query if p.query else "")


def kt_str(s):
    """Kotlin "..." içine güvenli metin."""
    return s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")


def is_challenge(code, text):
    t = (text or "")[:20000]
    return code in (403, 429, 503) and any(k in t for k in (
        "Just a moment", "cf-chl", "challenge-platform", "cf_chl_opt", "Attention Required"))


def name_from_domain(u):
    h = host(u).split(".")[0]
    h = re.sub(r"\d+$", "", h) or h
    parts = re.split(r"[^a-zA-Z0-9]+", h)
    name = "".join(p[:1].upper() + p[1:] for p in parts if p)
    if not name or not name[0].isalpha():
        name = "Site" + name
    return name


# ----------------------------------------------------------------------------- ağ / HAR
class Fetcher:
    def __init__(self, har_path=None, offline=False):
        self.s = requests.Session()
        self.s.headers.update({
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
        })
        self.har = {}
        self.har_entries = []
        self.offline = offline
        self.log = []
        self.blocked = set()
        if har_path:
            self._load_har(har_path)

    def _load_har(self, path):
        with open(path, encoding="utf-8", errors="replace") as f:
            har = json.load(f)
        for e in har.get("log", {}).get("entries", []):
            rq, rs = e.get("request", {}), e.get("response", {})
            text = (rs.get("content") or {}).get("text") or ""
            if (rs.get("content") or {}).get("encoding") == "base64":
                try:
                    import base64
                    text = base64.b64decode(text).decode("utf-8", "replace")
                except Exception:
                    text = ""
            self.har_entries.append({
                "method": rq.get("method"), "url": rq.get("url", ""), "status": rs.get("status", 0),
                "mime": (rs.get("content") or {}).get("mimeType", ""), "text": text,
                "post": (rq.get("postData") or {}).get("text", ""),
            })
            if rq.get("method") == "GET" and text and rs.get("status", 0) in range(200, 300):
                self.har.setdefault(norm(rq.get("url", "")), (rs.get("status"), text, rq.get("url")))
        print(f"[HAR] {len(self.har_entries)} istek okundu, {len(self.har)} sayfa kullanılabilir.")

    def get(self, url, referer=None):
        """(kod, metin, son_url) döner; hata olursa (0, '', url)."""
        key = norm(url)
        if key in self.har:
            code, text, u = self.har[key]
            return code, text, u
        alt = key[:-1] if key.endswith("/") else key + "/"
        if alt in self.har:
            code, text, u = self.har[alt]
            return code, text, u
        if self.offline:
            return 0, "", url
        try:
            h = {"Referer": referer} if referer else {}
            r = self.s.get(url, headers=h, timeout=20, allow_redirects=True)
            code, text = r.status_code, r.text
            self.log.append(f"GET {code} {url}")
            if is_challenge(code, text):
                self.blocked.add(host(url))
            return code, text, r.url
        except Exception as ex:
            self.log.append(f"GET HATA {url}: {ex.__class__.__name__}")
            return 0, "", url

    def soup(self, url, referer=None):
        code, text, final = self.get(url, referer)
        return BeautifulSoup(text or "", "html.parser"), code, text, final


# ----------------------------------------------------------------------------- analiz
def selector_of(el):
    classes = [c for c in (el.get("class") or []) if re.match(r"^[A-Za-z_][\w-]*$", c)
               and not re.match(r"^(active|show|hide|hidden|lazy|loaded|swiper-slide-\w+|col-\w*\d+)$", c)]
    if classes:
        return f"{el.name}.{classes[0]}"
    return None


def content_links(soup, base):
    h = host(base)
    out = []
    for a in soup.find_all("a", href=True):
        href = urljoin(base, a["href"].strip())
        if not href.startswith("http") or host(href) != h:
            continue
        p = path_of(href)
        if p in ("", "/") or JUNK_PATH.search(href):
            continue
        out.append((a, href))
    return out


def detect_cards(soup, base):
    """Tekrarlayan kart kapsayıcısını bulur. Dönen: (seçici, örnekler[(el, href)])"""
    sigs = Counter()
    examples = defaultdict(list)
    for a, href in content_links(soup, base):
        # Yukarı çık: kapsayıcı yalnız BU içeriğin linkini içerdiği sürece.
        node, best = a, None
        for _ in range(5):
            if node is None or node.name in ("body", "html", "[document]"):
                break
            links = {norm(urljoin(base, x["href"])) for x in node.find_all("a", href=True)} | (
                {norm(href)} if node.name == "a" else set())
            if len(links) > 1:
                break
            if node.find("img") is not None and selector_of(node):
                best = node
            node = node.parent
        if best is None:
            continue
        sig = selector_of(best)
        if sig and len(examples[sig]) < 400 and all(e is not best for e, _ in examples[sig][-3:]):
            sigs[sig] += 1
            examples[sig].append((best, href))
    ranked = [(s, n) for s, n in sigs.most_common() if n >= 4]
    if not ranked:
        return None, []
    # En çok tekrar edeni seç; eşitlikte article/li tercih.
    ranked.sort(key=lambda x: (x[1] + (2 if x[0].startswith(("article.", "li.")) else 0)), reverse=True)
    sig = ranked[0][0]
    return sig, examples[sig]


def detect_title_selectors(examples):
    cands = [".card-title", "h2", "h3", "h4", ".title", ".name", ".film-title", ".movie-title",
             "[class*=title]", "[class*=name]", "a[title]"]
    res = []
    for c in cands:
        ok = 0
        for el, _ in examples[:40]:
            t = el.select_one(c)
            if t is not None and t.get_text(strip=True) and len(t.get_text(strip=True)) < 120:
                ok += 1
        ratio = ok / max(1, min(40, len(examples)))
        if ratio >= 0.6 and c != "a[title]":
            res.append((ratio, c))
    res.sort(key=lambda x: -x[0])
    return [c for _, c in res[:3]]


def detect_poster_attrs(examples):
    attrs = ["data-src", "data-lazy-src", "data-original", "data-srcset", "srcset", "src"]
    score = Counter()
    for el, _ in examples[:40]:
        img = el.find("img")
        if img is None:
            continue
        for a in attrs:
            v = (img.get(a) or "").strip()
            if v and not v.startswith("data:") and (v.startswith("http") or v.startswith("/")):
                score[a] += 1
    ordered = [a for a, _ in score.most_common()]
    return ordered + [a for a in attrs if a not in ordered]


def card_title(el, title_sel):
    for c in title_sel:
        x = el.select_one(c)
        if x is not None and x.get_text(strip=True):
            return x.get_text(strip=True)
    a = el if el.name == "a" else el.find("a")
    if a is not None and a.get("title"):
        return a.get("title")
    img = el.find("img")
    return img.get("alt") if img is not None else None


def first_seg(u):
    segs = [s for s in path_of(u).split("/") if s]
    return segs[0].lower() if segs else ""


def classify_hints(hrefs):
    movie, series = Counter(), Counter()
    for h in hrefs:
        seg = first_seg(h)
        p = path_of(h).lower()
        if MOVIE_SEG.match(seg) or re.search(r"/(film|movie)s?/", p):
            movie[f"/{seg}/"] += 1
        elif SERIES_SEG.match(seg) or re.search(r"/(dizi|series|tvshows?|anime)/", p):
            series[f"/{seg}/"] += 1
    return [k for k, _ in movie.most_common(3)], [k for k, _ in series.most_common(3)]


def count_cards(soup, sel):
    try:
        return len(soup.select(sel))
    except Exception:
        return 0


def detect_categories(F, home_soup, base, card_sel, limit=8):
    seen, cands = set(), []
    areas = home_soup.select("nav a[href], header a[href], [class*=menu] a[href], [class*=nav] a[href], "
                             "[id*=menu] a[href], [class*=dropdown] a[href]")
    pool = areas or home_soup.find_all("a", href=True)
    for a in pool:
        href = urljoin(base, a.get("href", "").strip())
        text = re.sub(r"\s+", " ", a.get_text(" ", strip=True))
        if not href.startswith("http") or host(href) != host(base) or JUNK_PATH.search(href):
            continue
        p = path_of(href)
        if p in ("", "/") or len(text) < 2 or len(text) > 30:
            continue
        depth = len([s for s in p.split("/") if s])
        if depth > 3:
            continue
        if not CATEGORY_WORDS.search(p + " " + text) and not areas:
            continue
        k = norm(href)
        if k in seen:
            continue
        seen.add(k)
        cands.append((text, href))
    found = []
    for text, href in cands[:25]:
        soup, code, _, _ = F.soup(href, base + "/")
        n = count_cards(soup, card_sel)
        print(f"  kategori dene: {text!r:28} {code} kart={n}")
        if n >= 4:
            found.append((text.title() if text.isupper() or text.islower() else text, href, soup))
        if len(found) >= limit:
            break
    return found


PAGE_PATTERNS = [
    (re.compile(r"/page/(\d+)/?(?:$|\?)"), "{url}/page/{page}/"),
    (re.compile(r"/sayfa/(\d+)/?(?:$|\?)"), "{url}/sayfa/{page}"),
    (re.compile(r"[?&]page=(\d+)"), "{url}?page={page}"),
    (re.compile(r"[?&]sayfa=(\d+)"), "{url}?sayfa={page}"),
    (re.compile(r"[?&]p=(\d+)"), "{url}?p={page}"),
    (re.compile(r"/(\d+)/?$"), "{url}/{page}/"),
]


def page_url(pattern, base, page):
    b = base.rstrip("/")
    p = pattern
    if "?" in b and p.startswith("{url}?"):
        p = p.replace("{url}?", "{url}&", 1)
    return p.replace("{url}", b).replace("{page}", str(page))


def detect_pagination(F, cat_url, cat_soup, card_sel):
    hrefs = [urljoin(cat_url, a["href"]) for a in cat_soup.find_all("a", href=True)]
    for rx, pat in PAGE_PATTERNS:
        if any((m := rx.search(h)) and m.group(1) == "2" for h in hrefs):
            u2 = page_url(pat, cat_url, 2)
            s2, code, _, _ = F.soup(u2, cat_url)
            n = count_cards(s2, card_sel)
            first1 = [norm(urljoin(cat_url, a["href"])) for a in cat_soup.select(card_sel + " a[href]")[:3]]
            first2 = [norm(urljoin(u2, a["href"])) for a in s2.select(card_sel + " a[href]")[:3]]
            print(f"  sayfalama dene: {pat} -> {code} kart={n}")
            if n >= 4 and first1 != first2:
                return pat, u2
    # Linkte görünmese de yaygın biçimleri dene
    for pat in ["{url}/page/{page}/", "{url}?page={page}", "{url}/sayfa/{page}"]:
        u2 = page_url(pat, cat_url, 2)
        s2, code, _, _ = F.soup(u2, cat_url)
        n = count_cards(s2, card_sel)
        first1 = [norm(urljoin(cat_url, a["href"])) for a in cat_soup.select(card_sel + " a[href]")[:3]]
        first2 = [norm(urljoin(u2, a["href"])) for a in s2.select(card_sel + " a[href]")[:3]]
        print(f"  sayfalama (tahmin) dene: {pat} -> {code} kart={n}")
        if code == 200 and n >= 4 and first1 != first2:
            return pat, u2
    return "", None


def detect_search(F, base, home_soup, card_sel, word):
    cands = []
    for form in home_soup.find_all("form"):
        inp = form.find("input", attrs={"type": re.compile("text|search", re.I)}) or form.find("input", attrs={"name": True})
        if inp is None or not inp.get("name"):
            continue
        if (form.get("method") or "get").lower() != "get":
            continue
        action = urljoin(base + "/", form.get("action") or "/")
        if host(action) != host(base):
            continue
        cands.append(f"{action}{'&' if '?' in action else '?'}{inp['name']}={{q}}")
    cands += [f"{base}/?s={{q}}", f"{base}/arama?q={{q}}", f"{base}/search?q={{q}}", f"{base}/ara?q={{q}}",
              f"{base}/arama/{{q}}", f"{base}/search/{{q}}", f"{base}/?q={{q}}"]
    tried = set()
    for c in cands:
        if c in tried:
            continue
        tried.add(c)
        u = c.replace("{q}", quote_plus(word))
        soup, code, _, _ = F.soup(u, base + "/")
        n = count_cards(soup, card_sel)
        print(f"  arama dene: {c} -> {code} kart={n}")
        if code == 200 and n >= 1:
            return c, n
    return "", 0


def detect_episodes(soup, base, page_url_):
    links = []
    for a in soup.find_all("a", href=True):
        href = urljoin(base, a["href"].strip())
        if host(href) != host(base) or norm(href) == norm(page_url_):
            continue
        links.append(href.lower())
    best, best_n = "", 0
    for tok in EP_TOKENS:
        n = len({l for l in links if tok in l})
        # bölüm/episode içeren tercih; sezon-only daha düşük puan
        bonus = 0 if ("sezon" in tok or "season" in tok) else 1
        if n >= 2 and (n + bonus * 1000) > best_n:
            best, best_n = tok, n + bonus * 1000
    if best:
        return f'a[href*="{best}"]', len({l for l in links if best in l}), ("sezon" in best or "season" in best)
    return "", 0, False


def analyze_player(F, page_url_, html):
    soup = BeautifulSoup(html or "", "html.parser")
    info = OrderedDict()
    ifr = []
    for f in soup.find_all("iframe"):
        src = f.get("src") or f.get("data-src") or f.get("data-lazy-src") or ""
        if src and not any(j in src for j in IFRAME_JUNK):
            ifr.append(urljoin(page_url_, src))
    info["iframe"] = ifr
    data_urls = []
    for el in soup.find_all(True):
        for k, v in el.attrs.items():
            if k.startswith("data-") and isinstance(v, str) and (v.startswith("http") or v.startswith("//")) \
                    and not re.search(r"\.(jpe?g|png|webp|gif|svg)(\?|$)", v, re.I):
                data_urls.append(f"{k}={v[:120]}")
    info["data_url"] = data_urls[:15]
    info["dooplay"] = bool(soup.select_one("li.dooplay_player_option, #playeroptionsul"))
    info["data_cfg"] = "data-cfg" in (html or "")
    low = (html or "").lower()
    info["m3u8_in_page"] = ".m3u8" in low
    info["jwplayer"] = "jwplayer" in low
    info["packed_js"] = "eval(function(p,a,c,k,e," in low
    info["ajax_endpoints"] = sorted(set(re.findall(r"""["'](/[\w\-/]*(?:ajax|api|player|embed|source)[\w\-/.]*)["']""",
                                                    html or "", re.I)))[:20]
    info["admin_ajax"] = "admin-ajax.php" in low
    info["alternatif"] = sorted(set(a.get_text(" ", strip=True)[:30] for a in soup.select(
        "[class*=alternatif] a, [class*=source] a, [class*=server] a, [class*=player] li, .tabs a")))[:12]
    return info


def har_player_hints(F, h):
    out = []
    for e in F.har_entries:
        u, t = e["url"], e["text"] or ""
        if not u.startswith("http"):
            continue
        if any(k in u.lower() for k in (".m3u8", "/embed", "player", "ajax", "/api/", "source")) or \
                (e["mime"].startswith("application/json") and ("m3u8" in t or "embed" in t or "iframe" in t)):
            if re.search(r"\.(ts|png|jpe?g|webp|css|woff2?|js)(\?|$)", u, re.I) or \
                    re.search(r"/api/(img|image|analytics|comments?|brand|ad-config|title-overrides)|analytics|"
                              r"google|facebook|doubleclick", u, re.I):
                continue
            out.append(f"{e['method']} {e['status']} {u[:160]}" + (f"  BODY={e['post'][:80]}" if e["post"] else ""))
    return out[:60]


# ----------------------------------------------------------------------------- Kotlin şablonları
KT_TEMPLATE = r'''package com.darkness.@@PKGN@@

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * @@NAME@@ — darkness_uretici.py ile @@DATE@@ tarihinde @@URL@@ analiz edilerek üretildi.
 *
 * Site değişirse önce aşağıdaki "ANALİZ SONUÇLARI" bölümündeki seçicileri güncelle.
 * Oynatma sırası: sayfadaki doğrudan m3u8/mp4 → gömülü oynatıcılar (iframe, data-*,
 * DooPlay) → CloudStream extractor'ları → oynatıcı sayfasında m3u8 arama →
 * gerçek tarayıcı (WebViewResolver) son çare olarak.
 */
class @@NAME@@ : MainAPI() {
    override var mainUrl = "@@MAINURL@@"
    override var name = "@@NAME@@"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(@@TVTYPES@@)

    // ------------------------------------------------------------ ANALİZ SONUÇLARI
    /** Liste/arama sayfasındaki tek bir içerik kartı. */
    private val cardSelector = "@@CARD@@"
    /** Kart içinde başlık (sırayla denenir; yoksa a[title] / img[alt]). */
    private val titleSelectors = listOf(@@TITLESEL@@)
    /** Afiş resminin adresinin bulunduğu özellikler (sırayla). */
    private val posterAttrs = listOf(@@POSTERATTRS@@)
    /** Arama adresi: {q} = aranan kelime. Boşsa arama kapalı. */
    private val searchUrl = @@SEARCH@@
    /** Sayfalama: {url} = liste adresi, {page} = sayfa no. Boşsa tek sayfa. */
    private val pagePattern = "@@PAGEPAT@@"
    /** Dizi sayfasındaki bölüm linkleri. */
    private val episodeSelector = "@@EPSEL@@"
    /** Adreste bunlar geçiyorsa film / dizi kabul edilir. */
    private val movieHints = listOf(@@MOVIEHINTS@@)
    private val seriesHints = listOf(@@SERIESHINTS@@)
    /** WordPress DooPlay teması (admin-ajax.php doo_player_ajax). */
    private val useDooPlay = @@DOOPLAY@@

    override val mainPage = mainPageOf(
@@MAINPAGE@@
    )

    // ------------------------------------------------------------ ağ
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
    private val cfKiller by lazy { CloudflareKiller() }

    private fun headers(referer: String = "$mainUrl/") = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer" to referer,
    )

    private fun isChallenge(code: Int, body: String): Boolean =
        code in setOf(403, 429, 503) && (
            body.contains("Just a moment", true) || body.contains("cf-chl", true) ||
                body.contains("challenge-platform", true) || body.contains("cf_chl_opt", true)
            )

    /** GET; Cloudflare engeli görülürse bir kez CloudflareKiller ile yeniden dener. */
    private suspend fun get(url: String, referer: String = "$mainUrl/"): NiceResponse {
        val r = app.get(url, headers = headers(referer))
        if (!isChallenge(r.code, r.text)) return r
        Log.w(TAG, "CF_BLOCK code=${r.code} $url")
        return runCatching { app.get(url, headers = headers(referer), interceptor = cfKiller) }.getOrDefault(r)
    }

    // ------------------------------------------------------------ yardımcılar
    private fun abs(u: String?): String? {
        val s = u?.trim()?.replace("\\/", "/")?.takeIf {
            it.isNotBlank() && !it.startsWith("data:") && !it.startsWith("javascript", true) && !it.startsWith("#")
        } ?: return null
        val root = mainUrl.trimEnd('/')
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> root + s
            else -> "$root/$s"
        }
    }

    private fun originOf(u: String): String =
        Regex("""^(https?://[^/]+)""").find(u)?.groupValues?.getOrNull(1) ?: u

    private fun hostOf(u: String): String = originOf(u).substringAfter("://")

    private fun absTo(base: String, u: String): String {
        val s = u.trim().replace("\\/", "/")
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> originOf(base) + s
            else -> runCatching { java.net.URI(base).resolve(s).toString() }.getOrDefault(s)
        }
    }

    private fun Element.posterUrl(): String? {
        val img = if (tagName() == "img") this else (selectFirst("img") ?: return null)
        for (a in posterAttrs) {
            val v = img.attr(a).trim()
            if (v.isBlank()) continue
            val first = if (a.endsWith("srcset")) v.split(",").first().trim().split(" ").first() else v
            abs(first)?.let { return it }
        }
        return null
    }

    private fun isSeriesUrl(u: String) = seriesHints.any { u.contains(it, true) }
    private fun isMovieUrl(u: String) = movieHints.any { u.contains(it, true) }

    private fun Element.toCard(): SearchResponse? {
        val a = if (tagName() == "a") this else (selectFirst("a[href]") ?: return null)
        val href = abs(a.attr("href")) ?: return null
        val title = titleSelectors.firstNotNullOfOrNull { sel ->
            runCatching { selectFirst(sel) }.getOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }
        }
            ?: a.attr("title").trim().takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        val poster = posterUrl()
        val year = Regex("""\b(19|20)\d{2}\b""").find(text())?.value?.toIntOrNull()
        return if (isSeriesUrl(href) && !isMovieUrl(href)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster; this.year = year }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster; this.year = year }
        }
    }

    private fun Document.cards(): List<SearchResponse> =
        select(cardSelector).mapNotNull { it.toCard() }.distinctBy { it.url }

    private fun pagedUrl(base: String, page: Int): String {
        if (page <= 1 || pagePattern.isBlank()) return base
        val b = base.trimEnd('/')
        var p = pagePattern
        if (b.contains('?') && p.startsWith("{url}?")) p = p.replaceFirst("{url}?", "{url}&")
        return p.replace("{url}", b).replace("{page}", page.toString())
    }

    // ------------------------------------------------------------ ana sayfa / arama
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1 && pagePattern.isBlank()) {
            return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)
        }
        val url = pagedUrl(request.data, page)
        val items = runCatching { get(url).document.cards() }
            .onFailure { Log.e(TAG, "MAIN_ERR $url ${it.message}") }
            .getOrDefault(emptyList())
        Log.i(TAG, "MAIN ${request.name} page=$page items=${items.size} url=$url")
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && pagePattern.isNotBlank())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (searchUrl.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val url = searchUrl.replace("{q}", q)
        val items = runCatching { get(url).document.cards() }
            .onFailure { Log.e(TAG, "SEARCH_ERR $url ${it.message}") }
            .getOrDefault(emptyList())
        Log.i(TAG, "SEARCH '$query' items=${items.size}")
        return items
    }

    // ------------------------------------------------------------ detay
    private val seasonRes = listOf(
        Regex("""(\d+)\s*[-_.]?\s*sezon""", RegexOption.IGNORE_CASE),
        Regex("""sezon\s*[-_]?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""season\s*[-_]?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""\b[sS](\d{1,2})[eE]\d{1,3}\b"""),
    )
    private val episodeRes = listOf(
        Regex("""(\d+)\s*[-_.]?\s*b[öo]l[üu]m""", RegexOption.IGNORE_CASE),
        Regex("""b[öo]l[üu]m\s*[-_]?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""episode\s*[-_]?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""\b[sS]\d{1,2}[eE](\d{1,3})\b"""),
    )

    private fun firstNum(res: List<Regex>, vararg texts: String): Int? =
        texts.firstNotNullOfOrNull { t ->
            res.firstNotNullOfOrNull { it.find(t)?.groupValues?.getOrNull(1)?.toIntOrNull() }
        }

    override suspend fun load(url: String): LoadResponse? {
        val doc = get(url).document
        val rawTitle = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title()
        val title = rawTitle
            .replace(Regex("""\s*[-|–]\s*[^-|–]*$"""), "")
            .replace(Regex("""\s+(izle|full hd izle|türkçe dublaj izle|altyazılı izle)\s*$""", RegexOption.IGNORE_CASE), "")
            .trim().ifBlank { rawTitle }
        val poster = abs(doc.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: doc.selectFirst(".poster img, .film-poster img, img[class*=poster], .thumb img")?.posterUrl()
        val plot = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")?.trim()
        val year = Regex("""\b(19|20)\d{2}\b""")
            .find(doc.select(".year, .date, .meta, .info, .details, [class*=year]").text())?.value?.toIntOrNull()
        val tags = doc.select("a[href*=/tur/], a[href*=/kategori/], a[href*=/genre/], a[href*=/category/], a[rel=tag]")
            .map { it.text().trim() }.filter { it.isNotBlank() && it.length < 30 }.distinct().take(8)

        val seen = HashSet<String>()
        val episodes = if (episodeSelector.isBlank()) emptyList() else doc.select(episodeSelector).mapNotNull { a ->
            val href = abs(a.attr("href")) ?: return@mapNotNull null
            if (href.trimEnd('/') == url.trimEnd('/') || !seen.add(href.trimEnd('/'))) return@mapNotNull null
            val text = a.text().trim()
            newEpisode(href) {
                this.season = firstNum(seasonRes, href, text) ?: 1
                this.episode = firstNum(episodeRes, href, text)
                this.name = a.attr("title").trim().ifBlank { text }.takeIf { it.isNotBlank() && it.length < 90 }
                this.posterUrl = a.posterUrl()
            }
        }.sortedWith(compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 }))

        Log.i(TAG, "LOAD $url episodes=${episodes.size}")
        if (episodes.isNotEmpty() || (isSeriesUrl(url) && !isMovieUrl(url))) {
            val eps = episodes.ifEmpty { listOf(newEpisode(url) { this.name = title }) }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, eps) {
                this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags
            }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster; this.plot = plot; this.year = year; this.tags = tags
        }
    }

    // ------------------------------------------------------------ oynatma
    private val fileRe = Regex(
        """["']?(?:file|src|source|url|hls)["']?\s*[:=]\s*["']([^"']+?\.(?:m3u8|mp4)(?:\?[^"']*)?)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val mediaRe = Regex(
        """(?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE,
    )
    private val subRe = Regex(
        """["']?file["']?\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["'][^{}]*?["']?label["']?\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val iframeJunk = listOf(
        "googletagmanager", "recaptcha", "facebook.com", "disqus", "twitter.com", "doubleclick",
        "youtube.com/embed", "youtube-nocookie", "instagram.com",
    )

    private fun unpacked(html: String): String = buildString {
        append(html.replace("\\/", "/"))
        if (html.contains("eval(function(p,a,c,k,e,")) {
            runCatching { getAndUnpack(html) }.getOrNull()?.let { append('\n').append(it.replace("\\/", "/")) }
        }
    }

    private fun mediaIn(html: String, base: String): List<String> {
        val text = unpacked(html)
        val out = LinkedHashSet<String>()
        fileRe.findAll(text).forEach { out += absTo(base, it.groupValues[1]) }
        mediaRe.findAll(text).forEach { out += absTo(base, it.value) }
        return out.filter {
            it.startsWith("http") && !it.contains("thumb", true) && !it.contains("preview", true) &&
                !it.contains("/ads", true) && !it.contains("trailer", true)
        }
    }

    private fun embedsIn(doc: Document): List<String> {
        val out = LinkedHashSet<String>()
        doc.select("iframe").forEach { f ->
            listOf("src", "data-src", "data-lazy-src").map { f.attr(it).trim() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("about:") }
                ?.let { abs(it) }?.let { out += it }
        }
        val keys = listOf("data-iframe", "data-embed", "data-video", "data-link", "data-url", "data-frame", "data-src")
        doc.select("[data-iframe], [data-embed], [data-video], [data-link], [data-url], [data-frame]").forEach { e ->
            keys.map { e.attr(it).trim() }
                .filter { (it.startsWith("http") || it.startsWith("//")) && !Regex("""\.(jpe?g|png|webp|gif)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .forEach { u -> abs(u)?.let { out += it } }
        }
        return out.filter { u -> iframeJunk.none { u.contains(it, true) } }
    }

    private suspend fun dooPlayEmbeds(doc: Document, pageUrl: String): List<String> {
        val out = ArrayList<String>()
        for (o in doc.select("li.dooplay_player_option[data-post], li[data-post][data-nume][data-type]")) {
            val nume = o.attr("data-nume")
            if (nume.equals("trailer", true)) continue
            val r = runCatching {
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax", "post" to o.attr("data-post"),
                        "nume" to nume, "type" to o.attr("data-type"),
                    ),
                    headers = headers(pageUrl) + ("X-Requested-With" to "XMLHttpRequest"),
                ).text
            }.getOrNull() ?: continue
            val embed = Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(r)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: continue
            val src = if (embed.contains("<iframe", true)) {
                Regex("""src=["']([^"']+)["']""").find(embed)?.groupValues?.get(1)
            } else embed
            abs(src)?.let { out += it }
        }
        Log.i(TAG, "DOOPLAY embeds=${out.size}")
        return out
    }

    private suspend fun emitMedia(url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val isHls = url.contains(".m3u8", true)
        callback(
            newExtractorLink(name, name, url, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua, "Referer" to referer, "Origin" to originOf(referer))
            }
        )
    }

    /** Son çare: embed sayfasını gerçek tarayıcıda (WebView) açıp ağdaki m3u8/mp4 isteğini yakalar. */
    private suspend fun browserFallback(embed: String, pageUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val re = Regex("""\.(m3u8|mp4)(\?|$)""", RegexOption.IGNORE_CASE)
        val got = runCatching {
            app.get(embed, referer = pageUrl, interceptor = com.lagradost.cloudstream3.network.WebViewResolver(re)).url
        }.getOrNull() ?: return false
        if (!re.containsMatchIn(got)) return false
        emitMedia(got, embed, callback)
        return true
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = get(data)
        val doc = res.document
        var found = 0

        // 1) Sayfada doğrudan yayın adresi
        mediaIn(res.text, data).forEach { emitMedia(it, data, callback); found++ }

        // 2) Gömülü oynatıcılar
        val embeds = LinkedHashSet<String>()
        embeds += embedsIn(doc)
        if (useDooPlay || doc.selectFirst("li.dooplay_player_option") != null) embeds += dooPlayEmbeds(doc, data)
        Log.i(TAG, "LINKS page=$data direct=$found embeds=${embeds.size} hosts=${embeds.map { hostOf(it) }}")

        for (e in embeds) {
            val bare = e.substringBefore('?')
            if (bare.endsWith(".m3u8", true) || bare.endsWith(".mp4", true)) {
                emitMedia(e, data, callback); found++; continue
            }
            var got = false
            // 2a) CloudStream'in hazır extractor'ları (bilinen video sunucuları)
            runCatching { loadExtractor(e, data, subtitleCallback) { link -> got = true; callback(link) } }
            // 2b) Tanınmayan oynatıcı: sayfasında m3u8/mp4 ara (packed JS dahil)
            if (!got) {
                val html = runCatching { app.get(e, headers = headers(data)).text }.getOrNull().orEmpty()
                val media = mediaIn(html, e)
                media.forEach { emitMedia(it, e, callback) }
                subRe.findAll(unpacked(html)).forEach {
                    subtitleCallback(newSubtitleFile(it.groupValues[2], absTo(e, it.groupValues[1])))
                }
                got = media.isNotEmpty()
                Log.i(TAG, "EMBED ${hostOf(e)} media=${media.size}")
            }
            // 2c) Hiçbiri tutmadıysa gerçek tarayıcıda (WebView) yayını yakala
            if (!got) got = browserFallback(e, data, callback)
            if (got) found++
        }
        Log.i(TAG, "LINKS_DONE found=$found embeds=${embeds.size}")
        return found > 0
    }

    companion object {
        private const val TAG = "@@TAG@@"
    }
}
'''

PLUGIN_TEMPLATE = '''package com.darkness.@@PKGN@@

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class @@NAME@@Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(@@NAME@@())
    }
}
'''

GRADLE_TEMPLATE = '''version = 1

cloudstream {
    authors     = listOf("Darkness")
    language    = "tr"
    description = "@@DESC@@"

    status  = 1
    tvTypes = listOf(@@GRADLETYPES@@)

    iconUrl = "https://www.google.com/s2/favicons?domain=@@HOST@@&sz=%size%"
}
'''


SETTINGS_TPL = 'rootProject.name = "@@NAME@@Eklentileri"\n\n// build.gradle.kts dosyası olan her klasör otomatik olarak eklenti sayılır.\nval disabled = listOf<String>()\n\nFile(rootDir, ".").eachDir { dir ->\n    if (!disabled.contains(dir.name) && File(dir, "build.gradle.kts").exists()) {\n        include(dir.name)\n    }\n}\n\nfun File.eachDir(block: (File) -> Unit) {\n    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }\n}\n'
ROOTB_TPL = 'import com.android.build.gradle.BaseExtension\nimport com.lagradost.cloudstream3.gradle.CloudstreamExtension\nimport org.jetbrains.kotlin.gradle.dsl.JvmTarget\nimport org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile\n\nbuildscript {\n    repositories {\n        google()\n        mavenCentral()\n        maven("https://jitpack.io")\n    }\n\n    dependencies {\n        classpath("com.android.tools.build:gradle:8.7.3")\n        // CloudStream Gradle eklentisi: .cs3 dosyalarını üretir\n        classpath("com.github.recloudstream:gradle:-SNAPSHOT")\n        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")\n    }\n}\n\nallprojects {\n    repositories {\n        google()\n        mavenCentral()\n        maven("https://jitpack.io")\n    }\n}\n\nfun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =\n    extensions.getByName<CloudstreamExtension>("cloudstream").configuration()\n\nfun Project.android(configuration: BaseExtension.() -> Unit) =\n    extensions.getByName<BaseExtension>("android").configuration()\n\nsubprojects {\n    apply(plugin = "com.android.library")\n    apply(plugin = "kotlin-android")\n    apply(plugin = "com.lagradost.cloudstream3.gradle")\n\n    cloudstream {\n        // GitHub Actions\'ta GITHUB_REPOSITORY otomatik olarak "kullanici/depo" olur\n        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "kullanici/depo")\n    }\n\n    android {\n        namespace = "@@PKG@@"\n\n        defaultConfig {\n            minSdk = 21\n            compileSdkVersion(35)\n            targetSdk = 35\n        }\n\n        compileOptions {\n            sourceCompatibility = JavaVersion.VERSION_11\n            targetCompatibility = JavaVersion.VERSION_11\n        }\n\n        tasks.withType<KotlinJvmCompile> {\n            compilerOptions {\n                jvmTarget.set(JvmTarget.JVM_11)\n                freeCompilerArgs.addAll(\n                    "-Xno-call-assertions",\n                    "-Xno-param-assertions",\n                    "-Xno-receiver-assertions"\n                )\n            }\n        }\n    }\n\n    dependencies {\n        val cloudstream by configurations\n        val implementation by configurations\n\n        // CloudStream sınıflarının taslakları\n        cloudstream("com.lagradost:cloudstream3:pre-release")\n\n        implementation(kotlin("stdlib"))\n        implementation("com.github.Blatzar:NiceHttp:0.4.18")\n        implementation("org.jsoup:jsoup:1.22.1")\n        // jsoup 1.22 tür açıklamaları için gerekli\n        implementation("org.jspecify:jspecify:1.0.0")\n        // Jackson\'ı 2.13.1\'in üstüne çıkarma: eski cihazlarda bozulur\n        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")\n        // Rhino uygulamada hazır gelir; yalnızca derleme için gerekli (Rhino kullanan referans eklentiler)\n        val compileOnly by configurations\n        compileOnly("org.mozilla:rhino:1.7.14")\n        compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")\n    }\n}\n\ntask<Delete>("clean") {\n    delete(rootProject.layout.buildDirectory)\n}\n'
PROPS_TPL = 'org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\nandroid.useAndroidX=true\nandroid.enableJetifier=true\n'
GIT_TPL = '*.iml\n.gradle\n/local.properties\n/.idea\n.DS_Store\n/build\n**/build\n/captures\n.externalNativeBuild\n.cxx\nlocal.properties\n.vscode\n'


def kt_list(items):
    return ", ".join(f'"{kt_str(x)}"' for x in items)


def rel_to_main(u, base):
    """https://site.com/filmler/ -> "$mainUrl/filmler/" (alan adı değişirse eklenti yine çalışsın)."""
    b = base.rstrip("/")
    if u.startswith(b):
        return '"$mainUrl' + kt_str(u[len(b):]) + '"'
    return f'"{kt_str(u)}"'


# ----------------------------------------------------------------------------- ana akış
def main():
    ap = argparse.ArgumentParser(description="Site adresinden CS3 eklenti zip'i üretir.")
    ap.add_argument("url", nargs="?", help="Site adresi (https://...)")
    ap.add_argument("--ad", help="Eklenti adı (varsayılan: alan adından)")
    ap.add_argument("--har", help="Tarayıcıdan alınmış HAR dosyası (site Python'u engelliyorsa)")
    ap.add_argument("--offline", action="store_true", help="Yalnız HAR kullan, internete çıkma")
    ap.add_argument("--cikti", default="cikti", help="Çıktı klasörü")
    args = ap.parse_args()

    url = args.url or input("Site adresi (https://...): ").strip()
    if not url:
        print("Adres gerekli.")
        return 2
    if not url.startswith("http"):
        url = "https://" + url
    F = Fetcher(args.har, args.offline)

    rep = []  # rapor satırları
    todo = []  # elle bakılacaklar

    def R(s=""):
        print(s)
        rep.append(s)

    R(f"=== {url} analizi ({datetime.datetime.now():%Y-%m-%d %H:%M}) ===")
    soup, code, html, final = F.soup(url)
    if final and host(final) != host(url):
        R(f"Yönlendirme: {url} -> {final}")
    p = urlparse(final or url)
    base = f"{p.scheme}://{p.netloc}"
    name = re.sub(r"[^A-Za-z0-9_]", "", args.ad or name_from_domain(base)) or "YeniEklenti"
    R(f"Ana adres: {base}   HTTP {code}   boyut={len(html or '')}   eklenti adı: {name}")

    if host(base) in F.blocked or is_challenge(code, html):
        R("!! SİTE ENGELLİYOR (Cloudflare). Python siteyi açamıyor.")
        R("   Çözüm: Chrome'da siteyi aç → F12 → Network → ana sayfa, bir kategori, arama, bir film ve")
        R("   bir dizi sayfasını gez → sağ tık 'Save all as HAR' → şu komutla tekrar çalıştır:")
        R(f"   python darkness_uretici.py {url} --har site.har")
        todo.append("Cloudflare engeli: HAR ile tekrar çalıştır. Eklentide CloudflareKiller yedeği var ama tutmayabilir.")
    if code == 0 or not html:
        R("!! Site açılamadı (internet / adres / engel).")
        if not args.har:
            return 1

    # Next.js / SPA uyarısı
    if "__NEXT_DATA__" in (html or "") or (html and len(BeautifulSoup(html, 'html.parser').find_all('a')) < 8):
        todo.append("Site JavaScript ile çiziliyor olabilir (Next.js/SPA): kartlar HTML'de yoksa API'den okunmalı → HAR gönder.")

    # 1) kartlar
    card_sel, examples = detect_cards(soup, base)
    if not card_sel:
        R("✗ Kart bulunamadı (ana sayfada tekrarlayan film/dizi kutusu yok).")
        todo.append("Kart seçicisi bulunamadı: cardSelector'ı elle yaz (ör. 'div.movie-item').")
        card_sel = "article"
        examples = []
    else:
        R(f"✓ Kart seçicisi: {card_sel}   ({len(examples)} adet)")
    title_sel = detect_title_selectors(examples) if examples else []
    poster_attrs = detect_poster_attrs(examples) if examples else ["data-src", "src"]
    R(f"  başlık: {title_sel or ['a[title] / img[alt]']}   afiş: {poster_attrs[:3]}")
    hrefs = [h for _, h in examples]
    for el, h in examples[:3]:
        R(f"  örnek: {card_title(el, title_sel)!s:40.40} {h}")

    # 2) kategoriler
    R("\n-- Kategoriler")
    cats = detect_categories(F, soup, base, card_sel) if examples else []
    main_pages = []
    if count_cards(soup, card_sel) >= 4:
        main_pages.append(("Son Eklenenler", base + "/"))
    for text, href, _ in cats:
        if norm(href) != norm(base):
            main_pages.append((text, href))
    if not main_pages:
        main_pages.append(("Son Eklenenler", base + "/"))
        todo.append("Kategori bulunamadı: mainPage listesine elle adres ekle.")
    for t, h in main_pages:
        R(f"✓ {t:25} {h}")

    # sınıflandırma (kategori kartları da dahil)
    for _, _, cs in cats:
        hrefs += [norm(urljoin(base, a["href"])) for a in cs.select(card_sel + " a[href]")[:30]]
    movie_hints, series_hints = classify_hints(hrefs)
    if not movie_hints and not series_hints:
        todo.append("Film/dizi adres ayrımı bulunamadı: movieHints/seriesHints'i elle doldur (ör. '/film/', '/dizi/').")
    R(f"\n  film adresleri: {movie_hints}   dizi adresleri: {series_hints}")

    # 3) sayfalama
    R("\n-- Sayfalama")
    page_pat = ""
    probe_cat = cats[0] if cats else None
    if probe_cat:
        page_pat, u2 = detect_pagination(F, probe_cat[1], probe_cat[2], card_sel)
    elif examples:
        page_pat, u2 = detect_pagination(F, base + "/", soup, card_sel)
    R(f"✓ Sayfalama: {page_pat}" if page_pat else "✗ Sayfalama bulunamadı (yalnız 1. sayfa gelecek).")
    if not page_pat:
        todo.append("Sayfalama yok: liste 'Daha fazla' ile AJAX yükleniyor olabilir → HAR ile bak.")

    # 4) arama
    R("\n-- Arama")
    word = "a"
    for el, _ in examples[:10]:
        t = card_title(el, title_sel)
        if t:
            w = max(re.findall(r"[A-Za-zÇĞİÖŞÜçğıöşü]{4,}", t) or [""], key=len)
            if w:
                word = w
                break
    search_tpl, n = detect_search(F, base, soup, card_sel, word) if examples else ("", 0)
    if search_tpl:
        R(f"✓ Arama: {search_tpl}  ('{word}' → {n} sonuç)")
    else:
        R("✗ Arama bulunamadı ya da sonuçlar kart biçiminde değil.")
        todo.append("Arama: site AJAX/JSON arama kullanıyor olabilir (ör. /ajax-search) → HAR ile bak.")

    # 5) film ve dizi sayfası
    R("\n-- Detay sayfaları")
    movie_url = next((h for h in hrefs if any(m in h for m in movie_hints)), None) if movie_hints else None
    series_url = next((h for h in hrefs if any(s in h for s in series_hints)), None) if series_hints else None
    if not movie_url and hrefs:
        movie_url = hrefs[0]
    ep_sel, ep_count, ep_season_only, player_page = "", 0, False, movie_url
    if series_url:
        ssoup, scode, shtml, _ = F.soup(series_url, base + "/")
        ep_sel, ep_count, ep_season_only = detect_episodes(ssoup, base, series_url)
        R(f"  dizi örneği: {series_url}  HTTP {scode}")
        if ep_sel:
            R(f"✓ Bölüm linkleri: {ep_sel}  ({ep_count} adet)" + ("  [yalnız SEZON linkleri!]" if ep_season_only else ""))
            if ep_season_only:
                todo.append("Dizi sayfasında yalnız sezon linkleri var: bölümler sezon sayfasında. load() içinde sezon sayfalarını da gez.")
            first_ep = next((urljoin(base, a["href"]) for a in ssoup.select(ep_sel)), None)
            if not movie_url or not movie_hints:
                player_page = first_ep or player_page
            elif first_ep:
                R(f"  ilk bölüm: {first_ep}")
        else:
            R("✗ Dizi sayfasında bölüm linki bulunamadı (sezon/bölüm AJAX ile yükleniyor olabilir).")
            todo.append("Bölümler bulunamadı: episodeSelector'ı elle yaz ya da dizi sayfasının HAR'ını gönder.")
    else:
        R("  dizi örneği yok (site yalnız film olabilir).")

    player = {}
    if player_page:
        psoup, pcode, phtml, _ = F.soup(player_page, base + "/")
        R(f"  oynatma sayfası: {player_page}  HTTP {pcode}")
        player = analyze_player(F, player_page, phtml)
        R("\n-- Oynatıcı")
        for k, v in player.items():
            if v:
                R(f"  {k}: {v if not isinstance(v, list) else ''}")
                if isinstance(v, list):
                    for x in v[:10]:
                        R(f"      {x}")
        if not (player["iframe"] or player["data_url"] or player["dooplay"] or player["m3u8_in_page"]):
            R("✗ Sayfada oynatıcı adresi yok: oynatıcı JavaScript/AJAX ile geliyor.")
            todo.append("Oynatıcı JS/AJAX ile yükleniyor: bir film açılırken HAR al, gönder (loadLinks'e özel akış gerekecek).")
        if player.get("data_cfg"):
            todo.append("Sayfada data-cfg var (Dizipal tipi şifreli config): Dizipal eklentisindeki akış uyarlanmalı.")
        if player.get("alternatif"):
            todo.append("Alternatif kaynak sekmeleri var: her biri ayrı istek olabilir → HAR'da sekmelere tıkla.")

    if args.har:
        hints = har_player_hints(F, host(base))
        if hints:
            R("\n-- HAR'daki oynatıcı/API istekleri (özel akış için)")
            for h in hints:
                R("  " + h)

    # tür
    types = []
    if movie_hints or not series_hints:
        types.append("Movie")
    if series_hints or ep_sel:
        types.append("TvSeries")

    # 6) üret
    main_entries = ",\n".join(f'        {rel_to_main(h, base)} to "{kt_str(t)}"' for t, h in main_pages[:10])
    search_kt = rel_to_main(search_tpl, base) if search_tpl else '""'
    kt = (KT_TEMPLATE
          .replace("@@PKGN@@", name.lower())
          .replace("@@NAME@@", name)
          .replace("@@DATE@@", f"{datetime.date.today():%Y-%m-%d}")
          .replace("@@URL@@", base)
          .replace("@@MAINURL@@", kt_str(base))
          .replace("@@TVTYPES@@", ", ".join(f"TvType.{t}" for t in types))
          .replace("@@CARD@@", kt_str(card_sel))
          .replace("@@TITLESEL@@", kt_list(title_sel))
          .replace("@@POSTERATTRS@@", kt_list(poster_attrs))
          .replace("@@SEARCH@@", search_kt)
          .replace("@@PAGEPAT@@", kt_str(page_pat))
          .replace("@@EPSEL@@", kt_str(ep_sel))
          .replace("@@MOVIEHINTS@@", kt_list(movie_hints))
          .replace("@@SERIESHINTS@@", kt_list(series_hints))
          .replace("@@DOOPLAY@@", "true" if player.get("dooplay") else "false")
          .replace("@@MAINPAGE@@", main_entries)
          .replace("@@TAG@@", name.upper()[:23]))
    plugin = PLUGIN_TEMPLATE.replace("@@NAME@@", name).replace("@@PKGN@@", name.lower())
    desc_types = " / ".join({"Movie": "Film", "TvSeries": "Dizi"}[t] for t in types)
    gradle = (GRADLE_TEMPLATE
              .replace("@@DESC@@", kt_str(f"{name} - {desc_types} ({host(base)})"))
              .replace("@@GRADLETYPES@@", ", ".join(f'"{t}"' for t in types))
              .replace("@@HOST@@", host(base)))

    R("\n=== ELLE BAKILACAK ===")
    if todo:
        for t in todo:
            R(" • " + t)
    else:
        R(" (yok — derleyip telefonda dene)")

    os.makedirs(args.cikti, exist_ok=True)
    zpath = os.path.join(args.cikti, f"{name}.zip")
    rpath = os.path.join(args.cikti, f"{name}_ANALIZ.txt")
    report = "\n".join(rep) + "\n\n-- İstek günlüğü\n" + "\n".join(F.log) + "\n"
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        top = f"{name}-cloudstream/"
        mod = top + f"{name}/"
        src = mod + f"src/main/kotlin/com/darkness/{name.lower()}/"
        z.writestr(top + "settings.gradle.kts", SETTINGS_TPL.replace("@@NAME@@", name))
        z.writestr(top + "build.gradle.kts", ROOTB_TPL.replace("@@PKG@@", f"com.darkness.{name.lower()}"))
        z.writestr(top + "gradle.properties", PROPS_TPL)
        z.writestr(top + ".gitignore", GIT_TPL)
        z.writestr(mod + "build.gradle.kts", gradle)
        z.writestr(src + f"{name}.kt", kt)
        z.writestr(src + f"{name}Plugin.kt", plugin)
        z.writestr(mod + f"{name}_ANALIZ.txt", report)
    with open(rpath, "w", encoding="utf-8") as f:
        f.write(report)
    print(f"\n>>> Hazır: {zpath}")
    print(f">>> Rapor: {rpath}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
