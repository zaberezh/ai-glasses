package app.glassmate.glasses

import app.glassmate.Core
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TimeZone

/** Команды очков поверх GlassesLink. */
class Glasses(private val link: GlassesLink) {

    /** То же, что делает официальное приложение сразу после подключения. */
    suspend fun onConnected() {
        setTime()
        link.request(Cmd.COPROCESSOR_INFO, timeoutMs = 3000)
        val info = deviceInfo(Info.NAME, Info.FIRMWARE, Info.POWER)
        info[Info.NAME]?.cString()?.takeIf { it.isNotBlank() }?.let {
            Core.deviceName.value = it
            Core.log("Имя очков: $it")
        }
        info[Info.FIRMWARE]?.let { Core.firmware.value = it.toHex(); Core.log("Прошивка: ${it.toHex()}") }
        info[Info.POWER]?.firstOrNull()?.let { Core.battery.value = parsePower(it) }
        if (Core.battery.value == null) {
            val p = deviceInfo(Info.POWER)[Info.POWER]
            Core.log("Заряд (отдельный запрос): ${p?.toHex() ?: "нет ответа"}")
            p?.firstOrNull()?.let { Core.battery.value = parsePower(it) }
        }
    }

    suspend fun battery(): Pair<Int, Boolean>? =
        deviceInfo(Info.POWER)[Info.POWER]?.firstOrNull()?.let { parsePower(it) }

    suspend fun setWearDetection(on: Boolean): Boolean =
        link.request(Cmd.IN_EAR_DETECT, byteArrayOf(if (on) 1 else 0)) != null

    /** Максимальная длительность видео / аудио на очках, в минутах. */
    suspend fun setVideoLimit(minutes: Int): Boolean =
        link.request(Cmd.GLASS_CONFIG, byteArrayOf(0x82.toByte(), 1, minutes.toByte())) != null

    suspend fun setAudioLimit(minutes: Int): Boolean =
        link.request(Cmd.GLASS_CONFIG, byteArrayOf(0x83.toByte(), 1, minutes.toByte())) != null

    suspend fun setTime(): Boolean {
        val nowMs = System.currentTimeMillis()
        val tzMinutes = TimeZone.getDefault().getOffset(nowMs) / 60000
        val p = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((nowMs / 1000).toInt())
            .putShort(tzMinutes.toShort())
            .array()
        return link.request(Cmd.SET_TIME, p, timeoutMs = 4000) != null
    }

    suspend fun deviceInfo(vararg types: Int): Map<Int, ByteArray> {
        val req = ByteArray(types.size * 2)
        types.forEachIndexed { i, t -> req[i * 2] = t.toByte() }
        val resp = link.request(Cmd.DEVICE_INFO, req, timeoutMs = 4000) ?: return emptyMap()
        return parseTlv(resp)
    }

    /** Текущие настройки кнопок: жест -> действие. */
    suspend fun readKeys(): Map<Int, Int>? {
        val v = deviceInfo(Info.KEY_SETTINGS)[Info.KEY_SETTINGS] ?: return null
        val out = LinkedHashMap<Int, Int>()
        var p = 0
        while (v.size - p >= 3) {
            val type = v[p].toInt() and 0xFF
            val len = v[p + 1].toInt() and 0xFF
            if (len == 1) out[type] = v[p + 2].toInt() and 0xFF
            p += 2 + len
        }
        return out
    }

    suspend fun setKey(gesture: Int, function: Int): Boolean =
        link.request(Cmd.KEY, byteArrayOf(gesture.toByte(), 1, function.toByte())) != null

    private fun status(r: ByteArray?): Int? = r?.let { if (it.isEmpty()) 0 else it[0].toInt() and 0xFF }

    // Камера очков спит и просыпается ~8–10 с, поэтому ответа на команды камеры ждём долго.

    /** Обычное фото — сохраняется в память очков. Возвращает код ответа (0 = ок, null = нет ответа). */
    suspend fun takePhoto(): Int? = status(link.request(Cmd.CAMERA_SHOT, byteArrayOf(0), CAMERA_TIMEOUT))

    suspend fun startVideo(): Int? = status(link.request(Cmd.START_VIDEO, timeoutMs = CAMERA_TIMEOUT))

    suspend fun startAudio(): Int? = status(link.request(Cmd.START_AUDIO, timeoutMs = 15000))

    /** Остановить запись / выключить камеру и Wi-Fi очков. */
    suspend fun stopCamera(): Int? = status(link.request(Cmd.CAMERA_OFF, timeoutMs = 10000))

    suspend fun workState(): Int? = status(link.request(Cmd.WORK_STATE))

    /**
     * ИИ-снимок: очки делают кадр и присылают JPEG кусками по Bluetooth
     * (уведомления 0xE3: [status][данные], status 1 — будет ещё, 0 — последний).
     */
    suspend fun captureAiPhoto(timeoutMs: Long = 30000): ByteArray = coroutineScope {
        val out = ByteArrayOutputStream()
        val done = CompletableDeferred<Unit>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            link.notifications.collect { m ->
                if (m.cmd != Cmd.AI_PIC_PUSH || m.payload.isEmpty()) return@collect
                when (val st = m.payload[0].toInt() and 0xFF) {
                    0, 1 -> {
                        out.write(m.payload, 1, m.payload.size - 1)
                        if (st == 0) done.complete(Unit)
                    }
                    else -> done.completeExceptionally(IOException("очки вернули ошибку снимка ($st)"))
                }
            }
        }
        try {
            // Ответ может не прийти, пока камера просыпается, — тогда просто ждём сам снимок
            val r = link.request(Cmd.CAMERA_SHOT, byteArrayOf(1), CAMERA_TIMEOUT)
            val code = status(r)
            if (r != null && code != 0) Core.log("Ответ очков на ИИ-снимок: $code")
            val finished = withTimeoutOrNull(timeoutMs) { done.await() } != null
            val bytes = out.toByteArray()
            if (!finished && !looksLikeJpeg(bytes)) throw IOException("снимок не пришёл за ${timeoutMs / 1000} с")
            if (bytes.isEmpty()) throw IOException("пустой снимок")
            bytes
        } finally {
            collector.cancel()
        }
    }

    private fun looksLikeJpeg(b: ByteArray) =
        b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
            b[b.size - 2] == 0xFF.toByte() && b[b.size - 1] == 0xD9.toByte()

    /**
     * Включает на очках точку доступа и файловый сервер (для импорта по Wi-Fi).
     * Адрес сервера очки присылают уведомлением 0xE7 — его ловит Core.
     */
    suspend fun startFileServer(ssid: String, password: String): Boolean {
        val r = link.request(Cmd.WIFI_CONFIG, wifiConfigPayload(1, ssid, password, 0)) ?: return false
        if (status(r) != 0) Core.log("Ответ очков на настройку Wi-Fi: ${status(r)}")
        return link.request(Cmd.FILE_MANAGE, timeoutMs = 10000) != null
    }

    companion object {
        const val CAMERA_TIMEOUT = 25_000L

        /** Байт заряда: бит 7 — заряжается, младшие 7 бит — процент. */
        fun parsePower(b: Byte): Pair<Int, Boolean> = (b.toInt() and 0x7F) to ((b.toInt() and 0x80) != 0)
    }

    private fun wifiConfigPayload(mode: Int, ssid: String, password: String, channel: Int): ByteArray {
        val s = ssid.toByteArray(Charsets.UTF_8)
        val p = password.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(1); out.write(1); out.write(mode)
        out.write(2); out.write(s.size); out.write(s)
        out.write(3); out.write(p.size); out.write(p)
        out.write(4); out.write(1); out.write(channel)
        return out.toByteArray()
    }
}
