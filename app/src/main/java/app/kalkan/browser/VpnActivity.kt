package app.kalkan.browser

import android.content.ClipboardManager
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** WireGuard VPN + sistem geneli DNS reklam engelleme ekranı. */
class VpnActivity : AppCompatActivity() {

    private lateinit var dnsStatus: TextView
    private lateinit var dnsStats: TextView
    private lateinit var dnsBtn: MaterialButton
    private lateinit var wgStatus: TextView
    private lateinit var wgInfo: TextView
    private lateinit var wgBtn: MaterialButton
    private lateinit var wgDelete: MaterialButton

    private val ui = Handler(Looper.getMainLooper())
    private var pendingStart: (() -> Unit)? = null
    private val wgListener: () -> Unit = { refresh() }

    private val prepareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val action = pendingStart
        pendingStart = null
        if (r.resultCode == RESULT_OK) action?.invoke()
        else toast("VPN izni verilmedi")
    }

    private val pickConf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFromUri(uri)
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 1000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "VPN ve Koruma"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }

        // ---------------- DNS kartı ----------------
        val dnsCard = card()
        val dnsBox = dnsCard.getChildAt(0) as LinearLayout
        dnsBox.addView(heading("Sistem geneli reklam engelleme"))
        dnsBox.addView(body("Telefondaki TÜM uygulamalarda (oyunlar, sosyal medya, diğer tarayıcılar) reklam ve izleyici alan adlarını engeller. Sunucu gerekmez; yalnızca DNS sorguları süzülür, internet hızın etkilenmez."))
        dnsStatus = body("").also { it.setPadding(0, dp(10), 0, 0) }
        dnsStats = body("")
        dnsBox.addView(dnsStatus)
        dnsBox.addView(dnsStats)
        dnsBtn = MaterialButton(this).apply {
            setOnClickListener { toggleDns() }
        }
        dnsBox.addView(dnsBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        col.addView(dnsCard)

        // ---------------- WireGuard kartı ----------------
        val wgCard = card()
        val wgBox = wgCard.getChildAt(0) as LinearLayout
        wgBox.addView(heading("WireGuard VPN"))
        wgBox.addView(body("IP adresini gizler ve tüm trafiği şifreler. Bir VPN sağlayıcısından (ör. Proton VPN, Mullvad, kendi sunucun) indirdiğin WireGuard .conf dosyasını içe aktar."))
        wgStatus = body("").also { it.setPadding(0, dp(10), 0, 0) }
        wgInfo = body("")
        wgBox.addView(wgStatus)
        wgBox.addView(wgInfo)
        wgBtn = MaterialButton(this).apply { setOnClickListener { toggleWg() } }
        wgBox.addView(wgBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun outlined(text: String, action: () -> Unit) =
            MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                this.text = text
                setOnClickListener { action() }
            }
        row.addView(outlined("Dosyadan") { pickConf.launch(arrayOf("*/*")) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        row.addView(outlined("Yapıştır") { pasteConfig() },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        wgDelete = outlined("Sil") {
            MaterialAlertDialogBuilder(this)
                .setTitle("VPN yapılandırması silinsin mi?")
                .setPositiveButton("Sil") { _, _ -> WgManager.deleteConfig(this); refresh() }
                .setNegativeButton("Vazgeç", null).show()
        }
        row.addView(wgDelete, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        wgBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        col.addView(wgCard)

        col.addView(body("Not: Android aynı anda yalnızca bir VPN çalıştırabilir. WireGuard bağlanınca DNS koruması kapanır; bu durumda tarayıcı içi reklam engelleme çalışmaya devam eder.").apply {
            setPadding(dp(4), dp(8), dp(4), 0)
            textSize = 12.5f
        })

        setContentView(ScrollView(this).apply { addView(col) })
        WgManager.addListener(wgListener)
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        WgManager.removeListener(wgListener)
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun card(): MaterialCardView {
        val c = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(context, R.color.field)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.field))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            }
        }
        c.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        })
        return c
    }

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(ContextCompat.getColor(context, R.color.onBar))
        setPadding(0, 0, 0, dp(6))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t
        textSize = 14f
        setTextColor(ContextCompat.getColor(context, R.color.muted))
    }

    private fun refresh() {
        val dnsOn = DnsVpnService.running
        dnsStatus.text = if (dnsOn) "● Açık" else "○ Kapalı"
        dnsStatus.setTextColor(ContextCompat.getColor(this, if (dnsOn) R.color.brand else R.color.muted))
        dnsStats.text = "Bu oturumda ${DnsVpnService.blocked.get()} / ${DnsVpnService.queries.get()} sorgu engellendi · " +
            "${"%,d".format(FilterManager.engine.blockDomains.size)} alan adı listede"
        dnsBtn.text = if (dnsOn) "Durdur" else "Başlat"

        val hasConf = WgManager.configText != null
        val st = WgManager.status
        wgStatus.text = when {
            !hasConf -> "Yapılandırma yok"
            st == WgManager.Status.UP -> "● Bağlı"
            st == WgManager.Status.CONNECTING -> "◐ Bağlanıyor…"
            st == WgManager.Status.ERROR -> "✕ Hata: ${WgManager.lastError ?: ""}"
            else -> "○ Bağlı değil"
        }
        wgStatus.setTextColor(ContextCompat.getColor(this, if (st == WgManager.Status.UP) R.color.brand else R.color.muted))
        wgInfo.text = if (hasConf) "Profil: ${WgManager.configName}" + (WgManager.endpointLabel()?.let { " · Sunucu: $it" } ?: "") else ""
        wgBtn.isEnabled = hasConf
        wgBtn.text = if (st == WgManager.Status.UP || st == WgManager.Status.CONNECTING) "Bağlantıyı kes" else "Bağlan"
        wgDelete.isEnabled = hasConf
    }

    private fun withVpnPermission(action: () -> Unit) {
        val i = VpnService.prepare(this)
        if (i == null) action() else {
            pendingStart = action
            try {
                prepareLauncher.launch(i)
            } catch (_: Exception) {
                pendingStart = null
                toast("VPN izni istenemedi")
            }
        }
    }

    private fun toggleDns() {
        if (DnsVpnService.running) {
            DnsVpnService.stop(this)
            ui.postDelayed({ refresh() }, 300)
            return
        }
        withVpnPermission {
            if (WgManager.status == WgManager.Status.UP) WgManager.disconnect(this)
            ui.postDelayed({
                DnsVpnService.start(this)
                ui.postDelayed({ refresh() }, 500)
            }, if (WgManager.status == WgManager.Status.UP) 800L else 0L)
        }
    }

    private fun toggleWg() {
        val st = WgManager.status
        if (st == WgManager.Status.UP || st == WgManager.Status.CONNECTING) {
            WgManager.disconnect(this)
            return
        }
        withVpnPermission { WgManager.connect(this) }
    }

    private fun importFromUri(uri: Uri) {
        try {
            var name = "VPN"
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)?.substringBeforeLast('.') ?: name
            }
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            saveConfig(name, text)
        } catch (e: Exception) {
            toast("Dosya okunamadı: ${e.message}")
        }
    }

    private fun pasteConfig() {
        val input = EditText(this).apply {
            hint = "[Interface]\nPrivateKey = …\nAddress = …\n\n[Peer]\nPublicKey = …\nEndpoint = …"
            minLines = 8
            textSize = 13f
            setTypeface(android.graphics.Typeface.MONOSPACE)
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            val txt = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
            if (txt != null && txt.contains("[Interface]")) setText(txt)
        }
        val wrap = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("WireGuard yapılandırması")
            .setView(wrap)
            .setPositiveButton("Kaydet") { _, _ -> saveConfig("VPN", input.text.toString()) }
            .setNegativeButton("Vazgeç", null)
            .show()
    }

    private fun saveConfig(name: String, text: String) {
        val err = WgManager.saveConfig(name.take(30), text)
        if (err != null) {
            MaterialAlertDialogBuilder(this).setTitle("İçe aktarılamadı").setMessage(err)
                .setPositiveButton("Tamam", null).show()
        } else {
            toast("VPN profili kaydedildi")
            refresh()
        }
    }
}
