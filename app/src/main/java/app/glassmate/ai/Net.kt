package app.glassmate.ai

import app.glassmate.Core
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Общий HTTP-клиент с устойчивым поиском адреса сервера.
 *
 * DNS телефона (особенно через VPN) временами не находит сервер. Тогда:
 *  1. берём последний известный адрес — он хранится и между запусками приложения;
 *  2. если его нет, спрашиваем публичные DNS напрямую по UDP (Яндекс, Google, Cloudflare);
 *  3. если и они молчат — DNS-over-HTTPS.
 * Каждый удачный ответ запоминается, поэтому сбой DNS больше не ломает запрос.
 */
object Net {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "dns").apply { isDaemon = true } }

    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private fun doh(url: String, vararg ips: String) = DnsOverHttps.Builder().client(bootstrap)
        .url(url.toHttpUrl())
        .bootstrapDnsHosts(ips.map { InetAddress.getByName(it) })
        .build()

    private val dohServers by lazy {
        listOf(
            doh("https://cloudflare-dns.com/dns-query", "1.1.1.1", "1.0.0.1"),
            doh("https://dns.google/dns-query", "8.8.8.8", "8.8.4.4"),
        )
    }

    /** Публичные DNS: Яндекс, Google, Cloudflare. */
    private val udpServers = listOf("77.88.8.8", "8.8.8.8", "1.1.1.1")

    private val cache = ConcurrentHashMap<String, List<InetAddress>>()
    @Volatile private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            for (line in Core.settings.dnsCache) {
                val host = line.substringBefore('=')
                val ips = line.substringAfter('=').split(',').filter { it.isNotBlank() }
                    .mapNotNull { ip -> DnsWire.literal(ip)?.let { InetAddress.getByAddress(host, it) } }
                if (ips.isNotEmpty()) cache.putIfAbsent(host, ips)
            }
        }
    }

    private fun remember(host: String, ips: List<InetAddress>) {
        val old = cache.put(host, ips)
        if (old != null && old.map { it.hostAddress } == ips.map { it.hostAddress }) return
        runCatching {
            Core.settings.dnsCache = cache.entries
                .map { (h, list) -> h + "=" + list.joinToString(",") { it.hostAddress.orEmpty() } }.toSet()
        }
    }

    /** Спросить публичные DNS по UDP одновременно; первый ответивший выигрывает. */
    private fun udpLookup(host: String): List<InetAddress> {
        val tasks = udpServers.map { server -> Callable { queryUdp(server, host) } }
        return runCatching { pool.invokeAny(tasks, 3, TimeUnit.SECONDS) }.getOrDefault(emptyList())
    }

    private fun queryUdp(server: String, host: String): List<InetAddress> {
        DatagramSocket().use { sock ->
            sock.soTimeout = 2500
            val id = (System.nanoTime() and 0xFFFF).toInt()
            val q = DnsWire.query(host, id)
            sock.send(DatagramPacket(q, q.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            sock.receive(p)
            val ips = DnsWire.parse(buf.copyOf(p.length), id).map { InetAddress.getByAddress(host, it) }
            if (ips.isEmpty()) throw UnknownHostException(host) // пусть invokeAny ждёт другой сервер
            return ips
        }
    }

    private fun publicLookup(host: String): List<InetAddress> {
        val udp = udpLookup(host)
        if (udp.isNotEmpty()) return udp
        for (d in dohServers) {
            val r = runCatching { d.lookup(host) }.getOrNull()
            if (!r.isNullOrEmpty()) return r
        }
        return emptyList()
    }

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            load()
            try {
                return Dns.SYSTEM.lookup(hostname).also { remember(hostname, it) }
            } catch (e: UnknownHostException) {
                cache[hostname]?.let { known ->
                    Core.log("DNS телефона не нашёл $hostname — беру последний известный адрес")
                    // адрес мог смениться: уточняем в фоне, запрос не задерживаем
                    pool.execute { runCatching { publicLookup(hostname).takeIf { it.isNotEmpty() }?.let { remember(hostname, it) } } }
                    return known
                }
                val r = publicLookup(hostname)
                if (r.isNotEmpty()) {
                    Core.log("DNS телефона не нашёл $hostname — взял адрес у публичного DNS")
                    remember(hostname, r)
                    return r
                }
                throw e
            }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Заранее находит адрес сервера и открывает с ним соединение (DNS + TLS),
     * чтобы сам вопрос к модели ушёл без этой задержки. Ответ сервера не важен.
     */
    fun warm(url: String) {
        pool.execute {
            runCatching {
                val root = url.toHttpUrl().newBuilder().encodedPath("/").query(null).build()
                client.newCall(Request.Builder().url(root).head().build()).execute().close()
            }
        }
    }
}

/** Минимальный разбор DNS: запрос записи A и чтение адресов из ответа. Без Android — проверяется тестом. */
object DnsWire {
    fun query(host: String, id: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(id shr 8); out.write(id)
        out.write(0x01); out.write(0x00)            // обычный запрос, рекурсия желательна
        out.write(0); out.write(1)                  // один вопрос
        repeat(6) { out.write(0) }
        for (label in host.trimEnd('.').split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            require(b.isNotEmpty() && b.size <= 63) { "плохое имя $host" }
            out.write(b.size); out.write(b)
        }
        out.write(0)
        out.write(0); out.write(1)                  // тип A
        out.write(0); out.write(1)                  // класс IN
        return out.toByteArray()
    }

    /** IPv4-адреса из ответа. Пустой список — ответа нет, он чужой или с ошибкой. */
    fun parse(msg: ByteArray, id: Int): List<ByteArray> {
        fun u8(i: Int) = msg[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (msg.size < 12 || u16(0) != (id and 0xFFFF)) return emptyList()
        if (u8(2) and 0x80 == 0 || u8(3) and 0x0F != 0) return emptyList()
        var p = 12
        fun skipName(): Boolean {
            while (p < msg.size) {
                val l = u8(p)
                when {
                    l == 0 -> { p += 1; return true }
                    l and 0xC0 == 0xC0 -> { p += 2; return true }  // ссылка на имя выше
                    else -> p += 1 + l
                }
            }
            return false
        }
        repeat(u16(4)) { if (!skipName()) return emptyList(); p += 4 }
        val out = ArrayList<ByteArray>()
        repeat(u16(6)) {
            if (!skipName() || p + 10 > msg.size) return out
            val type = u16(p)
            val len = u16(p + 8)
            p += 10
            if (p + len > msg.size) return out
            if (type == 1 && len == 4) out += msg.copyOfRange(p, p + 4)
            p += len
        }
        return out
    }

    /** "1.2.3.4" → 4 байта; не IPv4 → null (без обращения к DNS). */
    fun literal(ip: String): ByteArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        return ByteArray(4) { i -> (parts[i].toIntOrNull()?.takeIf { it in 0..255 } ?: return null).toByte() }
    }
}
