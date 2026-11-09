package app.glassmate.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.glassmate.Core
import app.glassmate.GlassesService
import app.glassmate.R
import app.glassmate.Settings
import app.glassmate.glasses.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Экран с заголовком и прокручиваемым содержимым. */
private fun Host.screen(title: String, action: View? = null, fill: (LinearLayout) -> Unit): View = with(ctx) {
    val content = vbox(dp(16))
    fill(content)
    vbox().apply {
        setBackgroundColor(Col.BG)
        addView(screenHeader(title, { pop() }, action))
        addView(ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }, lin(MATCH, 0, 1f))
    }
}

private fun gap(l: LinearLayout) = l.addView(View(l.context), lin(MATCH, l.context.dp(10)))

/** Выполнить команду на очках в фоне и сообщить результат. */
private fun Host.onGlasses(ok: String, block: suspend (app.glassmate.glasses.Glasses) -> Boolean) {
    val g = Core.glasses ?: run { toast("Очки не подключены — настройка применится при следующей отправке"); return }
    Core.scope.launch {
        val res = runCatching { block(g) }.getOrDefault(false)
        ui.launch { toast(if (res) ok else "Очки не ответили") }
    }
}

// ---------------- Вкладка «Мой» ----------------

fun meTab(h: Host): View = with(h.ctx) {
    val root = vbox(dp(16))
    root.addView(FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF7A7C82.toInt()) }
        addView(icon(R.drawable.ic_person, 0xFF1E1F23.toInt(), 96).apply {
            layoutParams = FrameLayout.LayoutParams(dp(96), dp(96), Gravity.CENTER or Gravity.BOTTOM)
        })
        clipToOutline = true
    }, LinearLayout.LayoutParams(dp(120), dp(120)).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = dp(48)
    })
    root.addView(label("GlassMate", 24f, bold = true).apply { gravity = Gravity.CENTER }, lin(MATCH, WRAP, top = dp(16)))
    val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
    root.addView(label("Версия $version", 14f, Col.SUB).apply { gravity = Gravity.CENTER }, lin(MATCH, WRAP, bottom = dp(28)))

    root.addView(arrowRow(R.drawable.ic_shield, "Защита от фонового режима", "Чтобы Android не выключал связь с очками") {
        requestBatteryExemption(h)
    }.first)
    gap(root)
    root.addView(arrowRow(R.drawable.ic_list, "Журнал", "Что происходит с очками и ИИ") { h.push(logScreen(h)) }.first)
    gap(root)
    root.addView(arrowRow(R.drawable.ic_settings, "Настройки") { h.push(settingsScreen(h)) }.first)
    gap(root)
    root.addView(arrowRow(R.drawable.ic_info, "Исходный код", "github.com/zaberezh/ai-glasses") {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/zaberezh/ai-glasses"))) }
    }.first)
    ScrollView(this).apply { addView(root) }
}

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(h: Host) {
    val a = h.ctx
    val pm = a.getSystemService(PowerManager::class.java)
    if (pm != null && pm.isIgnoringBatteryOptimizations(a.packageName)) {
        h.toast("Уже включено: Android не ограничивает GlassMate")
        return
    }
    runCatching {
        a.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
    }.onFailure {
        runCatching { a.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

// ---------------- Настройки ----------------

fun settingsScreen(h: Host): View = h.screen("Настройки") { c ->
    val a = h.ctx
    val s = Core.settings
    with(a) {
        c.addView(sectionTitle("Общие сведения"))
        c.addView(switchRow(R.drawable.ic_glasses_outline, "Обнаружение ношения",
            "Музыка ставится на паузу, когда очки сняты", s.wearDetection) { v ->
            s.wearDetection = v
            h.onGlasses(if (v) "Обнаружение ношения включено" else "Обнаружение ношения выключено") { it.setWearDetection(v) }
        })
        gap(c)
        val video = arrowRow(R.drawable.ic_videocam, "Длительность видеозаписи", "${s.videoLimit} мин") {}
        video.first.setOnClickListener {
            val opts = listOf(1, 3, 5, 10, 12, 15, 20, 30)
            dialog().setTitle("Длительность видеозаписи")
                .setSingleChoiceItems(opts.map { "$it мин" }.toTypedArray(), opts.indexOf(s.videoLimit)) { d, i ->
                    s.videoLimit = opts[i]
                    video.second.text = "${opts[i]} мин"
                    h.onGlasses("Сохранено на очках") { it.setVideoLimit(opts[i]) }
                    d.dismiss()
                }.show()
        }
        c.addView(video.first)
        gap(c)
        val audio = arrowRow(R.drawable.ic_mic, "Длительность аудиозаписи", "${s.audioLimit} мин") {}
        audio.first.setOnClickListener {
            val opts = listOf(5, 10, 30, 60, 90, 120)
            dialog().setTitle("Длительность аудиозаписи")
                .setSingleChoiceItems(opts.map { "$it мин" }.toTypedArray(), opts.indexOf(s.audioLimit)) { d, i ->
                    s.audioLimit = opts[i]
                    audio.second.text = "${opts[i]} мин"
                    h.onGlasses("Сохранено на очках") { it.setAudioLimit(opts[i]) }
                    d.dismiss()
                }.show()
        }
        c.addView(audio.first)

        c.addView(sectionTitle("Ассистент"))
        c.addView(switchRow(R.drawable.ic_mic, "Микрофон очков", "Слушать вопрос через очки, а не телефон", s.useGlassesMic) {
            s.useGlassesMic = it
        })
        gap(c)
        c.addView(switchRow(R.drawable.ic_touch, "Касания дужки запускают ИИ",
            "Плей/пауза — вопрос, следующий трек — «Что я вижу?»", s.mediaButtons) {
            s.mediaButtons = it
            if (Core.connected.value) GlassesService.start(a, GlassesService.ACTION_REFRESH_BUTTONS)
        })
        gap(c)
        c.addView(switchRow(R.drawable.ic_sparkles, "ИИ-кнопка очков",
            "Запускать ассистента по сигналу 0xB0 от очков", s.aiKeyTrigger) { s.aiKeyTrigger = it })
        gap(c)
        c.addView(switchRow(R.drawable.ic_image, "Сохранять ИИ-снимки", "В галерею, папка GlassMate", s.saveAiPhotos) {
            s.saveAiPhotos = it
        })
        gap(c)
        val langs = linkedMapOf("ru-RU" to "Русский", "be-BY" to "Беларуская", "uk-UA" to "Українська", "en-US" to "English")
        val lang = arrowRow(R.drawable.ic_translate, "Язык речи", langs[s.language] ?: s.language) {}
        lang.first.setOnClickListener {
            val keys = langs.keys.toList()
            dialog().setTitle("Язык речи")
                .setSingleChoiceItems(langs.values.toTypedArray(), keys.indexOf(s.language)) { d, i ->
                    s.language = keys[i]
                    lang.second.text = langs[keys[i]]
                    d.dismiss()
                }.show()
        }
        c.addView(lang.first)

        c.addView(sectionTitle("Устройство"))
        c.addView(arrowRow(R.drawable.ic_time, "Синхронизировать время") {
            h.onGlasses("Время на очках обновлено") { it.setTime() }
        }.first)
        gap(c)
        c.addView(arrowRow(R.drawable.ic_info, "Информация об устройстве") {
            val b = Core.battery.value
            dialog().setTitle(Core.deviceName.value).setMessage(
                "Состояние: ${if (Core.connected.value) "подключены" else "не подключены"}\n" +
                    "Адрес: ${Core.deviceAddress ?: "—"}\n" +
                    "Заряд: ${b?.let { "${it.first}%" + if (it.second) ", заряжаются" else "" } ?: "—"}\n" +
                    "Прошивка: ${Core.firmware.value.ifEmpty { "—" }}"
            ).setPositiveButton("ОК", null).show()
        }.first)
        gap(c)
        c.addView(arrowRow(R.drawable.ic_bluetooth, "Подключение очков") { Actions.connectDialog(h) }.first)
        gap(c)
        c.addView(arrowRow(R.drawable.ic_videocam, "Проверить видеопоток по Bluetooth",
            "Тест: включает трансляцию камеры на 6 секунд и пишет в журнал, что пришло") {
            Actions.testBtStream(h)
        }.first)
        gap(c)
        c.addView(switchRow(R.drawable.ic_list, "Подробный журнал", "Все байты обмена с очками — для отладки", s.verboseLog) {
            s.verboseLog = it
        })
    }
}

// ---------------- Жесты ----------------

fun gesturesScreen(h: Host): View = h.screen("Жесты сенсорной панели") { c ->
    val a = h.ctx
    with(a) {
        c.addView(label(
            "Назначьте касаниям дужек действия. Чтобы касание запускало ИИ, выберите «Плей / пауза» " +
                "(вопрос голосом) или «Следующий трек» («Что я вижу?»). «Предыдущий трек» прерывает ответ ИИ.",
            14f, Col.SUB
        ).apply { setPadding(dp(4), 0, dp(4), dp(14)) })
        val subs = HashMap<Int, TextView>()
        val current = HashMap<Int, Int>()

        fun reload() {
            val g = Core.glasses ?: run {
                subs.values.forEach { it.text = "очки не подключены" }
                return
            }
            Core.scope.launch {
                val keys = runCatching { g.readKeys() }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (keys == null) h.toast("Очки не прислали настройки кнопок")
                    for ((gesture, tv) in subs) {
                        val f = keys?.get(gesture)
                        if (f != null) current[gesture] = f
                        tv.text = when {
                            keys == null -> "—"
                            f == null -> "не поддерживается"
                            else -> Keys.FUNCTIONS[f] ?: "действие $f"
                        }
                    }
                }
            }
        }

        for ((gesture, title) in Keys.GESTURES) {
            val row = arrowRow(R.drawable.ic_touch, title, "…") {}
            subs[gesture] = row.second
            row.first.setOnClickListener {
                val g = Core.glasses ?: run { Actions.connectDialog(h); return@setOnClickListener }
                val ids = Keys.FUNCTIONS.keys.toList()
                dialog().setTitle(title)
                    .setSingleChoiceItems(Keys.FUNCTIONS.values.toTypedArray(), ids.indexOf(current[gesture] ?: -1)) { d, i ->
                        d.dismiss()
                        row.second.text = "сохраняю…"
                        Core.scope.launch {
                            runCatching { g.setKey(gesture, ids[i]) }
                            withContext(Dispatchers.Main) { reload() }
                        }
                    }.show()
            }
            c.addView(row.first)
            gap(c)
        }
        reload()
    }
}

// ---------------- Настройки ИИ ----------------

fun aiSettingsScreen(h: Host): View = h.screen("Настройки ИИ-помощника") { c ->
    val a = h.ctx
    val s = Core.settings
    with(a) {
        var provider = s.provider
        val chips = hbox()
        val hint = label("", 13f, Col.SUB).apply { setPadding(dp(4), dp(10), dp(4), 0) }
        fun drawChips() {
            chips.removeAllViews()
            listOf("anthropic" to "Claude (Anthropic)", "openai" to "OpenAI-совместимый").forEach { (id, t) ->
                chips.addView(chip(t, provider == id) { provider = id; drawChips() }, lin(WRAP, WRAP, end = dp(10)))
            }
            hint.text = if (provider == "anthropic") {
                "Для Claude и сервисов-посредников с форматом Anthropic (например ai.starimg.ru, если у них есть " +
                    "инструкция для Claude Code). Адрес можно оставить пустым — тогда api.anthropic.com."
            } else {
                "Для OpenAI, OpenRouter, Gemini и посредников с форматом OpenAI. Укажите адрес, например " +
                    "https://api.openai.com/v1, и модель, которая понимает изображения."
            }
        }
        drawChips()
        c.addView(label("Формат API", 13f, Col.SUB).apply { setPadding(dp(4), 0, 0, dp(8)) })
        c.addView(chips)
        c.addView(hint)

        val (urlBox, url) = field("Адрес API (Base URL)", s.baseUrl, "https://…")
        val (modelBox, model) = field("Модель", s.model, "например claude-sonnet-5-5")
        val (keyBox, key) = field("API-ключ", s.apiKey, "ключ из личного кабинета", password = true)
        val (promptBox, prompt) = field("Системная инструкция", s.systemPrompt, multiline = true)
        val (lookBox, look) = field("Вопрос для «Что я вижу?»", s.lookQuestion)
        listOf(urlBox, modelBox, keyBox, promptBox, lookBox).forEach { c.addView(it) }
        gap(c)
        var attach = s.attachPhoto
        var auto = s.autoPhoto
        c.addView(switchRow(R.drawable.ic_camera, "Камера очков для ИИ", "ИИ может видеть кадр с камеры очков", attach) { attach = it })
        gap(c)
        c.addView(switchRow(R.drawable.ic_eye, "Снимок только по смыслу",
            "Фотографировать, только если вопрос про то, что перед глазами («что это», «прочитай»…). Так ответ быстрее.",
            auto) { auto = it })

        c.addView(sectionTitle("Live-режим (Gemini)"))
        c.addView(label(
            "Живой разговор голосом: ИИ слышит вас через очки, видит кадр с камеры каждые 1–2 секунды, " +
                "его можно перебивать. Нужен ключ Gemini API: aistudio.google.com → Get API key (бесплатно). " +
                "Запуск — кнопка «● Live» во вкладке AI.",
            13f, Col.SUB
        ).apply { setPadding(dp(4), 0, dp(4), 0) })
        val (gkBox, gk) = field("Ключ Gemini API", s.geminiKey, "AIza…", password = true)
        val (gmBox, gm) = field("Модель Live (пусто — выбрать автоматически)", s.geminiLiveModel, "автоматически")
        val (gvBox, gv) = field("Голос (Puck, Charon, Kore, Fenrir, Aoede, Leda, Orus, Zephyr)", s.geminiVoice)
        val (gfBox, gf) = field("Кадр с камеры каждые, мс", s.liveFrameMs.toString())
        gf.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        listOf(gkBox, gmBox, gvBox, gfBox).forEach { c.addView(it) }

        c.addView(primaryButton("Сохранить") {
            s.autoPhoto = auto
            s.geminiKey = gk.text.toString().trim()
            s.geminiLiveModel = gm.text.toString().trim()
            s.geminiVoice = gv.text.toString().trim().ifEmpty { "Puck" }
            s.liveFrameMs = gf.text.toString().toIntOrNull()?.coerceIn(500, 10000) ?: 1500
            s.provider = provider
            s.baseUrl = url.text.toString().trim()
            s.model = model.text.toString().trim()
            s.apiKey = key.text.toString().trim()
            s.systemPrompt = prompt.text.toString().trim().ifEmpty { Settings.DEFAULT_PROMPT }
            s.lookQuestion = look.text.toString().trim().ifEmpty { "Что ты видишь?" }
            s.attachPhoto = attach
            h.toast("Сохранено")
            h.pop()
        }, lin(MATCH, WRAP, top = dp(20), bottom = dp(20)))
    }
}

// ---------------- Журнал ----------------

fun logScreen(h: Host): View {
    val a = h.ctx
    val copy = a.label("Копировать", 15f, Col.ACCENT).apply {
        setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
        setOnClickListener {
            a.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("GlassMate", Core.logLines.value.joinToString("\n")))
            h.toast("Журнал скопирован")
        }
    }
    val text = a.label("", 12f, 0xFFD0D2D6.toInt()).apply {
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }
    h.ui.launch { Core.logLines.collect { text.text = it.asReversed().joinToString("\n").ifEmpty { "Пока пусто" } } }
    return h.screen("Журнал", copy) { it.addView(text) }
}
