package app.glassmate

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.graphics.Bitmap
import android.util.Log
import app.glassmate.glasses.Cmd
import app.glassmate.glasses.Glasses
import app.glassmate.glasses.GlassesLink
import app.glassmate.glasses.Keys
import app.glassmate.glasses.cString
import app.glassmate.glasses.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

    /** Сообщения чата с ИИ. */
    data class ChatItem(val fromUser: Boolean, val text: String, val image: Bitmap? = null)
    val chat = MutableStateFlow<List<ChatItem>>(emptyList())
    /** Что делает ассистент: "", "Слушаю…", "Смотрю…", "Думаю…", "Отвечаю…". */
    val assistantState = MutableStateFlow("")

    fun addChat(item: ChatItem) = chat.update { (it + item).takeLast(100) }

    @Volatile var link: GlassesLink? = null
        private set
    @Volatile var deviceAddress: String? = null
        private set

    val glasses: Glasses? get() = link?.takeIf { it.isOpen }?.let { Glasses(it) }

    private var watchJob: Job? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

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

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        scope.launch(Dispatchers.IO) {
            disconnect()
            status.value = "Подключение…"
            try {
                val adapter = app.getSystemService(BluetoothManager::class.java)!!.adapter
                val dev = adapter.getRemoteDevice(address)
                val l = GlassesLink.connect(dev, adapter)
                l.onClosed = {
                    if (link === l) {
                        link = null
                        connected.value = false
                        battery.value = null
                        recording.value = null
                        status.value = "Отключено"
                        log("Соединение с очками закрыто")
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
            } catch (e: Exception) {
                status.value = "Ошибка подключения"
                log("Ошибка подключения: ${e.message}")
            }
        }
    }

    fun disconnect() {
        watchJob?.cancel()
        link?.close()
        link = null
        connected.value = false
        battery.value = null
        recording.value = null
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
                    Cmd.WORK_STATE -> log("Режим очков: ${m.payload.firstOrNull()?.toInt()?.and(0xFF)}")
                    Cmd.AI_CHAT_STATE -> {
                        log("Очки: сигнал ИИ-кнопки (${m.payload.toHex()})")
                        if (settings.aiKeyTrigger) assistant.trigger("ИИ-кнопка очков")
                    }
                    Cmd.POWER -> m.payload.firstOrNull()?.let { battery.value = Glasses.parsePower(it) }
                    0x05 -> log("Очки сообщили новые настройки кнопок: ${m.payload.toHex()}")
                    else -> log("← 0x%02X: %s".format(m.cmd, m.payload.toHex(24)))
                }
            }
        }
    }

    fun describeKeys(map: Map<Int, Int>): String =
        map.entries.joinToString("\n") { (g, f) ->
            "${Keys.GESTURES[g] ?: "Жест $g"} → ${Keys.FUNCTIONS[f] ?: "Действие $f"}"
        }
}
