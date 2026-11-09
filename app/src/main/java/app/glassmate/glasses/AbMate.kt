package app.glassmate.glasses

import java.io.ByteArrayOutputStream

/*
 * Протокол ABMate (чипы Bluetrum), на котором работают очки MT5 Ultra.
 * Восстановлен по приложению AIBuds 1.9.8 (com.topstep.aibuds).
 *
 * Кадр:  [seq] [command] [type] [frag] [len] [payload...]
 *   seq   — счётчик 0..15, растёт на каждый кадр
 *   type  — 1 = запрос, 2 = ответ, 3 = уведомление от очков
 *   frag  — старшие 4 бита: (кол-во кадров - 1), младшие: номер кадра
 *   len   — длина payload в этом кадре (по умолчанию до 15 байт)
 */

object FrameType {
    const val REQUEST = 1
    const val RESPONSE = 2
    const val NOTIFY = 3
}

/** Коды команд (из com.bluetrum.devicemanager.cmd.Command). */
object Cmd {
    const val POWER = 0x01               // уведомление: заряд [байт: бит 7 = заряжается, 0..6 = %]
    const val KEY = 0x22                 // настройка кнопок: TLV [keyType][1][function]
    const val IN_EAR_DETECT = 0x26       // обнаружение ношения: [1/0]
    const val DEVICE_INFO = 0x27         // запрос информации: [type][0]...
    const val AI_CHAT_STATE = 0xB0
    const val AI_RECORD_STATE = 0xB1
    const val AI_RECORD_DATA = 0xB2
    const val LIVE_STREAM = 0xBC
    const val CAMERA_PIC_PUSH = 0xBD
    const val AI_CHAT_OPUS = 0x9F
    const val COPROCESSOR_INFO = 0xE0
    const val CAMERA_SHOT = 0xE1         // [0] = фото на карту очков, [1] = ИИ-снимок (JPEG приходит по Bluetooth)
    const val CAMERA_OFF = 0xE2          // выключить камеру / остановить запись / выключить Wi-Fi
    const val AI_PIC_PUSH = 0xE3         // уведомление: [status][кусок JPEG]; status 1 = будет ещё, 0 = последний
    const val START_VIDEO = 0xE4
    const val START_AUDIO = 0xE5
    const val WIFI_CONFIG = 0xE6
    const val FILE_MANAGE = 0xE7         // запуск файлового сервера; уведомление = адрес файлов (строка)
    const val START_STREAMING = 0xE8     // уведомление = адрес RTSP
    const val COPROCESSOR_VERSION = 0xE9
    const val STORAGE_INFO = 0xEA
    const val WIFI_CONFIG_STATE = 0xEB
    const val MEDIA_FILE_COUNT = 0xEF
    const val SET_TIME = 0xF0
    const val WIFI_DIRECT_ADDRESS = 0xF2
    const val WORK_STATE = 0xF6
    const val GLASS_CONFIG = 0xF7        // TLV [key][len][value]
    const val GLASS_DEBUG = 0xFC
}

/** Типы информации для Cmd.DEVICE_INFO. */
object Info {
    const val POWER = 0x01
    const val FIRMWARE = 0x02
    const val NAME = 0x03
    const val KEY_SETTINGS = 0x05
    const val VOLUME = 0x06
    const val MAX_PACKET_SIZE = 0xFF
}

/** Жесты и действия для Cmd.KEY (из KeyRequest). */
object Keys {
    val GESTURES = linkedMapOf(
        1 to "Левая дужка: одно касание",
        2 to "Правая дужка: одно касание",
        3 to "Левая дужка: двойное касание",
        4 to "Правая дужка: двойное касание",
        5 to "Левая дужка: тройное касание",
        6 to "Правая дужка: тройное касание",
        7 to "Левая дужка: удержание",
        8 to "Правая дужка: удержание",
    )
    val FUNCTIONS = linkedMapOf(
        0 to "Ничего",
        1 to "Перезвонить",
        2 to "Голосовой ассистент",
        3 to "Предыдущий трек",
        4 to "Следующий трек",
        5 to "Громкость +",
        6 to "Громкость −",
        7 to "Плей / пауза",
        8 to "Игровой режим",
        9 to "Режим шумоподавления",
    )
}

class Message(val cmd: Int, val type: Int, val payload: ByteArray) {
    override fun toString() = "cmd=0x%02X type=%d payload=%s".format(cmd, type, payload.toHex())
}

fun ByteArray.toHex(max: Int = 48): String {
    val sb = StringBuilder()
    for (i in 0 until minOf(size, max)) sb.append("%02X".format(this[i].toInt() and 0xFF))
    if (size > max) sb.append("…(${size}б)")
    return sb.toString()
}

/** Строка до первого нулевого байта. */
fun ByteArray.cString(): String {
    val end = indexOf(0.toByte()).let { if (it < 0) size else it }
    return String(this, 0, end, Charsets.UTF_8)
}

/** Разбор [type][len][value]... */
fun parseTlv(data: ByteArray): Map<Int, ByteArray> {
    val out = LinkedHashMap<Int, ByteArray>()
    var p = 0
    while (data.size - p >= 2) {
        val t = data[p].toInt() and 0xFF
        val len = data[p + 1].toInt() and 0xFF
        if (data.size - p - 2 < len) break
        out[t] = data.copyOfRange(p + 2, p + 2 + len)
        p += 2 + len
    }
    return out
}

class FrameEncoder(var maxPayload: Int = 15) {
    private var seq = 0

    @Synchronized
    fun encode(cmd: Int, payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) {
            return listOf(byteArrayOf(nextSeq(), cmd.toByte(), FrameType.REQUEST.toByte(), 0, 0))
        }
        val total = (payload.size + maxPayload - 1) / maxPayload
        require(total <= 16) { "Слишком длинная команда (${payload.size} байт)" }
        val out = ArrayList<ByteArray>(total)
        for (i in 0 until total) {
            val from = i * maxPayload
            val len = minOf(maxPayload, payload.size - from)
            val f = ByteArray(5 + len)
            f[0] = nextSeq()
            f[1] = cmd.toByte()
            f[2] = FrameType.REQUEST.toByte()
            f[3] = ((((total - 1) shl 4) and 0xF0) or (i and 0x0F)).toByte()
            f[4] = len.toByte()
            System.arraycopy(payload, from, f, 5, len)
            out += f
        }
        return out
    }

    private fun nextSeq(): Byte {
        val s = seq
        seq = (seq + 1) and 0x0F
        return s.toByte()
    }
}

/**
 * Собирает сообщения из прочитанных кусков. По тому же каналу очки шлют
 * пакеты другого протокола (облачный ИИ StarBurst), их пропускаем — так же
 * поступает и официальное приложение.
 */
class FrameDecoder {
    private var cmd = -1
    private var type = -1
    private var total = 0
    private var expected = 0
    private val buf = ByteArrayOutputStream()

    fun feed(chunk: ByteArray): List<Message> {
        val out = ArrayList<Message>()
        var p = 0
        while (chunk.size - p >= 5) {
            val c = chunk[p + 1].toInt() and 0xFF
            val t = chunk[p + 2].toInt() and 0xFF
            val frag = chunk[p + 3].toInt() and 0xFF
            val len = chunk[p + 4].toInt() and 0xFF
            if (t !in 1..3 || chunk.size - p - 5 < len) {
                // Не наш кадр: ищем дальше начало следующего кадра ABMate
                p++
                continue
            }
            val payload = chunk.copyOfRange(p + 5, p + 5 + len)
            p += 5 + len
            val index = frag and 0x0F
            val count = ((frag shr 4) and 0x0F) + 1
            if (index == 0) {
                reset()
                cmd = c; type = t; total = count
            } else if (c != cmd || t != type || count != total || index != expected) {
                reset()
                continue
            }
            buf.write(payload)
            expected = index + 1
            if (index == total - 1) {
                out += Message(cmd, type, buf.toByteArray())
                reset()
            }
        }
        return out
    }

    private fun reset() {
        cmd = -1; type = -1; total = 0; expected = 0
        buf.reset()
    }
}
