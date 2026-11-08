package app.glassmate

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import app.glassmate.glasses.Glasses
import app.glassmate.glasses.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private val ui = MainScope()
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var imageView: ImageView
    private lateinit var answerView: TextView
    private lateinit var logView: TextView
    private val keySpinners = LinkedHashMap<Int, Spinner>()
    private var deviceAddresses: List<String> = emptyList()

    private val s get() = Core.settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNeededPermissions()
        observe()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(t: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        text = t
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun section(title: String) {
        root.addView(text(title, 18f, bold = true).apply { setPadding(0, dp(20), 0, dp(6)) })
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun edit(hint: String, value: String, password: Boolean = false, multiline: Boolean = false) =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            if (multiline) minLines = 3
        }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
    }

    private fun check(t: String, value: Boolean) = CheckBox(this).apply {
        text = t
        isChecked = value
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(text("GlassMate", 24f, bold = true))
        statusView = text("Не подключено", 16f)
        root.addView(statusView)

        // --- Подключение ---
        section("Очки")
        deviceSpinner = Spinner(this)
        root.addView(deviceSpinner)
        root.addView(row(
            button("Подключить") { connect() },
            button("Отключить") { Core.disconnect() },
        ))
        root.addView(text(
            "Перед подключением закройте приложение AIBuds (Настройки → Приложения → AIBuds → Остановить): " +
                "очки держат только одно такое соединение.", 13f
        ))

        // --- Действия ---
        section("Действия")
        root.addView(row(
            button("Спросить голосом") { Core.assistant.trigger("кнопка в приложении") },
            button("Что я вижу?") { Core.assistant.look() },
        ))
        root.addView(row(
            button("ИИ-снимок") { aiPhoto() },
            button("Фото на очки") { glassesAction("Фото") { "код ${it.takePhoto()}" } },
        ))
        root.addView(row(
            button("Видео: старт") { glassesAction("Видео") { "код ${it.startVideo()}" } },
            button("Стоп записи") { glassesAction("Стоп") { "код ${it.stopCamera()}" } },
        ))
        root.addView(row(
            button("Импорт по Wi-Fi") { importMedia() },
            button("Новый диалог") { Core.assistant.clearHistory() },
        ))
        val question = edit("Вопрос текстом", "", multiline = true)
        root.addView(question)
        root.addView(button("Отправить вопрос") {
            val q = question.text.toString().trim()
            if (q.isNotEmpty()) Core.assistant.askText(q)
        })
        imageView = ImageView(this).apply {
            adjustViewBounds = true
            visibility = View.GONE
        }
        root.addView(imageView)
        answerView = text("", 15f)
        root.addView(answerView)

        // --- Кнопки очков ---
        section("Кнопки на очках")
        root.addView(text(
            "Чтобы касание запускало ИИ, назначьте ему «Плей / пауза» (вопрос голосом) или " +
                "«Следующий трек» («Что я вижу?»). «Предыдущий трек» прерывает ответ. " +
                "Ещё вариант — «Голосовой ассистент», затем выберите GlassMate для голосовых команд.", 13f
        ))
        root.addView(button("Считать с очков") { readKeys() })
        val functionNames = Keys.FUNCTIONS.values.toList()
        for ((gesture, label) in Keys.GESTURES) {
            root.addView(text(label, 14f))
            val sp = spinner(functionNames)
            keySpinners[gesture] = sp
            root.addView(sp)
        }
        root.addView(button("Записать на очки") { writeKeys() })

        // --- ИИ ---
        section("Модель ИИ")
        val providers = listOf("Claude (Anthropic)", "OpenAI-совместимый API")
        val providerSpinner = spinner(providers).apply { setSelection(if (s.provider == "anthropic") 0 else 1) }
        root.addView(providerSpinner)
        val baseUrl = edit("Адрес API (пусто = по умолчанию)", s.baseUrl)
        val model = edit("Модель", s.model)
        val apiKey = edit("API-ключ", s.apiKey, password = true)
        val prompt = edit("Системная инструкция", s.systemPrompt, multiline = true)
        val look = edit("Вопрос для «Что я вижу?»", s.lookQuestion)
        val lang = edit("Язык речи (например ru-RU)", s.language)
        val hint = text("", 13f)
        fun updateHint(anthropic: Boolean) {
            hint.text = if (anthropic) {
                "По умолчанию api.anthropic.com. Модель, например: claude-sonnet-5-5 или claude-haiku-5-5 (быстрее)."
            } else {
                "Укажите адрес, например https://api.openai.com/v1, https://openrouter.ai/api/v1 или " +
                    "https://generativelanguage.googleapis.com/v1beta/openai, и модель с поддержкой изображений."
            }
        }
        updateHint(s.provider == "anthropic")
        providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateHint(pos == 0)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        listOf(hint, baseUrl, model, apiKey, prompt, look, lang).forEach { root.addView(it) }
        val attach = check("Прикладывать снимок с камеры очков", s.attachPhoto)
        val mic = check("Слушать микрофоном очков", s.useGlassesMic)
        val save = check("Сохранять ИИ-снимки в галерею", s.saveAiPhotos)
        val media = check("Касания дужки (медиакнопки) запускают ИИ", s.mediaButtons)
        val aiKey = check("Запускать по сигналу ИИ-кнопки очков (0xB0)", s.aiKeyTrigger)
        listOf(attach, mic, save, media, aiKey).forEach { root.addView(it) }
        root.addView(button("Сохранить настройки") {
            s.provider = if (providerSpinner.selectedItemPosition == 0) "anthropic" else "openai"
            s.baseUrl = baseUrl.text.toString().trim()
            s.model = model.text.toString().trim()
            s.apiKey = apiKey.text.toString().trim()
            s.systemPrompt = prompt.text.toString().trim().ifEmpty { Settings.DEFAULT_PROMPT }
            s.lookQuestion = look.text.toString().trim().ifEmpty { "Что ты видишь?" }
            s.language = lang.text.toString().trim().ifEmpty { "ru-RU" }
            s.attachPhoto = attach.isChecked
            s.useGlassesMic = mic.isChecked
            s.saveAiPhotos = save.isChecked
            s.mediaButtons = media.isChecked
            s.aiKeyTrigger = aiKey.isChecked
            if (Core.connected.value) GlassesService.start(this, GlassesService.ACTION_REFRESH_BUTTONS)
            toast("Сохранено")
        })

        // --- Журнал ---
        section("Журнал")
        root.addView(button("Скопировать журнал") {
            val cm = getSystemService(ClipboardManager::class.java)!!
            cm.setPrimaryClip(ClipData.newPlainText("GlassMate", Core.logLines.value.joinToString("\n")))
            toast("Журнал скопирован")
        })
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(logView)
    }

    private fun observe() {
        ui.launch { Core.status.collect { statusView.text = it } }
        ui.launch { Core.logLines.collect { logView.text = it.asReversed().joinToString("\n") } }
        ui.launch { Core.lastAnswer.collect { answerView.text = it } }
        ui.launch {
            Core.lastImage.collect { bmp ->
                if (bmp != null) {
                    imageView.setImageBitmap(bmp)
                    imageView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------- Подключение ----------

    private fun requestNeededPermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) need += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) {
            need += Manifest.permission.POST_NOTIFICATIONS
            need += Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) refreshDevices() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshDevices()
    }

    @SuppressLint("MissingPermission")
    private fun refreshDevices() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Core.log("Нет разрешения «Устройства поблизости» — разрешите его в настройках приложения")
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val bonded = adapter.bondedDevices.orEmpty().sortedByDescending {
            val n = it.name.orEmpty().lowercase()
            listOf("mt5", "glass", "ai", "ultra").count { k -> n.contains(k) }
        }
        deviceAddresses = bonded.map { it.address }
        val names = bonded.map { "${it.name ?: "Без имени"}  (${it.address})" }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val last = deviceAddresses.indexOf(s.lastDevice)
        if (last >= 0) deviceSpinner.setSelection(last)
        if (names.isEmpty()) Core.log("Нет сопряжённых устройств: сначала подключите очки в настройках Bluetooth")
    }

    private fun connect() {
        val pos = deviceSpinner.selectedItemPosition
        if (pos < 0 || pos >= deviceAddresses.size) {
            refreshDevices()
            toast("Выберите очки в списке")
            return
        }
        GlassesService.start(this)
        Core.connect(deviceAddresses[pos])
    }

    // ---------- Команды ----------

    private fun glassesAction(name: String, block: suspend (Glasses) -> String) {
        val g = Core.glasses ?: run { toast("Очки не подключены"); return }
        Core.scope.launch {
            try {
                Core.log("$name: ${block(g)}")
            } catch (e: Exception) {
                Core.log("$name: ошибка — ${e.message}")
            }
        }
    }

    private fun aiPhoto() = glassesAction("ИИ-снимок") { g ->
        val raw = g.captureAiPhoto()
        Core.assistant.prepareImage(raw)
        "получен, ${raw.size / 1024} КБ"
    }

    private fun importMedia() {
        if (Core.glasses == null) { toast("Очки не подключены"); return }
        Core.scope.launch {
            try {
                val n = WifiImporter(applicationContext).import()
                Core.log("Импорт завершён: сохранено файлов — $n (Галерея → GlassMate)")
            } catch (e: Exception) {
                Core.log("Импорт: ошибка — ${e.message}")
            }
        }
    }

    private fun readKeys() = glassesAction("Кнопки") { g ->
        val keys = g.readKeys() ?: return@glassesAction "очки не прислали настройки"
        withContext(Dispatchers.Main) { applyKeysToUi(keys) }
        "\n" + Core.describeKeys(keys)
    }

    private fun applyKeysToUi(keys: Map<Int, Int>) {
        val functionIds = Keys.FUNCTIONS.keys.toList()
        for ((gesture, func) in keys) {
            val idx = functionIds.indexOf(func)
            if (idx >= 0) keySpinners[gesture]?.setSelection(idx)
        }
    }

    private fun writeKeys() {
        val functionIds = Keys.FUNCTIONS.keys.toList()
        val wanted = keySpinners.mapValues { (_, sp) -> functionIds[sp.selectedItemPosition.coerceAtLeast(0)] }
        glassesAction("Запись кнопок") { g ->
            val before = g.readKeys().orEmpty()
            var changed = 0
            for ((gesture, func) in wanted) {
                if (before[gesture] == func) continue
                if (before.isNotEmpty() && gesture !in before) continue // жест не поддерживается очками
                g.setKey(gesture, func)
                changed++
            }
            val after = g.readKeys()
            if (after != null) withContext(Dispatchers.Main) { applyKeysToUi(after) }
            "изменено жестов: $changed" + (after?.let { "\n" + Core.describeKeys(it) } ?: "")
        }
    }
}
