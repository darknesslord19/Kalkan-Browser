package app.kalkan.browser

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Ayarlar"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()

            findPreference<ListPreference>("search_engine")?.apply {
                entries = Store.ENGINES.values.map { it.first }.toTypedArray()
                entryValues = Store.ENGINES.keys.toTypedArray()
                if (value == null) value = "google"
            }
            findPreference<ListPreference>("dns_upstream")?.apply {
                entries = DnsVpnService.UPSTREAMS.values.toTypedArray()
                entryValues = DnsVpnService.UPSTREAMS.keys.toTypedArray()
                if (value == null) value = "1.1.1.1"
            }
            findPreference<ListPreference>("theme")?.setOnPreferenceChangeListener { _, v ->
                KalkanApp.applyTheme(v as String); true
            }

            // Filtre listeleri
            findPreference<PreferenceCategory>("cat_lists")?.let { cat ->
                FilterManager.LISTS.forEach { l ->
                    cat.addPreference(SwitchPreferenceCompat(ctx).apply {
                        key = "list_${l.id}"
                        title = l.name
                        setDefaultValue(l.defaultOn)
                        isChecked = FilterManager.isEnabled(l.id)
                        isIconSpaceReserved = false
                        setOnPreferenceChangeListener { _, _ ->
                            view?.post { FilterManager.requestRebuild(ctx) }
                            true
                        }
                    })
                }
                cat.addPreference(Preference(ctx).apply {
                    title = "Listeleri şimdi güncelle"
                    summary = "Filtreler 3 günde bir otomatik güncellenir"
                    isIconSpaceReserved = false
                    setOnPreferenceClickListener {
                        Toast.makeText(ctx, "İndiriliyor…", Toast.LENGTH_SHORT).show()
                        FilterManager.requestUpdate(ctx) { n ->
                            Toast.makeText(ctx, if (n > 0) "$n liste güncellendi" else "Güncelleme başarısız", Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                })
            }

            findPreference<Preference>("allowlist")?.setOnPreferenceClickListener {
                val list = Store.allowlist().sorted()
                if (list.isEmpty()) {
                    Toast.makeText(ctx, "İzin listesi boş", Toast.LENGTH_SHORT).show()
                } else {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Korumanın kapalı olduğu siteler")
                        .setItems(list.toTypedArray()) { _, w ->
                            Store.toggleAllowlist(list[w])
                            Toast.makeText(ctx, "${list[w]} için koruma yeniden açıldı", Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton("Kapat", null)
                        .show()
                }
                true
            }

            findPreference<Preference>("clear_data")?.setOnPreferenceClickListener {
                val labels = arrayOf("Geçmiş", "Çerezler ve site verileri", "Önbellek")
                val checked = booleanArrayOf(true, false, true)
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Tarama verilerini temizle")
                    .setMultiChoiceItems(labels, checked) { _, w, c -> checked[w] = c }
                    .setPositiveButton("Temizle") { _, _ ->
                        if (checked[0]) Store.clearHistory()
                        if (checked[1]) {
                            CookieManager.getInstance().removeAllCookies(null)
                            WebStorage.getInstance().deleteAllData()
                        }
                        if (checked[2]) WebView(ctx).apply { clearCache(true); destroy() }
                        Toast.makeText(ctx, "Temizlendi", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Vazgeç", null)
                    .show()
                true
            }

            findPreference<Preference>("default_browser")?.setOnPreferenceClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
                true
            }

            findPreference<Preference>("vpn")?.setOnPreferenceClickListener {
                startActivity(Intent(ctx, VpnActivity::class.java)); true
            }

            findPreference<Preference>("about")?.summary =
                "Sürüm ${BuildConfig.VERSION_NAME} · Toplam ${"%,d".format(Store.totalBlocked.get())} reklam/izleyici engellendi"
        }
    }
}
