package app.kalkan.browser

import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executor

/** Proxy / Tor ayarları ve ağ bağlantıları. */
object Net {
    private val direct = Executor { it.run() }

    val mode: String get() = Store.str("proxy_mode", "off")

    val host: String get() = if (mode == "tor") "127.0.0.1" else Store.str("proxy_host", "")

    val port: Int get() = if (mode == "tor") 9050 else Store.str("proxy_port", "0").toIntOrNull() ?: 0

    val active: Boolean get() = mode != "off" && host.isNotBlank() && port > 0

    fun label(): String = when {
        !active -> "Kapalı"
        mode == "tor" -> "Tor (Orbot) · 127.0.0.1:9050"
        mode == "http" -> "HTTP · $host:$port"
        else -> "SOCKS5 · $host:$port"
    }

    fun proxy(): Proxy {
        if (!active) return Proxy.NO_PROXY
        val type = if (mode == "http") Proxy.Type.HTTP else Proxy.Type.SOCKS
        return Proxy(type, InetSocketAddress.createUnresolved(host, port))
    }

    fun open(url: String): HttpURLConnection = URL(url).openConnection(proxy()) as HttpURLConnection

    val webViewSupported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    /** WebView'lere proxy uygular. */
    fun applyWebView(onDone: ((Boolean) -> Unit)? = null) {
        if (!webViewSupported) { onDone?.invoke(false); return }
        try {
            val pc = ProxyController.getInstance()
            if (!active) {
                pc.clearProxyOverride(direct) { onDone?.invoke(true) }
            } else {
                val rule = if (mode == "http") "$host:$port" else "socks5://$host:$port"
                val cfg = ProxyConfig.Builder()
                    .addProxyRule(rule)
                    .addBypassRule("localhost")
                    .addBypassRule("127.0.0.1")
                    .build()
                pc.setProxyOverride(cfg, direct) { onDone?.invoke(true) }
            }
        } catch (e: Exception) {
            onDone?.invoke(false)
        }
    }
}
