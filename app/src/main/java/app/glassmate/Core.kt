package app.glassmate

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.graphics.Bitmap
import android.util.Log
import app.glassmate.glasses.Cmd
import app.glassmate.glasses.FrameType
import app.glassmate.glasses.GlassAbility
import app.glassmate.glasses.Glasses
import app.glassmate.glasses.GlassesLink
import app.glassmate.glasses.Info
import app.glassmate.glasses.Keys
import app.glassmate.glasses.cString
import app.glassmate.glasses.parseTlv
import app.glassmate.glasses.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Общее состояние приложения (одно на процесс). */
object Core {
    lateinit var app: Application
        private set
    lateinit var settings: Settings
        private set
    lateinit var assistant: Assistant
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val status = MutableStateFlow("Не подключено")
    val connected = MutableStateFlow(false)
    val logLines = MutableStateFlow<List<String>>(emptyList())
    val lastImage = MutableStateFlow<Bitmap?>(null)
    val lastAnswer = MutableStateFlow("")
    /** Адрес файлового сервера очков (приходит уведомлением при импорте по Wi-Fi). */
    val wifiFileAddress = MutableStateFlow<String?>(null)

    val deviceName = MutableStateFlow("AI Glasses")
    val firmware = MutableStateFlow("")
    /** Заряд очков: процент и признак зарядки. */
    val battery = MutableStateFlow<Pair<Int, Boolean>?>(null)
    /** Что сейчас пишут очки: "video", "audio" или null. */
    val recording = MutableStateFlow<String?>(null)
    /** Когда началась запись (SystemClock.elapsedRealtime) — для таймера на плитке. */
    @Volatile var recordingSince = 0L
    /** Ход импорта по Wi-Fi: «Импорт: файл 3 из 12 · 45%». Пусто — импорт не идёт. */
    val importProgress = MutableStateFlow("")
    /** Маска способностей очков (GLASS_ABILITY_*), null — ещё не приходила. */
    val abilities = MutableStateFlow<Int?>(null)
    /** Очки просят скрыть запись: бит GLASS_ABILITY_HIDE_RECORD (512). */
    val hideRecord = MutableStateFlow(false)

    /** Сообщения чата с ИИ. */
    data class ChatItem(val fromUser: Boolean, val text: String, val image: Bitmap? = null)
    val chat = MutableStateFlow<List<ChatItem>>(emptyList())
    /** Что делает ассистент: "", "Слушаю…", "Смотрю…", "Думаю…", "Отвечаю…". */
    val assistantState = MutableStateFlow("")

    /** Сообщения об ошибке подключения — экран показывает их диалогом. */
    val connectError = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun addChat(item: ChatItem) = chat.update { (it + item).takeLast(100) }

    /** Обновить текст последнего сообщения (ответ ИИ приходит по кусочкам). */
    fun updateLastChat(text: String) = chat.update { list ->
        if (list.isEmpty()) list else list.dropLast(1) + list.last().copy(text = text)
    }

    /** Последний кадр «живого взгляда»: время получения и JPEG. */
    val liveFrame = MutableStateFlow<Pair<Long, ByteArray>?>(null)

    @Volatile var link: GlassesLink? = null
        private set
    @Volatile var deviceAddress: String? = null
        private set

    val glasses: Glasses? get() = link?.takeIf { it.isOpen }?.let { Glasses(it) }

    private var watchJob: Job? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(app: Application) {
        this.app = app
        settings = Settings(app)
        assistant = Assistant(app)
    }

    fun log(msg: String) {
        Log.i("GlassMate", msg)
        val line = "${timeFmt.format(Date())}  $msg"
        logLines.update { (it + line).takeLast(300) }
    }

    /** Пользователь хочет быть на связи: после обрыва подключаемся снова сами. */
    @Volatile private var wantConnected = false
    private var connectJob: Job? = null
    private var reconnectJob: Job? = null

    /** Подключиться к очкам. silent — фоновая попытка: без диалога об ошибке. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun connect(address: String, silent: Boolean = false) {
        wantConnected = true
        if (!silent) reconnectJob?.cancel()
        connectJob?.cancel()
        connectJob = scope.launch(Dispatchers.IO) {
            closeLink()
            status.value = "Подключение…"
            try {
                val adapter = app.getSystemService(BluetoothManager::class.java)!!.adapter
                val dev = adapter.getRemoteDevice(address)
                val l = GlassesLink.connect(dev, adapter)
                // пока шло подключение, его могли отменить или заменить новым
                if (!wantConnected || !isActive) {
                    l.close()
                    return@launch
                }
                l.onClosed = {
                    if (link === l) {
                        link = null
                        resetState()
                        status.value = "Отключено"
                        log("Соединение с очками закрыто")
                        if (wantConnected) scheduleReconnect(address)
                    }
                }
                link = l
                deviceAddress = address
                settings.lastDevice = address
                connected.value = true
                status.value = "Подключено: ${dev.name ?: address}"
                log("Подключено к ${dev.name ?: address}")
                watch(l)
                Glasses(l).onConnected()
                if (settings.warmCamera) scope.launch { runCatching { Glasses(l).warmUp(force = true) } }
                pollBattery(l)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (silent) {
                    status.value = "Отключено"
                } else {
                    status.value = "Ошибка подключения"
                    log("Ошибка подключения: ${e.message}")
                    connectError.tryEmit(e.message ?: "неизвестная ошибка")
                }
            }
        }
    }

    /**
     * Очки отошли, выключились или легли в футляр — пробуем вернуться сами:
     * через 3 с, потом реже, но не реже раза в минуту.
     */
    @Synchronized
    private fun scheduleReconnect(address: String) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var wait = 3_000L
            while (wantConnected && link == null) {
                kotlinx.coroutines.delay(wait)
                if (!wantConnected || link != null) break
                val btOn = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true }
                    .getOrDefault(false)
                if (btOn) {
                    connect(address, silent = true)
                    connectJob?.join()
                }
                wait = (wait * 2).coerceAtMost(60_000L)
            }
        }
    }

    /** Подключиться к последним очкам, если ещё не подключены. */
    fun autoConnect() {
        val last = settings.lastDevice
        if (last.isNotEmpty() && link == null && status.value != "Подключение…") connect(last)
    }

    /** Разбудить камеру заранее (приложение открыли) — чтобы съёмка началась сразу. */
    fun warmCamera() {
        if (!settings.warmCamera || recording.value != null) return
        val g = glasses ?: return
        scope.launch { runCatching { g.warmUp() } }
    }

    /** Заряд раз в минуту — на случай, если очки не присылают его сами. */
    private fun pollBattery(l: GlassesLink) {
        scope.launch {
            while (l.isOpen) {
                kotlinx.coroutines.delay(60_000)
                if (!l.isOpen) break
                runCatching { Glasses(l).battery() }.getOrNull()?.let { battery.value = it }
            }
        }
    }

    /** Отключиться по желанию пользователя — без автоматического возврата. */
    @Synchronized
    fun disconnect() {
        wantConnected = false
        reconnectJob?.cancel()
        connectJob?.cancel()
        closeLink()
        status.value = "Отключено"
    }

    private fun closeLink() {
        watchJob?.cancel()
        val l = link
        link = null // сначала забываем, потом закрываем: onClosed не примет это за обрыв
        l?.close()
        resetState()
    }

    private fun resetState() {
        connected.value = false
        battery.value = null
        recording.value = null
        abilities.value = null
        hideRecord.value = false
    }

    /** Разбор уведомлений, которые очки шлют сами. */
    private fun watch(l: GlassesLink) {
        watchJob?.cancel()
        watchJob = scope.launch {
            l.notifications.collect { m ->
                when (m.cmd) {
                    // поток данных — не засоряем лог
                    Cmd.AI_PIC_PUSH, Cmd.LIVE_STREAM, Cmd.CAMERA_PIC_PUSH,
                    Cmd.AI_RECORD_DATA, Cmd.AI_CHAT_OPUS, Cmd.GLASS_DEBUG -> Unit

                    Cmd.FILE_MANAGE -> {
                        val a = m.payload.cString().trim()
                        if (a.isNotEmpty()) {
                            wifiFileAddress.value = a
                            log("Адрес файлов на очках: $a")
                        }
                    }
                    Cmd.WIFI_CONFIG_STATE -> log("Wi-Fi очков: состояние ${m.payload.toHex()}")
                    // Очки сами присылают информацию тем же TLV, что и в ответе на 0x27.
                    // Ключ 0xFE (INFO_DEVICE_ABILITY) — маска способностей.
                    Cmd.DEVICE_INFO -> {
                        val tlv = parseTlv(m.payload)
                        if (tlv.isEmpty()) log("Очки сообщили информацию: ${m.payload.toHex()}")
                        for ((k, v) in tlv) {
                            if (k == Info.DEVICE_ABILITY) onAbilities(v, "уведомление 0x27, ключ 0xFE")
                            else log("Очки сообщили информацию: тип 0x%02X = %s".format(k, v.toHex()))
                        }
                    }
                    Cmd.GLASS_CONFIG -> {
                        val tlv = parseTlv(m.payload)
                        if (tlv.isEmpty()) log("Настройки очков: ${m.payload.toHex()}")
                        else for ((k, v) in tlv) log("Настройки очков: ключ 0x%02X = %s".format(k, v.toHex()))
                    }
                    Cmd.WORK_STATE -> log("Режим очков: ${m.payload.firstOrNull()?.toInt()?.and(0xFF)}")
                    Cmd.AI_CHAT_STATE -> {
                        log("Очки: сигнал ИИ-кнопки (${m.payload.toHex()})")
                        if (settings.aiKeyTrigger) assistant.trigger("ИИ-кнопка очков")
                    }
                    Cmd.IN_EAR_DETECT -> log("Датчик ношения: ${m.payload.toHex()}")
                    Cmd.POWER -> m.payload.firstOrNull()?.let { battery.value = Glasses.parsePower(it) }
                    0x05 -> log("Очки сообщили новые настройки кнопок: ${m.payload.toHex()}")
                    else -> log("← 0x%02X%s: %s".format(
                        m.cmd, if (m.type == FrameType.REQUEST) " (запрос очков)" else "", m.payload.toHex(24)))
                }
            }
        }
    }

    /**
     * Разбор слова способностей очков. Подробности — в GlassAbility.
     *
     * Слово приходит ключом 0xFE (INFO_DEVICE_ABILITY) внутри DEVICE_INFO (0x27):
     * и в ответе на запрос при подключении, и уведомлением, если очки сообщают
     * его сами. Сырые байты пишем в журнал — по нему видна вся маска.
     */
    fun onAbilities(payload: ByteArray, from: String) {
        val mask = GlassAbility.parse(payload)
        if (mask == null) {
            log("Способности очков ($from): пустой ответ")
            return
        }
        abilities.value = mask
        hideRecord.value = (mask and GlassAbility.HIDE_RECORD) != 0
        log("Способности очков ($from): ${GlassAbility.describe(mask)}")
        if (hideRecord.value) log("Очки не поддерживают запись, её надо скрыть (бит 512)")
    }

    fun describeKeys(map: Map<Int, Int>): String =
        map.entries.joinToString("\n") { (g, f) ->
            "${Keys.GESTURES[g] ?: "Жест $g"} → ${Keys.FUNCTIONS[f] ?: "Действие $f"}"
        }
}
