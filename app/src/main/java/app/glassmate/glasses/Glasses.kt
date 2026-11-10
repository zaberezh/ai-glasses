package app.glassmate.glasses

import app.glassmate.Core
import kotlinx.coroutines.CompletableDeferred
import android.os.SystemClock
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        val info = deviceInfo(Info.NAME, Info.FIRMWARE, Info.POWER, Info.DEVICE_ABILITY, Info.MAX_PACKET_SIZE)
        info[Info.MAX_PACKET_SIZE]?.let { Core.log("Размер пакета очков: ${it.toHex()}") }
        info[Info.DEVICE_ABILITY]?.let { Core.onAbilities(it, "ответ 0x27, ключ 0xFE") }
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
    suspend fun setVideoLimit(minutes: Int): Boolean = setConfig(GlassKey.VIDEO_LIMIT_TIME, minutes) != null

    suspend fun setAudioLimit(minutes: Int): Boolean = setConfig(GlassKey.AUDIO_LIMIT_TIME, minutes) != null

    /** Одно однобайтовое значение настройки: 0xF7 [ключ][1][значение]. Возвращает код ответа. */
    suspend fun setConfig(key: Int, value: Int): Int? =
        status(link.request(Cmd.GLASS_CONFIG, byteArrayOf(key.toByte(), 1, value.toByte())))

    /**
     * Яркость экрана очков: 0xF7 [0x8C][1][уровень].
     *
     * Диапазон в SDK не проверяется (там просто int → byte), а прочитать текущее
     * значение нечем: ключа 0x8C нет ни среди типов DEVICE_INFO, ни в уведомлениях.
     * Единственный признак поддержки — код ответа: 0 приняли, иное отвергли.
     */
    suspend fun setBrightness(level: Int): Int? = setConfig(GlassKey.BRIGHTNESS, level)

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
     * Будит камеру очков заранее. Камерой управляет отдельный сопроцессор, который
     * спит и загружается несколько секунд. Запросы его состояния, памяти и числа
     * файлов — то, что делает приложение очков при открытии; после них сопроцессор
     * уже работает, и снимок или запись начинаются без ожидания загрузки.
     * Команды только читают данные. Время каждого ответа попадает в журнал.
     */
    suspend fun warmUp(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastWarmAt < WARM_INTERVAL_MS) return
        lastWarmAt = now
        for (cmd in WARM_COMMANDS) {
            if (!link.isOpen) return
            runCatching { link.request(cmd, timeoutMs = 8000) }
        }
    }

    /**
     * ИИ-снимок: очки делают кадр и присылают JPEG кусками по Bluetooth
     * (уведомления 0xE3: [status][данные], status 1 — будет ещё, 0 — последний).
     */
    suspend fun captureAiPhoto(timeoutMs: Long = 30000): ByteArray = captureLock.withLock {
        coroutineScope {
            val out = ByteArrayOutputStream()
            val done = CompletableDeferred<Unit>()
            val t0 = SystemClock.elapsedRealtime()
            var firstAt = 0L
            var chunks = 0
            // куски принимаем прямо в потоке чтения — так ни один не теряется
            link.setSink(Cmd.AI_PIC_PUSH) { m ->
                if (m.payload.isEmpty()) return@setSink
                if (chunks == 0) Core.log("ИИ-снимок: первый кусок ${m.payload.toHex(12)}")
                chunks++
                when (val st = m.payload[0].toInt() and 0xFF) {
                    0, 1 -> {
                        if (firstAt == 0L) firstAt = SystemClock.elapsedRealtime()
                        synchronized(out) { out.write(m.payload, 1, m.payload.size - 1) }
                        if (st == 0) done.complete(Unit)
                    }
                    else -> done.completeExceptionally(IOException("очки вернули ошибку снимка ($st)"))
                }
            }
            // Ответ на команду может прийти позже самого снимка или не прийти вовсе — его не ждём
            val req = launch {
                val code = runCatching { status(link.request(Cmd.CAMERA_SHOT, byteArrayOf(1), CAMERA_TIMEOUT)) }.getOrNull()
                if (code != null && code != 0) Core.log("Ответ очков на ИИ-снимок: $code")
            }
            try {
                val finished = withTimeoutOrNull(timeoutMs) { done.await() } != null
                val bytes = trimToJpeg(synchronized(out) { out.toByteArray() })
                if (!finished && !looksLikeJpeg(bytes)) {
                    throw IOException(
                        if (chunks == 0) "камера не прислала ни одного куска за ${timeoutMs / 1000} с"
                        else "снимок пришёл не целиком ($chunks кусков, ${bytes.size / 1024} КБ)"
                    )
                }
                if (bytes.isEmpty()) throw IOException("пустой снимок")
                if (!looksLikeJpeg(bytes)) Core.log("ИИ-снимок: не похож на JPEG, начало ${bytes.toHex(8)}, конец ${bytes.copyOfRange(maxOf(0, bytes.size - 4), bytes.size).toHex()}")
                val now = SystemClock.elapsedRealtime()
                Core.log("ИИ-снимок: ${bytes.size / 1024} КБ, $chunks кусков, первый через ${firstAt - t0} мс, весь за ${now - t0} мс")
                lastWarmAt = now
                bytes
            } finally {
                link.clearSink(Cmd.AI_PIC_PUSH)
                req.cancel()
            }
        }
    }

    /**
     * Обрезает всё до начала JPEG (FF D8 FF). Так отбрасывается хвост прошлого, прерванного
     * снимка, если он долетел уже после нового запроса. Если начала нет — данные как есть.
     */
    private fun trimToJpeg(b: ByteArray): ByteArray {
        for (i in 0 until b.size - 2) {
            if (b[i] == 0xFF.toByte() && b[i + 1] == 0xD8.toByte() && b[i + 2] == 0xFF.toByte()) {
                if (i > 0) Core.log("ИИ-снимок: отброшено $i байт перед началом JPEG")
                return if (i == 0) b else b.copyOfRange(i, b.size)
            }
        }
        return b
    }

    private fun looksLikeJpeg(b: ByteArray) =
        b.size > 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
            b[b.size - 2] == 0xFF.toByte() && b[b.size - 1] == 0xD9.toByte()

    /**
     * Включает на очках точку доступа и файловый сервер (для импорта по Wi-Fi).
     * Адрес сервера очки присылают уведомлением 0xE7 — его ловит Core.
     */
    suspend fun startFileServer(ssid: String, password: String): Boolean {
        // Wi-Fi включает тот же сопроцессор, что и камеру: он просыпается до 10 с,
        // поэтому ждём столько же, сколько команды камеры. Прежних 8 с не хватало.
        val r = link.request(Cmd.WIFI_CONFIG, wifiConfigPayload(1, ssid, password, 0), CAMERA_TIMEOUT)
        if (r == null) Core.log("Очки не подтвердили включение Wi-Fi — всё равно пробую подключиться")
        else if (status(r) != 0) Core.log("Ответ очков на настройку Wi-Fi: ${status(r)}")
        val f = link.request(Cmd.FILE_MANAGE, timeoutMs = CAMERA_TIMEOUT)
        // адрес файлов может прийти и прямо в ответе, а не только уведомлением
        f?.cString()?.trim()?.takeIf { it.length >= 7 && it.count { c -> c == '.' } >= 3 && it.all { c -> c.code in 0x21..0x7E } }?.let {
            Core.wifiFileAddress.value = it
            Core.log("Адрес файлов на очках (из ответа): $it")
        }
        return r != null || f != null
    }

    companion object {
        const val CAMERA_TIMEOUT = 25_000L

        /** Снимки идут по одному: два одновременных перемешали бы куски JPEG. */
        private val captureLock = Mutex()

        /** Запросы, которыми будим сопроцессор камеры. */
        private val WARM_COMMANDS = intArrayOf(Cmd.COPROCESSOR_INFO, Cmd.STORAGE_INFO, Cmd.MEDIA_FILE_COUNT)
        private const val WARM_INTERVAL_MS = 20_000L
        @Volatile private var lastWarmAt = 0L

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
