package app.kalkan.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import java.util.concurrent.Executors

/** WireGuard VPN istemcisi (kullanıcının içe aktardığı .conf ile). */
object WgManager {

    enum class Status { DOWN, CONNECTING, UP, ERROR }

    @Volatile var status = Status.DOWN
        private set
    @Volatile var lastError: String? = null
        private set

    private var backend: GoBackend? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    private val tunnel = object : Tunnel {
        override fun getName() = "kalkan"
        override fun onStateChange(newState: Tunnel.State) {
            status = if (newState == Tunnel.State.UP) Status.UP else Status.DOWN
            notifyListeners()
        }
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun notifyListeners() {
        val copy = synchronized(listeners) { listeners.toList() }
        main.post { copy.forEach { it() } }
    }

    val configText: String? get() = Store.prefs.getString("wg_config", null)
    val configName: String get() = Store.str("wg_name", "VPN")

    /** Yapılandırmayı doğrular ve kaydeder. Hata mesajı döner veya null. */
    fun saveConfig(name: String, text: String): String? {
        return try {
            val cfg = Config.parse(text.byteInputStream())
            if (cfg.peers.isEmpty()) return "Yapılandırmada [Peer] bölümü yok"
            Store.prefs.edit().putString("wg_config", text).putString("wg_name", name).apply()
            null
        } catch (e: Exception) {
            "Geçersiz WireGuard yapılandırması: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    fun endpointLabel(): String? = try {
        val cfg = Config.parse((configText ?: return null).byteInputStream())
        val ep = cfg.peers.firstOrNull()?.endpoint?.orElse(null)
        ep?.host
    } catch (_: Exception) { null }

    fun deleteConfig(ctx: Context) {
        disconnect(ctx)
        Store.prefs.edit().remove("wg_config").remove("wg_name").apply()
        notifyListeners()
    }

    fun connect(ctx: Context) {
        val text = configText ?: return
        val app = ctx.applicationContext
        status = Status.CONNECTING
        lastError = null
        notifyListeners()
        // Aynı anda tek VPN çalışabilir: DNS korumasını kapat
        DnsVpnService.stop(app)
        worker.execute {
            try {
                val b = backend ?: GoBackend(app).also { backend = it }
                val cfg = Config.parse(text.byteInputStream())
                b.setState(tunnel, Tunnel.State.UP, cfg)
                status = Status.UP
            } catch (e: Exception) {
                status = Status.ERROR
                lastError = e.message ?: e.javaClass.simpleName
            }
            notifyListeners()
        }
    }

    fun disconnect(ctx: Context) {
        val b = backend ?: return
        worker.execute {
            try {
                b.setState(tunnel, Tunnel.State.DOWN, null)
            } catch (_: Exception) {
            }
            status = Status.DOWN
            notifyListeners()
        }
    }
}
