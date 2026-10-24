package app.kalkan.browser

/** IPv4/UDP/DNS paket yardımcıları (saf JVM, test edilebilir). */
object DnsPacket {

    class Query(val name: String, val qtype: Int, val questionEnd: Int)

    class UdpInfo(
        val srcIp: ByteArray, val dstIp: ByteArray,
        val srcPort: Int, val dstPort: Int, val payload: ByteArray
    )

    fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v shr 8) and 0xFF).toByte()
        b[o + 1] = (v and 0xFF).toByte()
    }

    /** IPv4 UDP paketini çözer; DNS değilse null. */
    fun parseUdp(pkt: ByteArray, len: Int): UdpInfo? {
        if (len < 28) return null
        val ver = (pkt[0].toInt() shr 4) and 0xF
        if (ver != 4) return null
        val ihl = (pkt[0].toInt() and 0xF) * 4
        if (ihl < 20 || len < ihl + 8) return null
        if ((pkt[9].toInt() and 0xFF) != 17) return null
        val srcPort = u16(pkt, ihl)
        val dstPort = u16(pkt, ihl + 2)
        val udpLen = u16(pkt, ihl + 4)
        val start = ihl + 8
        val plen = minOf(udpLen - 8, len - start)
        if (plen <= 0) return null
        return UdpInfo(
            pkt.copyOfRange(12, 16), pkt.copyOfRange(16, 20),
            srcPort, dstPort, pkt.copyOfRange(start, start + plen)
        )
    }

    fun parseQuery(dns: ByteArray): Query? {
        if (dns.size < 12) return null
        if ((dns[2].toInt() and 0x80) != 0) return null // yanıt
        if (u16(dns, 4) < 1) return null
        var o = 12
        val sb = StringBuilder()
        while (true) {
            if (o >= dns.size) return null
            val l = dns[o].toInt() and 0xFF
            if (l == 0) { o++; break }
            if ((l and 0xC0) != 0) return null
            if (o + 1 + l > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until l) sb.append((dns[o + 1 + i].toInt() and 0xFF).toChar())
            o += l + 1
        }
        if (o + 4 > dns.size) return null
        val qtype = u16(dns, o)
        return Query(sb.toString().lowercase(), qtype, o + 4)
    }

    /** Engellenen alan adı için 0.0.0.0 / :: yanıtı üretir. */
    fun blockedResponse(dns: ByteArray, q: Query): ByteArray {
        val answer = when (q.qtype) {
            1 -> 4
            28 -> 16
            else -> 0
        }
        val qLen = q.questionEnd - 12
        val size = 12 + qLen + (if (answer > 0) 12 + answer else 0)
        val r = ByteArray(size)
        r[0] = dns[0]; r[1] = dns[1]
        r[2] = (0x80 or (dns[2].toInt() and 0x79)).toByte()
        r[3] = 0x80.toByte()
        put16(r, 4, 1)
        put16(r, 6, if (answer > 0) 1 else 0)
        System.arraycopy(dns, 12, r, 12, qLen)
        if (answer > 0) {
            var o = 12 + qLen
            r[o] = 0xC0.toByte(); r[o + 1] = 0x0C; o += 2
            put16(r, o, q.qtype); o += 2
            put16(r, o, 1); o += 2
            r[o] = 0; r[o + 1] = 0; r[o + 2] = 0x01; r[o + 3] = 0x2C; o += 4 // TTL 300
            put16(r, o, answer); o += 2
            // adres baytları zaten sıfır
        }
        return r
    }

    fun buildUdp(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val total = 28 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45
        put16(p, 2, total)
        put16(p, 6, 0x4000)
        p[8] = 64
        p[9] = 17
        System.arraycopy(src, 0, p, 12, 4)
        System.arraycopy(dst, 0, p, 16, 4)
        put16(p, 10, checksum(p, 0, 20))
        put16(p, 20, sport)
        put16(p, 22, dport)
        put16(p, 24, 8 + payload.size)
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += u16(b, i)
            i += 2
        }
        if (len % 2 == 1) sum += (b[off + len - 1].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
