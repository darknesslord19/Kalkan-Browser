package app.kalkan.browser

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLConnection
import java.util.concurrent.Executors

/** Uygulama içi indirme yöneticisi: çok parçalı, duraklat/devam ettir. */
object DownloadEngine {
    const val QUEUED = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val DONE = 3
    const val FAILED = 4

    private const val MAX_ACTIVE = 3
    private const val PARTS = 4
    private const val MIN_SPLIT = 4L * 1024 * 1024

    class Task(
        val id: Long, val url: String, var name: String, var mime: String?,
        val referer: String?, val ua: String, val cookie: String?
    ) {
        @Volatile var total = -1L
        @Volatile var status = QUEUED
        @Volatile var error: String? = null
        @Volatile var resumable = false
        val parts = ArrayList<LongArray>() // [başlangıç, bitiş(-1 = bilinmiyor), indirilen]
        @Volatile var uri: String? = null
        var created = System.currentTimeMillis()
        @Volatile var speed = 0L
        @Volatile var cancel = false

        val done: Long get() = synchronized(parts) { parts.sumOf { it[2] } }
        val percent: Int get() = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else -1
    }

    private lateinit var app: Context
    private val tasks = ArrayList<Task>()
    private val listeners = mutableListOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var reporterRunning = false

    fun init(ctx: Context) {
        app = ctx.applicationContext
        load()
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun notifyListeners() {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it() } }
    }

    fun list(): List<Task> = synchronized(tasks) { tasks.toList() }.asReversed()

    val activeCount: Int get() = synchronized(tasks) { tasks.count { it.status == RUNNING || it.status == QUEUED } }

    fun add(url: String, name: String, mime: String?, referer: String?, ua: String): Task {
        val cookie = try { CookieManager.getInstance().getCookie(url) } catch (_: Exception) { null }
        val t = Task(System.currentTimeMillis(), url, name, mime, referer, ua, cookie)
        synchronized(tasks) { tasks.add(t) }
        save()
        schedule()
        return t
    }

    fun pause(t: Task) {
        if (t.status == QUEUED) {
            t.status = PAUSED
            save(); notifyListeners()
        } else if (t.status == RUNNING) {
            t.cancel = true
        }
    }

    fun resume(t: Task) {
        if (t.status == PAUSED || t.status == FAILED) {
            t.status = QUEUED
            t.error = null
            save()
            schedule()
        }
    }

    fun remove(t: Task, deleteFile: Boolean) {
        t.cancel = true
        synchronized(tasks) { tasks.remove(t) }
        io.execute {
            Thread.sleep(300)
            tempFile(t).delete()
            if (deleteFile) t.uri?.let {
                try {
                    val u = Uri.parse(it)
                    if (u.scheme == "content") app.contentResolver.delete(u, null, null)
                    else u.path?.let { p -> File(p).delete() }
                } catch (_: Exception) {
                }
            }
        }
        save(); notifyListeners()
    }

    fun clearFinished() {
        synchronized(tasks) { tasks.removeAll { it.status == DONE } }
        save(); notifyListeners()
    }

    private fun schedule() {
        synchronized(tasks) {
            var active = tasks.count { it.status == RUNNING }
            for (t in tasks) {
                if (active >= MAX_ACTIVE) break
                if (t.status == QUEUED) {
                    t.status = RUNNING
                    t.cancel = false
                    active++
                    Thread({ run(t) }, "kalkan-dl-${t.id}").start()
                }
            }
        }
        DownloadService.sync(app)
        startReporter()
        notifyListeners()
    }

    private fun startReporter() {
        if (reporterRunning) return
        reporterRunning = true
        Thread({
            val last = HashMap<Long, Long>()
            while (true) {
                Thread.sleep(800)
                val running = synchronized(tasks) { tasks.filter { it.status == RUNNING } }
                for (t in running) {
                    val d = t.done
                    val prev = last[t.id] ?: d
                    t.speed = ((d - prev) * 1000L / 800L).coerceAtLeast(0)
                    last[t.id] = d
                }
                notifyListeners()
                DownloadService.sync(app)
                if (running.isEmpty() && activeCount == 0) break
            }
            reporterRunning = false
        }, "kalkan-dl-report").start()
    }

    fun tempFile(t: Task): File {
        val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "dl")
        dir.mkdirs()
        return File(dir, "${t.id}.part")
    }

    private fun connect(t: Task, range: String?) = Net.open(t.url).apply {
        instanceFollowRedirects = true
        connectTimeout = 20000
        readTimeout = 30000
        setRequestProperty("User-Agent", t.ua)
        if (!t.referer.isNullOrEmpty() && t.referer.startsWith("http")) setRequestProperty("Referer", t.referer)
        if (!t.cookie.isNullOrEmpty()) setRequestProperty("Cookie", t.cookie)
        setRequestProperty("Accept-Encoding", "identity")
        if (range != null) setRequestProperty("Range", range)
    }

    private fun prepare(t: Task) {
        val c = connect(t, "bytes=0-")
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Sunucu hatası $code")
            val type = c.contentType
            if (t.mime.isNullOrEmpty() && !type.isNullOrEmpty()) t.mime = type.substringBefore(';').trim()
            val cd = c.getHeaderField("Content-Disposition")
            if (!cd.isNullOrEmpty()) {
                t.name = Saver.sanitize(URLUtil.guessFileName(t.url, cd, t.mime))
            }
            if (code == 206) {
                t.resumable = true
                t.total = c.getHeaderField("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1
            } else {
                t.total = c.contentLengthLong
                t.resumable = c.getHeaderField("Accept-Ranges")?.contains("bytes", true) == true && t.total > 0
            }
        } finally {
            c.disconnect()
        }
        synchronized(t.parts) {
            t.parts.clear()
            if (t.resumable && t.total >= MIN_SPLIT) {
                val size = t.total / PARTS
                for (i in 0 until PARTS) {
                    val start = i * size
                    val end = if (i == PARTS - 1) t.total - 1 else start + size - 1
                    t.parts.add(longArrayOf(start, end, 0))
                }
            } else {
                t.parts.add(longArrayOf(0, if (t.total > 0) t.total - 1 else -1, 0))
            }
        }
        tempFile(t).delete()
        save()
    }

    private fun run(t: Task) {
        try {
            if (t.parts.isEmpty() || !t.resumable) prepare(t)
            val tmp = tempFile(t)
            if (t.total > 0) RandomAccessFile(tmp, "rw").use { if (it.length() < t.total) it.setLength(t.total) }
            var failure: Exception? = null
            val threads = ArrayList<Thread>()
            val parts = synchronized(t.parts) { t.parts.toList() }
            for (p in parts) {
                if (p[1] >= 0 && p[0] + p[2] > p[1]) continue
                val th = Thread {
                    try {
                        downloadPart(t, p, tmp)
                    } catch (e: Exception) {
                        if (!t.cancel) {
                            failure = e
                            t.cancel = true
                        }
                    }
                }
                threads.add(th)
                th.start()
            }
            threads.forEach { it.join() }

            when {
                failure != null -> {
                    t.status = FAILED
                    t.error = failure?.message ?: "Bağlantı hatası"
                    if (!t.resumable) synchronized(t.parts) { t.parts.clear() }
                    Notif.show(app, notifId(t), t.name, "İndirme başarısız: ${t.error}", 101)
                }
                t.cancel -> {
                    t.status = PAUSED
                    if (!t.resumable) synchronized(t.parts) { t.parts.clear() }
                }
                else -> {
                    if (t.total > 0 && t.done < t.total) throw IOException("Dosya eksik indi")
                    val mime = t.mime?.takeIf { it.contains('/') }
                        ?: URLConnection.guessContentTypeFromName(t.name) ?: "application/octet-stream"
                    val uri = Saver.save(app, t.name, mime) { o ->
                        RandomAccessFile(tmp, "r").use { raf ->
                            val limit = if (t.total > 0) t.total else raf.length()
                            val buf = ByteArray(1 shl 16)
                            var left = limit
                            while (left > 0) {
                                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n <= 0) break
                                o.write(buf, 0, n)
                                left -= n
                            }
                        }
                    }
                    tmp.delete()
                    t.uri = uri.toString()
                    t.status = DONE
                    if (t.total <= 0) t.total = t.done
                    Notif.show(app, notifId(t), t.name, "İndirme tamamlandı · İndirilenler/Kalkan", 101, uri, mime)
                }
            }
        } catch (e: Exception) {
            t.status = FAILED
            t.error = e.message ?: "Hata"
            Notif.show(app, notifId(t), t.name, "İndirme başarısız: ${t.error}", 101)
        }
        t.speed = 0
        save()
        schedule()
    }

    private fun downloadPart(t: Task, p: LongArray, tmp: File) {
        val pos = p[0] + p[2]
        val range = if (t.resumable) (if (p[1] >= 0) "bytes=$pos-${p[1]}" else "bytes=$pos-") else null
        val c = connect(t, range)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("Sunucu hatası $code")
            if (t.resumable && code == 200 && pos > 0) throw IOException("Sunucu devam ettirmeyi desteklemiyor")
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.seek(pos)
                c.inputStream.use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (!t.cancel) {
                        var n = input.read(buf)
                        if (n <= 0) break
                        if (p[1] >= 0) {
                            val remain = p[1] - (p[0] + p[2]) + 1
                            if (remain <= 0) break
                            if (n > remain) n = remain.toInt()
                        }
                        raf.write(buf, 0, n)
                        synchronized(t.parts) { p[2] += n }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun notifId(t: Task) = 5000 + (t.id % 100000).toInt()

    fun summary(): String {
        val running = synchronized(tasks) { tasks.filter { it.status == RUNNING } }
        if (running.isEmpty()) return "Sırada bekliyor"
        val speed = running.sumOf { it.speed }
        val known = running.filter { it.total > 0 }
        val pct = if (known.isNotEmpty()) (known.sumOf { it.done } * 100 / known.sumOf { it.total }).toInt() else -1
        return "${running.size} dosya" + (if (pct >= 0) " · %$pct" else "") + " · ${MediaSniffer.formatSize(speed)}/sn"
    }

    // ---------- Kalıcılık ----------
    private fun save() {
        val arr = JSONArray()
        synchronized(tasks) {
            for (t in tasks) {
                val parts = JSONArray()
                synchronized(t.parts) { t.parts.forEach { parts.put(JSONArray().put(it[0]).put(it[1]).put(it[2])) } }
                arr.put(
                    JSONObject().put("id", t.id).put("url", t.url).put("name", t.name).put("mime", t.mime ?: "")
                        .put("ref", t.referer ?: "").put("ua", t.ua).put("cookie", t.cookie ?: "")
                        .put("total", t.total).put("status", t.status).put("error", t.error ?: "")
                        .put("res", t.resumable).put("parts", parts).put("uri", t.uri ?: "").put("created", t.created)
                )
            }
        }
        val text = arr.toString()
        io.execute {
            try {
                val f = File(app.filesDir, "downloads.json.tmp")
                f.writeText(text)
                f.renameTo(File(app.filesDir, "downloads.json"))
            } catch (_: Exception) {
            }
        }
    }

    private fun load() {
        try {
            val f = File(app.filesDir, "downloads.json")
            if (!f.exists()) return
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = Task(
                    o.getLong("id"), o.getString("url"), o.getString("name"),
                    o.optString("mime").ifEmpty { null }, o.optString("ref").ifEmpty { null },
                    o.optString("ua"), o.optString("cookie").ifEmpty { null }
                )
                t.total = o.optLong("total", -1)
                t.status = o.optInt("status", PAUSED)
                if (t.status == RUNNING || t.status == QUEUED) t.status = PAUSED
                t.error = o.optString("error").ifEmpty { null }
                t.resumable = o.optBoolean("res")
                t.uri = o.optString("uri").ifEmpty { null }
                t.created = o.optLong("created", t.id)
                o.optJSONArray("parts")?.let { pa ->
                    for (j in 0 until pa.length()) {
                        val p = pa.getJSONArray(j)
                        t.parts.add(longArrayOf(p.getLong(0), p.getLong(1), p.getLong(2)))
                    }
                }
                if (!t.resumable && t.status != DONE) t.parts.clear()
                tasks.add(t)
            }
        } catch (_: Exception) {
        }
    }
}

/** İndirmeler sürerken uygulamanın kapatılmasını önleyen ön plan servisi. */
class DownloadService : Service() {

    companion object {
        private const val ID = 4242
        @Volatile private var running = false

        fun sync(ctx: Context) {
            val need = DownloadEngine.activeCount > 0
            if (need && !running) {
                try {
                    ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
                    running = true
                } catch (_: Exception) {
                }
            } else if (need && running) {
                update(ctx)
            } else if (!need && running) {
                ctx.stopService(Intent(ctx, DownloadService::class.java))
                running = false
            }
        }

        private fun build(ctx: Context): Notification {
            Notif.ensureChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, 1, Intent(ctx, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(ctx, Notif.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle("Kalkan indiriyor")
                .setContentText(DownloadEngine.summary())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }

        private fun update(ctx: Context) {
            try {
                androidx.core.app.NotificationManagerCompat.from(ctx).notify(ID, build(ctx))
            } catch (_: SecurityException) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, ID, build(this),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
        } catch (_: Exception) {
        }
        running = true
        if (DownloadEngine.activeCount == 0) {
            stopSelf()
            running = false
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
