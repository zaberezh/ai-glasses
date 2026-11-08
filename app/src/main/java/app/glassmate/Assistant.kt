package app.glassmate

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import app.glassmate.ai.AiClient
import app.glassmate.voice.Voice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Голосовой ассистент: слушает вопрос через микрофон очков, одновременно делает
 * ИИ-снимок камерой очков, отправляет всё в выбранную модель и озвучивает ответ.
 */
class Assistant(private val ctx: Context) {

    val voice = Voice(ctx)
    private val history = ArrayList<AiClient.Turn>()
    private val busy = AtomicBoolean(false)

    /** Вопрос голосом. */
    fun trigger(source: String) = start(source, typed = null)

    /** «Что я вижу?» — без голоса, с вопросом из настроек. */
    fun look() = start("что я вижу", typed = Core.settings.lookQuestion)

    /** Вопрос текстом из приложения. */
    fun askText(text: String) = start("текст", typed = text)

    fun clearHistory() {
        synchronized(history) { history.clear() }
        Core.log("История диалога очищена")
    }

    private fun start(source: String, typed: String?) {
        if (!busy.compareAndSet(false, true)) {
            Core.log("Ассистент уже занят")
            return
        }
        Core.scope.launch {
            try {
                process(source, typed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Core.log("Ошибка ассистента: ${e.message}")
                runCatching { voice.speak("Ошибка. ${e.message?.take(100) ?: ""}", Core.settings.language) }
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun process(source: String, typed: String?) = coroutineScope {
        val s = Core.settings
        if (s.apiKey.isBlank()) {
            Core.log("Укажите API-ключ модели в настройках")
            voice.speak("Сначала укажите ключ API в приложении", s.language)
            return@coroutineScope
        }
        Core.log("Ассистент: $source")
        val glasses = Core.glasses
        val photo: Deferred<ByteArray?>? =
            if (s.attachPhoto && glasses != null) async {
                runCatching { glasses.captureAiPhoto() }
                    .onFailure { Core.log("Снимок не получен: ${it.message}") }
                    .getOrNull()
            } else null

        val question = typed ?: run {
            voice.beep()
            voice.listen(s.language, s.useGlassesMic)
        }
        if (question.isNullOrBlank()) {
            Core.log("Вопрос не распознан")
            photo?.cancel()
            return@coroutineScope
        }
        Core.log("Вы: $question")

        val jpeg = photo?.await()?.let { prepareImage(it) }
        if (jpeg != null) Core.log("Снимок приложен (${jpeg.size / 1024} КБ)")

        val turns = synchronized(history) { history.toList() }
        val answer = AiClient(s).ask(turns, question, jpeg).ifBlank { "Модель ничего не ответила." }
        Core.log("ИИ: $answer")
        Core.lastAnswer.value = answer
        synchronized(history) {
            history += AiClient.Turn("user", question)
            history += AiClient.Turn("assistant", answer)
            while (history.size > 12) {
                history.removeAt(0)
                history.removeAt(0)
            }
        }
        voice.speak(answer, s.language)
    }

    /** Уменьшает снимок до 1280 px по длинной стороне, показывает в приложении и при желании сохраняет. */
    fun prepareImage(raw: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: run {
            Core.log("Снимок не похож на JPEG (${raw.size} байт)")
            return raw
        }
        Core.lastImage.value = bmp
        if (Core.settings.saveAiPhotos) saveToGallery(raw)
        val longSide = maxOf(bmp.width, bmp.height)
        if (longSide <= 1280) return raw
        val k = 1280f / longSide
        val scaled = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
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
