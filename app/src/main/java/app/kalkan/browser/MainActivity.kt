package app.kalkan.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.Filter
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity() {

    companion object {
        const val ASSET_HOST = "appassets.androidplatform.net"
        const val HOME_URL = "https://$ASSET_HOST/assets/home.html"
        const val EXTRA_OPEN_URL = "open_url"
        private const val INCOGNITO_PROFILE = "kalkan_incognito"
    }

    class Tab(val id: Long, val webView: WebView, val incognito: Boolean, val parentId: Long?) {
        var title = ""
        var url = ""
        @Volatile var pageHost = ""
        @Volatile var blocked = 0
        var progress = 100
        var desktop = false
        var favicon: Bitmap? = null
        var pendingUrl: String? = null
        val media = LinkedHashMap<String, MediaItem>()
    }

    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null
    private var nextId = 1L
    private val closedUrls = ArrayList<String>()

    private lateinit var root: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private lateinit var urlBar: AutoCompleteTextView
    private lateinit var reloadBtn: ImageButton
    private lateinit var shieldIcon: ImageView
    private lateinit var shieldBadge: TextView
    private lateinit var progress: LinearProgressIndicator
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var webContainer: FrameLayout
    private lateinit var backBtn: ImageButton
    private lateinit var forwardBtn: ImageButton
    private lateinit var tabsCount: TextView
    private lateinit var fullscreen: FrameLayout
    private lateinit var mediaBtn: View
    private lateinit var mediaBadge: TextView

    private lateinit var assetLoader: WebViewAssetLoader
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var shieldPosted = false

    private val docStartSupported by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
    private val profilesSupported by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }

    private val mobileUA by lazy {
        WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")
    }
    private val desktopUA by lazy {
        val chrome = Regex("Chrome/([\\d.]+)").find(mobileUA)?.groupValues?.get(1) ?: "130.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private var pendingAction: (() -> Unit)? = null

    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        pendingWebPermission?.let { req ->
            val granted = ArrayList<String>()
            for (r in req.resources) {
                when (r) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> if (has(Manifest.permission.CAMERA)) granted.add(r)
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> if (has(Manifest.permission.RECORD_AUDIO)) granted.add(r)
                    PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID -> granted.add(r)
                }
            }
            if (granted.isEmpty()) req.deny() else req.grant(granted.toTypedArray())
            pendingWebPermission = null
        }
        pendingGeo?.let { (origin, cb) ->
            cb.invoke(origin, has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION), false)
            pendingGeo = null
        }
        pendingAction?.let { action ->
            pendingAction = null
            if (res.values.all { it }) action()
        }
    }

    private val filterListener: () -> Unit = { updateShield() }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // =====================================================================
    // Yaşam döngüsü
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        setupToolbar()
        setupBack()
        FilterManager.addListener(filterListener)

        val restored = restoreSession()
        val opened = handleIntent(intent)
        if (!restored && !opened) newTab(HOME_URL, false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        applySettingsToAll()
        current?.webView?.onResume()
        updateAll()
    }

    override fun onPause() {
        super.onPause()
        saveSession()
        Store.persistCounters()
        CookieManager.getInstance().flush()
        if (!Store.bool("bg_play", true)) current?.webView?.onPause()
    }

    override fun onDestroy() {
        FilterManager.removeListener(filterListener)
        for (t in tabs) {
            try { t.webView.destroy() } catch (_: Exception) {}
        }
        tabs.clear()
        super.onDestroy()
    }

    private fun bindViews() {
        root = findViewById(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
        urlBar = findViewById(R.id.urlBar)
        reloadBtn = findViewById(R.id.reloadBtn)
        shieldIcon = findViewById(R.id.shieldIcon)
        shieldBadge = findViewById(R.id.shieldBadge)
        progress = findViewById(R.id.progress)
        swipe = findViewById(R.id.swipe)
        webContainer = findViewById(R.id.webContainer)
        backBtn = findViewById(R.id.backBtn)
        forwardBtn = findViewById(R.id.forwardBtn)
        tabsCount = findViewById(R.id.tabsCount)
        fullscreen = findViewById(R.id.fullscreen)
        mediaBtn = findViewById(R.id.mediaBtn)
        mediaBadge = findViewById(R.id.mediaBadge)
    }

    // =====================================================================
    // Araç çubuğu
    // =====================================================================

    private fun setupToolbar() {
        val adapter = SuggestAdapter()
        urlBar.setAdapter(adapter)
        urlBar.setOnItemClickListener { _, _, pos, _ ->
            val e = adapter.getItem(pos)
            loadInCurrent(e.url)
        }
        urlBar.setOnEditorActionListener { _, id, ev ->
            if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE ||
                (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN)
            ) {
                go(urlBar.text.toString()); true
            } else false
        }
        urlBar.setOnFocusChangeListener { _, has ->
            if (has) {
                val t = current
                val text = if (t == null || isHome(t.url)) "" else t.url
                urlBar.setText(text, false)
                urlBar.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
                urlBar.post { urlBar.selectAll() }
            } else {
                updateUrlBar()
            }
        }

        reloadBtn.setOnClickListener {
            val t = current ?: return@setOnClickListener
            if (t.progress < 100) t.webView.stopLoading() else t.webView.reload()
        }
        findViewById<View>(R.id.shieldBtn).setOnClickListener { showShield() }
        mediaBtn.setOnClickListener { showMedia() }
        backBtn.setOnClickListener { current?.webView?.let { if (it.canGoBack()) it.goBack() } }
        forwardBtn.setOnClickListener { current?.webView?.let { if (it.canGoForward()) it.goForward() } }
        findViewById<View>(R.id.homeBtn).setOnClickListener { loadInCurrent(HOME_URL) }
        findViewById<View>(R.id.tabsBtn).setOnClickListener { showTabs() }
        findViewById<View>(R.id.tabsBtn).setOnLongClickListener { newTab(HOME_URL, false); true }
        findViewById<View>(R.id.menuBtn).setOnClickListener { showMenu() }

        swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.brand))
        swipe.setOnRefreshListener { current?.webView?.reload() }
        swipe.setOnChildScrollUpCallback { _, _ -> (current?.webView?.scrollY ?: 0) > 0 }

        // Sayfada bul
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                current?.webView?.findAllAsync(s?.toString() ?: "")
            }
        })
        findInput.setOnEditorActionListener { _, _, _ -> current?.webView?.findNext(true); true }
        findViewById<View>(R.id.findNext).setOnClickListener { current?.webView?.findNext(true) }
        findViewById<View>(R.id.findPrev).setOnClickListener { current?.webView?.findNext(false) }
        findViewById<View>(R.id.findClose).setOnClickListener { closeFind() }
    }

    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val t = current
                when {
                    customView != null -> hideFullscreen()
                    findBar.isVisible -> closeFind()
                    urlBar.hasFocus() -> { urlBar.clearFocus(); hideKeyboard() }
                    t != null && t.webView.canGoBack() -> t.webView.goBack()
                    t != null && t.parentId != null && tabs.size > 1 -> closeTab(t)
                    else -> moveTaskToBack(true)
                }
            }
        })
    }

    private fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        loadInCurrent(resolve(text))
    }

    private fun loadInCurrent(url: String) {
        urlBar.dismissDropDown()
        urlBar.clearFocus()
        hideKeyboard()
        val t = current
        if (t == null) newTab(url, false) else {
            t.pendingUrl = null
            t.webView.loadUrl(url)
        }
    }

    fun resolve(t: String): String {
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(t)) return t
        if (t.startsWith("about:") || t.startsWith("javascript:") || t.startsWith("data:")) return t
        if (!t.contains(' ')) {
            if (t.startsWith("localhost") || Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(t)) return "http://$t"
            if (t.contains('.') && Patterns.WEB_URL.matcher(t).matches()) return "https://$t"
        }
        return Store.searchUrl(t)
    }

    // =====================================================================
    // Sekmeler
    // =====================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private fun newTab(url: String?, incognito: Boolean, background: Boolean = false, parent: Tab? = null, quiet: Boolean = false): Tab {
        val wv = WebView(this)
        if (incognito && profilesSupported) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(INCOGNITO_PROFILE)
                WebViewCompat.setProfile(wv, INCOGNITO_PROFILE)
            } catch (_: Exception) {
            }
        }
        val tab = Tab(nextId++, wv, incognito, parent?.id)
        configure(tab)
        val idx = if (parent != null) (tabs.indexOf(parent) + 1).coerceIn(0, tabs.size) else tabs.size
        tabs.add(idx, tab)
        if (url != null) {
            if (background) tab.pendingUrl = url else wv.loadUrl(url)
            tab.url = url
        }
        if (!background) switchTo(tab) else {
            updateTabCount()
            if (!quiet) toast("Sekme arka planda açıldı")
        }
        if (incognito && !background && !quiet) {
            toast(if (profilesSupported) "Gizli sekme: geçmiş ve çerezler saklanmaz" else "Gizli sekme: geçmiş kaydedilmez")
        }
        saveSessionSoon()
        return tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(tab: Tab) {
        val wv = tab.webView
        wv.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.bar))
        val s = wv.settings
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportZoom(true)
        s.setSupportMultipleWindows(true)
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.userAgentString = if (Store.bool("desktop_default", false)) desktopUA else mobileUA
        tab.desktop = Store.bool("desktop_default", false)
        if (tab.incognito && !profilesSupported) s.cacheMode = WebSettings.LOAD_NO_CACHE
        applySettings(tab)

        wv.addJavascriptInterface(CosmeticBridge(tab), "KalkanCosmetic")
        if (docStartSupported) {
            try {
                WebViewCompat.addDocumentStartJavaScript(wv, Scripts.CONTENT, setOf("*"))
            } catch (_: Exception) {
            }
        }
        wv.webViewClient = Client(tab)
        wv.webChromeClient = Chrome(tab)
        wv.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime, tab) }
        wv.setOnLongClickListener { longPress(tab) }
        wv.setFindListener { active, num, done ->
            if (done && tab === current) findCount.text = if (num == 0) "0/0" else "${active + 1}/$num"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettings(tab: Tab) {
        val s = tab.webView.settings
        s.javaScriptEnabled = Store.bool("javascript", true)
        s.javaScriptCanOpenWindowsAutomatically = !Store.bool("popup_block", true)
        s.textZoom = Store.str("text_zoom", "100").toIntOrNull() ?: 100
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                s.isAlgorithmicDarkeningAllowed = Store.bool("force_dark", false)
            } catch (_: Exception) {
            }
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(tab.webView, !Store.bool("block_3p_cookies", true))
    }

    private fun applySettingsToAll() {
        tabs.forEach { applySettings(it) }
        swipe.isEnabled = Store.bool("pull_refresh", true)
    }

    private fun switchTo(tab: Tab) {
        val prev = current
        if (prev != null && prev !== tab) {
            webContainer.removeView(prev.webView)
        }
        current = tab
        if (tab.webView.parent == null) webContainer.addView(tab.webView)
        tab.webView.onResume()
        tab.pendingUrl?.let {
            tab.pendingUrl = null
            tab.webView.loadUrl(it)
        }
        if (findBar.isVisible) closeFind()
        updateAll()
        saveSessionSoon()
    }

    private fun closeTab(tab: Tab) {
        val idx = tabs.indexOf(tab)
        if (idx < 0) return
        tabs.removeAt(idx)
        if (!tab.incognito && tab.url.isNotEmpty() && !isHome(tab.url)) closedUrls.add(tab.url)
        webContainer.removeView(tab.webView)
        try {
            tab.webView.stopLoading()
            tab.webView.destroy()
        } catch (_: Exception) {
        }
        if (tab.incognito && tabs.none { it.incognito }) ui.postDelayed({ clearIncognito() }, 500)
        if (tab === current) {
            current = null
            val next = tabs.firstOrNull { it.id == tab.parentId }
                ?: tabs.getOrNull(idx) ?: tabs.getOrNull(idx - 1)
            if (next != null) switchTo(next) else newTab(HOME_URL, false)
        }
        updateTabCount()
        saveSessionSoon()
    }

    private fun closeAllTabs() {
        for (t in tabs.toList()) {
            webContainer.removeView(t.webView)
            try { t.webView.destroy() } catch (_: Exception) {}
        }
        tabs.clear()
        current = null
        ui.postDelayed({ clearIncognito() }, 500)
        newTab(HOME_URL, false)
    }

    private fun clearIncognito() {
        if (!profilesSupported || tabs.any { it.incognito }) return
        try {
            val store = ProfileStore.getInstance()
            if (!store.deleteProfile(INCOGNITO_PROFILE)) {
                store.getProfile(INCOGNITO_PROFILE)?.let { p ->
                    p.cookieManager.removeAllCookies(null)
                    p.webStorage.deleteAllData()
                }
            }
        } catch (_: Exception) {
            try {
                ProfileStore.getInstance().getProfile(INCOGNITO_PROFILE)?.let { p ->
                    p.cookieManager.removeAllCookies(null)
                    p.webStorage.deleteAllData()
                }
            } catch (_: Exception) {
            }
        }
    }

    // =====================================================================
    // Arayüz güncellemeleri
    // =====================================================================

    private fun updateAll() {
        updateUrlBar(); updateNav(); updateProgress(); updateShield(); updateTabCount(); updateMediaBtn()
    }

    private fun isHome(url: String) = url.startsWith(HOME_URL)

    private fun hostOf(url: String): String = try {
        Uri.parse(url).host?.lowercase() ?: ""
    } catch (_: Exception) { "" }

    private fun displayUrl(url: String): String {
        var u = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (u.endsWith("/")) u = u.dropLast(1)
        return u
    }

    private fun updateUrlBar() {
        val t = current ?: return
        if (!urlBar.hasFocus()) {
            urlBar.setText(if (isHome(t.url)) "" else displayUrl(t.url), false)
            val icon = when {
                isHome(t.url) || t.url.isEmpty() -> R.drawable.ic_search_small
                t.url.startsWith("https://") -> R.drawable.ic_lock_small
                else -> 0
            }
            urlBar.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        }
        urlBar.hint = if (t.incognito) "Gizli sekme — ara veya adres yaz" else "Ara veya adres yaz"
        urlBar.backgroundTintList = if (t.incognito) ColorStateList.valueOf(Color.parseColor("#557C5CFF")) else null
    }

    private fun updateNav() {
        val wv = current?.webView ?: return
        backBtn.alpha = if (wv.canGoBack()) 1f else 0.35f
        forwardBtn.alpha = if (wv.canGoForward()) 1f else 0.35f
    }

    private fun updateProgress() {
        val t = current ?: return
        val loading = t.progress < 100
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        progress.setProgressCompat(t.progress, true)
        reloadBtn.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)
        if (!loading) swipe.isRefreshing = false
    }

    private fun protectionActive(t: Tab?): Boolean =
        Store.adblock && (t == null || !Store.isAllowlisted(t.pageHost))

    private fun updateShield() {
        val t = current
        val on = protectionActive(t)
        shieldIcon.setImageResource(if (on) R.drawable.ic_shield else R.drawable.ic_shield_off)
        val n = t?.blocked ?: 0
        if (on && n > 0) {
            shieldBadge.visibility = View.VISIBLE
            shieldBadge.text = if (n > 99) "99+" else n.toString()
        } else shieldBadge.visibility = View.GONE
    }

    private fun postShield() {
        if (shieldPosted) return
        shieldPosted = true
        ui.postDelayed({ shieldPosted = false; updateShield() }, 250)
    }

    private fun updateTabCount() {
        tabsCount.text = if (tabs.size > 99) ":D" else tabs.size.toString()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun isDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    private fun showKeyboard(v: View) {
        v.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kalkan", text))
        if (Build.VERSION.SDK_INT < 33) toast("Kopyalandı")
    }

    private fun share(text: String, title: String = "") {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (title.isNotEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title)
        startActivity(Intent.createChooser(i, "Paylaş"))
    }

    private fun selectableBg(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    private fun textColor() = ContextCompat.getColor(this, R.color.onBar)
    private fun mutedColor() = ContextCompat.getColor(this, R.color.muted)

    // =====================================================================
    // Bul
    // =====================================================================

    private fun openFind() {
        toolbar.visibility = View.GONE
        findBar.visibility = View.VISIBLE
        findInput.setText("")
        findCount.text = ""
        showKeyboard(findInput)
    }

    private fun closeFind() {
        current?.webView?.clearMatches()
        findBar.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(findInput.windowToken, 0)
    }

    // =====================================================================
    // Tam ekran video
    // =====================================================================

    private fun showFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
        if (customView != null) { cb.onCustomViewHidden(); return }
        customView = view
        customCallback = cb
        fullscreen.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fullscreen.visibility = View.VISIBLE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun hideFullscreen() {
        val v = customView ?: return
        fullscreen.removeView(v)
        fullscreen.visibility = View.GONE
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        customView = null
        try { customCallback?.onCustomViewHidden() } catch (_: Exception) {}
        customCallback = null
    }

    // =====================================================================
    // WebView istemcileri
    // =====================================================================

    inner class CosmeticBridge(private val tab: Tab) {
        @JavascriptInterface
        fun config(host: String): String {
            val page = tab.pageHost.ifEmpty { host.lowercase() }
            val on = Store.adblock && FilterManager.ready && page != ASSET_HOST && !Store.isAllowlisted(page)
            return JSONObject().put("on", on).put("bg", Store.bool("bg_play", true)).toString()
        }

        @JavascriptInterface
        fun media(url: String, tag: String, referer: String) {
            if (!url.startsWith("http")) return
            val kind = (try { MediaSniffer.classify(Uri.parse(url)) } catch (_: Exception) { null })
                ?: if (tag == "audio") MediaSniffer.AUDIO else MediaSniffer.VIDEO
            addMedia(tab, url, kind, referer)
        }

        @JavascriptInterface
        fun specific(host: String): String = try {
            FilterManager.engine.specificCss(host.lowercase())
        } catch (_: Exception) { "" }

        @JavascriptInterface
        fun generic(json: String, host: String): String = try {
            val o = JSONObject(json)
            fun list(a: JSONArray?): List<String> {
                if (a == null) return emptyList()
                return (0 until a.length()).map { a.getString(it) }
            }
            FilterManager.engine.genericCss(list(o.optJSONArray("c")), list(o.optJSONArray("i")), host.lowercase())
        } catch (_: Exception) { "" }
    }

    private fun typeOf(req: WebResourceRequest): Int {
        val accept = req.requestHeaders["Accept"] ?: req.requestHeaders["accept"] ?: ""
        val path = req.url.path?.lowercase() ?: ""
        return when {
            accept.startsWith("image/") || path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".svg") || path.endsWith(".ico") ||
                path.endsWith(".avif") -> AdBlockEngine.T_IMAGE
            accept.startsWith("text/css") || path.endsWith(".css") -> AdBlockEngine.T_CSS
            path.endsWith(".js") || path.endsWith(".mjs") -> AdBlockEngine.T_SCRIPT
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") || path.endsWith(".otf") -> AdBlockEngine.T_FONT
            path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".mp3") ||
                path.endsWith(".ts") -> AdBlockEngine.T_MEDIA
            accept.startsWith("text/html") -> AdBlockEngine.T_SUBDOC
            else -> 0
        }
    }

    private val emptyResponse: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    inner class Client(private val tab: Tab) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            handleScheme(view, request.url)

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = url
            tab.pageHost = hostOf(url)
            tab.blocked = 0
            tab.favicon = favicon
            tab.title = ""
            synchronized(tab.media) { tab.media.clear() }
            if (tab === current) updateMediaBtn()
            if (!docStartSupported) view.evaluateJavascript(Scripts.CONTENT, null)
            if (tab === current) { updateUrlBar(); updateShield(); updateNav() }
        }

        override fun onPageCommitVisible(view: WebView, url: String) {
            if (!docStartSupported) view.evaluateJavascript(Scripts.CONTENT, null)
        }

        override fun onPageFinished(view: WebView, url: String) {
            tab.url = url
            tab.title = view.title ?: tab.title
            if (isHome(url)) injectHome(tab)
            else if (!tab.incognito && url.startsWith("http")) Store.addHistory(url, view.title ?: "")
            if (tab === current) { updateUrlBar(); updateNav(); swipe.isRefreshing = false }
            saveSessionSoon()
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            tab.url = url
            tab.pageHost = hostOf(url)
            if (tab === current) { updateUrlBar(); updateNav() }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            if (u.host == ASSET_HOST) return assetLoader.shouldInterceptRequest(u)
            MediaSniffer.classify(u)?.let { kind ->
                addMedia(tab, u.toString(), kind, request.requestHeaders["Referer"] ?: tab.url)
            }
            if (request.isForMainFrame) return null
            val scheme = u.scheme ?: return null
            if (scheme != "http" && scheme != "https") return null
            if (!Store.adblock) return null
            val page = tab.pageHost
            if (page == ASSET_HOST || Store.isAllowlisted(page)) return null
            val host = u.host?.lowercase() ?: return null
            return try {
                if (FilterManager.engine.shouldBlock(u.toString(), host, page, typeOf(request))) {
                    tab.blocked++
                    Store.addBlocked()
                    if (tab === current) postShield()
                    emptyResponse
                } else null
            } catch (_: Exception) {
                null
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.cancel()
            if (tab === current) toast("Güvenlik sertifikası geçersiz, bağlantı engellendi")
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
            ui.post {
                val url = tab.url
                closeTab(tab)
                if (url.isNotEmpty()) newTab(url, tab.incognito)
                toast("Sayfa çöktü, yeniden açıldı")
            }
            return true
        }
    }

    private fun handleScheme(view: WebView, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in setOf("http", "https", "about", "data", "blob", "javascript", "file")) return false
        if (scheme == "intent") {
            try {
                val i = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                i.addCategory(Intent.CATEGORY_BROWSABLE)
                i.component = null
                i.selector = null
                try {
                    startActivity(i)
                } catch (e: ActivityNotFoundException) {
                    val fallback = i.getStringExtra("browser_fallback_url")
                    if (fallback != null) view.loadUrl(fallback)
                    else i.`package`?.let { pkg ->
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
                        } catch (_: Exception) {
                            toast("Bu bağlantıyı açacak uygulama yok")
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return true
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: Exception) {
            toast("Bu bağlantıyı açacak uygulama yok")
        }
        return true
    }

    inner class Chrome(private val tab: Tab) : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            tab.progress = newProgress
            if (tab === current) { updateProgress(); updateNav() }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            tab.title = title ?: ""
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            tab.favicon = icon
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = showFullscreen(view, callback)

        override fun onHideCustomView() = hideFullscreen()

        override fun onShowFileChooser(
            webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams
        ): Boolean {
            fileCallback?.onReceiveValue(null)
            fileCallback = filePathCallback
            return try {
                fileChooser.launch(fileChooserParams.createIntent())
                true
            } catch (_: Exception) {
                fileCallback = null
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            ui.post { askWebPermission(request) }
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            ui.post { askGeo(origin, callback) }
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture && Store.bool("popup_block", true)) {
                toast("Açılır pencere engellendi")
                return false
            }
            val t = newTab(null, tab.incognito, false, tab)
            val transport = resultMsg.obj as WebView.WebViewTransport
            transport.webView = t.webView
            resultMsg.sendToTarget()
            return true
        }

        override fun onCloseWindow(window: WebView) {
            tabs.firstOrNull { it.webView === window }?.let { closeTab(it) }
        }

        override fun getDefaultVideoPoster(): Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    private fun askWebPermission(request: PermissionRequest) {
        val res = request.resources
        if (res.all { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }) {
            request.grant(res); return
        }
        val wantsCam = res.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        val wantsMic = res.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        if (!wantsCam && !wantsMic) { request.deny(); return }
        val what = listOfNotNull(if (wantsCam) "kamera" else null, if (wantsMic) "mikrofon" else null).joinToString(" ve ")
        val host = request.origin?.host ?: "Bu site"
        MaterialAlertDialogBuilder(this)
            .setTitle("İzin isteği")
            .setMessage("$host $what erişimi istiyor.")
            .setPositiveButton("İzin ver") { _, _ ->
                val need = ArrayList<String>()
                if (wantsCam && !has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA)
                if (wantsMic && !has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
                if (need.isEmpty()) request.grant(res)
                else {
                    pendingWebPermission = request
                    permLauncher.launch(need.toTypedArray())
                }
            }
            .setNegativeButton("Engelle") { _, _ -> request.deny() }
            .setOnCancelListener { request.deny() }
            .show()
    }

    private fun askGeo(origin: String, cb: GeolocationPermissions.Callback) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Konum izni")
            .setMessage("${Uri.parse(origin).host ?: origin} konumunu öğrenmek istiyor.")
            .setPositiveButton("İzin ver") { _, _ ->
                if (has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    cb.invoke(origin, true, false)
                } else {
                    pendingGeo = Pair(origin, cb)
                    permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
            .setNegativeButton("Engelle") { _, _ -> cb.invoke(origin, false, false) }
            .setOnCancelListener { cb.invoke(origin, false, false) }
            .show()
    }

    // =====================================================================
    // Ana sayfa
    // =====================================================================

    private fun injectHome(tab: Tab) {
        val sites = JSONArray()
        Store.topSites().forEach {
            sites.put(JSONObject().put("t", it.title.ifEmpty { hostOf(it.url) }).put("u", it.url))
        }
        val data = JSONObject()
            .put("engine", Store.searchTemplate)
            .put("engineName", Store.engineName)
            .put("sites", sites)
            .put("blocked", Store.totalBlocked.get())
            .put("incognito", tab.incognito)
            .put("dark", isDark())
        tab.webView.evaluateJavascript("window.kalkanInit && window.kalkanInit($data);", null)
    }

    // =====================================================================
    // İndirme & uzun basma
    // =====================================================================

    private fun download(url: String, ua: String?, cd: String?, mime: String?, tab: Tab?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) {
            toast("Bu indirme türü desteklenmiyor")
            return
        }
        if (Build.VERSION.SDK_INT <= 28 && !has(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            pendingAction = { download(url, ua, cd, mime, tab) }
            permLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return
        }
        val name = URLUtil.guessFileName(url, cd, mime)
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setDescription("Kalkan ile indiriliyor")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!mime.isNullOrEmpty()) req.setMimeType(mime)
            CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie", it) }
            req.addRequestHeader("User-Agent", ua ?: mobileUA)
            val ref = tab?.url
            if (ref != null && ref.startsWith("http")) req.addRequestHeader("Referer", ref)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            Snackbar.make(root, "İndiriliyor: $name", Snackbar.LENGTH_LONG)
                .setAction("İndirilenler") { openDownloads() }
                .show()
        } catch (e: Exception) {
            toast("İndirme başlatılamadı: ${e.message}")
        }
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast("İndirilenler açılamadı")
        }
    }

    private fun longPress(tab: Tab): Boolean {
        val r = tab.webView.hitTestResult
        val extra = r.extra ?: return false
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> linkMenu(tab, extra)
            WebView.HitTestResult.IMAGE_TYPE -> imageMenu(tab, extra, null)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val h = Handler(Looper.getMainLooper()) { m ->
                    imageMenu(tab, extra, m.data?.getString("url"))
                    true
                }
                tab.webView.requestFocusNodeHref(h.obtainMessage())
            }
            else -> return false
        }
        return true
    }

    private fun linkMenu(tab: Tab, url: String) {
        val items = arrayOf("Yeni sekmede aç", "Arka planda aç", "Gizli sekmede aç", "Bağlantıyı kopyala", "Paylaş", "Bağlantıyı indir")
        MaterialAlertDialogBuilder(this)
            .setTitle(displayUrl(url))
            .setItems(items) { _, w ->
                when (w) {
                    0 -> newTab(url, tab.incognito, false, tab)
                    1 -> newTab(url, tab.incognito, true, tab)
                    2 -> newTab(url, true)
                    3 -> copy(url)
                    4 -> share(url)
                    5 -> download(url, null, null, null, tab)
                }
            }.show()
    }

    private fun imageMenu(tab: Tab, src: String, link: String?) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (link != null && link != src) {
            labels.add("Bağlantıyı yeni sekmede aç"); actions.add { newTab(link, tab.incognito, false, tab) }
            labels.add("Bağlantıyı kopyala"); actions.add { copy(link) }
        }
        labels.add("Resmi yeni sekmede aç"); actions.add { newTab(src, tab.incognito, false, tab) }
        labels.add("Resmi indir"); actions.add { download(src, null, null, null, tab) }
        labels.add("Resim adresini kopyala"); actions.add { copy(src) }
        labels.add("Resmi paylaş"); actions.add { share(src) }
        MaterialAlertDialogBuilder(this)
            .setTitle(displayUrl(link ?: src))
            .setItems(labels.toTypedArray()) { _, w -> actions[w]() }
            .show()
    }

    // =====================================================================
    // Medya yakalayıcı
    // =====================================================================

    private fun addMedia(tab: Tab, url: String, kind: String, referer: String?) {
        val added = synchronized(tab.media) {
            if (tab.media.containsKey(url) || tab.media.size >= 200) false
            else {
                tab.media[url] = MediaItem(url, kind, referer)
                true
            }
        }
        if (added && tab === current) ui.post { updateMediaBtn() }
    }

    private fun updateMediaBtn() {
        val n = current?.let { synchronized(it.media) { it.media.size } } ?: 0
        mediaBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
        mediaBadge.text = if (n > 99) "99+" else n.toString()
    }

    private fun currentUA(): String = current?.webView?.settings?.userAgentString ?: mobileUA

    private fun ensureStorage(action: () -> Unit) {
        if (Saver.needsPermission(this)) {
            pendingAction = action
            permLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else action()
    }

    private fun mediaMeta(m: MediaItem): String {
        val parts = ArrayList<String>()
        parts.add(if (m.isStream) "${m.kind} akışı" else m.kind)
        if (!m.isStream) m.mime?.substringBefore(';')?.substringAfter('/')?.uppercase()?.let { if (it.length <= 8) parts.add(it) }
        MediaSniffer.formatSize(m.size).takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        parts.add(hostOf(m.url))
        return parts.joinToString(" · ")
    }

    private fun showMedia() {
        val t = current ?: return
        val items = synchronized(t.media) { t.media.values.toList() }
            .sortedWith(compareBy<MediaItem> { if (it.kind == MediaSniffer.HLS) 0 else if (it.kind == MediaSniffer.VIDEO) 1 else 2 })
        if (items.isEmpty()) {
            toast("Bu sayfada medya bulunamadı — videoyu oynatmayı dene")
            return
        }
        val d = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(12), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "Yakalanan medya (${items.size})"
            textSize = 18f
            setTextColor(textColor())
            setPadding(dp(8), 0, 0, dp(2))
        })
        box.addView(TextView(this).apply {
            text = "Dokunarak indir veya harici oynatıcıda aç. Liste, oynattıkça dolar."
            textSize = 12.5f
            setTextColor(mutedColor())
            setPadding(dp(8), 0, dp(8), dp(10))
        })
        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        val ua = currentUA()
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(4), dp(10))
                    setBackgroundResource(R.drawable.bg_tile)
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    }
                }
                val icon = ImageView(parent.context).apply { imageTintList = ColorStateList.valueOf(textColor()) }
                row.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
                val col = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(8), 0)
                }
                val t1 = TextView(parent.context).apply { textSize = 14.5f; maxLines = 1; setTextColor(textColor()) }
                val t2 = TextView(parent.context).apply { textSize = 12f; maxLines = 1; setTextColor(mutedColor()) }
                col.addView(t1); col.addView(t2)
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val dl = ImageButton(parent.context).apply {
                    setImageResource(R.drawable.ic_download)
                    setBackgroundResource(selectableBg())
                    contentDescription = "İndir"
                }
                row.addView(dl, LinearLayout.LayoutParams(dp(44), dp(44)))
                row.tag = arrayOf<View>(icon, t1, t2, dl)
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val m = items[position]
                @Suppress("UNCHECKED_CAST")
                val v = holder.itemView.tag as Array<View>
                (v[0] as ImageView).setImageResource(if (m.kind == MediaSniffer.AUDIO) R.drawable.ic_music else R.drawable.ic_movie)
                (v[1] as TextView).text = m.name
                (v[2] as TextView).text = mediaMeta(m)
                holder.itemView.setOnClickListener { d.dismiss(); mediaActions(m) }
                v[3].setOnClickListener { d.dismiss(); downloadMedia(m) }
                if (!m.probed) MediaSniffer.probe(m, ua) {
                    val idx = items.indexOf(m)
                    if (idx >= 0) notifyItemChanged(idx)
                }
            }
        }
        rv.adapter = adapter
        box.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        d.setContentView(box)
        d.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.65).toInt()
        d.show()
    }

    private fun mediaActions(m: MediaItem) {
        val labels = arrayOf("İndir", "Harici oynatıcıda aç", "Yeni sekmede oynat", "Bağlantıyı kopyala", "Paylaş")
        MaterialAlertDialogBuilder(this)
            .setTitle(m.name)
            .setItems(labels) { _, w ->
                when (w) {
                    0 -> downloadMedia(m)
                    1 -> openExternal(m)
                    2 -> newTab(m.url, current?.incognito ?: false)
                    3 -> copy(m.url)
                    4 -> share(m.url)
                }
            }.show()
    }

    private fun downloadMedia(m: MediaItem) {
        when (m.kind) {
            MediaSniffer.DASH -> toast("DASH akışları indirilemiyor — harici oynatıcıda açabilirsin")
            MediaSniffer.HLS -> ensureStorage {
                if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val title = current?.title?.takeIf { it.isNotBlank() } ?: m.name
                toast("Akış indiriliyor — ilerlemeyi bildirimde görebilirsin")
                HlsDownloader.start(this, m.url, m.referer, currentUA(), title) { msg -> toast(msg) }
            }
            else -> download(m.url, currentUA(), null, m.mime, current)
        }
    }

    private fun openExternal(m: MediaItem) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(m.url), MediaSniffer.mimeOf(m))
        val h = MediaSniffer.headers(m.url, m.referer, currentUA())
        i.putExtra("headers", h.flatMap { listOf(it.key, it.value) }.toTypedArray())
        current?.title?.let { i.putExtra("title", it) }
        try {
            startActivity(Intent.createChooser(i, "Oynatıcı seç"))
        } catch (_: Exception) {
            toast("Uygun oynatıcı yok — VLC veya MX Player kurabilirsin")
        }
    }

    // =====================================================================
    // Kaynak kodu & sayfa kaydetme
    // =====================================================================

    private fun fetchLiveHtml(tab: Tab, cb: (String?) -> Unit) {
        val js = "(function(){var q=String.fromCharCode(34);var d=document.doctype;" +
            "var s=d?'<!DOCTYPE '+d.name+(d.publicId?' PUBLIC '+q+d.publicId+q:'')+(d.systemId?' '+q+d.systemId+q:'')+'>\\n':'';" +
            "return s+document.documentElement.outerHTML;})()"
        tab.webView.evaluateJavascript(js) { r ->
            val html = try {
                if (r == null || r == "null") null else org.json.JSONTokener(r).nextValue() as? String
            } catch (_: Exception) { null }
            cb(html)
        }
    }

    private fun viewSource(tab: Tab) {
        fetchLiveHtml(tab) { html ->
            if (html == null) { toast("Kaynak kodu alınamadı"); return@fetchLiveHtml }
            val ua = tab.webView.settings.userAgentString
            val url = tab.url
            Thread {
                try {
                    java.io.File(SourceActivity.dir(this), SourceActivity.LIVE_FILE).writeText(html)
                    java.io.File(SourceActivity.dir(this), SourceActivity.ORIG_FILE).delete()
                    runOnUiThread {
                        startActivity(Intent(this, SourceActivity::class.java)
                            .putExtra(SourceActivity.EXTRA_URL, url)
                            .putExtra(SourceActivity.EXTRA_UA, ua))
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("Kaynak açılamadı: ${e.message}") }
                }
            }.start()
        }
    }

    private fun savePage(tab: Tab) {
        val name = Saver.sanitize(tab.title.ifBlank { tab.pageHost })
        val items = arrayOf(
            "Web arşivi (.mht) — resimlerle tek dosya",
            "HTML — yalnızca sayfa kodu",
            "PDF — yazdır ekranından"
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("Sayfayı kaydet")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> ensureStorage { saveMht(tab, name) }
                    1 -> ensureStorage { saveHtml(tab, name) }
                    2 -> print(tab)
                }
            }.show()
    }

    private fun saveInBackground(fileName: String, mime: String, write: (java.io.OutputStream) -> Unit, after: () -> Unit = {}) {
        Thread {
            val msg = try {
                Saver.save(this, fileName, mime, write)
                "Kaydedildi: İndirilenler/Kalkan/$fileName"
            } catch (e: Exception) {
                "Kaydedilemedi: ${e.message}"
            } finally {
                after()
            }
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun saveMht(tab: Tab, name: String) {
        val tmp = java.io.File(cacheDir, "page_${System.currentTimeMillis()}.mht")
        tab.webView.saveWebArchive(tmp.path, false) { path ->
            if (path == null) {
                toast("Web arşivi oluşturulamadı — HTML veya PDF dene")
            } else {
                val f = java.io.File(path)
                saveInBackground("$name.mht", "multipart/related", { o -> f.inputStream().use { it.copyTo(o) } }) { f.delete() }
            }
        }
    }

    private fun saveHtml(tab: Tab, name: String) {
        fetchLiveHtml(tab) { html ->
            if (html == null) toast("Sayfa kodu alınamadı")
            else saveInBackground("$name.html", "text/html", { o -> o.write(html.toByteArray()) })
        }
    }

    private fun translate(url: String) {
        loadInCurrent("https://translate.google.com/translate?sl=auto&tl=tr&u=" + java.net.URLEncoder.encode(url, "UTF-8"))
    }


    // =====================================================================
    // Menü
    // =====================================================================

    private fun showMenu() {
        val t = current
        val d = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(20))
        }
        if (t != null && !isHome(t.url)) {
            box.addView(TextView(this).apply {
                text = t.title.ifEmpty { displayUrl(t.url) }
                textSize = 15f
                setTextColor(textColor())
                maxLines = 1
                setPadding(dp(8), dp(4), dp(8), 0)
            })
            box.addView(TextView(this).apply {
                text = displayUrl(t.url)
                textSize = 12f
                setTextColor(mutedColor())
                maxLines = 1
                setPadding(dp(8), 0, dp(8), dp(8))
            })
        }
        val grid = GridLayout(this).apply { columnCount = 4 }
        box.addView(grid)

        fun tile(icon: Int, label: String, badge: Int = 0, action: () -> Unit) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(12), dp(2), dp(10))
                setBackgroundResource(selectableBg())
                isClickable = true
                setOnClickListener { d.dismiss(); action() }
            }
            val iconBox = FrameLayout(this)
            iconBox.addView(ImageView(this).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(textColor())
            }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            if (badge > 0) {
                iconBox.addView(TextView(this).apply {
                    text = if (badge > 99) "99+" else badge.toString()
                    textSize = 9.5f
                    setTextColor(Color.parseColor("#1A1205"))
                    setBackgroundResource(R.drawable.bg_badge)
                    gravity = Gravity.CENTER
                    minWidth = dp(15)
                    setPadding(dp(3), 0, dp(3), 0)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(15), Gravity.END or Gravity.TOP))
            }
            v.addView(iconBox, LinearLayout.LayoutParams(dp(40), dp(30)))
            v.addView(TextView(this).apply {
                text = label; textSize = 11.5f; gravity = Gravity.CENTER; maxLines = 2
                setTextColor(textColor()); setPadding(0, dp(6), 0, 0)
            })
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)
            ).apply { width = 0 }
            grid.addView(v, lp)
        }

        val url = t?.url ?: ""
        val isPage = t != null && url.startsWith("http") && !isHome(url)
        val bookmarked = isPage && Store.isBookmarked(url)
        val mediaCount = t?.let { synchronized(it.media) { it.media.size } } ?: 0

        tile(R.drawable.ic_add, "Yeni sekme") { newTab(HOME_URL, false) }
        tile(R.drawable.ic_incognito, "Gizli sekme") { newTab(HOME_URL, true) }
        tile(if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border, if (bookmarked) "Yer iminden çıkar" else "Yer imi ekle") {
            if (!isPage) toast("Bu sayfa eklenemez")
            else toast(if (Store.toggleBookmark(url, t!!.title)) "Yer imlerine eklendi" else "Yer iminden çıkarıldı")
        }
        tile(R.drawable.ic_bookmark, "Yer imleri") { openLibrary(LibraryActivity.MODE_BOOKMARKS) }

        tile(R.drawable.ic_history, "Geçmiş") { openLibrary(LibraryActivity.MODE_HISTORY) }
        tile(R.drawable.ic_download, "İndirilenler") { openDownloads() }
        tile(R.drawable.ic_movie, "Medya", mediaCount) { showMedia() }
        tile(R.drawable.ic_save, "Sayfayı kaydet") { if (isPage) savePage(t!!) else toast("Bu sayfa kaydedilemez") }

        tile(R.drawable.ic_code, "Kaynak kodu") { if (isPage) viewSource(t!!) else toast("Önce bir site aç") }
        tile(R.drawable.ic_search, "Sayfada bul") { if (isPage) openFind() }
        tile(R.drawable.ic_share, "Paylaş") { if (isPage) share(url, t!!.title) }
        tile(R.drawable.ic_translate, "Çevir") { if (isPage) translate(url) }

        tile(if (t?.desktop == true) R.drawable.ic_phone else R.drawable.ic_desktop,
            if (t?.desktop == true) "Mobil site" else "Masaüstü site") { t?.let { toggleDesktop(it) } }
        tile(R.drawable.ic_print, "Yazdır / PDF") { if (isPage) print(t!!) }
        tile(R.drawable.ic_copy, "Adresi kopyala") { if (isPage) copy(url) }
        tile(R.drawable.ic_undo, "Kapatılanı aç") {
            val last = closedUrls.removeLastOrNull()
            if (last != null) newTab(last, false) else toast("Kapatılan sekme yok")
        }

        tile(R.drawable.ic_vpn, "VPN & Koruma") { startActivity(Intent(this, VpnActivity::class.java)) }
        tile(R.drawable.ic_settings, "Ayarlar") { startActivity(Intent(this, SettingsActivity::class.java)) }
        tile(R.drawable.ic_sweep, "Gizlileri kapat") {
            tabs.filter { it.incognito }.forEach { closeTab(it) }
            toast("Gizli sekmeler kapatıldı")
        }
        tile(R.drawable.ic_exit, "Çıkış") {
            saveSession()
            tabs.filter { it.incognito }.forEach { closeTab(it) }
            finishAndRemoveTask()
        }

        d.setContentView(box)
        d.show()
    }

    private fun openLibrary(mode: String) {
        startActivity(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, mode))
    }

    private fun toggleDesktop(tab: Tab) {
        tab.desktop = !tab.desktop
        tab.webView.settings.userAgentString = if (tab.desktop) desktopUA else mobileUA
        tab.webView.reload()
        toast(if (tab.desktop) "Masaüstü sitesi istendi" else "Mobil site istendi")
    }

    private fun print(tab: Tab) {
        val pm = getSystemService(PRINT_SERVICE) as PrintManager
        val name = tab.title.ifEmpty { "Kalkan sayfası" }
        pm.print(name, tab.webView.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
    }

    // =====================================================================
    // Sekme listesi
    // =====================================================================

    private fun showTabs() {
        val d = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            textSize = 18f
            setTextColor(textColor())
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun hb(label: String, action: () -> Unit) = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            setOnClickListener { action() }
        }
        header.addView(hb("+ Sekme") { d.dismiss(); newTab(HOME_URL, false) })
        header.addView(hb("+ Gizli") { d.dismiss(); newTab(HOME_URL, true) })
        header.addView(hb("Tümünü kapat") { d.dismiss(); closeAllTabs() })
        box.addView(header)

        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = tabs.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(10), dp(4), dp(10))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(6)
                    }
                }
                val icon = ImageView(parent.context).apply { id = View.generateViewId() }
                row.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
                val col = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), 0, dp(8), 0)
                }
                val t1 = TextView(parent.context).apply { textSize = 15f; maxLines = 1; setTextColor(textColor()) }
                val t2 = TextView(parent.context).apply { textSize = 12f; maxLines = 1; setTextColor(mutedColor()) }
                col.addView(t1); col.addView(t2)
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val close = ImageButton(parent.context).apply {
                    setImageResource(R.drawable.ic_close)
                    setBackgroundResource(selectableBg())
                    contentDescription = "Kapat"
                }
                row.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
                row.tag = arrayOf<View>(icon, t1, t2, close)
                return object : RecyclerView.ViewHolder(row) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val tab = tabs[position]
                @Suppress("UNCHECKED_CAST")
                val v = holder.itemView.tag as Array<View>
                val icon = v[0] as ImageView
                if (tab.favicon != null) icon.setImageBitmap(tab.favicon)
                else icon.setImageResource(if (tab.incognito) R.drawable.ic_lock else R.drawable.ic_search)
                val home = isHome(tab.url) || tab.url.isEmpty()
                (v[1] as TextView).text = (if (tab.incognito) "Gizli · " else "") +
                    (if (home) "Yeni sekme" else tab.title.ifEmpty { displayUrl(tab.url) })
                (v[2] as TextView).text = if (home) "Kalkan" else displayUrl(tab.url)
                holder.itemView.setBackgroundResource(if (tab === current) R.drawable.bg_card_active else R.drawable.bg_tile)
                holder.itemView.setOnClickListener { d.dismiss(); switchTo(tab) }
                v[3].setOnClickListener {
                    closeTab(tab)
                    title.text = "Sekmeler (${tabs.size})"
                    notifyDataSetChanged()
                }
            }
        }
        rv.adapter = adapter
        title.text = "Sekmeler (${tabs.size})"
        box.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        d.setContentView(box)
        d.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.7).toInt()
        d.behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        d.show()
        val idx = tabs.indexOf(current)
        if (idx >= 0) rv.scrollToPosition(idx)
    }

    // =====================================================================
    // Kalkan paneli
    // =====================================================================

    private fun showShield() {
        val t = current
        val d = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }
        fun label(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(top), 0, 0)
        }
        val brand = ContextCompat.getColor(this, R.color.brand)
        val host = t?.pageHost?.takeIf { it.isNotEmpty() && it != ASSET_HOST }
        box.addView(label("Kalkan Koruması", 20f, textColor(), true))
        if (host != null) box.addView(label(host, 14f, mutedColor(), top = 2))
        box.addView(label((t?.blocked ?: 0).toString(), 44f, brand, true, 12))
        box.addView(label("bu sayfada engellenen reklam ve izleyici", 14f, mutedColor()))
        box.addView(label("Toplam: ${"%,d".format(Store.totalBlocked.get())} engellendi", 15f, textColor(), top = 12))

        if (host != null) {
            box.addView(MaterialSwitch(this).apply {
                text = "Bu sitede koruma açık"
                isChecked = !Store.isAllowlisted(host)
                setPadding(0, dp(16), 0, 0)
                setOnCheckedChangeListener { _, _ ->
                    Store.toggleAllowlist(host)
                    t?.webView?.reload()
                    updateShield()
                }
            })
        }
        box.addView(MaterialSwitch(this).apply {
            text = "Reklam engelleme (tüm siteler)"
            isChecked = Store.adblock
            setPadding(0, dp(8), 0, 0)
            setOnCheckedChangeListener { _, c ->
                Store.prefs.edit().putBoolean("adblock", c).apply()
                updateShield()
                current?.webView?.reload()
            }
        })

        val e = FilterManager.engine
        val last = FilterManager.lastUpdate
        val info = buildString {
            append("Kurallar: ${"%,d".format(e.networkRuleCount)} ağ · ${"%,d".format(e.cosmeticRuleCount)} kozmetik\n")
            append(
                when {
                    FilterManager.updating -> "Listeler güncelleniyor…"
                    last == 0L -> "Listeler henüz indirilmedi (yerleşik liste kullanılıyor)"
                    else -> "Son güncelleme: " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(last))
                }
            )
        }
        box.addView(label(info, 12.5f, mutedColor(), top = 14))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        row.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Listeleri güncelle"
            setOnClickListener {
                toast("Filtre listeleri indiriliyor…")
                d.dismiss()
                FilterManager.requestUpdate(this@MainActivity) { n ->
                    toast(if (n > 0) "$n liste güncellendi" else "Güncelleme başarısız — internet bağlantını kontrol et")
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(MaterialButton(this).apply {
            text = "VPN"
            setOnClickListener { d.dismiss(); startActivity(Intent(this@MainActivity, VpnActivity::class.java)) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)

        d.setContentView(box)
        d.show()
    }

    // =====================================================================
    // Oturum & intent
    // =====================================================================

    private val saveRunnable = Runnable { saveSession() }

    private fun saveSessionSoon() {
        ui.removeCallbacks(saveRunnable)
        ui.postDelayed(saveRunnable, 1500)
    }

    private fun saveSession() {
        val normal = tabs.filter { !it.incognito }
        val arr = JSONArray()
        normal.forEach { arr.put(it.pendingUrl ?: it.url.ifEmpty { HOME_URL }) }
        Store.prefs.edit()
            .putString("session", arr.toString())
            .putInt("session_index", normal.indexOf(current).coerceAtLeast(0))
            .apply()
    }

    private fun restoreSession(): Boolean {
        if (!Store.bool("restore_tabs", true)) return false
        return try {
            val arr = JSONArray(Store.prefs.getString("session", "[]"))
            if (arr.length() == 0) return false
            val idx = Store.prefs.getInt("session_index", 0).coerceIn(0, arr.length() - 1)
            for (i in 0 until arr.length()) newTab(arr.getString(i), false, background = true, quiet = true)
            switchTo(tabs[idx])
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val i = intent ?: return false
        val url: String? = when (i.action) {
            Intent.ACTION_VIEW -> i.dataString
            Intent.ACTION_WEB_SEARCH -> i.getStringExtra(SearchManager.QUERY)?.let { resolve(it) }
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { txt ->
                val m = Patterns.WEB_URL.matcher(txt)
                if (m.find()) resolve(m.group()) else resolve(txt)
            }
            else -> i.getStringExtra(EXTRA_OPEN_URL)
        }
        if (url.isNullOrBlank()) return false
        i.action = null
        i.removeExtra(EXTRA_OPEN_URL)
        newTab(url, false)
        return true
    }

    // =====================================================================
    // Adres önerileri
    // =====================================================================

    inner class SuggestAdapter : ArrayAdapter<Entry>(this, android.R.layout.simple_list_item_2, android.R.id.text1) {
        private var items: List<Entry> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int): Entry = items[position]

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val e = items[position]
            v.findViewById<TextView>(android.R.id.text1).apply {
                text = e.title.ifEmpty { displayUrl(e.url) }
                maxLines = 1
            }
            v.findViewById<TextView>(android.R.id.text2).apply {
                text = if (e.time == -1L) Store.engineName else displayUrl(e.url)
                maxLines = 1
            }
            return v
        }

        override fun getFilter(): Filter = object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val q = constraint?.toString()?.trim() ?: ""
                val list = ArrayList<Entry>()
                if (q.isNotEmpty()) {
                    list.add(Entry(resolve(q), q, -1L))
                    list.addAll(Store.suggest(q).filter { it.url != list[0].url })
                }
                return FilterResults().apply { values = list; count = list.size }
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                @Suppress("UNCHECKED_CAST")
                items = (results?.values as? List<Entry>) ?: emptyList()
                if (items.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
            }

            override fun convertResultToString(resultValue: Any?): CharSequence =
                (resultValue as? Entry)?.url ?: ""
        }
    }
}
