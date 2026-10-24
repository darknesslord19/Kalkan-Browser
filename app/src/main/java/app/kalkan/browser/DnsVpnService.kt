package app.kalkan.browser

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Sistem geneli reklam engelleme: yalnızca DNS trafiğini sahte bir DNS sunucusu üzerinden
 * yakalar, reklam/izleyici alan adlarını 0.0.0.0'a yönlendirir, gerisini gerçek DNS'e iletir.
 * Diğer tüm trafik VPN dışından normal akar (pil ve hız dostu).
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.kalkan.browser.DNS_START"
        const val ACTION_STOP = "app.kalkan.browser.DNS_STOP"
        private const val FAKE_DNS = "10.111.222.2"

        @Volatile var running = false
            private set
        val blocked = AtomicLong(0)
        val queries = AtomicLong(0)

        val UPSTREAMS = linkedMapOf(
            "1.1.1.1" to "Cloudflare (1.1.1.1)",
            "8.8.8.8" to "Google (8.8.8.8)",
            "9.9.9.9" to "Quad9 (9.9.9.9)",
            "94.140.14.14" to "AdGuard DNS (94.140.14.14)"
        )

        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            if (!running) return
            ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private val pool = Executors.newFixedThreadPool(6)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        if (tun != null) return
        val b = Builder()
            .setSession("Kalkan Koruması")
            .addAddress("10.111.222.1", 32)
            .addDnsServer(FAKE_DNS)
            .addRoute(FAKE_DNS, 32)
            .setMtu(1500)
        if (Build.VERSION.SDK_INT >= 29) b.setBlocking(true)
        val fd = try { b.establish() } catch (e: Exception) { null } ?: run { stopSelf(); return }
        tun = fd
        running = true
        Store.prefs.edit().putBoolean("dns_vpn_on", true).apply()
        reader = Thread({ loop(fd) }, "kalkan-dns").apply { start() }
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try { input.read(buf) } catch (e: IOException) { break }
            if (n <= 0) {
                try { Thread.sleep(15) } catch (_: InterruptedException) { break }
                continue
            }
            val info = DnsPacket.parseUdp(buf, n) ?: continue
            if (info.dstPort != 53) continue
            pool.execute { handle(info, output) }
        }
    }

    private fun handle(info: DnsPacket.UdpInfo, output: FileOutputStream) {
        val q = DnsPacket.parseQuery(info.payload) ?: return
        queries.incrementAndGet()
        val engine = FilterManager.engine
        val reply = if (q.name.isNotEmpty() && !Store.isAllowlisted(q.name) && engine.isDomainBlocked(q.name)) {
            blocked.incrementAndGet()
            Store.addBlocked()
            DnsPacket.blockedResponse(info.payload, q)
        } else {
            forward(info.payload)
        } ?: return
        val pkt = DnsPacket.buildUdp(info.dstIp, info.srcIp, 53, info.srcPort, reply)
        try {
            synchronized(output) { output.write(pkt) }
        } catch (_: IOException) {
        }
    }

    private fun forward(query: ByteArray): ByteArray? {
        val upstream = Store.str("dns_upstream", "1.1.1.1")
        return try {
            DatagramSocket().use { s ->
                protect(s)
                s.soTimeout = 5000
                s.send(DatagramPacket(query, query.size, InetAddress.getByName(upstream), 53))
                val rb = ByteArray(4096)
                val p = DatagramPacket(rb, rb.size)
                s.receive(p)
                rb.copyOf(p.length)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun shutdown() {
        running = false
        Store.prefs.edit().putBoolean("dns_vpn_on", false).apply()
        Store.persistCounters()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        reader?.interrupt()
        reader = null
    }

    override fun onRevoke() {
        shutdown()
        stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        pool.shutdownNow()
        super.onDestroy()
    }
}
