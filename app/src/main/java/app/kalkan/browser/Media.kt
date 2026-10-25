package app.kalkan.browser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Sayfanın yüklediği bir kaynak (medya, betik, stil, font, resim...). */
class MediaItem(
    val url: String,
    val kind: String,
    val referer: String?,
    val category: Int = MediaSniffer.CAT_MEDIA
) {
    @Volatile var size: Long = -1
    @Volatile var mime: String? = null
    @Volatile var probed = false
    @Volatile var time: Long = System.currentTimeMillis()
    @Volatile var blocked = false
    @Volatile var method: String = "GET"
    @Volatile var reqHeaders: Map<String, String> = emptyMap()

    val name: String
        get() = try {
            Uri.decode(Uri.parse(url).lastPathSegment ?: Uri.parse(url).host ?: "kaynak")
        } catch (_: Exception) { "kaynak" }

    val isStream get() = kind == MediaSniffer.HLS || kind == MediaSniffer.DASH
    val isMedia get() = category == MediaSniffer.CAT_MEDIA
    val isCode get() = category == MediaSniffer.CAT_SCRIPT || category == MediaSniffer.CAT_STYLE ||
        category == MediaSniffer.CAT_DOC || kind == "JSON" || kind == "XML" || kind == "M3U8"
}

object MediaSniffer {
    const val VIDEO = "VIDEO"
    const val AUDIO = "SES"
    const val HLS = "HLS"
    const val DASH = "DASH"

    const val CAT_ALL = -1
    const val CAT_MEDIA = 0
    const val CAT_IMAGE = 1
    const val CAT_SCRIPT = 2
    const val CAT_STYLE = 3
    const val CAT_FONT = 4
    const val CAT_DOC = 5
    const val CAT_OTHER = 6
    const val CAT_BLOCKED = 7

    val CATEGORY_NAMES = linkedMapOf(
        CAT_ALL to "Tümü", CAT_MEDIA to "Medya", CAT_IMAGE to "Resim", CAT_SCRIPT to "JS",
        CAT_STYLE to "CSS", CAT_FONT to "Font", CAT_DOC to "Belge", CAT_OTHER to "Diğer", CAT_BLOCKED to "Engellenen"
    )

    private val VIDEO_EXT = listOf(".mp4", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".flv", ".avi")
    private val AUDIO_EXT = listOf(".mp3", ".m4a", ".aac", ".ogg", ".oga", ".opus", ".wav", ".flac")
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "avif", "bmp")
    private val FONT_EXT = setOf("woff", "woff2", "ttf", "otf", "eot")

    fun classify(u: Uri): String? {
        val host = u.host ?: return null
        if (host.endsWith("googlevideo.com")) return null
        val p = u.path?.lowercase() ?: return null
        return when {
            p.endsWith(".m3u8") -> HLS
            p.endsWith(".mpd") -> DASH
            VIDEO_EXT.any { p.endsWith(it) } -> VIDEO
            AUDIO_EXT.any { p.endsWith(it) } -> AUDIO
            else -> null
        }
    }

    /** Kaynağın etiketi ve kategorisi. */
    fun describe(u: Uri, accept: String, mainFrame: Boolean): Pair<String, Int> {
        classify(u)?.let { return Pair(it, CAT_MEDIA) }
        val last = (u.lastPathSegment ?: "").lowercase()
        val ext = last.substringAfterLast('.', "").takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } } ?: ""
        return when {
            mainFrame -> Pair("HTML", CAT_DOC)
            ext == "js" || ext == "mjs" -> Pair("JS", CAT_SCRIPT)
            ext == "css" -> Pair("CSS", CAT_STYLE)
            ext in FONT_EXT -> Pair(ext.uppercase(), CAT_FONT)
            ext in IMAGE_EXT -> Pair(ext.uppercase(), CAT_IMAGE)
            ext == "ts" || ext == "m4s" -> Pair("PARÇA", CAT_OTHER)
            ext == "json" -> Pair("JSON", CAT_OTHER)
            ext == "xml" -> Pair("XML", CAT_OTHER)
            ext == "html" || ext == "htm" || ext == "php" || ext == "aspx" -> Pair("HTML", CAT_DOC)
            ext == "pdf" -> Pair("PDF", CAT_DOC)
            accept.startsWith("image/") -> Pair("RESİM", CAT_IMAGE)
            accept.startsWith("text/css") -> Pair("CSS", CAT_STYLE)
            accept.startsWith("text/html") -> Pair("HTML", CAT_DOC)
            else -> Pair("URL", CAT_OTHER)
        }
    }

    fun iconOf(m: MediaItem): Int = when {
        m.blocked -> R.drawable.ic_block
        m.kind == AUDIO -> R.drawable.ic_music
        m.category == CAT_MEDIA -> R.drawable.ic_movie
        m.category == CAT_IMAGE -> R.drawable.ic_image
        m.category == CAT_SCRIPT || m.category == CAT_STYLE || m.kind == "JSON" -> R.drawable.ic_braces
        m.category == CAT_FONT -> R.drawable.ic_font
        m.category == CAT_DOC -> R.drawable.ic_doc
        else -> R.drawable.ic_link
    }

    fun mimeOf(item: MediaItem): String = item.mime?.substringBefore(';')?.trim()?.takeIf { it.contains('/') } ?: when (item.kind) {
        HLS -> "application/x-mpegURL"
        DASH -> "application/dash+xml"
        AUDIO -> "audio/*"
        else -> "video/*"
    }

    private val probePool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    /** Boyut ve tür bilgisini HEAD isteğiyle öğrenir. */
    fun probe(item: MediaItem, ua: String, onDone: () -> Unit) {
        if (item.probed || item.isStream) return
        item.probed = true
        probePool.execute {
            try {
                val c = Net.open(item.url)
                c.requestMethod = "HEAD"
                c.connectTimeout = 10000
                c.readTimeout = 10000
                headers(item.url, item.referer, ua).forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (c.responseCode in 200..299) {
                    item.size = c.contentLengthLong
                    item.mime = c.contentType
                }
                c.disconnect()
            } catch (_: Exception) {
            }
            main.post(onDone)
        }
    }

    fun headers(url: String, referer: String?, ua: String): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = ua
        if (!referer.isNullOrEmpty() && referer.startsWith("http")) h["Referer"] = referer
        try {
            CookieManager.getInstance().getCookie(url)?.let { h["Cookie"] = it }
        } catch (_: Exception) {
        }
        return h
    }

    fun formatSize(b: Long): String = when {
        b < 0 -> ""
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> String.format("%.0f KB", b / 1024.0)
        b < 1024L * 1024 * 1024 -> String.format("%.1f MB", b / 1048576.0)
        else -> String.format("%.2f GB", b / 1073741824.0)
    }
}

/** İndirilenler/Kalkan klasörüne dosya yazar. */
object Saver {
    fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(80).ifEmpty { "kalkan" }

    fun needsPermission(ctx: Context) = Build.VERSION.SDK_INT < 29 &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun save(ctx: Context, name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val r = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kalkan")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("Dosya oluşturulamadı")
            try {
                val out = r.openOutputStream(uri) ?: throw IOException("Dosya açılamadı")
                out.use(write)
            } catch (e: Exception) {
                r.delete(uri, null, null)
                throw e
            }
            v.clear()
            v.put(MediaStore.MediaColumns.IS_PENDING, 0)
            r.update(uri, v, null, null)
            return uri
        }
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Kalkan")
        dir.mkdirs()
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        f.outputStream().use(write)
        MediaScannerConnection.scanFile(ctx, arrayOf(f.path), arrayOf(mime), null)
        return Uri.fromFile(f)
    }
}

/** İlerleme bildirimleri. */
object Notif {
    private const val CHANNEL = "kalkan_downloads"

    const val CHANNEL_ID = CHANNEL

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "İndirmeler", NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    /** progress: 0..100 ilerleme, -1 belirsiz, 101 bitti */
    fun show(ctx: Context, id: Int, title: String, text: String, progress: Int, open: Uri? = null, mime: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(ctx)
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
        when {
            progress in 0..100 -> b.setProgress(100, progress, false).setOngoing(true)
            progress == -1 -> b.setProgress(0, 0, true).setOngoing(true)
            else -> b.setAutoCancel(true)
        }
        if (open != null && open.scheme == "content") {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(open, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentIntent(PendingIntent.getActivity(ctx, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        try {
            NotificationManagerCompat.from(ctx).notify(id, b.build())
        } catch (_: SecurityException) {
        }
    }
}

/** HLS (m3u8) akışını indirip tek dosyada birleştirir. AES-128 şifreli akışları çözer. */
object HlsDownloader {

    private val main = Handler(Looper.getMainLooper())
    @Volatile var active = 0
        private set

    private class Seg(val url: String, val keyUrl: String?, val iv: ByteArray?, val seq: Long)

    fun start(ctx: Context, url: String, referer: String?, ua: String, title: String, onDone: (String) -> Unit) {
        val app = ctx.applicationContext
        val id = 2000 + (System.currentTimeMillis() % 100000).toInt()
        val name = Saver.sanitize(title.substringBeforeLast(".m3u8"))
        active++
        Thread {
            var tmp: File? = null
            try {
                Notif.show(app, id, name, "Akış çözümleniyor…", -1)
                val h = MediaSniffer.headers(url, referer, ua)
                var plUrl = url
                var text = String(get(plUrl, h), Charsets.UTF_8)
                if (!text.trimStart().startsWith("#EXTM3U")) throw IOException("Geçerli bir HLS listesi değil")

                if (text.contains("#EXT-X-STREAM-INF")) {
                    val lines = text.lines()
                    var best: String? = null
                    var bestBw = -1L
                    for (i in lines.indices) {
                        val l = lines[i].trim()
                        if (!l.startsWith("#EXT-X-STREAM-INF")) continue
                        val bw = attrs(l)["BANDWIDTH"]?.toLongOrNull() ?: 0L
                        val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }?.trim()
                        if (next != null && bw > bestBw) { bestBw = bw; best = next }
                    }
                    plUrl = resolve(plUrl, best ?: throw IOException("Akışta kalite seçeneği bulunamadı"))
                    text = String(get(plUrl, h), Charsets.UTF_8)
                }

                val segs = ArrayList<Seg>()
                var seq = 0L
                var keyUrl: String? = null
                var keyIv: ByteArray? = null
                var mapUrl: String? = null
                for (raw in text.lines()) {
                    val l = raw.trim()
                    when {
                        l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                        l.startsWith("#EXT-X-KEY:") -> {
                            val a = attrs(l)
                            when (a["METHOD"] ?: "NONE") {
                                "NONE" -> { keyUrl = null; keyIv = null }
                                "AES-128" -> {
                                    keyUrl = resolve(plUrl, a["URI"] ?: throw IOException("Şifre anahtarı adresi yok"))
                                    keyIv = a["IV"]?.let { hex(it) }
                                }
                                else -> throw IOException("Bu video DRM korumalı, indirilemez")
                            }
                        }
                        l.startsWith("#EXT-X-MAP:") -> mapUrl = attrs(l)["URI"]?.let { resolve(plUrl, it) }
                        l.isNotEmpty() && !l.startsWith("#") -> {
                            segs.add(Seg(resolve(plUrl, l), keyUrl, keyIv, seq))
                            seq++
                        }
                    }
                }
                if (segs.isEmpty()) throw IOException("Akışta parça bulunamadı")

                val firstPath = segs[0].url.substringBefore('?').lowercase()
                val isMp4 = mapUrl != null || firstPath.endsWith(".m4s") || firstPath.endsWith(".mp4")
                val ext = if (isMp4) "mp4" else "ts"
                val mime = if (isMp4) "video/mp4" else "video/mp2t"
                val file = File(app.cacheDir, "hls_$id.$ext")
                tmp = file
                val keys = HashMap<String, ByteArray>()
                var lastPct = -1
                file.outputStream().buffered(1 shl 16).use { out ->
                    mapUrl?.let { out.write(retry { get(it, h) }) }
                    for ((i, s) in segs.withIndex()) {
                        var data = retry { get(s.url, h) }
                        val ku = s.keyUrl
                        if (ku != null) {
                            val key = keys.getOrPut(ku) { retry { get(ku, h) } }
                            val iv = s.iv ?: ByteBuffer.allocate(16).putLong(8, s.seq).array()
                            data = decrypt(data, key, iv)
                        }
                        out.write(data)
                        val pct = (i + 1) * 100 / segs.size
                        if (pct != lastPct) {
                            lastPct = pct
                            Notif.show(app, id, name, "%$pct · ${i + 1}/${segs.size} parça", pct)
                        }
                    }
                }
                Notif.show(app, id, name, "Kaydediliyor…", -1)
                val outName = "$name.$ext"
                val uri = Saver.save(app, outName, mime) { o -> file.inputStream().use { it.copyTo(o, 1 shl 16) } }
                Notif.show(app, id, outName, "İndirme tamamlandı · İndirilenler/Kalkan", 101, uri, mime)
                main.post { onDone("İndirildi: İndirilenler/Kalkan/$outName") }
            } catch (e: Exception) {
                Notif.show(app, id, name, "Başarısız: ${e.message}", 101)
                main.post { onDone("Akış indirilemedi: ${e.message}") }
            } finally {
                tmp?.delete()
                active--
            }
        }.start()
    }

    private fun get(url: String, headers: Map<String, String>): ByteArray {
        val c = Net.open(url)
        c.instanceFollowRedirects = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Sunucu hatası $code")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun <T> retry(block: () -> T): T {
        var last: Exception? = null
        repeat(3) {
            try {
                return block()
            } catch (e: Exception) {
                last = e
                try { Thread.sleep(800L * (it + 1)) } catch (_: InterruptedException) {}
            }
        }
        throw last ?: IOException("Bilinmeyen hata")
    }

    fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        val body = line.substringAfter(':')
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(body).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2].trim('"')
        }
        return out
    }

    fun resolve(base: String, rel: String): String = URL(URL(base), rel).toString()

    fun hex(s: String): ByteArray {
        var h = s.removePrefix("0x").removePrefix("0X")
        if (h.length < 32) h = h.padStart(32, '0')
        return ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return c.doFinal(data)
    }
}
