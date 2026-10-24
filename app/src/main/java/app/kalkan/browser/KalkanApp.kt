package app.kalkan.browser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

class KalkanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        applyTheme(Store.str("theme", "system"))
        FilterManager.init(this)
    }

    companion object {
        fun applyTheme(mode: String) {
            AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }
}
