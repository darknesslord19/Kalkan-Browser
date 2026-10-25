package app.kalkan.browser

import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.webkit.ProfileStore
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private var consoleRefresh: (() -> Unit)? = null

private const val RED = "#E5484D"
private const val GREEN = "#30A46C"
private const val BLUE = "#3E8EF7"
private const val AMBER = "#E2A336"

// =====================================================================
// Ortak yardımcılar
// =====================================================================

internal fun MainActivity.pageTab(): MainActivity.Tab? {
    val t = current
    if (t == null || !t.url.startsWith("http") || isHome(t.url)) {
        toast("Önce bir site aç")
        return null
    }
    return t
}

private fun MainActivity.sheet(fullHeight: Float = 0.85f): BottomSheetDialog {
    val d = BottomSheetDialog(this)
    d.behavior.peekHeight = (resources.displayMetrics.heightPixels * fullHeight).toInt()
    d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
    d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    return d
}

private fun MainActivity.sheetHeader(title: String, subtitle: String? = null): LinearLayout {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(14), dp(16), dp(8))
    }
    col.addView(TextView(this).apply {
        text = title
        textSize = 22f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor())
    })
    if (!subtitle.isNullOrEmpty()) col.addView(TextView(this).apply {
        text = subtitle
        textSize = 13f
        setTextColor(mutedColor())
        maxLines = 2
    })
    return col
}

private fun MainActivity.iconButton(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
    setImageResource(icon)
    setBackgroundResource(selectableBg())
    contentDescription = desc
    imageTintList = ColorStateList.valueOf(textColor())
    setOnClickListener { onClick() }
}

private fun MainActivity.textBtn(label: String, onClick: () -> Unit) =
    MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
        text = label
        setOnClickListener { onClick() }
    }

private fun MainActivity.field(hint: String, value: String = "", multi: Boolean = false, mono: Boolean = false) = EditText(this).apply {
    this.hint = hint
    setText(value)
    textSize = if (mono) 13f else 15f
    if (mono) typeface = Typeface.MONOSPACE
    if (multi) {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        minLines = 6
        gravity = Gravity.TOP or Gravity.START
        isSingleLine = false
    } else {
        setSingleLine()
    }
}

private fun MainActivity.formBox(vararg views: View): ScrollView {
    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(22), dp(8), dp(22), 0)
    }
    views.forEach { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }) }
    return ScrollView(this).apply { addView(col) }
}

private fun MainActivity.small(text: String, color: Int = mutedColor()) = TextView(this).apply {
    this.text = text
    textSize = 12.5f
    setTextColor(color)
}

private fun MainActivity.simpleList(rows: () -> Int, bind: (Int, TextView, TextView, View) -> Unit): RecyclerView {
    val rv = RecyclerView(this)
    rv.layoutManager = LinearLayoutManager(this)
    rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = rows()
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val col = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_tile)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            }
            val t1 = TextView(parent.context).apply { textSize = 14.5f; setTextColor(textColor()); maxLines = 2 }
            val t2 = TextView(parent.context).apply { textSize = 12.5f; setTextColor(mutedColor()); maxLines = 3 }
            col.addView(t1); col.addView(t2)
            col.tag = Pair(t1, t2)
            return object : RecyclerView.ViewHolder(col) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            @Suppress("UNCHECKED_CAST")
            val p = holder.itemView.tag as Pair<TextView, TextView>
            bind(position, p.first, p.second, holder.itemView)
        }
    }
    return rv
}

// =====================================================================
// Araçlar penceresi
// =====================================================================

internal fun MainActivity.showTools() {
    val d = sheet(0.9f)
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), 0, dp(10), dp(24))
    }
    val t = current
    val host = t?.pageHost?.takeIf { it.isNotEmpty() && it != MainActivity.ASSET_HOST }
    box.addView(sheetHeader("Araçlar", host ?: "Sayfa araçları için önce bir site aç"))

    val refreshers = ArrayList<() -> Unit>()

    fun section(title: String): GridLayout {
        box.addView(TextView(this).apply {
            text = title.uppercase(Locale("tr"))
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(mutedColor())
            setPadding(dp(12), dp(14), 0, dp(2))
        })
        val g = GridLayout(this).apply { columnCount = 4 }
        box.addView(g)
        return g
    }

    fun tile(
        grid: GridLayout, icon: Int, label: String,
        sub: (() -> String?)? = null, on: (() -> Boolean)? = null, badge: (() -> Int)? = null,
        keepOpen: Boolean = false, action: () -> Unit
    ) {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(2), dp(10), dp(2), dp(8))
            setBackgroundResource(selectableBg())
            isClickable = true
        }
        val iconBox = FrameLayout(this)
        val iv = ImageView(this).apply { setImageResource(icon) }
        iconBox.addView(iv, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        val badgeView = TextView(this).apply {
            textSize = 9.5f
            setTextColor(Color.parseColor("#1A1205"))
            setBackgroundResource(R.drawable.bg_badge)
            gravity = Gravity.CENTER
            minWidth = dp(15)
            setPadding(dp(3), 0, dp(3), 0)
            visibility = View.GONE
        }
        iconBox.addView(badgeView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(15), Gravity.END or Gravity.TOP))
        v.addView(iconBox, LinearLayout.LayoutParams(dp(40), dp(30)))
        v.addView(TextView(this).apply {
            text = label; textSize = 11.5f; gravity = Gravity.CENTER; maxLines = 2
            setTextColor(textColor()); setPadding(0, dp(5), 0, 0)
        })
        val subView = TextView(this).apply {
            textSize = 10.5f; gravity = Gravity.CENTER; maxLines = 1
            setTextColor(mutedColor())
            visibility = View.GONE
        }
        v.addView(subView)
        val refresh = {
            val isOn = on?.invoke()
            iv.imageTintList = ColorStateList.valueOf(
                if (isOn == true) ContextCompat.getColor(this, R.color.brand) else textColor()
            )
            val s = sub?.invoke() ?: isOn?.let { if (it) "Açık" else "Kapalı" }
            subView.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            subView.text = s
            val b = badge?.invoke() ?: 0
            badgeView.visibility = if (b > 0) View.VISIBLE else View.GONE
            badgeView.text = if (b > 99) "99+" else b.toString()
        }
        refresh()
        refreshers.add(refresh)
        v.setOnClickListener {
            if (keepOpen) {
                action()
                refreshers.forEach { it() }
            } else {
                d.dismiss()
                action()
            }
        }
        grid.addView(v, GridLayout.LayoutParams(
            GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)
        ).apply { width = 0 })
    }

    fun toggle(key: String, def: Boolean, msgOn: String, msgOff: String): () -> Unit = {
        val now = !Store.bool(key, def)
        Store.prefs.edit().putBoolean(key, now).apply()
        toast(if (now) msgOn else msgOff)
    }

    val page = section("Sayfa")
    tile(page, R.drawable.ic_hide, "Öğe gizle") { startPicker("zap") }
    tile(page, R.drawable.ic_touch, "Atlamayı öğret") { startPicker("teach") }
    tile(page, R.drawable.ic_reader, "Okuma modu") { toggleReader() }
    tile(page, R.drawable.ic_camera, "Ekran görüntüsü") { screenshot() }
    tile(page, R.drawable.ic_qr_scan, "QR tara") { scanQr() }
    tile(page, R.drawable.ic_qr, "QR oluştur") { showQr() }
    tile(page, R.drawable.ic_rule, "Site kuralları",
        badge = { Store.allRules("hide").size + Store.allRules("skip").size }) { showRules() }
    tile(page, R.drawable.ic_translate, "Çevir") { pageTab()?.let { translate(it.url) } }

    val dev = section("Geliştirici")
    tile(dev, R.drawable.ic_terminal, "Konsol",
        badge = { current?.let { c -> synchronized(c.console) { c.console.count { it.level == 'E' } } } ?: 0 }) { showConsole() }
    tile(dev, R.drawable.ic_network, "Ağ kaydı") { showNetwork() }
    tile(dev, R.drawable.ic_cookie, "Çerez & depolama") { showStorage() }
    tile(dev, R.drawable.ic_extension, "Betikler",
        sub = { Store.scriptList().count { it.on }.let { if (it > 0) "$it aktif" else null } }) { showScripts() }
    tile(dev, R.drawable.ic_devices, "User-Agent", sub = { uaName(Store.str("ua_mode", "default")) }) { chooseUA() }
    tile(dev, R.drawable.ic_code, "Kaynak kodu") { pageTab()?.let { viewSource(it) } }

    val media = section("Medya")
    tile(media, R.drawable.ic_speed, "Video hızı", sub = { current?.rate?.let { if (it == 1f) "Normal" else "${fmtRate(it)}x" } }) { chooseSpeed() }
    tile(media, R.drawable.ic_pip, "Resim içinde resim") { enterPip() }
    tile(media, R.drawable.ic_download, "İndirme yöneticisi", badge = { DownloadEngine.activeCount }) { openDownloads() }
    tile(media, R.drawable.ic_movie, "Media Sniffer", badge = { current?.mediaCount ?: 0 }) { showMedia() }
    tile(media, R.drawable.ic_fullscreen, "Otomatik PiP", on = { Store.bool("auto_pip", true) }, keepOpen = true,
        action = toggle("auto_pip", true, "Tam ekran videodan çıkınca küçük pencerede oynar", "Otomatik PiP kapalı"))
    tile(media, R.drawable.ic_download, "Dahili indirici", on = { Store.bool("internal_dl", true) }, keepOpen = true,
        action = toggle("internal_dl", true, "Dosyalar Kalkan indirme yöneticisiyle iner", "Dosyalar Android indiricisiyle iner"))

    val priv = section("Gizlilik")
    tile(priv, R.drawable.ic_globe, "Proxy / Tor", on = { Net.active }, sub = { if (Net.active) Net.mode.uppercase() else "Kapalı" }) { chooseProxy() }
    tile(priv, R.drawable.ic_lock, "Uygulama kilidi", on = { lockMode() != "off" }, sub = { lockName(lockMode()) }) { chooseLock() }
    tile(priv, R.drawable.ic_delete, "Çıkışta sil", on = { Store.bool("clear_on_exit", false) }, keepOpen = true,
        action = toggle("clear_on_exit", false, "Çıkış'a basınca geçmiş, çerezler ve sekmeler silinir", "Çıkışta silme kapalı"))
    tile(priv, R.drawable.ic_sweep, "Gizlileri kapat", badge = { tabs.count { it.incognito } }) {
        tabs.filter { it.incognito }.forEach { closeTab(it) }
        toast("Gizli sekmeler kapatıldı")
    }
    tile(priv, R.drawable.ic_sweep, "Verileri temizle") { confirmClearAll() }

    val other = section("Diğer")
    tile(other, R.drawable.ic_backup, "Yedekle") { exportBackup() }
    tile(other, R.drawable.ic_restore, "Geri yükle") {
        internalNav = true
        backupPicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
    }
    tile(other, R.drawable.ic_fullscreen, "Kaydırınca gizle", on = { Store.bool("hide_bars", false) }, keepOpen = true,
        action = toggle("hide_bars", false, "Aşağı kaydırınca çubuklar gizlenir", "Çubuklar hep görünür"))
    tile(other, R.drawable.ic_swap, "Kaydırarak sekme", on = { Store.bool("swipe_tabs", true) }, keepOpen = true,
        action = toggle("swipe_tabs", true, "Alt çubukta sağa/sola kaydırarak sekme değiştir", "Kaydırarak sekme değiştirme kapalı"))

    d.setContentView(NestedScrollView(this).apply { addView(box) })
    d.show()
}

private fun fmtRate(r: Float): String = if (r % 1f == 0f) r.toInt().toString() else r.toString()

// =====================================================================
// Öğe gizle & atlamayı öğret
// =====================================================================

internal fun MainActivity.startPicker(mode: String) {
    val t = pageTab() ?: return
    t.webView.evaluateJavascript(Scripts.PICKER.replace("__MODE__", mode), null)
    toast(if (mode == "zap") "Gizlemek istediğin öğeye dokun, sonra ✓" else "Otomatik basılacak düğmeye dokun, sonra ✓")
}

internal fun MainActivity.onPicked(tab: MainActivity.Tab, mode: String, selector: String, label: String) {
    val host = tab.pageHost.removePrefix("www.")
    if (host.isEmpty()) return
    if (mode == "zap") {
        Store.addRule("hide", host, selector)
        Snackbar.make(root, "Öğe gizlendi · $host", Snackbar.LENGTH_LONG)
            .setAction("Geri al") {
                Store.removeRule("hide", host, selector)
                tab.webView.reload()
            }.show()
    } else {
        Store.addRule("skip", host, selector)
        toast("Öğrenildi: “${label.ifBlank { "düğme" }}” bu sitede otomatik basılacak")
    }
}

internal fun MainActivity.showRules() {
    val d = sheet()
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(sheetHeader("Site kuralları", "Gizlediğin öğeler ve otomatik basılan düğmeler. Silmek için dokun."))
    val here = current?.pageHost?.removePrefix("www.") ?: ""
    var items: List<Triple<String, String, String>> = emptyList()
    val empty = small("Henüz kural yok.\nAraçlar → Öğe gizle veya Atlamayı öğret ile ekleyebilirsin.").apply {
        gravity = Gravity.CENTER; setPadding(dp(24), dp(40), dp(24), dp(40))
    }
    root.addView(empty)
    lateinit var rv: RecyclerView
    fun load() {
        items = (Store.allRules("hide").map { Triple("hide", it.first, it.second) } +
            Store.allRules("skip").map { Triple("skip", it.first, it.second) })
            .sortedWith(compareBy({ if (here.isNotEmpty() && (here == it.second || here.endsWith("." + it.second))) 0 else 1 }, { it.second }))
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rv.adapter?.notifyDataSetChanged()
    }
    rv = simpleList({ items.size }) { i, t1, t2, row ->
        val (kind, site, sel) = items[i]
        t1.text = (if (kind == "hide") "Gizle · " else "Otomatik bas · ") + site
        t1.setTextColor(if (kind == "hide") Color.parseColor(RED) else ContextCompat.getColor(this, R.color.brand_dark))
        t2.text = sel
        t2.typeface = Typeface.MONOSPACE
        row.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Kural silinsin mi?")
                .setMessage("$site\n\n$sel")
                .setPositiveButton("Sil") { _, _ ->
                    Store.removeRule(kind, site, sel)
                    load()
                    current?.takeIf { it.pageHost.removePrefix("www.").endsWith(site) }?.webView?.reload()
                }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
    }
    rv.setPadding(dp(14), dp(4), dp(14), dp(20))
    rv.clipToPadding = false
    root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.65).toInt()))
    load()
    d.setContentView(root)
    d.show()
}

// =====================================================================
// Okuma modu, ekran görüntüsü, QR, çeviri
// =====================================================================

internal fun MainActivity.toggleReader() {
    val t = pageTab() ?: return
    t.webView.evaluateJavascript(Scripts.READER) { r ->
        when (r?.trim('"')) {
            "none" -> toast("Bu sayfada okunacak metin bulunamadı")
            "off" -> toast("Okuma modu kapatıldı")
        }
    }
}

internal fun MainActivity.screenshot() {
    val t = pageTab() ?: return
    MaterialAlertDialogBuilder(this)
        .setTitle("Ekran görüntüsü")
        .setItems(arrayOf("Görünen alan", "Tüm sayfa (uzun)")) { _, w ->
            ensureStorage { capture(t, w == 1) }
        }.show()
}

private fun MainActivity.capture(t: MainActivity.Tab, full: Boolean) {
    val wv = t.webView
    val w = wv.width
    if (w <= 0 || wv.height <= 0) { toast("Sayfa henüz çizilmedi"); return }
    var h = wv.height
    var capped = false
    if (full) {
        @Suppress("DEPRECATION")
        val total = (wv.contentHeight * wv.scale).toInt()
        val maxH = minOf(20000, 90_000_000 / (w * 4))
        capped = total > maxH
        h = total.coerceAtMost(maxH).coerceAtLeast(wv.height)
    }
    val bmp = try {
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    } catch (e: OutOfMemoryError) {
        toast("Sayfa çok uzun, bellek yetmedi — görünen alanı dene"); return
    }
    val c = Canvas(bmp)
    c.drawColor(Color.WHITE)
    if (!full) c.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
    try { wv.draw(c) } catch (e: Exception) { toast("Çizilemedi: ${e.message}"); bmp.recycle(); return }
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val name = Saver.sanitize(t.pageHost.removePrefix("www.")) + "-$stamp" + if (full) ".jpg" else ".png"
    toast("Kaydediliyor…")
    Thread {
        val msg = try {
            Saver.save(this, name, if (full) "image/jpeg" else "image/png") { o ->
                bmp.compress(if (full) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 90, o)
            }
            "Kaydedildi: İndirilenler/Kalkan/$name" + if (capped) " (sayfa çok uzun olduğu için kırpıldı)" else ""
        } catch (e: Exception) {
            "Kaydedilemedi: ${e.message}"
        } finally {
            bmp.recycle()
        }
        runOnUiThread { toast(msg) }
    }.start()
}

internal fun MainActivity.scanQr() {
    internalNav = true
    qrLauncher.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("QR kodu çerçevenin içine getir")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
    )
}

internal fun MainActivity.onQrResult(text: String) {
    val looksUrl = Regex("^(https?://|www\\.)", RegexOption.IGNORE_CASE).containsMatchIn(text.trim()) ||
        (!text.contains(' ') && text.contains('.') && android.util.Patterns.WEB_URL.matcher(text.trim()).matches())
    val b = MaterialAlertDialogBuilder(this)
        .setTitle("QR kod")
        .setMessage(text)
        .setNeutralButton("Kopyala") { _, _ -> copy(text) }
    if (looksUrl) b.setPositiveButton("Aç") { _, _ -> newTab(resolve(text.trim()), current?.incognito ?: false) }
    else b.setPositiveButton("Ara") { _, _ -> newTab(Store.searchUrl(text), current?.incognito ?: false) }
    b.setNegativeButton("Kapat", null).show()
}

internal fun MainActivity.showQr() {
    val t = pageTab() ?: return
    val bmp = try {
        BarcodeEncoder().encodeBitmap(t.url, BarcodeFormat.QR_CODE, 720, 720)
    } catch (e: Exception) {
        toast("QR oluşturulamadı: ${e.message}"); return
    }
    val iv = ImageView(this).apply {
        setImageBitmap(bmp)
        setBackgroundColor(Color.WHITE)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        adjustViewBounds = true
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), 0)
        addView(iv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(small(displayUrl(t.url)).apply { setPadding(0, dp(10), 0, 0); maxLines = 2 })
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(t.title.ifBlank { t.pageHost })
        .setView(box)
        .setPositiveButton("Paylaş") { _, _ -> share(t.url, t.title) }
        .setNegativeButton("Kapat", null)
        .show()
}

// =====================================================================
// Konsol
// =====================================================================

internal fun MainActivity.addConsole(tab: MainActivity.Tab, level: Char, text: String, src: String) {
    synchronized(tab.console) {
        tab.console.addLast(MainActivity.ConsoleLine(level, text.take(4000), src))
        while (tab.console.size > 500) tab.console.removeFirst()
    }
    if (tab === current) consoleRefresh?.let { r -> ui.post(r) }
}

internal fun MainActivity.showConsole() {
    val t = current ?: return
    val d = sheet(0.9f)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(sheetHeader("Konsol", t.pageHost.ifEmpty { "Kalkan" }), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    var lines: List<MainActivity.ConsoleLine> = emptyList()
    head.addView(iconButton(R.drawable.ic_copy, "Tümünü kopyala") {
        copy(lines.joinToString("\n") { "[${it.level}] ${it.text}" + if (it.src.isNotEmpty()) "  (${it.src})" else "" })
    }, LinearLayout.LayoutParams(dp(46), dp(46)))
    head.addView(iconButton(R.drawable.ic_delete, "Temizle") {
        synchronized(t.console) { t.console.clear() }
        consoleRefresh?.invoke()
    }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(8) })
    root.addView(head)

    val empty = small("Henüz mesaj yok. Sayfanın console.log çıktıları ve JavaScript hataları burada görünür.\nAşağıya kod yazıp çalıştırabilirsin.").apply {
        setPadding(dp(22), dp(16), dp(22), dp(16))
    }
    root.addView(empty)
    val rv = RecyclerView(this)
    rv.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = lines.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context).apply {
                typeface = Typeface.MONOSPACE
                textSize = 12f
                setPadding(dp(14), dp(5), dp(14), dp(5))
                setTextIsSelectable(true)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val l = lines[position]
            val tv = holder.itemView as TextView
            val prefix = when (l.level) { 'C' -> "› "; 'R' -> "← "; 'E' -> "✕ "; 'W' -> "⚠ "; else -> "" }
            tv.text = prefix + l.text + (if (l.src.isNotEmpty()) "\n   ${l.src} · ${fmt.format(Date(l.time))}" else "")
            val (fg, bg) = when (l.level) {
                'E' -> Pair(Color.parseColor(RED), Color.parseColor("#1AE5484D"))
                'W' -> Pair(Color.parseColor(AMBER), Color.parseColor("#1AE2A336"))
                'R' -> Pair(ContextCompat.getColor(this@showConsole, R.color.brand_dark), Color.TRANSPARENT)
                'C' -> Pair(mutedColor(), Color.TRANSPARENT)
                else -> Pair(textColor(), Color.TRANSPARENT)
            }
            tv.setTextColor(fg)
            tv.setBackgroundColor(bg)
        }
    }
    root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55).toInt()))

    val inputRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(6), dp(6), dp(12))
    }
    val input = EditText(this).apply {
        hint = "JavaScript yaz (ör. document.title)"
        typeface = Typeface.MONOSPACE
        textSize = 13f
        maxLines = 4
        setBackgroundResource(R.drawable.bg_url)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setTextColor(textColor())
        setHintTextColor(mutedColor())
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    inputRow.addView(iconButton(R.drawable.ic_play, "Çalıştır") {
        val code = input.text.toString().trim()
        if (code.isEmpty()) return@iconButton
        val tab = current ?: return@iconButton
        addConsole(tab, 'C', code, "")
        input.setText("")
        tab.webView.evaluateJavascript("(function(){try{return (0,eval)(${JSONObject.quote(code)});}catch(e){return 'Hata: '+e;}})()") { r ->
            addConsole(tab, 'R', prettyJs(r), "")
        }
    }, LinearLayout.LayoutParams(dp(48), dp(48)))
    root.addView(inputRow)

    consoleRefresh = {
        lines = synchronized(t.console) { t.console.toList() }
        empty.visibility = if (lines.isEmpty()) View.VISIBLE else View.GONE
        rv.adapter?.notifyDataSetChanged()
        if (lines.isNotEmpty()) rv.scrollToPosition(lines.size - 1)
    }
    consoleRefresh?.invoke()
    d.setOnDismissListener { consoleRefresh = null }
    d.setContentView(root)
    d.show()
}

private fun prettyJs(r: String?): String {
    if (r == null || r == "null") return "undefined"
    return try {
        when (val v = org.json.JSONTokener(r).nextValue()) {
            is String -> v
            is JSONObject -> v.toString(2)
            is JSONArray -> v.toString(2)
            else -> v.toString()
        }
    } catch (_: Exception) { r }
}

// =====================================================================
// Ağ kaydı
// =====================================================================

private class NetRow(
    val url: String, val type: String, val dur: Double, val status: Int,
    val transfer: Long, val body: Long, val start: Double, val proto: String, val blocked: Boolean = false
)

internal fun MainActivity.showNetwork() {
    val t = pageTab() ?: return
    t.webView.evaluateJavascript(Scripts.PERF) { r ->
        val rows = ArrayList<NetRow>()
        try {
            val a = JSONArray(r ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                rows.add(NetRow(o.optString("n"), o.optString("t"), o.optDouble("d", 0.0), o.optInt("s"),
                    o.optLong("z"), o.optLong("b"), o.optDouble("st", 0.0), o.optString("p")))
            }
        } catch (_: Exception) {
        }
        synchronized(t.res) { t.res.values.filter { it.blocked }.toList() }.forEach {
            rows.add(NetRow(it.url, it.kind.lowercase(), 0.0, -1, 0, 0, Double.MAX_VALUE, "", true))
        }
        showNetworkSheet(t, rows)
    }
}

private fun MainActivity.showNetworkSheet(t: MainActivity.Tab, all: List<NetRow>) {
    val d = sheet(0.9f)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val okRows = all.filter { !it.blocked }
    val totalBytes = okRows.sumOf { it.transfer }
    val load = okRows.maxOfOrNull { it.start + it.dur } ?: 0.0
    root.addView(sheetHeader("Ağ kaydı",
        "${okRows.size} istek · ${MediaSniffer.formatSize(totalBytes).ifEmpty { "0 B" }} aktarıldı · ${"%.1f".format(load / 1000)} sn" +
            (all.count { it.blocked }.let { if (it > 0) " · $it engellendi" else "" })))

    var query = ""
    var sortBy = 0 // 0 zaman, 1 süre, 2 boyut
    val search = EditText(this).apply {
        hint = "Adreste ara"
        setSingleLine()
        textSize = 14f
        setBackgroundResource(R.drawable.bg_url)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setTextColor(textColor())
        setHintTextColor(mutedColor())
    }
    root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = dp(16); marginEnd = dp(16)
    })
    val chips = ChipGroup(this).apply { isSingleSelection = true; setPadding(dp(16), dp(4), dp(16), 0) }
    root.addView(chips)

    var rows: List<NetRow> = emptyList()
    lateinit var rv: RecyclerView
    fun apply() {
        var l = all.filter { query.isEmpty() || it.url.contains(query, true) }
        l = when (sortBy) {
            1 -> l.sortedByDescending { it.dur }
            2 -> l.sortedByDescending { it.transfer }
            else -> l.sortedBy { it.start }
        }
        rows = l
        rv.adapter?.notifyDataSetChanged()
    }
    listOf("Sıra", "En yavaş", "En büyük").forEachIndexed { i, label ->
        chips.addView(Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = i == 0
            setOnClickListener { sortBy = i; apply() }
        })
    }
    rv = simpleList({ rows.size }) { i, t1, t2, row ->
        val n = rows[i]
        val status = when {
            n.blocked -> "ENGEL"
            n.status > 0 -> n.status.toString()
            else -> "—"
        }
        val color = when {
            n.blocked || n.status >= 400 -> Color.parseColor(RED)
            n.status in 300..399 -> Color.parseColor(BLUE)
            n.status in 200..299 -> Color.parseColor(GREEN)
            else -> mutedColor()
        }
        val method = synchronized(t.res) { t.res[n.url]?.method } ?: "GET"
        val size = MediaSniffer.formatSize(if (n.transfer > 0) n.transfer else n.body)
        val sp = android.text.SpannableStringBuilder()
        sp.append(status)
        sp.setSpan(android.text.style.ForegroundColorSpan(color), 0, status.length, 0)
        sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, status.length, 0)
        sp.append("  $method  ${n.type}")
        if (!n.blocked) sp.append("  ·  ${n.dur.toLong()} ms")
        if (size.isNotEmpty()) sp.append("  ·  $size")
        t1.text = sp
        t2.text = n.url
        t2.maxLines = 2
        row.setOnClickListener { netDetails(t, n) }
    }
    rv.setPadding(dp(14), dp(8), dp(14), dp(20))
    rv.clipToPadding = false
    root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.62).toInt()))
    search.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; apply() }
    })
    apply()
    d.setContentView(root)
    d.show()
}

private fun MainActivity.netDetails(t: MainActivity.Tab, n: NetRow) {
    val m = synchronized(t.res) { t.res[n.url] }
    val headers = m?.reqHeaders ?: emptyMap()
    val sb = StringBuilder()
    sb.append(n.url).append("\n\n")
    sb.append("Yöntem: ${m?.method ?: "GET"}\n")
    sb.append("Durum: ${if (n.blocked) "Kalkan tarafından engellendi" else if (n.status > 0) n.status.toString() else "bilinmiyor (farklı kaynak)"}\n")
    sb.append("Tür: ${n.type}\n")
    if (n.proto.isNotEmpty()) sb.append("Protokol: ${n.proto}\n")
    if (!n.blocked) {
        sb.append("Süre: ${n.dur.toLong()} ms (başlangıç ${n.start.toLong()} ms)\n")
        sb.append("Aktarılan: ${MediaSniffer.formatSize(n.transfer).ifEmpty { "—" }} · Gövde: ${MediaSniffer.formatSize(n.body).ifEmpty { "—" }}\n")
    }
    if (headers.isNotEmpty()) {
        sb.append("\nİstek başlıkları:\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\n") }
    }
    val tv = TextView(this).apply {
        text = sb
        textSize = 12.5f
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setTextColor(textColor())
        setPadding(dp(22), dp(8), dp(22), 0)
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("İstek ayrıntısı")
        .setView(ScrollView(this).apply { addView(tv) })
        .setPositiveButton("cURL kopyala") { _, _ -> copy(curlOf(n.url, m?.method ?: "GET", headers)) }
        .setNeutralButton("Aç") { _, _ -> newTab(n.url, t.incognito, false, t) }
        .setNegativeButton("Kapat", null)
        .show()
}

private fun curlOf(url: String, method: String, headers: Map<String, String>): String {
    fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
    val sb = StringBuilder("curl ")
    if (method != "GET") sb.append("-X $method ")
    sb.append(q(url))
    headers.forEach { (k, v) -> sb.append(" \\\n  -H ").append(q("$k: $v")) }
    return sb.toString()
}

// =====================================================================
// Çerez & depolama editörü
// =====================================================================

private fun MainActivity.cookieManagerFor(tab: MainActivity.Tab): CookieManager {
    if (tab.incognito && profilesSupported) {
        try {
            ProfileStore.getInstance().getProfile(MainActivity.INCOGNITO_PROFILE)?.let { return it.cookieManager }
        } catch (_: Exception) {
        }
    }
    return CookieManager.getInstance()
}

internal fun MainActivity.showStorage() {
    val t = pageTab() ?: return
    val d = sheet(0.9f)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(sheetHeader("Çerez & depolama", t.pageHost))
    var kind = "cookie"
    var items: List<Pair<String, String>> = emptyList()
    val chips = ChipGroup(this).apply { isSingleSelection = true; setPadding(dp(16), 0, dp(16), 0) }
    root.addView(chips)
    val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), 0, dp(10), 0) }
    root.addView(actions)
    val empty = small("").apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(30), dp(24), dp(30)) }
    root.addView(empty)
    lateinit var rv: RecyclerView
    val cm = cookieManagerFor(t)
    val url = t.url

    fun show(list: List<Pair<String, String>>, err: String? = null) {
        items = list.sortedBy { it.first.lowercase() }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        empty.text = err ?: "Bu sitede kayıt yok."
        rv.adapter?.notifyDataSetChanged()
    }

    fun load() {
        if (kind == "cookie") {
            val raw = cm.getCookie(url) ?: ""
            show(raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }.map {
                val i = it.indexOf('=')
                if (i < 0) Pair(it, "") else Pair(it.substring(0, i), it.substring(i + 1))
            })
        } else {
            t.webView.evaluateJavascript(Scripts.storageDump(kind)) { r ->
                try {
                    val v = org.json.JSONTokener(r ?: "[]").nextValue()
                    if (v is JSONArray) {
                        show((0 until v.length()).map { i -> val p = v.getJSONArray(i); Pair(p.getString(0), p.getString(1)) })
                    } else show(emptyList(), (v as? String)?.removePrefix("ERR:") ?: "Okunamadı")
                } catch (e: Exception) {
                    show(emptyList(), "Okunamadı: ${e.message}")
                }
            }
        }
    }

    fun deleteCookie(name: String) {
        val host = t.pageHost
        val base = AdBlockEngine.baseDomain(host)
        cm.setCookie(url, "$name=; Max-Age=0; path=/")
        cm.setCookie(url, "$name=; Max-Age=0; path=/; domain=$host")
        cm.setCookie(url, "$name=; Max-Age=0; path=/; domain=.$base")
        cm.flush()
    }

    fun edit(key: String?, value: String) {
        val k = field("Ad / anahtar", key ?: "")
        if (key != null) k.isEnabled = false
        val v = field("Değer", value, multi = true, mono = true)
        val b = MaterialAlertDialogBuilder(this)
            .setTitle(if (key == null) "Yeni kayıt" else key)
            .setView(formBox(k, v))
            .setPositiveButton("Kaydet") { _, _ ->
                val name = k.text.toString().trim()
                val value2 = v.text.toString()
                if (name.isEmpty()) { toast("Ad boş olamaz"); return@setPositiveButton }
                if (kind == "cookie") {
                    cm.setCookie(url, "$name=$value2; path=/")
                    cm.flush()
                    load()
                } else {
                    t.webView.evaluateJavascript("$kind.setItem(${JSONObject.quote(name)}, ${JSONObject.quote(value2)})") { load() }
                }
            }
            .setNegativeButton("Vazgeç", null)
        if (key != null) b.setNeutralButton("Sil") { _, _ ->
            if (kind == "cookie") { deleteCookie(key); load() }
            else t.webView.evaluateJavascript("$kind.removeItem(${JSONObject.quote(key)})") { load() }
        }
        b.show()
    }

    listOf("cookie" to "Çerezler", "localStorage" to "localStorage", "sessionStorage" to "sessionStorage").forEach { (k, label) ->
        chips.addView(Chip(this).apply {
            text = label
            isCheckable = true
            isChecked = k == kind
            setOnClickListener { kind = k; load() }
        })
    }
    actions.addView(textBtn("+ Ekle") { edit(null, "") })
    actions.addView(textBtn("Kopyala (JSON)") {
        val o = JSONObject()
        items.forEach { o.put(it.first, it.second) }
        copy(o.toString(2))
    })
    actions.addView(textBtn("Tümünü sil") {
        MaterialAlertDialogBuilder(this)
            .setTitle("Hepsi silinsin mi?")
            .setMessage("${t.pageHost} için tüm ${if (kind == "cookie") "çerezler" else kind} silinecek.")
            .setPositiveButton("Sil") { _, _ ->
                if (kind == "cookie") { items.forEach { deleteCookie(it.first) }; load() }
                else t.webView.evaluateJavascript("$kind.clear()") { load() }
            }
            .setNegativeButton("Vazgeç", null)
            .show()
    })

    rv = simpleList({ items.size }) { i, t1, t2, row ->
        val (k, v) = items[i]
        t1.text = k
        t1.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        t2.text = v.ifEmpty { "(boş)" }
        t2.typeface = Typeface.MONOSPACE
        row.setOnClickListener { edit(k, v) }
        row.setOnLongClickListener { copy(v); true }
    }
    rv.setPadding(dp(14), dp(4), dp(14), dp(20))
    rv.clipToPadding = false
    root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6).toInt()))
    load()
    d.setContentView(root)
    d.show()
}

// =====================================================================
// Kullanıcı betikleri
// =====================================================================

internal fun MainActivity.runUserScripts(tab: MainActivity.Tab) {
    val list = Store.scriptsFor(tab.pageHost)
    for (sc in list) {
        val code = "(function(){try{\n" + sc.code + "\n}catch(e){console.error('[Betik: ' + " +
            JSONObject.quote(sc.name) + " + '] ' + e);}})();"
        tab.webView.evaluateJavascript(code, null)
    }
}

internal fun MainActivity.showScripts() {
    val d = sheet()
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(sheetHeader("Kullanıcı betikleri", "Seçtiğin sitelerde sayfa yüklenince kendi JavaScript kodunu çalıştırır."),
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(head)
    var items: List<UserScript> = emptyList()
    val empty = small("Henüz betik yok.").apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(30), dp(24), dp(30)) }
    lateinit var rv: RecyclerView
    fun load() {
        items = Store.scriptList()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rv.adapter?.notifyDataSetChanged()
    }
    head.addView(textBtn("+ Yeni") { editScript(null) { load() } }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) })
    root.addView(empty)
    rv = RecyclerView(this)
    rv.layoutManager = LinearLayoutManager(this)
    rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_tile)
                setPadding(dp(14), dp(10), dp(8), dp(10))
                isClickable = true
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
            }
            val col = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL }
            val t1 = TextView(parent.context).apply { textSize = 15f; setTextColor(textColor()) }
            val t2 = TextView(parent.context).apply { textSize = 12.5f; setTextColor(mutedColor()); maxLines = 1 }
            col.addView(t1); col.addView(t2)
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val sw = MaterialSwitch(parent.context)
            row.addView(sw)
            row.tag = arrayOf<View>(t1, t2, sw)
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val sc = items[position]
            @Suppress("UNCHECKED_CAST")
            val v = holder.itemView.tag as Array<View>
            (v[0] as TextView).text = sc.name.ifBlank { "Adsız betik" }
            (v[1] as TextView).text = "Siteler: " + sc.match.ifBlank { "*" }
            val sw = v[2] as MaterialSwitch
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = sc.on
            sw.setOnCheckedChangeListener { _, c -> sc.on = c; Store.saveScript(sc) }
            holder.itemView.setOnClickListener { editScript(sc) { load() } }
        }
    }
    rv.setPadding(dp(14), dp(4), dp(14), dp(20))
    rv.clipToPadding = false
    root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.6).toInt()))
    load()
    d.setContentView(root)
    d.show()
}

private fun MainActivity.editScript(sc: UserScript?, onSaved: () -> Unit) {
    val host = current?.pageHost?.removePrefix("www.")?.takeIf { it.isNotEmpty() && it != MainActivity.ASSET_HOST }
    val name = field("Ad", sc?.name ?: "")
    val match = field("Siteler: * veya ornek.com, *.site.com", sc?.match ?: (host ?: "*"))
    val code = field("// Sayfa yüklenince çalışır\ndocument.body.style.background = 'black';", sc?.code ?: "", multi = true, mono = true).apply {
        minLines = 10
    }
    val help = small("Kod sayfa yüklendikten sonra çalışır. Hatalar Konsol'da görünür.")
    val b = MaterialAlertDialogBuilder(this)
        .setTitle(if (sc == null) "Yeni betik" else "Betiği düzenle")
        .setView(formBox(name, match, code, help))
        .setPositiveButton("Kaydet") { _, _ ->
            val s = UserScript(sc?.id ?: System.currentTimeMillis(), name.text.toString().trim().ifEmpty { "Betik" },
                match.text.toString().trim().ifEmpty { "*" }, code.text.toString(), sc?.on ?: true)
            Store.saveScript(s)
            onSaved()
            current?.let { t -> if (Store.scriptMatches(s, t.pageHost) && t.url.startsWith("http")) runUserScripts(t) }
            toast("Betik kaydedildi")
        }
        .setNegativeButton("Vazgeç", null)
    if (sc != null) b.setNeutralButton("Sil") { _, _ -> Store.deleteScript(sc.id); onSaved() }
    b.show()
}

// =====================================================================
// User-Agent, video hızı
// =====================================================================

private val UA_MODES = linkedMapOf(
    "default" to "Varsayılan (Android)",
    "desktop" to "Masaüstü (Linux Chrome)",
    "windows" to "Windows Chrome",
    "iphone" to "iPhone Safari",
    "ipad" to "iPad Safari",
    "firefox" to "Firefox Android",
    "bot" to "Googlebot",
    "custom" to "Özel…"
)

private fun uaName(m: String) = when (m) {
    "default" -> "Varsayılan"
    "desktop" -> "Masaüstü"
    "windows" -> "Windows"
    "iphone" -> "iPhone"
    "ipad" -> "iPad"
    "firefox" -> "Firefox"
    "bot" -> "Googlebot"
    "custom" -> "Özel"
    else -> m
}

internal fun MainActivity.chooseUA() {
    val keys = UA_MODES.keys.toList()
    val cur = keys.indexOf(Store.str("ua_mode", "default")).coerceAtLeast(0)
    fun apply(mode: String) {
        Store.prefs.edit().putString("ua_mode", mode).apply()
        tabs.forEach { it.webView.settings.userAgentString = uaFor(it) }
        current?.webView?.reload()
        toast("User-Agent: ${uaName(mode)}")
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Kendini nasıl tanıtsın?")
        .setSingleChoiceItems(UA_MODES.values.toTypedArray(), cur) { dlg, w ->
            dlg.dismiss()
            val mode = keys[w]
            if (mode == "custom") {
                val f = field("User-Agent", Store.str("ua_custom", "").ifBlank { uaFor(current) }, multi = true, mono = true).apply { minLines = 3 }
                MaterialAlertDialogBuilder(this)
                    .setTitle("Özel User-Agent")
                    .setView(formBox(f))
                    .setPositiveButton("Uygula") { _, _ ->
                        Store.prefs.edit().putString("ua_custom", f.text.toString().trim()).apply()
                        apply("custom")
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            } else apply(mode)
        }
        .setNeutralButton("Şu anki UA'yı kopyala") { _, _ -> copy(uaFor(current)) }
        .show()
}

internal fun MainActivity.chooseSpeed() {
    val t = current ?: return
    val rates = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)
    val labels = rates.map { if (it == 1f) "Normal (1x)" else "${fmtRate(it)}x" }.toTypedArray()
    val cur = rates.indexOfFirst { it == t.rate }.coerceAtLeast(3)
    MaterialAlertDialogBuilder(this)
        .setTitle("Video hızı")
        .setSingleChoiceItems(labels, cur) { dlg, w ->
            dlg.dismiss()
            t.rate = rates[w]
            t.webView.evaluateJavascript(
                "document.querySelectorAll('video,audio').forEach(function(v){v.playbackRate=${rates[w]};});", null
            )
            toast("Video hızı: ${labels[w]}")
        }
        .show()
}

// =====================================================================
// Resim içinde resim
// =====================================================================

internal fun MainActivity.enterPip() {
    if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
        toast("Bu cihaz resim içinde resmi desteklemiyor"); return
    }
    if (customView != null) { doEnterPip(16f / 9f); return }
    val t = pageTab() ?: return
    t.webView.evaluateJavascript(Scripts.pip(true)) { r ->
        val ratio = r?.toFloatOrNull() ?: 0f
        if (ratio <= 0f) toast("Sayfada oynatılan video bulunamadı")
        else {
            pipCss = true
            doEnterPip(ratio)
        }
    }
}

private fun MainActivity.doEnterPip(ratio: Float) {
    val r = ratio.coerceIn(0.42f, 2.38f)
    try {
        val p = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(Rational((r * 1000).toInt(), 1000))
            .build()
        enterPictureInPictureMode(p)
    } catch (e: Exception) {
        toast("Küçük pencere açılamadı: ${e.message}")
    }
}

internal fun MainActivity.onPipChanged(inPip: Boolean) {
    val vis = if (inPip) View.GONE else View.VISIBLE
    if (!barsHidden) {
        topBar.visibility = vis
        bottomBar.visibility = vis
    }
    if (inPip) progress.visibility = View.INVISIBLE
    if (!inPip && pipCss) {
        current?.webView?.evaluateJavascript(Scripts.pip(false), null)
        pipCss = false
    }
}

// =====================================================================
// Proxy / Tor
// =====================================================================

internal fun MainActivity.chooseProxy() {
    val modes = listOf("off", "http", "socks", "tor")
    val labels = arrayOf("Kapalı", "HTTP proxy", "SOCKS5 proxy", "Tor (Orbot uygulaması ile)")
    val cur = modes.indexOf(Net.mode).coerceAtLeast(0)
    fun apply(mode: String) {
        Store.prefs.edit().putString("proxy_mode", mode).apply()
        if (!Net.webViewSupported) {
            toast("Bu cihazdaki WebView sürümü proxy desteklemiyor — Android System WebView'i güncelle")
            return
        }
        Net.applyWebView { ok ->
            ui.post {
                toast(if (ok) "Proxy: ${Net.label()}" else "Proxy uygulanamadı")
                current?.webView?.reload()
            }
        }
    }
    MaterialAlertDialogBuilder(this)
        .setTitle("Proxy / Tor")
        .setSingleChoiceItems(labels, cur) { dlg, w ->
            dlg.dismiss()
            when (val m = modes[w]) {
                "off" -> apply("off")
                "tor" -> {
                    val orbot = try { packageManager.getPackageInfo("org.torproject.android", 0); true } catch (_: Exception) { false }
                    if (!orbot) {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Orbot gerekli")
                            .setMessage("Tor ağı için Orbot uygulamasını kurup açman gerekiyor. Kalkan, Orbot'un 127.0.0.1:9050 SOCKS adresini kullanır.")
                            .setPositiveButton("Orbot'u indir") { _, _ ->
                                try {
                                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("market://details?id=org.torproject.android")))
                                } catch (_: Exception) {
                                    newTab("https://play.google.com/store/apps/details?id=org.torproject.android", false)
                                }
                            }
                            .setNeutralButton("Yine de aç") { _, _ -> apply("tor") }
                            .setNegativeButton("Vazgeç", null)
                            .show()
                    } else {
                        apply("tor")
                        toast("Orbot'u açıp bağlandığından emin ol")
                    }
                }
                else -> {
                    val h = field("Sunucu (ör. 192.168.1.10)", Store.str("proxy_host", ""))
                    val p = field("Port (ör. 8080)", Store.str("proxy_port", "")).apply { inputType = InputType.TYPE_CLASS_NUMBER }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(labels[w])
                        .setView(formBox(h, p))
                        .setPositiveButton("Uygula") { _, _ ->
                            Store.prefs.edit().putString("proxy_host", h.text.toString().trim())
                                .putString("proxy_port", p.text.toString().trim()).apply()
                            if (h.text.isBlank() || p.text.isBlank()) toast("Sunucu ve port gerekli")
                            else apply(m)
                        }
                        .setNegativeButton("Vazgeç", null)
                        .show()
                }
            }
        }
        .setNeutralButton("Bağlantıyı test et") { _, _ -> newTab("https://check.torproject.org/", current?.incognito ?: false) }
        .show()
}

// =====================================================================
// Uygulama kilidi
// =====================================================================

internal fun MainActivity.lockMode(): String = Store.str("lock_mode", "off")

private fun lockName(m: String) = when (m) {
    "app" -> "Tüm uygulama"
    "incognito" -> "Gizli sekmeler"
    else -> "Kapalı"
}

internal fun MainActivity.needsLock(tab: MainActivity.Tab?): Boolean = when (lockMode()) {
    "app" -> true
    "incognito" -> tab?.incognito == true
    else -> false
}

private val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

private fun MainActivity.canAuth() = BiometricManager.from(this).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

internal fun MainActivity.chooseLock() {
    val modes = listOf("off", "app", "incognito")
    val labels = arrayOf("Kapalı", "Tüm uygulama", "Sadece gizli sekmeler")
    MaterialAlertDialogBuilder(this)
        .setTitle("Parmak izi / ekran kilidi")
        .setSingleChoiceItems(labels, modes.indexOf(lockMode()).coerceAtLeast(0)) { dlg, w ->
            dlg.dismiss()
            if (modes[w] != "off" && !canAuth()) {
                toast("Telefonunda parmak izi veya ekran kilidi ayarlı değil")
                return@setSingleChoiceItems
            }
            Store.prefs.edit().putString("lock_mode", modes[w]).apply()
            toast(when (modes[w]) {
                "app" -> "Kalkan'a her dönüşünde kilit sorulacak"
                "incognito" -> "Gizli sekmelere dönüşte kilit sorulacak"
                else -> "Uygulama kilidi kapalı"
            })
        }
        .show()
}

internal fun MainActivity.showLock() {
    if (!canAuth()) { locked = false; return }
    if (lockOverlay != null) return
    val o = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(ContextCompat.getColor(this@showLock, R.color.bar))
        isClickable = true
        isFocusable = true
        elevation = dp(30).toFloat()
    }
    o.addView(ImageView(this).apply {
        setImageResource(R.drawable.ic_shield)
    }, LinearLayout.LayoutParams(dp(64), dp(64)))
    o.addView(TextView(this).apply {
        text = if (lockMode() == "incognito") "Gizli sekmeler kilitli" else "Kalkan kilitli"
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textColor())
        gravity = Gravity.CENTER
        setPadding(0, dp(16), 0, dp(20))
    })
    o.addView(MaterialButton(this).apply {
        text = "Kilidi aç"
        setOnClickListener { promptUnlock() }
    })
    if (lockMode() == "incognito") {
        o.addView(textBtn("Normal sekmeye dön") {
            val normal = tabs.lastOrNull { !it.incognito }
            root.removeView(lockOverlay)
            lockOverlay = null
            if (normal != null) switchTo(normal) else newTab(MainActivity.HOME_URL, false)
        })
    }
    lockOverlay = o
    root.addView(o, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    promptUnlock()
}

private fun MainActivity.promptUnlock() {
    val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            locked = false
            lockOverlay?.let { root.removeView(it) }
            lockOverlay = null
        }
    })
    internalNav = true
    try {
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Kalkan kilidini aç")
                .setSubtitle("Parmak izi veya ekran kilidi")
                .setAllowedAuthenticators(AUTH)
                .build()
        )
    } catch (e: Exception) {
        toast("Kilit açılamadı: ${e.message}")
    }
}

// =====================================================================
// Veri temizleme & yedekleme
// =====================================================================

internal fun MainActivity.clearAllData() {
    Store.clearHistory()
    try {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        tabs.firstOrNull()?.webView?.clearCache(true)
        android.webkit.WebViewDatabase.getInstance(this).clearHttpAuthUsernamePassword()
    } catch (_: Exception) {
    }
    closedUrls.clear()
    Store.clearSession()
}

private fun MainActivity.confirmClearAll() {
    MaterialAlertDialogBuilder(this)
        .setTitle("Tüm veriler silinsin mi?")
        .setMessage("Geçmiş, çerezler, site verileri ve önbellek silinir. Yer imleri ve ayarlar kalır.")
        .setPositiveButton("Sil") { _, _ ->
            clearAllData()
            current?.webView?.reload()
            toast("Temizlendi")
        }
        .setNegativeButton("Vazgeç", null)
        .show()
}

private fun MainActivity.exportBackup() {
    ensureStorage {
        val name = "kalkan-yedek-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".json"
        Thread {
            val msg = try {
                val json = Store.exportJson()
                Saver.save(this, name, "application/json") { it.write(json.toByteArray()) }
                "Yedek kaydedildi: İndirilenler/Kalkan/$name"
            } catch (e: Exception) {
                "Yedeklenemedi: ${e.message}"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }
}

internal fun MainActivity.importBackup(uri: Uri) {
    Thread {
        val msg = try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val n = Store.importJson(text)
            runOnUiThread {
                KalkanApp.applyTheme(Store.str("theme", "system"))
                Net.applyWebView()
                applySettingsToAll()
                tabs.forEach { it.webView.settings.userAgentString = uaFor(it) }
            }
            "$n öğe geri yüklendi"
        } catch (e: Exception) {
            "Geri yüklenemedi: ${e.message}"
        }
        runOnUiThread { toast(msg) }
    }.start()
}

// =====================================================================
// Hareketler: kaydırarak sekme değiştir, kaydırınca çubukları gizle
// =====================================================================

internal fun MainActivity.setupGestures() {
    val flung = BooleanArray(1)
    val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!Store.bool("swipe_tabs", true) || e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > dp(60) && abs(dx) > abs(dy) * 1.5f && abs(velocityX) > 500) {
                flung[0] = true
                switchRelative(if (dx < 0) 1 else -1)
                return true
            }
            return false
        }
    })
    val listener = View.OnTouchListener { v, e ->
        if (v === urlBar && urlBar.hasFocus()) return@OnTouchListener false
        gd.onTouchEvent(e)
        if (flung[0] && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) {
            flung[0] = false
            val c = MotionEvent.obtain(e)
            c.action = MotionEvent.ACTION_CANCEL
            v.onTouchEvent(c)
            c.recycle()
            true
        } else false
    }
    val bar = bottomBar as ViewGroup
    for (i in 0 until bar.childCount) bar.getChildAt(i).setOnTouchListener(listener)
    urlBar.setOnTouchListener(listener)
}

private fun MainActivity.switchRelative(step: Int) {
    val idx = tabs.indexOf(current)
    if (idx < 0 || tabs.size < 2) { toast("Başka sekme yok"); return }
    val n = (idx + step).coerceIn(0, tabs.size - 1)
    if (n == idx) { toast(if (step > 0) "Son sekme" else "İlk sekme"); return }
    switchTo(tabs[n])
    toast("Sekme ${n + 1} / ${tabs.size}")
}

internal fun MainActivity.onPageScroll(y: Int, dy: Int) {
    if (!Store.bool("hide_bars", false) || urlBar.hasFocus() || findBar.isVisible || isInPictureInPictureMode) {
        if (barsHidden && !isInPictureInPictureMode) showBars()
        return
    }
    if (y <= 0) {
        if (barsHidden) showBars()
        scrollAcc = 0
        return
    }
    if ((dy > 0 && scrollAcc < 0) || (dy < 0 && scrollAcc > 0)) scrollAcc = 0
    scrollAcc += dy
    if (!barsHidden && scrollAcc > dp(60)) hideBars()
    else if (barsHidden && scrollAcc < -dp(40)) showBars()
}

private fun MainActivity.hideBars() {
    barsHidden = true
    scrollAcc = 0
    topBar.visibility = View.GONE
    bottomBar.visibility = View.GONE
}

internal fun MainActivity.showBars() {
    barsHidden = false
    scrollAcc = 0
    topBar.visibility = View.VISIBLE
    bottomBar.visibility = View.VISIBLE
}
