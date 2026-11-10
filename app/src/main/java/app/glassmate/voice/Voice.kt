package app.glassmate.voice

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.glassmate.Core
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** Распознавание речи (микрофон очков через SCO) и озвучка ответа в динамики очков. */
class Voice(private val ctx: Context) {

    private val audio = ctx.getSystemService(AudioManager::class.java)!!
    private var tts: TextToSpeech? = null
    private val ttsReady = CompletableDeferred<Boolean>()

    private suspend fun ensureTts(): TextToSpeech? {
        if (tts == null) {
            withContext(Dispatchers.Main) {
                if (tts == null) tts = TextToSpeech(ctx) { st -> ttsReady.complete(st == TextToSpeech.SUCCESS) }
            }
        }
        return if (ttsReady.await()) tts else null
    }

    /** Заранее запустить синтез речи: его первый запуск занимает до пары секунд. */
    suspend fun warm() { runCatching { ensureTts() } }

    suspend fun beep() {
        withContext(Dispatchers.Main) {
            runCatching {
                val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 70)
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                delay(250)
                tg.release()
            }
        }
    }

    /** Слушает одну фразу. Возвращает текст или null. */
    suspend fun listen(language: String, useHeadsetMic: Boolean): String? {
        val sco = useHeadsetMic && startSco()
        try {
            return withContext(Dispatchers.Main) { recognizeOnce(language) }
        } finally {
            if (sco) stopSco()
        }
    }

    private suspend fun recognizeOnce(language: String): String? = suspendCancellableCoroutine { cont ->
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Core.log("Распознавание речи недоступно: установите/обновите приложение Google")
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val sr = SpeechRecognizer.createSpeechRecognizer(ctx)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // не обрывать фразу на первой короткой паузе
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500L)
        }
        var bestPartial: String? = null
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                sr.destroy()
                // если итог короче промежуточного (бывает при обрыве звука), берём промежуточный
                val best = listOfNotNull(text, bestPartial).maxByOrNull { it.length }
                if (cont.isActive) cont.resume(best)
            }

            override fun onError(error: Int) {
                Core.log("Распознавание речи: ошибка $error")
                sr.destroy()
                if (cont.isActive) cont.resume(bestPartial)
            }

            override fun onReadyForSpeech(params: Bundle?) = Core.log("Слушаю…")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.length > (bestPartial?.length ?: 0) }?.let { bestPartial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        sr.startListening(intent)
        cont.invokeOnCancellation {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { sr.cancel(); sr.destroy() }
            }
        }
    }

    /** Переключает ввод на микрофон Bluetooth-гарнитуры (очков). */
    suspend fun startSco(): Boolean {
        return try {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val dev = audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (dev == null) {
                    Core.log("Микрофон очков не найден — слушаю микрофоном телефона")
                    audio.mode = AudioManager.MODE_NORMAL
                    return false
                }
                audio.setCommunicationDevice(dev)
            } else {
                @Suppress("DEPRECATION")
                audio.startBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = true
            }
            delay(800) // канал SCO поднимается не мгновенно
            true
        } catch (e: Exception) {
            Core.log("Не удалось включить микрофон очков: ${e.message}")
            false
        }
    }

    fun stopSco() {
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                audio.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audio.stopBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = false
            }
            audio.mode = AudioManager.MODE_NORMAL
        }
    }

    private val done = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun installListener(t: TextToSpeech) {
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { done.remove(it)?.complete(Unit) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { done.remove(it)?.complete(Unit) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { done.remove(it)?.complete(Unit) }
            }
        })
    }

    /** Ставит фразу в очередь озвучки; возвращает ожидание её окончания. */
    private fun enqueue(t: TextToSpeech, text: String, flush: Boolean): CompletableDeferred<Unit> {
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<Unit>()
        done[id] = d
        val params = Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC) }
        main.post {
            val r = t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, id)
            if (r != TextToSpeech.SUCCESS) done.remove(id)?.complete(Unit)
        }
        return d
    }

    private suspend fun prepare(language: String): TextToSpeech? {
        val t = ensureTts() ?: run {
            Core.log("Синтез речи недоступен")
            return null
        }
        withContext(Dispatchers.Main) {
            t.language = Locale.forLanguageTag(language)
            t.setSpeechRate(Core.settings.speechRate)
            installListener(t)
        }
        return t
    }

    suspend fun speak(text: String, language: String) {
        if (text.isBlank()) return
        val t = prepare(language) ?: return
        enqueue(t, text, flush = true).await()
    }

    /**
     * Озвучка ответа по мере его поступления: текст режется на предложения,
     * каждое уходит в синтез, как только закончилось.
     */
    inner class Stream(private val t: TextToSpeech?) {
        private val buf = StringBuilder()
        private var last: CompletableDeferred<Unit>? = null
        private var first = true

        /** Конец предложения: знак препинания, после которого уже пришёл пробел или перенос. */
        private val end = Regex("""[.!?…;:\n]\s""")

        @Synchronized
        fun feed(piece: String) {
            buf.append(piece)
            while (true) {
                // первую фразу отдаём сразу, дальше — кусками не короче ~20 символов
                val min = if (first) 1 else 20
                val m = end.find(buf, minOf(min, buf.length)) ?: break
                val cut = m.range.first + 1
                say(buf.substring(0, cut))
                buf.delete(0, cut)
            }
        }

        private fun say(text: String) {
            val tt = t ?: return
            if (text.isBlank()) return
            last = enqueue(tt, text.trim(), flush = first)
            first = false
        }

        suspend fun finish() {
            synchronized(this) { say(buf.toString()); buf.setLength(0) }
            last?.await()
        }
    }

    suspend fun stream(language: String): Stream = Stream(prepare(language))

    fun stopSpeaking() {
        tts?.stop()
    }
}
