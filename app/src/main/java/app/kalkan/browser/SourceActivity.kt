package app.kalkan.browser

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Sayfa kaynak kodu görüntüleyici: renklendirme, satır numarası, kopyala/indir/paylaş. */
class SourceActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_UA = "ua"
        const val EXTRA_FETCH = "fetch"
        const val LIVE_FILE = "live.html"
        const val ORIG_FILE = "orig.html"

        fun dir(ctx: Context) = File(ctx.cacheDir, "src").apply { mkdirs() }
    }

    private lateinit var web: WebView
    private lateinit var loader: WebViewAssetLoader
    private var pageUrl = ""
    private var ua = ""
    private var file = LIVE_FILE
    private var wrap = false
    private var lastQuery = ""
    private var fetchOnly = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) download() else toast("Depolama izni verilmedi")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private val host get() = try { Uri.parse(pageUrl).host ?: "sayfa" } catch (_: Exception) { "sayfa" }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        ua = intent.getStringExtra(EXTRA_UA) ?: WebSettings.getDefaultUserAgent(this)
        title = "Kaynak kodu"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loader = WebViewAssetLoader.Builder()
            .setDomain(MainActivity.ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/src/", WebViewAssetLoader.InternalStoragePathHandler(this, dir(this)))
            .build()
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        setContentView(FrameLayout(this).apply { addView(web) })
        fetchOnly = intent.getBooleanExtra(EXTRA_FETCH, false)
        if (fetchOnly) {
            File(dir(this), ORIG_FILE).delete()
            fetchOriginal()
        } else render()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun render() {
        supportActionBar?.subtitle = when {
            fetchOnly -> Uri.parse(pageUrl).lastPathSegment ?: host
            file == LIVE_FILE -> "Canlı DOM · $host"
            else -> "Orijinal · $host"
        }
        web.loadUrl("https://${MainActivity.ASSET_HOST}/assets/source.html?f=$file&w=${if (wrap) 1 else 0}&t=${System.currentTimeMillis()}")
        invalidateOptionsMenu()
    }

    private fun currentFile() = File(dir(this), file)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Kopyala").setIcon(R.drawable.ic_copy).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 2, 1, "İndir").setIcon(R.drawable.ic_download).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 3, 2, "Paylaş")
        menu.add(0, 4, 3, "Kodda ara")
        menu.add(0, 5, 4, "Sonraki eşleşme")
        menu.add(0, 6, 5, if (wrap) "Satır kaydırmayı kapat" else "Satırları kaydır")
        if (!fetchOnly) menu.add(0, 7, 6, if (file == LIVE_FILE) "Orijinal kaynağı göster" else "Canlı DOM'u göster")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            1 -> copy()
            2 -> if (Saver.needsPermission(this)) permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else download()
            3 -> share()
            4 -> find()
            5 -> if (lastQuery.isNotEmpty()) web.findNext(true) else find()
            6 -> { wrap = !wrap; render() }
            7 -> toggleSource()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun copy() {
        val text = try { currentFile().readText() } catch (_: Exception) { "" }
        if (text.isEmpty()) { toast("Kaynak boş"); return }
        if (text.length > 800_000) {
            toast("Kod çok büyük (${text.length / 1000} bin karakter), kopyalamak yerine indir")
            return
        }
        try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kaynak kodu", text))
            toast("Kaynak kodu kopyalandı")
        } catch (e: Exception) {
            toast("Kopyalanamadı: kod çok büyük, indir")
        }
    }

    private fun download() {
        val src = currentFile()
        if (!src.exists()) { toast("Kaynak bulunamadı"); return }
        val name = if (fetchOnly) Saver.sanitize(Uri.parse(pageUrl).lastPathSegment ?: host)
            else Saver.sanitize(host) + (if (file == LIVE_FILE) "-dom" else "-kaynak") + ".html"
        Thread {
            val msg = try {
                Saver.save(this, name, if (name.endsWith(".html")) "text/html" else "text/plain") { o -> src.inputStream().use { it.copyTo(o) } }
                "Kaydedildi: İndirilenler/Kalkan/$name"
            } catch (e: Exception) {
                "Kaydedilemedi: ${e.message}"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun share() {
        val text = try { currentFile().readText() } catch (_: Exception) { "" }
        if (text.length > 200_000) {
            toast("Paylaşmak için çok büyük — İndir'i kullan")
            return
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                .putExtra(Intent.EXTRA_SUBJECT, "$host kaynak kodu"), "Kaynak kodunu paylaş"
        ))
    }

    private fun find() {
        val input = EditText(this).apply { setText(lastQuery); setSingleLine(); hint = "örn. <script" }
        val box = FrameLayout(this).apply {
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Kodda ara")
            .setView(box)
            .setPositiveButton("Ara") { _, _ ->
                lastQuery = input.text.toString()
                if (lastQuery.isNotEmpty()) {
                    web.setFindListener { active, num, done ->
                        if (done) toast(if (num == 0) "Bulunamadı" else "${active + 1} / $num")
                    }
                    web.findAllAsync(lastQuery)
                }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun toggleSource() {
        if (file == ORIG_FILE) { file = LIVE_FILE; render(); return }
        fetchOriginal()
    }

    private fun fetchOriginal() {
        val orig = File(dir(this), ORIG_FILE)
        toast("Kaynak indiriliyor…")
        Thread {
            val err = try {
                val c = URL(pageUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                c.setRequestProperty("User-Agent", ua)
                CookieManager.getInstance().getCookie(pageUrl)?.let { c.setRequestProperty("Cookie", it) }
                val cs = c.contentType?.substringAfter("charset=", "")?.substringBefore(';')?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: "UTF-8"
                val stream = if (c.responseCode >= 400) c.errorStream else c.inputStream
                val enc = try { java.nio.charset.Charset.forName(cs) } catch (_: Exception) { Charsets.UTF_8 }
                val text = stream?.use { String(it.readBytes(), enc) } ?: ""
                c.disconnect()
                orig.writeText(text)
                null
            } catch (e: Exception) {
                e.message ?: "bilinmeyen hata"
            }
            runOnUiThread {
                if (err != null) toast("Orijinal kaynak alınamadı: $err")
                else { file = ORIG_FILE; render() }
            }
        }.start()
    }
}
