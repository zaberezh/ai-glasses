package app.glassmate

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Импорт фото/видео с очков по Wi-Fi — как в официальном приложении:
 * очки поднимают точку доступа "AiGlass<MAC>" (пароль 12345678), телефон к ней
 * подключается, список файлов берётся из <адрес>/media.config, файлы — <адрес>/<имя>.
 */
class WifiImporter(private val ctx: Context) {

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)!!

    suspend fun import(): Int {
        val glasses = Core.glasses ?: throw IOException("очки не подключены")
        val mac = Core.deviceAddress ?: throw IOException("неизвестен адрес очков")
        val ssid = "AiGlass" + mac.replace(":", "").uppercase()
        val password = "12345678"

        Core.wifiFileAddress.value = null
        Core.log("Включаю Wi-Fi на очках ($ssid)…")
        if (!glasses.startFileServer(ssid, password)) throw IOException("очки не включили Wi-Fi")

        Core.log("Подключаюсь к Wi-Fi очков — подтвердите запрос системы, если он появится")
        val (network, callback) = connectWifi(ssid, password)
        try {
            val address = withTimeoutOrNull(15000) { Core.wifiFileAddress.filterNotNull().first() }
                ?: gatewayUrl(network)
                ?: throw IOException("очки не сообщили адрес файлов")
            val base = normalize(address)
            Core.log("Файловый сервер: $base")

            val listText = String(httpGet(network, URL(URL(base), "media.config")), Charsets.UTF_8)
            val files = parseList(listText)
            Core.log("Файлов на очках: ${files.size}")
            var ok = 0
            for (name in files) {
                try {
                    val data = httpGet(network, URL(URL(base), name))
                    save(name.substringAfterLast('/'), data)
                    ok++
                    Core.log("Сохранено: $name (${data.size / 1024} КБ)")
                } catch (e: Exception) {
                    Core.log("Не удалось скачать $name: ${e.message}")
                }
            }
            return ok
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
            runCatching { glasses.stopCamera() }
        }
    }

    private suspend fun connectWifi(ssid: String, password: String): Pair<Network, ConnectivityManager.NetworkCallback> =
        withTimeoutOrNull(70000) {
            suspendCancellableCoroutine<Pair<Network, ConnectivityManager.NetworkCallback>> { cont ->
                val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).setWpa2Passphrase(password).build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .setNetworkSpecifier(spec)
                    .build()
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (cont.isActive) cont.resume(network to this)
                    }

                    override fun onUnavailable() {
                        if (cont.isActive) cont.resumeWithException(IOException("Wi-Fi очков не найден или подключение отклонено"))
                    }
                }
                cm.requestNetwork(request, cb, 60000)
                cont.invokeOnCancellation { runCatching { cm.unregisterNetworkCallback(cb) } }
            }
        } ?: throw IOException("не удалось подключиться к Wi-Fi очков")

    private fun gatewayUrl(network: Network): String? {
        val gw = cm.getLinkProperties(network)?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
            ?: return null
        Core.log("Адрес не пришёл, пробую шлюз сети: $gw")
        return "http://$gw/"
    }

    private fun normalize(address: String): String {
        var a = address.trim()
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
        if (!a.endsWith("/")) a += "/"
        return a
    }

    private suspend fun httpGet(network: Network, url: URL): ByteArray = withContext(Dispatchers.IO) {
        val c = network.openConnection(url) as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "TSClient/1.0")
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private fun parseList(text: String): List<String> {
        val t = text.trim()
        runCatching {
            val arr = when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> JSONObject(t).optJSONArray("files")
                else -> null
            }
            if (arr != null) return (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
        }
        return t.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    private fun save(name: String, data: ByteArray) {
        val ext = name.substringAfterLast('.', "").lowercase()
        val (collection, mime, dir) = when (ext) {
            "jpg", "jpeg" -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/jpeg", "Pictures/GlassMate")
            "png" -> Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/png", "Pictures/GlassMate")
            "mp4", "mov" -> Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/mp4", "Movies/GlassMate")
            "opus", "ogg" -> Triple(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "audio/ogg", "Download/GlassMate")
            "wav" -> Triple(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "audio/wav", "Download/GlassMate")
            "mp3" -> Triple(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "audio/mpeg", "Download/GlassMate")
            else -> Triple(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "application/octet-stream", "Download/GlassMate")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
        }
        val uri = ctx.contentResolver.insert(collection, values) ?: throw IOException("галерея недоступна")
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
    }
}
