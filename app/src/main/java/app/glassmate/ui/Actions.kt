package app.glassmate.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.View
import app.glassmate.Core
import app.glassmate.GlassesService
import app.glassmate.WifiImporter
import app.glassmate.glasses.Glasses
import app.glassmate.glasses.Info
import app.glassmate.glasses.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** То, что нужно экранам от главного окна. */
interface Host {
    val ctx: Activity
    val ui: CoroutineScope
    fun push(v: View)
    fun pop()
    fun selectTab(i: Int)
    fun toast(s: String)
}

object Actions {

    fun requireGlasses(h: Host): Glasses? {
        val g = Core.glasses
        if (g == null) {
            h.toast("Сначала подключите очки")
            connectDialog(h)
        }
        return g
    }

    @SuppressLint("MissingPermission")
    fun connectDialog(h: Host) {
        val a = h.ctx
        if (Build.VERSION.SDK_INT >= 31 &&
            a.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            a.requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        if (Core.connected.value) {
            a.dialog().setTitle(Core.deviceName.value)
                .setItems(arrayOf("Отключить", "Подключить другие очки")) { _, i ->
                    if (i == 0) Core.disconnect() else pickDevice(h)
                }.show()
        } else {
            pickDevice(h)
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice(h: Host) {
        val a = h.ctx
        val adapter = a.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            h.toast("Включите Bluetooth")
            return
        }
        val bonded = adapter.bondedDevices.orEmpty().sortedByDescending {
            val n = it.name.orEmpty().lowercase()
            listOf("mt5", "glass", "ai", "ultra").count { k -> n.contains(k) }
        }
        if (bonded.isEmpty()) {
            a.dialog().setTitle("Нет сопряжённых устройств")
                .setMessage("Сначала подключите очки в настройках Bluetooth телефона.")
                .setPositiveButton("ОК", null).show()
            return
        }
        val names = bonded.map { "${it.name ?: "Без имени"}\n${it.address}" }.toTypedArray()
        a.dialog().setTitle("Выберите очки")
            .setItems(names) { _, i ->
                GlassesService.start(a)
                Core.connect(bonded[i].address)
            }.show()
    }

    private fun runOnGlasses(h: Host, name: String, block: suspend (Glasses) -> String?) {
        val g = requireGlasses(h) ?: return
        Core.scope.launch {
            try {
                val msg = block(g)
                if (msg != null) {
                    Core.log("$name: $msg")
                    h.ui.launch { h.toast(msg) }
                }
            } catch (e: Exception) {
                Core.log("$name: ошибка — ${e.message}")
                h.ui.launch { h.toast("$name: ${e.message}") }
            }
        }
    }

    // Кнопки камеры реагируют сразу: команда уходит в ту же секунду, состояние на экране
    // меняется, не дожидаясь ответа очков. Если очки откажут — состояние вернётся.

    fun photo(h: Host) {
        val g = requireGlasses(h) ?: return
        h.toast("Снимаю…")
        Core.scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val msg = try {
                when (g.takePhoto()) {
                    0 -> "Фото сохранено на очках (${secs(t0)})"
                    null -> "Очки не подтвердили снимок"
                    else -> "Очки отказались фотографировать (занята камера?)"
                }
            } catch (e: Exception) {
                "Фото: ${e.message}"
            }
            Core.log(msg)
            h.ui.launch { h.toast(msg) }
        }
    }

    fun toggleVideo(h: Host) = toggleRecording(h, "video", "видеозапись") { it.startVideo() }

    fun toggleAudio(h: Host) = toggleRecording(h, "audio", "аудиозапись") { it.startAudio() }

    private fun toggleRecording(h: Host, kind: String, name: String, start: suspend (Glasses) -> Int?) {
        val g = requireGlasses(h) ?: return
        if (Core.recording.value != null) {
            Core.recording.value = null
            h.toast("Запись остановлена")
            Core.scope.launch {
                val r = runCatching { g.stopCamera() }.getOrNull()
                Core.log("Стоп записи: ${if (r == null) "очки не ответили" else "код $r"}")
            }
            return
        }
        Core.recording.value = kind
        Core.scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val code = try {
                start(g)
            } catch (e: Exception) {
                if (Core.recording.value == kind) Core.recording.value = null
                Core.log("Старт записи ($name): ${e.message}")
                h.ui.launch { h.toast("Запись не началась: ${e.message}") }
                return@launch
            }
            when (code) {
                0 -> Core.log("Идёт $name, очки подтвердили за ${secs(t0)}")
                null -> Core.log("Старт записи ($name): очки не ответили — проверьте индикатор на очках")
                else -> {
                    if (Core.recording.value == kind) Core.recording.value = null
                    Core.log("Очки не начали запись ($name), код $code")
                    h.ui.launch { h.toast("Очки не начали запись") }
                }
            }
        }
    }

    private fun secs(t0: Long) = "%.1f с".format((SystemClock.elapsedRealtime() - t0) / 1000.0)

    /**
     * Замер скорости камеры: три снимка на память очков подряд и время ответа на каждый,
     * между ними — запросы, которыми будим камеру. По журналу видно, сколько занимает
     * «холодный» снимок, сколько повторный и помогает ли пробуждение.
     */
    fun cameraSpeedTest(h: Host) {
        val g = requireGlasses(h) ?: return
        h.toast("Тест камеры: около 40 секунд, очки сделают 3 снимка")
        Core.scope.launch {
            val res = ArrayList<String>()
            suspend fun shot(label: String) {
                val t0 = SystemClock.elapsedRealtime()
                val r = runCatching { g.takePhoto() }.getOrNull()
                val line = "$label: ${if (r == null) "нет ответа" else "код $r"} за ${secs(t0)}"
                Core.log("Тест камеры — $line")
                res += line
            }
            Core.log("Тест камеры: начало")
            shot("1-й снимок (как есть)")
            shot("2-й снимок (сразу следом)")
            Core.log("Тест камеры: пауза 20 с, чтобы камера уснула")
            kotlinx.coroutines.delay(20_000)
            val t0 = SystemClock.elapsedRealtime()
            runCatching { g.warmUp(force = true) }
            Core.log("Тест камеры — пробуждение заняло ${secs(t0)}")
            shot("3-й снимок (после пробуждения)")
            Core.log("Тест камеры: конец")
            h.ui.launch {
                h.ctx.dialog().setTitle("Тест камеры")
                    .setMessage(res.joinToString("\n") + "\n\nПодробности — в журнале (кнопка «Копировать»).")
                    .setPositiveButton("ОК", null).show()
            }
        }
    }

    fun aiPhoto(h: Host) = runOnGlasses(h, "ИИ-снимок") { g ->
        h.ui.launch { h.toast("Снимаю…") }
        val raw = g.captureAiPhoto()
        Core.assistant.prepareImage(raw)
        Core.addChat(Core.ChatItem(true, "Снимок с камеры очков", Core.lastImage.value))
        h.ui.launch { h.selectTab(1) }
        null
    }

    /**
     * Яркость экрана очков (0xF7 [0x8C]). Прочитать текущее значение нечем, поэтому
     * диалог просто отправляет уровень и пишет в журнал код ответа. Если на нуле
     * экран погаснет — вернуть видимость можно тем же диалогом.
     */
    fun brightnessDialog(h: Host) {
        requireGlasses(h) ?: return
        val levels = intArrayOf(0, 25, 50, 100, 128, 255)
        h.ctx.dialog().setTitle("Яркость экрана очков")
            .setItems(levels.map { "Уровень $it" }.toTypedArray()) { _, i ->
                val level = levels[i]
                runOnGlasses(h, "Яркость") { g ->
                    when (val st = g.setBrightness(level)) {
                        0 -> "$level — очки приняли (код 0)"
                        null -> "$level — очки не ответили"
                        else -> "$level — очки отвергли, код $st"
                    }
                }
            }.show()
    }

    /** Диагностика: есть ли у очков экран и что они о нём сообщают. */
    fun probeScreen(h: Host) = runOnGlasses(h, "Экран") { g ->
        val info = g.deviceInfo(
            Info.SCREEN_SWITCH, Info.RESOLUTION, Info.WORD_SPEED,
            Info.GLASS_DEVICE_MODEL, Info.AI_CHAT_SUPPORT,
        )
        if (info.isEmpty()) {
            "очки не ответили на запрос информации"
        } else {
            for ((k, v) in info) Core.log("Экран: тип 0x%02X = %s".format(k, v.toHex()))
            "ответили на ${info.size} полей — подробности в журнале"
        }
    }

    /** Диагностика: что очки шлют в режиме трансляции (0xE8 без параметров → уведомления 0xBC). */
    fun testBtStream(h: Host) {
        val g = requireGlasses(h) ?: return
        val link = Core.link ?: return
        h.toast("Тест видеопотока: 6 секунд…")
        Core.scope.launch {
            var count = 0
            var bytes = 0L
            val firsts = ArrayList<String>()
            val job = launch {
                link.notifications.collect { m ->
                    if (m.cmd == app.glassmate.glasses.Cmd.LIVE_STREAM || m.cmd == app.glassmate.glasses.Cmd.START_STREAMING) {
                        count++
                        bytes += m.payload.size
                        if (firsts.size < 6) firsts += "0x%02X[%d] %s".format(m.cmd, m.payload.size, m.payload.toHex(20))
                    }
                }
            }
            val r = link.request(app.glassmate.glasses.Cmd.START_STREAMING, timeoutMs = 15000)
            Core.log("Видеопоток: ответ на старт ${r?.toHex() ?: "нет"}")
            kotlinx.coroutines.delay(6000)
            job.cancel()
            runCatching { g.stopCamera() }
            Core.log("Видеопоток: пакетов $count, байт $bytes (≈${bytes / 6 / 1024} КБ/с)")
            firsts.forEach { Core.log("Видеопоток: $it") }
            h.ui.launch { h.toast("Тест завершён: пакетов $count, ${bytes / 1024} КБ. Подробности в журнале") }
        }
    }

    fun importMedia(h: Host, onDone: (() -> Unit)? = null) {
        requireGlasses(h) ?: return
        h.toast("Импорт: включаю Wi-Fi на очках…")
        Core.scope.launch {
            try {
                val n = WifiImporter(h.ctx.applicationContext).import()
                Core.log("Импорт завершён: файлов — $n")
                h.ui.launch { h.toast("Импортировано файлов: $n"); onDone?.invoke() }
            } catch (e: Exception) {
                Core.log("Импорт: ошибка — ${e.message}")
                h.ui.launch { h.toast("Импорт не удался: ${e.message}") }
            }
        }
    }
}
