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
    /** Типы очков — из массива glassInfoToRequest в DeviceInfoRequest. */
    const val GLASS_SN = 0x85
    const val GLASS_DEVICE_MODEL = 0x86
    const val AI_CHAT_SUPPORT = 0x8A
    const val RESOLUTION = 0x93
    const val SCREEN_SWITCH = 0x9A
    const val WORD_SPEED = 0x9C
    /** INFO_DEVICE_ABILITY / INFO_DEVICE_CAPABILITIES: маска способностей, сырые байты. */
    const val DEVICE_ABILITY = 0xFE
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

/**
 * Ключи TLV для Cmd.GLASS_CONFIG (0xF7) — из GlassConfigRequest в SDK.
 * Кадр: 0xF7 [ключ][длина][значение].
 */
object GlassKey {
    const val VIDEO_LIMIT_TIME = 0x82    // GLASS_VIDEO_LIMIT_TIME
    const val AUDIO_LIMIT_TIME = 0x83    // GLASS_AUDIO_LIMIT_TIME
    const val AUTH_STATE = 0x8B          // SET_SERVICE_AUTH_STATE
    /** CONFIG_GLASS_BRIGHTNESS: один байт. Обратного чтения протокол не даёт. */
    const val BRIGHTNESS = 0x8C
    const val VOLUME = 0x8E              // CONFIG_GLASS_VOLUME
    const val RESOLUTION = 0x93          // GLASS_RESOLUTION, уровни 0..3
    const val SCREEN_SWITCH = 0x9A       // GLASS_SCREEN_SWITCH, 0/1
}

/**
 * Битовая маска «способностей» очков.
 *
 * Сверено с TopStep WearKit SDK 3.0.2.8 (sdk-abmate-adapter.aar):
 * `com.topstep.wearkit.abmate.internal.ability.speech.SpeechAiProtocolKt`,
 * `GLASS_ABILITY_HIDE_RECORD = 512`.
 *
 * Откуда берётся слово: `DEVICE_INFO` (0x27), ключ TLV `INFO_DEVICE_ABILITY`
 * = 0xFE, сырые байты (в SDK — `ByteArrayPayloadHandler`, поле `deviceAbility`).
 * Байты складываются little-endian: `mask or ((b[i] and 0xFF) shl (8 * i))`.
 *
 * Как проверяет SDK: `FlagUtil.isFlagEnabled(mask, 512)`, то есть
 * `(mask and 512) > 0`, а `isAbMateRecordSupported()` возвращает обратное —
 * и `true`, если слово ещё не приходило. То есть **бит выставлен = запись
 * не поддерживается и её надо скрыть**, по умолчанию запись доступна.
 *
 * HIDE_RECORD = 512 = 0x0200 = `1 shl 9` — девятый бит. Это единственная
 * константа GLASS_ABILITY_* во всём SDK (проверены все 22 aar), и больше
 * ни один бит этого слова нигде в SDK не читается. Поэтому describe()
 * печатает остальные биты номерами — по журналу будет видна вся маска.
 */
object GlassAbility {
    /** 512 = 0x0200 = бит 9: прятать запись (функцию и индикатор) в приложении. */
    const val HIDE_RECORD = 512

    /** Имена расшифрованных битов. */
    private val NAMES = mapOf(
        HIDE_RECORD to "скрывать запись (GLASS_ABILITY_HIDE_RECORD, 512)",
    )

    /** Слово способностей из payload: 1–4 байта, little-endian. null, если байтов нет. */
    fun parse(data: ByteArray): Int? {
        if (data.isEmpty()) return null
        var v = 0
        for (i in 0 until minOf(data.size, 4)) v = v or ((data[i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    /** Есть ли бит в маске. Как FlagUtil.isFlagEnabled в SDK: строго больше нуля. */
    fun has(mask: Int?, bit: Int): Boolean = mask != null && (mask and bit) > 0

    /** Поддерживается ли запись. Повторяет isAbMateRecordSupported: без данных — да. */
    fun isRecordSupported(mask: Int?): Boolean = !has(mask, HIDE_RECORD)

    /** Разбор маски: известные биты по именам, неизвестные — номерами. */
    fun describe(mask: Int): String {
        if (mask == 0) return "нет ни одного бита (0)"
        val parts = ArrayList<String>()
        for (bit in 0 until 32) {
            val v = 1 shl bit
            if ((mask and v) == 0) continue
            parts += NAMES[v] ?: "бит $bit (0x%04X)".format(v)
        }
        return parts.joinToString(", ") + " [маска 0x%04X]".format(mask)
    }
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
