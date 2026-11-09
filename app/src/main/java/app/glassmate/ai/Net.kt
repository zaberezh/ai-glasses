package app.glassmate.ai

import app.glassmate.Core
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Общий HTTP-клиент. Если DNS телефона (часто через VPN) не может найти адрес сервера,
 * спрашиваем его у публичного DNS-over-HTTPS (Cloudflare, затем Google) по IP-адресу,
 * а удачный ответ запоминаем — так единичные сбои DNS не ломают запросы.
 */
object Net {
    private val bootstrap = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()

    private val cloudflare = DnsOverHttps.Builder().client(bootstrap)
        .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
        .build()

    private val google = DnsOverHttps.Builder().client(bootstrap)
        .url("https://dns.google/dns-query".toHttpUrl())
        .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
        .build()

    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                return Dns.SYSTEM.lookup(hostname).also { cache[hostname] = it }
            } catch (e: UnknownHostException) {
                for (fallback in listOf(cloudflare, google)) {
                    val r = runCatching { fallback.lookup(hostname) }.getOrNull()
                    if (!r.isNullOrEmpty()) {
                        Core.log("DNS телефона не нашёл $hostname — взял адрес у публичного DNS")
                        cache[hostname] = r
                        return r
                    }
                }
                cache[hostname]?.let {
                    Core.log("DNS недоступен — использую последний известный адрес $hostname")
                    return it
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
}
