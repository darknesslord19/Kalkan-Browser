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
        internal const val INCOGNITO_PROFILE = "kalkan_incognito"
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
        val res = LinkedHashMap<String, MediaItem>()
        @Volatile var mediaCount = 0
        @Volatile var autoAllowUntil = 0L
        @Volatile var rate = 1f
        val console = ArrayDeque<ConsoleLine>()
    }

    class ConsoleLine(val level: Char, val text: String, val src: String, val time: Long = System.currentTimeMillis())

    internal val tabs = ArrayList<Tab>()
    internal var current: Tab? = null
    internal var nextId = 1L
    internal val closedUrls = ArrayList<String>()

    internal lateinit var root: FrameLayout
    internal lateinit var toolbar: LinearLayout
    internal lateinit var findBar: LinearLayout
    internal lateinit var findInput: EditText
    internal lateinit var findCount: TextView
    internal lateinit var urlBar: AutoCompleteTextView
    internal lateinit var reloadBtn: ImageButton
    internal lateinit var shieldIcon: ImageView
    internal lateinit var shieldBadge: TextView
    internal lateinit var progress: LinearProgressIndicator
    internal lateinit var swipe: SwipeRefreshLayout
    internal lateinit var webContainer: FrameLayout
    internal lateinit var backBtn: ImageButton
    internal lateinit var forwardBtn: ImageButton
    internal lateinit var tabsCount: TextView
    internal lateinit var fullscreen: FrameLayout
    internal lateinit var mediaBtn: View
    internal lateinit var mediaBadge: TextView
    internal lateinit var preventBtn: ImageButton

    internal lateinit var assetLoader: WebViewAssetLoader
    internal val ui = Handler(Looper.getMainLooper())
    @Volatile private var shieldPosted = false

    internal val docStartSupported by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
    internal val profilesSupported by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }

    internal val mobileUA by lazy {
        WebSettings.getDefaultUserAgent(this)
            .replace("; wv", "")
            .replace(Regex("Version/\\d+\\.\\d+ "), "")
    }
    internal val desktopUA by lazy {
        val chrome = Regex("Chrome/([\\d.]+)").find(mobileUA)?.groupValues?.get(1) ?: "130.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    internal var customView: View? = null
    internal var customCallback: WebChromeClient.CustomViewCallback? = null
    internal var fileCallback: ValueCallback<Array<Uri>>? = null
    internal var pendingWebPermission: PermissionRequest? = null
    internal var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    internal var pendingAction: (() -> Unit)? = null

    internal val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCallback = null
    }

    internal val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
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

    internal val filterListener: () -> Unit = { updateShield() }

    internal val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    internal val qrLauncher = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        r.contents?.let { onQrResult(it) }
    }

    internal val backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBackup(uri)
    }

    internal lateinit var topBar: View
    internal lateinit var bottomBar: View
    internal var locked = false
    internal var internalNav = false
    internal var lockOverlay: View? = null
    internal var pipCss = false
    internal var barsHidden = false
    internal var scrollAcc = 0

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
        setupGestures()
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
        internalNav = false
        applySettingsToAll()
        current?.webView?.onResume()
        updateAll()
        if (locked && needsLock(current)) showLock()
    }

    override fun onPause() {
        super.onPause()
        saveSession()
        Store.persistCounters()
        CookieManager.getInstance().flush()
        if (!Store.bool("bg_play", true) && !isInPictureInPictureMode) current?.webView?.onPause()
    }

    override fun onStop() {
        super.onStop()
        if (!internalNav && lockMode() != "off") locked = true
    }

    override fun startActivity(intent: Intent?) {
        internalNav = true
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent?, options: Bundle?) {
        internalNav = true
        super.startActivity(intent, options)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Store.bool("auto_pip", true) && customView != null && !internalNav) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        onPipChanged(isInPictureInPictureMode)
    }

    override fun onDestroy() {
        if (isFinishing && Store.bool("clear_on_exit", false)) clearAllData()
        FilterManager.removeListener(filterListener)
        for (t in tabs) {
            try { t.webView.destroy() } catch (_: Exception) {}
        }
        tabs.clear()
        super.onDestroy()
    }

    internal fun bindViews() {
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
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        preventBtn = findViewById(R.id.preventBtn)
    }

    // =====================================================================
    // Araç çubuğu
    // =====================================================================

    internal fun setupToolbar() {
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
        preventBtn.setOnClickListener { showPreventMenu() }
        preventBtn.setOnLongClickListener { cyclePrevent(); true }
        backBtn.setOnClickListener { current?.webView?.let { if (it.canGoBack()) it.goBack() } }
        forwardBtn.setOnClickListener { current?.webView?.let { if (it.canGoForward()) it.goForward() } }
        findViewById<View>(R.id.homeBtn).setOnClickListener { loadInCurrent(HOME_URL) }
        findViewById<View>(R.id.tabsBtn).setOnClickListener { showTabs() }
        findViewById<View>(R.id.tabsBtn).setOnLongClickListener { newTab(HOME_URL, false); true }
        findViewById<View>(R.id.menuBtn).setOnClickListener { showMenu() }
        findViewById<View>(R.id.menuBtn).setOnLongClickListener { showTools(); true }

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

    internal fun setupBack() {
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

    internal fun go(input: String) {
        val text = input.trim()
        if (text.isEmpty()) return
        loadInCurrent(resolve(text))
    }

    internal fun loadInCurrent(url: String) {
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
    internal fun newTab(url: String?, incognito: Boolean, background: Boolean = false, parent: Tab? = null, quiet: Boolean = false): Tab {
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
    internal fun configure(tab: Tab) {
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
        tab.desktop = Store.bool("desktop_default", false)
        s.userAgentString = uaFor(tab)
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
        wv.setOnScrollChangeListener { _, _, y, _, oldY -> if (tab === current) onPageScroll(y, y - oldY) }
        wv.setFindListener { active, num, done ->
            if (done && tab === current) findCount.text = if (num == 0) "0/0" else "${active + 1}/$num"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    internal fun applySettings(tab: Tab) {
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

    internal fun applySettingsToAll() {
        tabs.forEach { applySettings(it) }
        swipe.isEnabled = Store.bool("pull_refresh", true)
    }

    internal fun switchTo(tab: Tab) {
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
        if (barsHidden) showBars()
        updateAll()
        if (locked && needsLock(tab)) showLock()
        saveSessionSoon()
    }

    internal fun closeTab(tab: Tab) {
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

    internal fun closeAllTabs() {
        for (t in tabs.toList()) {
            webContainer.removeView(t.webView)
            try { t.webView.destroy() } catch (_: Exception) {}
        }
        tabs.clear()
        current = null
        ui.postDelayed({ clearIncognito() }, 500)
        newTab(HOME_URL, false)
    }

    internal fun clearIncognito() {
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

    internal fun updateAll() {
        updateUrlBar(); updateNav(); updateProgress(); updateShield(); updateTabCount(); updateMediaBtn(); updatePreventBtn()
    }

    internal fun isHome(url: String) = url.startsWith(HOME_URL)

    internal fun hostOf(url: String): String = try {
        Uri.parse(url).host?.lowercase() ?: ""
    } catch (_: Exception) { "" }

    internal fun displayUrl(url: String): String {
        var u = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (u.endsWith("/")) u = u.dropLast(1)
        return u
    }

    internal fun updateUrlBar() {
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

    internal fun updateNav() {
        val wv = current?.webView ?: return
        backBtn.alpha = if (wv.canGoBack()) 1f else 0.35f
        forwardBtn.alpha = if (wv.canGoForward()) 1f else 0.35f
    }

    internal fun updateProgress() {
        val t = current ?: return
        val loading = t.progress < 100
        progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        progress.setProgressCompat(t.progress, true)
        reloadBtn.setImageResource(if (loading) R.drawable.ic_close else R.drawable.ic_refresh)
        if (!loading) swipe.isRefreshing = false
    }

    internal fun protectionActive(t: Tab?): Boolean =
        Store.adblock && (t == null || !Store.isAllowlisted(t.pageHost))

    internal fun updateShield() {
        val t = current
        val on = protectionActive(t)
        shieldIcon.setImageResource(if (on) R.drawable.ic_shield else R.drawable.ic_shield_off)
        val n = t?.blocked ?: 0
        if (on && n > 0) {
            shieldBadge.visibility = View.VISIBLE
            shieldBadge.text = if (n > 99) "99+" else n.toString()
        } else shieldBadge.visibility = View.GONE
    }

    internal fun postShield() {
        if (shieldPosted) return
        shieldPosted = true
        ui.postDelayed({ shieldPosted = false; updateShield() }, 250)
    }

    internal fun updateTabCount() {
        tabsCount.text = if (tabs.size > 99) ":D" else tabs.size.toString()
    }

    internal fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    internal fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    internal fun isDark() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    internal fun has(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    internal fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlBar.windowToken, 0)
    }

    internal fun showKeyboard(v: View) {
        v.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
    }

    internal fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kalkan", text))
        if (Build.VERSION.SDK_INT < 33) toast("Kopyalandı")
    }

    internal fun share(text: String, title: String = "") {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        if (title.isNotEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title)
        startActivity(Intent.createChooser(i, "Paylaş"))
    }

    internal fun selectableBg(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    internal fun textColor() = ContextCompat.getColor(this, R.color.onBar)
    internal fun mutedColor() = ContextCompat.getColor(this, R.color.muted)

    // =====================================================================
    // Bul
    // =====================================================================

    internal fun openFind() {
        toolbar.visibility = View.GONE
        findBar.visibility = View.VISIBLE
        findInput.setText("")
        findCount.text = ""
        showKeyboard(findInput)
    }

    internal fun closeFind() {
        current?.webView?.clearMatches()
        findBar.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(findInput.windowToken, 0)
    }

    // =====================================================================
    // Tam ekran video
    // =====================================================================

    internal fun showFullscreen(view: View, cb: WebChromeClient.CustomViewCallback) {
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

    internal fun hideFullscreen() {
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
            val o = JSONObject().put("on", on).put("bg", Store.bool("bg_play", true))
                .put("skip", Store.bool("auto_skip", true) && page != ASSET_HOST)
            if (page != ASSET_HOST) {
                val hide = Store.rulesFor("hide", page)
                if (hide.isNotEmpty()) o.put("hide", hide.joinToString("") { "$it{display:none!important}" })
                val skip = Store.rulesFor("skip", page)
                if (skip.isNotEmpty()) o.put("skipSel", JSONArray(skip))
            }
            return o.toString()
        }

        @JavascriptInterface
        fun rate(): Float = tab.rate

        @JavascriptInterface
        fun picked(mode: String, selector: String, label: String) {
            if (selector.isBlank()) return
            if (mode == "teach") tab.autoAllowUntil = System.currentTimeMillis() + 5000
            ui.post { onPicked(tab, mode, selector, label) }
        }

        @JavascriptInterface
        fun isAd(url: String): Boolean = try {
            val h = Uri.parse(url).host?.lowercase() ?: ""
            h.isNotEmpty() && (FilterManager.engine.isDomainBlocked(h) ||
                FilterManager.engine.shouldBlock(url, h, tab.pageHost, 0))
        } catch (_: Exception) { false }

        @JavascriptInterface
        fun autoClick(target: String, label: String) {
            tab.autoAllowUntil = System.currentTimeMillis() + 5000
            ui.post { if (tab === current) toast("Otomatik geçildi: \u201C${label.take(30)}\u201D") }
        }

        @JavascriptInterface
        fun media(url: String, tag: String, referer: String) {
            if (!url.startsWith("http")) return
            val kind = (try { MediaSniffer.classify(Uri.parse(url)) } catch (_: Exception) { null })
                ?: if (tag == "audio") MediaSniffer.AUDIO else MediaSniffer.VIDEO
            addResource(tab, url, kind, MediaSniffer.CAT_MEDIA, referer)
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

    internal fun typeOf(req: WebResourceRequest): Int {
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

    internal val emptyResponse: WebResourceResponse
        get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    inner class Client(private val tab: Tab) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            val scheme = u.scheme?.lowercase()
            if (request.isForMainFrame && shouldPrevent(tab, request)) {
                val target = u.toString()
                if (tab === current) {
                    Snackbar.make(root, "Yükleme engellendi: ${displayUrl(target)}", Snackbar.LENGTH_LONG)
                        .setAction("Yine de aç") { tab.webView.loadUrl(target) }
                        .show()
                }
                return true
            }
            return handleScheme(view, u)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.url = url
            tab.pageHost = hostOf(url)
            tab.blocked = 0
            tab.favicon = favicon
            tab.title = ""
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
            if (!isHome(url) && url.startsWith("http")) runUserScripts(tab)
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
            val rec = try { recordResource(tab, request) } catch (_: Exception) { null }
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
                    rec?.blocked = true
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

    internal fun handleScheme(view: WebView, uri: Uri): Boolean {
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

        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
            val lvl = when (m.messageLevel()) {
                android.webkit.ConsoleMessage.MessageLevel.ERROR -> 'E'
                android.webkit.ConsoleMessage.MessageLevel.WARNING -> 'W'
                android.webkit.ConsoleMessage.MessageLevel.DEBUG -> 'D'
                android.webkit.ConsoleMessage.MessageLevel.TIP -> 'I'
                else -> 'L'
            }
            val src = (m.sourceId() ?: "").substringAfterLast('/').substringBefore('?').take(40)
            addConsole(tab, lvl, m.message() ?: "", if (src.isNotEmpty()) "$src:${m.lineNumber()}" else "")
            return true
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
                internalNav = true
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
            if (preventMode() == "on" && System.currentTimeMillis() > tab.autoAllowUntil) {
                toast("Yükleme engeli açık: yeni pencere engellendi")
                return false
            }
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

    internal fun askWebPermission(request: PermissionRequest) {
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

    internal fun askGeo(origin: String, cb: GeolocationPermissions.Callback) {
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

    internal fun injectHome(tab: Tab) {
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

    internal fun download(url: String, ua: String?, cd: String?, mime: String?, tab: Tab?) {
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
        if (Store.bool("internal_dl", true)) {
            if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            DownloadEngine.add(url, Saver.sanitize(name), mime, tab?.url, ua ?: uaFor(tab))
            Snackbar.make(root, "İndiriliyor: $name", Snackbar.LENGTH_LONG)
                .setAction("Yönetici") { openDownloads() }
                .show()
            return
        }
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
                .setAction("İndirilenler") { openSystemDownloads() }
                .show()
        } catch (e: Exception) {
            toast("İndirme başlatılamadı: ${e.message}")
        }
    }

    internal fun openDownloads() {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    internal fun openSystemDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            toast("İndirilenler açılamadı")
        }
    }

    internal fun longPress(tab: Tab): Boolean {
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

    internal fun linkMenu(tab: Tab, url: String) {
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

    internal fun imageMenu(tab: Tab, src: String, link: String?) {
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
    // Media Sniffer — sayfanın yüklediği tüm kaynaklar
    // =====================================================================

    internal fun recordResource(tab: Tab, request: WebResourceRequest): MediaItem? {
        val u = request.url
        val scheme = u.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (request.isForMainFrame) {
            synchronized(tab.res) { tab.res.clear(); tab.mediaCount = 0 }
            if (tab === current) ui.post { updateMediaBtn() }
        }
        val accept = request.requestHeaders["Accept"] ?: request.requestHeaders["accept"] ?: ""
        val (label, cat) = MediaSniffer.describe(u, accept, request.isForMainFrame)
        val referer = request.requestHeaders["Referer"] ?: tab.url
        val item = addResource(tab, u.toString(), label, cat, referer)
        item.method = request.method ?: "GET"
        item.reqHeaders = HashMap(request.requestHeaders)
        return item
    }

    internal fun addResource(tab: Tab, url: String, kind: String, cat: Int, referer: String?): MediaItem {
        var newMedia = false
        val item = synchronized(tab.res) {
            val existing = tab.res[url]
            if (existing != null && cat == MediaSniffer.CAT_MEDIA && !existing.isMedia) tab.res.remove(url)
            if (existing != null && tab.res.containsKey(url)) {
                existing.time = System.currentTimeMillis()
                existing
            } else {
                if (tab.res.size >= 1500) {
                    val it = tab.res.entries.iterator()
                    val first = it.next().value
                    it.remove()
                    if (first.isMedia) tab.mediaCount--
                }
                val m = MediaItem(url, kind, referer, cat)
                tab.res[url] = m
                if (m.isMedia) { tab.mediaCount++; newMedia = true }
                m
            }
        }
        if (newMedia && tab === current) ui.post { updateMediaBtn() }
        return item
    }

    internal fun preventMode(): String = Store.str("prevent_mode", "auto")

    internal fun preventName(m: String) = when (m) {
        "on" -> "Tam engel"
        "off" -> "Kapalı"
        else -> "Otomatik"
    }

    /** Ana çerçeve geçişi engellenmeli mi? */
    internal fun shouldPrevent(tab: Tab, request: WebResourceRequest): Boolean {
        val u = request.url
        val scheme = u.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https" && scheme != "intent" && scheme != "market") return false
        if (System.currentTimeMillis() < tab.autoAllowUntil) return false
        return when (preventMode()) {
            "on" -> true
            "auto" -> {
                if (request.hasGesture() || request.isRedirect) return false
                if (scheme == "intent" || scheme == "market") return true
                val page = tab.pageHost
                if (page.isEmpty() || page == ASSET_HOST) return false
                val target = u.host?.lowercase() ?: return false
                AdBlockEngine.baseDomain(page) != AdBlockEngine.baseDomain(target)
            }
            else -> false
        }
    }

    internal fun setPreventMode(m: String) {
        Store.prefs.edit().putString("prevent_mode", m).apply()
        if (m == "on") current?.webView?.stopLoading()
        toast(when (m) {
            "on" -> "Tam engel: sayfa hiçbir yere geçemez"
            "auto" -> "Otomatik: istenmeyen yönlendirmeler engellenir"
            else -> "Sayfa yükleme engeli kapalı"
        })
        updatePreventBtn()
    }

    internal fun cyclePrevent() {
        setPreventMode(when (preventMode()) { "off" -> "auto"; "auto" -> "on"; else -> "off" })
    }

    internal fun showPreventMenu() {
        val pm = android.widget.PopupMenu(this, preventBtn)
        val m = preventMode()
        pm.menu.add(1, 1, 0, "Otomatik — istenmeyen yönlendirmeleri engelle").isChecked = m == "auto"
        pm.menu.add(1, 2, 1, "Tam engel — hiçbir geçişe izin verme").isChecked = m == "on"
        pm.menu.add(1, 3, 2, "Kapalı").isChecked = m == "off"
        pm.menu.setGroupCheckable(1, true, true)
        pm.menu.add(2, 4, 3, "Atla / Devam düğmelerine otomatik bas").apply {
            isCheckable = true
            isChecked = Store.bool("auto_skip", true)
        }
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> setPreventMode("auto")
                2 -> setPreventMode("on")
                3 -> setPreventMode("off")
                4 -> {
                    val now = !Store.bool("auto_skip", true)
                    Store.prefs.edit().putBoolean("auto_skip", now).apply()
                    toast(if (now) "Otomatik atlama açık (sayfayı yenileyince başlar)" else "Otomatik atlama kapalı")
                }
            }
            true
        }
        pm.show()
    }

    internal fun updatePreventBtn() {
        val m = preventMode()
        val color = when (m) {
            "on" -> Color.parseColor("#E5484D")
            "auto" -> ContextCompat.getColor(this, R.color.brand)
            else -> mutedColor()
        }
        preventBtn.imageTintList = ColorStateList.valueOf(color)
        preventBtn.contentDescription = "Sayfa yükleme engeli: " + preventName(m)
    }

    internal fun updateMediaBtn() {
        val n = current?.mediaCount ?: 0
        mediaBtn.visibility = if (n > 0) View.VISIBLE else View.GONE
        mediaBadge.text = if (n > 99) "99+" else n.toString()
    }

    internal fun currentUA(): String = current?.webView?.settings?.userAgentString ?: mobileUA

    internal fun ensureStorage(action: () -> Unit) {
        if (Saver.needsPermission(this)) {
            pendingAction = action
            permLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        } else action()
    }

    internal fun styledUrl(url: String): CharSequence {
        val muted = mutedColor()
        val strong = textColor()
        val schemeEnd = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val hostEnd = url.indexOf('/', schemeEnd).let { if (it < 0) url.length else it }
        val sp = android.text.SpannableString(url)
        sp.setSpan(android.text.style.ForegroundColorSpan(muted), 0, url.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (hostEnd > schemeEnd) {
            sp.setSpan(android.text.style.ForegroundColorSpan(strong), schemeEnd, hostEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
    }

    internal fun showMedia() {
        val t = current ?: return
        var filter = if (t.mediaCount > 0) MediaSniffer.CAT_MEDIA else MediaSniffer.CAT_ALL
        var query = ""
        val d = BottomSheetDialog(this)
        val ua = currentUA()
        val brand = ContextCompat.getColor(this, R.color.brand)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        // Başlık satırı
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), 0, dp(10), dp(6))
        }
        header.addView(TextView(this).apply {
            text = "Media Sniffer"
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(textColor())
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun headBtn(icon: Int, desc: String) = ImageButton(this).apply {
            setImageResource(icon)
            setBackgroundResource(selectableBg())
            contentDescription = desc
            imageTintList = ColorStateList.valueOf(textColor())
        }
        val searchBtn = headBtn(R.drawable.ic_search, "Ara")
        val mediaOnlyBtn = headBtn(R.drawable.ic_movie, "Sadece medya")
        val clearBtn = headBtn(R.drawable.ic_delete, "Listeyi temizle")
        header.addView(searchBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        header.addView(mediaOnlyBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        header.addView(clearBtn, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(header)

        val search = EditText(this).apply {
            hint = "Adreste ara (örn. m3u8, .js, cdn)"
            setSingleLine()
            textSize = 14f
            setBackgroundResource(R.drawable.bg_url)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setTextColor(textColor())
            setHintTextColor(mutedColor())
            visibility = View.GONE
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16); marginEnd = dp(16); bottomMargin = dp(6)
        })

        // Kategori çipleri
        val chipScroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = com.google.android.material.chip.ChipGroup(this).apply {
            isSingleLine = true
            setPadding(dp(16), 0, dp(16), 0)
        }
        chipScroll.addView(chips)
        root.addView(chipScroll)

        root.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.field)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(6) })

        val empty = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(mutedColor())
            setPadding(dp(24), dp(40), dp(24), dp(40))
            visibility = View.GONE
        }
        root.addView(empty)

        val rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        rv.clipToPadding = false
        rv.setPadding(dp(16), dp(12), dp(16), dp(24))
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.72).toInt()))

        var items: List<MediaItem> = emptyList()

        fun snapshot(): List<MediaItem> = synchronized(t.res) { t.res.values.toList() }

        fun matches(m: MediaItem): Boolean {
            val catOk = when (filter) {
                MediaSniffer.CAT_ALL -> true
                MediaSniffer.CAT_BLOCKED -> m.blocked
                else -> m.category == filter && !m.blocked
            }
            return catOk && (query.isEmpty() || m.url.contains(query, true))
        }

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = items.size

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val card = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundResource(R.drawable.bg_tile)
                    setPadding(dp(16), dp(8), dp(8), dp(16))
                    isClickable = true
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(12)
                    }
                }
                val top = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val icon = ImageView(parent.context)
                top.addView(icon, LinearLayout.LayoutParams(dp(22), dp(22)))
                val label = TextView(parent.context).apply {
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dp(10), 0, 0, 0)
                }
                top.addView(label)
                val time = TextView(parent.context).apply {
                    textSize = 13f
                    setTextColor(mutedColor())
                    setPadding(dp(10), 0, 0, 0)
                    maxLines = 1
                }
                top.addView(time, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                fun action(iconRes: Int, desc: String) = ImageButton(parent.context).apply {
                    setImageResource(iconRes)
                    setBackgroundResource(selectableBg())
                    contentDescription = desc
                    imageTintList = ColorStateList.valueOf(textColor())
                    scaleType = ImageView.ScaleType.CENTER
                }
                val copyB = action(R.drawable.ic_copy, "Kopyala")
                val shareB = action(R.drawable.ic_share, "Paylaş")
                val dlB = action(R.drawable.ic_download, "İndir")
                top.addView(copyB, LinearLayout.LayoutParams(dp(42), dp(42)))
                top.addView(shareB, LinearLayout.LayoutParams(dp(42), dp(42)))
                top.addView(dlB, LinearLayout.LayoutParams(dp(42), dp(42)))
                card.addView(top)
                val urlView = TextView(parent.context).apply {
                    textSize = 13.5f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(8), dp(8), 0)
                }
                card.addView(urlView)
                card.tag = arrayOf<View>(icon, label, time, copyB, shareB, dlB, urlView)
                return object : RecyclerView.ViewHolder(card) {}
            }

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val m = items[position]
                @Suppress("UNCHECKED_CAST")
                val v = holder.itemView.tag as Array<View>
                val tint = if (m.blocked) Color.parseColor("#E5484D") else textColor()
                (v[0] as ImageView).apply {
                    setImageResource(MediaSniffer.iconOf(m))
                    imageTintList = ColorStateList.valueOf(if (m.isMedia && !m.blocked) brand else tint)
                }
                (v[1] as TextView).apply {
                    text = if (m.blocked) "${m.kind} · ENGELLENDİ" else m.kind
                    setTextColor(tint)
                }
                val rel = android.text.format.DateUtils.getRelativeTimeSpanString(
                    m.time, System.currentTimeMillis(), android.text.format.DateUtils.SECOND_IN_MILLIS
                )
                val size = MediaSniffer.formatSize(m.size)
                (v[2] as TextView).text = if (size.isNotEmpty()) "$rel · $size" else rel
                v[3].setOnClickListener { copy(m.url) }
                v[4].setOnClickListener { share(m.url) }
                v[5].setOnClickListener { downloadMedia(m) }
                (v[6] as TextView).text = styledUrl(m.url)
                holder.itemView.setOnClickListener { resourceActions(m, d) }
                holder.itemView.setOnLongClickListener { copy(m.url); true }
                if (m.isMedia && !m.probed) MediaSniffer.probe(m, ua) {
                    val idx = items.indexOf(m)
                    if (idx >= 0) notifyItemChanged(idx)
                }
            }
        }
        rv.adapter = adapter

        fun refreshChips() {
            val all = snapshot()
            chips.removeAllViews()
            for ((cat, name) in MediaSniffer.CATEGORY_NAMES) {
                val count = when (cat) {
                    MediaSniffer.CAT_ALL -> all.size
                    MediaSniffer.CAT_BLOCKED -> all.count { it.blocked }
                    else -> all.count { it.category == cat && !it.blocked }
                }
                if (count == 0 && cat != MediaSniffer.CAT_ALL && cat != filter) continue
                chips.addView(com.google.android.material.chip.Chip(this).apply {
                    text = "$name $count"
                    isCheckable = true
                    isChecked = cat == filter
                    setOnClickListener {
                        filter = cat
                        refreshChipsAndList()
                    }
                })
            }
            mediaOnlyBtn.imageTintList = ColorStateList.valueOf(if (filter == MediaSniffer.CAT_MEDIA) brand else textColor())
        }

        fun refreshList() {
            items = snapshot().filter { matches(it) }.asReversed()
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            empty.text = when {
                snapshot().isEmpty() -> "Henüz kaynak yok — sayfayı yenile veya bir videoyu oynat."
                filter == MediaSniffer.CAT_MEDIA -> "Bu sayfada medya bulunamadı.\nVideoyu oynatmayı dene ya da \"Tümü\" sekmesine bak."
                else -> "Bu filtrede kaynak yok."
            }
            adapter.notifyDataSetChanged()
        }

        refreshChipsAndListHolder = {
            refreshChips()
            refreshList()
        }

        searchBtn.setOnClickListener {
            if (search.visibility == View.VISIBLE) {
                search.setText("")
                search.visibility = View.GONE
            } else {
                search.visibility = View.VISIBLE
                showKeyboard(search)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim() ?: ""
                refreshList()
            }
        })
        mediaOnlyBtn.setOnClickListener {
            filter = if (filter == MediaSniffer.CAT_MEDIA) MediaSniffer.CAT_ALL else MediaSniffer.CAT_MEDIA
            refreshChipsAndList()
        }
        clearBtn.setOnClickListener {
            synchronized(t.res) { t.res.clear(); t.mediaCount = 0 }
            updateMediaBtn()
            refreshChipsAndList()
        }

        refreshChipsAndList()

        // Liste açıkken canlı güncelle
        var lastSize = snapshot().size
        val ticker = object : Runnable {
            override fun run() {
                val n = snapshot().size
                if (n != lastSize) {
                    lastSize = n
                    refreshChipsAndList()
                } else if (items.isNotEmpty()) {
                    adapter.notifyItemRangeChanged(0, minOf(items.size, 30))
                }
                ui.postDelayed(this, 2000)
            }
        }
        ui.postDelayed(ticker, 2000)
        d.setOnDismissListener {
            ui.removeCallbacks(ticker)
            refreshChipsAndListHolder = null
        }

        d.setContentView(root)
        d.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.85).toInt()
        d.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        d.show()
    }

    internal var refreshChipsAndListHolder: (() -> Unit)? = null
    internal fun refreshChipsAndList() { refreshChipsAndListHolder?.invoke() }

    internal fun resourceActions(m: MediaItem, sheet: BottomSheetDialog) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if (m.isMedia) {
            labels.add("İndir"); acts.add { downloadMedia(m) }
            labels.add("Harici oynatıcıda aç"); acts.add { openExternal(m) }
            labels.add("Yeni sekmede oynat"); acts.add { sheet.dismiss(); newTab(m.url, current?.incognito ?: false) }
        } else {
            if (m.isCode) { labels.add("Kodu görüntüle"); acts.add { openCode(m) } }
            labels.add("Yeni sekmede aç"); acts.add { sheet.dismiss(); newTab(m.url, current?.incognito ?: false) }
            labels.add("İndir"); acts.add { downloadMedia(m) }
        }
        labels.add("Bağlantıyı kopyala"); acts.add { copy(m.url) }
        labels.add("Paylaş"); acts.add { share(m.url) }
        MaterialAlertDialogBuilder(this)
            .setTitle(m.name)
            .setItems(labels.toTypedArray()) { _, w -> acts[w]() }
            .show()
    }

    internal fun openCode(m: MediaItem) {
        startActivity(Intent(this, SourceActivity::class.java)
            .putExtra(SourceActivity.EXTRA_URL, m.url)
            .putExtra(SourceActivity.EXTRA_UA, currentUA())
            .putExtra(SourceActivity.EXTRA_FETCH, true))
    }

    internal fun downloadMedia(m: MediaItem) {
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

    internal fun openExternal(m: MediaItem) {
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

    internal fun fetchLiveHtml(tab: Tab, cb: (String?) -> Unit) {
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

    internal fun viewSource(tab: Tab) {
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

    internal fun savePage(tab: Tab) {
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

    internal fun saveInBackground(fileName: String, mime: String, write: (java.io.OutputStream) -> Unit, after: () -> Unit = {}) {
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

    internal fun saveMht(tab: Tab, name: String) {
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

    internal fun saveHtml(tab: Tab, name: String) {
        fetchLiveHtml(tab) { html ->
            if (html == null) toast("Sayfa kodu alınamadı")
            else saveInBackground("$name.html", "text/html", { o -> o.write(html.toByteArray()) })
        }
    }

    internal fun translate(url: String) {
        loadInCurrent("https://translate.google.com/translate?sl=auto&tl=tr&u=" + java.net.URLEncoder.encode(url, "UTF-8"))
    }


    // =====================================================================
    // Menü
    // =====================================================================

    internal fun showMenu() {
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
        val mediaCount = t?.mediaCount ?: 0

        tile(R.drawable.ic_add, "Yeni sekme") { newTab(HOME_URL, false) }
        tile(R.drawable.ic_incognito, "Gizli sekme") { newTab(HOME_URL, true) }
        tile(if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border, if (bookmarked) "Yer iminden çıkar" else "Yer imi ekle") {
            if (!isPage) toast("Bu sayfa eklenemez")
            else toast(if (Store.toggleBookmark(url, t!!.title)) "Yer imlerine eklendi" else "Yer iminden çıkarıldı")
        }
        tile(R.drawable.ic_bookmark, "Yer imleri") { openLibrary(LibraryActivity.MODE_BOOKMARKS) }

        tile(R.drawable.ic_history, "Geçmiş") { openLibrary(LibraryActivity.MODE_HISTORY) }
        tile(R.drawable.ic_download, "İndirilenler") { openDownloads() }
        tile(R.drawable.ic_movie, "Media Sniffer", mediaCount) { showMedia() }
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
        tile(R.drawable.ic_build, "Araçlar") { showTools() }
        tile(R.drawable.ic_exit, "Çıkış") {
            if (Store.bool("clear_on_exit", false)) clearAllData() else saveSession()
            tabs.filter { it.incognito }.forEach { closeTab(it) }
            finishAndRemoveTask()
        }

        d.setContentView(box)
        d.show()
    }

    internal fun openLibrary(mode: String) {
        startActivity(Intent(this, LibraryActivity::class.java).putExtra(LibraryActivity.EXTRA_MODE, mode))
    }

    internal fun uaFor(tab: Tab?): String {
        if (tab?.desktop == true) return desktopUA
        val chrome = Regex("Chrome/([\\d.]+)").find(mobileUA)?.groupValues?.get(1) ?: "130.0.0.0"
        return when (Store.str("ua_mode", "default")) {
            "desktop" -> desktopUA
            "windows" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
            "iphone" -> "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
            "ipad" -> "Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
            "firefox" -> "Mozilla/5.0 (Android 14; Mobile; rv:131.0) Gecko/131.0 Firefox/131.0"
            "bot" -> "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
            "custom" -> Store.str("ua_custom", "").ifBlank { mobileUA }
            else -> mobileUA
        }
    }

    internal fun toggleDesktop(tab: Tab) {
        tab.desktop = !tab.desktop
        tab.webView.settings.userAgentString = uaFor(tab)
        tab.webView.reload()
        toast(if (tab.desktop) "Masaüstü sitesi istendi" else "Mobil site istendi")
    }

    internal fun print(tab: Tab) {
        val pm = getSystemService(PRINT_SERVICE) as PrintManager
        val name = tab.title.ifEmpty { "Kalkan sayfası" }
        pm.print(name, tab.webView.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
    }

    // =====================================================================
    // Sekme listesi
    // =====================================================================

    internal fun showTabs() {
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

    internal fun showShield() {
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

    internal val saveRunnable = Runnable { saveSession() }

    internal fun saveSessionSoon() {
        ui.removeCallbacks(saveRunnable)
        ui.postDelayed(saveRunnable, 1500)
    }

    internal fun saveSession() {
        val normal = tabs.filter { !it.incognito }
        val arr = JSONArray()
        normal.forEach { arr.put(it.pendingUrl ?: it.url.ifEmpty { HOME_URL }) }
        Store.prefs.edit()
            .putString("session", arr.toString())
            .putInt("session_index", normal.indexOf(current).coerceAtLeast(0))
            .apply()
    }

    internal fun restoreSession(): Boolean {
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

    internal fun handleIntent(intent: Intent?): Boolean {
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
