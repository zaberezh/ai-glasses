package app.glassmate

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import app.glassmate.ai.AiClient
import app.glassmate.ai.Net
import app.glassmate.voice.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Голосовой ассистент: слушает вопрос через микрофон очков, одновременно делает
 * ИИ-снимок камерой очков, отправляет всё в выбранную модель и озвучивает ответ.
 */
class Assistant(private val ctx: Context) {

    val voice = Voice(ctx)
    private val history = ArrayList<AiClient.Turn>()
    private var job: Job? = null

    /** Вопрос голосом. Повторное нажатие, пока идёт запись вопроса, отменяет её. */
    fun trigger(source: String) {
        if (Core.assistantState.value == LISTENING) {
            stop()
            return
        }
        start(source, typed = null)
    }

    /** «Что я вижу?» — без голоса, с вопросом из настроек, снимок обязателен. */
    fun look() = start("что я вижу", typed = Core.settings.lookQuestion, forcePhoto = true)

    /** Вопрос текстом из приложения. */
    fun askText(text: String) = start("текст", typed = text)

    /** Прервать всё: запись вопроса, ожидание модели и озвучку. */
    @Synchronized
    fun stop() {
        job?.cancel()
        voice.stopSpeaking()
    }

    fun clearHistory() {
        synchronized(history) { history.clear() }
        lastJpeg = null
        Core.chat.value = emptyList()
        Core.log("История диалога очищена")
    }

    /**
     * Новый вопрос не ждёт, пока договорит прошлый ответ: старый прерывается.
     * Раньше в это время приложение молча отбрасывало вопрос.
     */
    @Synchronized
    private fun start(source: String, typed: String?, forcePhoto: Boolean = false) {
        val old = job
        if (old?.isActive == true) {
            old.cancel()
            voice.stopSpeaking()
        }
        job = Core.scope.launch {
            old?.join()
            try {
                process(source, typed, forcePhoto)
            } catch (e: CancellationException) {
                Core.log("Ассистент: прервано")
                throw e
            } catch (e: Exception) {
                Core.log("Ошибка ассистента: ${e.message}")
                Core.addChat(Core.ChatItem(false, "⚠ Ошибка: ${e.message}"))
                runCatching { voice.speak("Ошибка. ${e.message?.take(100) ?: ""}", Core.settings.language) }
            } finally {
                Core.assistantState.value = ""
            }
        }
    }

    /** Последний снимок, приложенный к вопросу, — для уточняющих вопросов без новой съёмки. */
    @Volatile private var lastJpeg: ByteArray? = null
    @Volatile private var lastJpegAt = 0L

    private suspend fun process(source: String, typed: String?, forcePhoto: Boolean = false) = coroutineScope {
        val s = Core.settings
        if (s.apiKey.isBlank()) {
            Core.log("Укажите API-ключ модели в настройках")
            Core.addChat(Core.ChatItem(false, "Сначала укажите адрес и ключ API: AI → ⚙ Настройки ИИ-помощника."))
            voice.speak("Сначала укажите ключ API в приложении", s.language)
            return@coroutineScope
        }
        Core.log("Ассистент: $source")
        val glasses = Core.glasses
        // пока слушаем вопрос и снимаем — заранее открываем соединение с сервером модели
        runCatching { Net.warm(AiClient(s).endpoint()) }
        val t0 = android.os.SystemClock.elapsedRealtime()

        // Свежий кадр из «живого взгляда» — снимать не нужно
        val liveFrame = Core.liveFrame.value?.takeIf { android.os.SystemClock.elapsedRealtime() - it.first < 4000 }?.second

        // Камера просыпается долго, поэтому для голосового вопроса начинаем снимать заранее,
        // а нужен ли снимок, решаем, когда вопрос уже распознан. Ненужный снимок просто не ждём.
        val photo: Deferred<ByteArray?>? =
            if (s.attachPhoto && glasses != null && liveFrame == null && (typed == null || forcePhoto || needsPhoto(typed))) async {
                runCatching { glasses.captureAiPhoto() }
                    .onFailure { Core.log("Снимок не получен: ${it.message}") }
                    .getOrNull()
            } else null

        val question = typed ?: run {
            Core.assistantState.value = LISTENING
            voice.beep()
            voice.listen(s.language, s.useGlassesMic)
        }
        if (question.isNullOrBlank()) {
            Core.log("Вопрос не распознан")
            Core.addChat(Core.ChatItem(false, "Не расслышал вопрос, попробуйте ещё раз."))
            photo?.cancel()
            return@coroutineScope
        }
        Core.log("Вы: $question")

        val wantPhoto = s.attachPhoto && (forcePhoto || !s.autoPhoto || needsPhoto(question))
        val followUp = !wantPhoto && lastJpeg != null &&
            System.currentTimeMillis() - lastJpegAt < 120_000 && isFollowUp(question)
        val jpeg: ByteArray? = when {
            wantPhoto && liveFrame != null -> prepareImage(liveFrame)
            wantPhoto && photo != null -> {
                Core.assistantState.value = "Смотрю…"
                photo.await()?.let { prepareImage(it) }
            }
            followUp -> lastJpeg
            else -> { photo?.cancel(); null }
        }
        if (jpeg != null && !followUp) { lastJpeg = jpeg; lastJpegAt = System.currentTimeMillis() }
        Core.log(when {
            jpeg == null -> "Без снимка"
            followUp -> "Уточнение — прикладываю прошлый снимок"
            else -> "Снимок приложен (${jpeg.size / 1024} КБ)"
        })
        Core.addChat(Core.ChatItem(true, question, if (jpeg != null && !followUp) Core.lastImage.value else null))
        Core.assistantState.value = "Думаю…"

        val turns = synchronized(history) { history.toList() }
        val speaker = voice.stream(s.language)
        val shown = StringBuilder()
        var firstPiece = true
        val answer = AiClient(s).ask(turns, question, jpeg) { piece ->
            if (firstPiece) {
                firstPiece = false
                Core.log("Первые слова ответа через ${android.os.SystemClock.elapsedRealtime() - t0} мс")
                Core.assistantState.value = "Отвечаю…"
                Core.addChat(Core.ChatItem(false, ""))
            }
            shown.append(piece)
            Core.updateLastChat(shown.toString())
            speaker.feed(piece)
        }.ifBlank { "Модель ничего не ответила." }
        if (firstPiece) Core.addChat(Core.ChatItem(false, answer)) else Core.updateLastChat(answer)
        Core.log("ИИ: $answer")
        Core.lastAnswer.value = answer
        synchronized(history) {
            history += AiClient.Turn("user", question)
            history += AiClient.Turn("assistant", answer)
            while (history.size > 8) {
                history.removeAt(0)
                history.removeAt(0)
            }
        }
        if (firstPiece) speaker.feed(answer + " ")
        speaker.finish()
    }

    companion object {
        const val LISTENING = "Слушаю…"

        /** Вопрос про то, что перед глазами. */
        private val visual = Regex(
            "(что|кто|какой|какая|какое|какие|чей|сколько|где)\\s+(это|тут|здесь|там|передо мной|вижу|видно|на (нём|ней|экране|столе|фото|картинке|улице))|" +
                "(?<![а-яё])(это|этот|эта|эти|вот)(?![а-яё])|передо мной|впереди|вокруг|рядом со мной|" +
                "посмотри|смотри|глянь|взгляни|видишь|вижу|опиши|прочитай|прочти|читай|переведи надпись|надпис|" +
                "вывеск|этикет|ценник|сколько стоит|экран|меню|табличк|номер|в руке|держу|покажи|узнай|распознай|определи|" +
                "что за |как называется|где я|look|see|what is (this|that)|read this|in front",
            RegexOption.IGNORE_CASE
        )
        private val followUpRx = Regex(
            "^(а (если|сколько|какой|какая|какое|какие|что там|где|кто|почему|как|это|он|она|они)|подробнее|расскажи (ещё|еще|подробнее) (о|об|про) (нём|ней|этом|них))",
            RegexOption.IGNORE_CASE
        )

        fun needsPhoto(q: String) = visual.containsMatchIn(q)
        fun isFollowUp(q: String) = followUpRx.containsMatchIn(q.trim())
    }

    /** Уменьшает снимок до 768 px по длинной стороне, показывает в приложении и при желании сохраняет. */
    fun prepareImage(raw: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: run {
            Core.log("Снимок не похож на JPEG (${raw.size} байт)")
            return raw
        }
        Core.lastImage.value = bmp
        if (Core.settings.saveAiPhotos) saveToGallery(raw)
        val longSide = maxOf(bmp.width, bmp.height)
        if (longSide <= 768) return raw
        val k = 768f / longSide
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    fun saveToGallery(jpeg: ByteArray) {
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "glass_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GlassMate")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(jpeg) }
        }.onFailure { Core.log("Не удалось сохранить снимок: ${it.message}") }
    }
}
