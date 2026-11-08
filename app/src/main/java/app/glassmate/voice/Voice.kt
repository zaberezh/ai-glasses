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
        }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                sr.destroy()
                if (cont.isActive) cont.resume(text)
            }

            override fun onError(error: Int) {
                Core.log("Распознавание речи: ошибка $error")
                sr.destroy()
                if (cont.isActive) cont.resume(null)
            }

            override fun onReadyForSpeech(params: Bundle?) = Core.log("Слушаю…")
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
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
    private suspend fun startSco(): Boolean {
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

    private fun stopSco() {
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

    suspend fun speak(text: String, language: String) {
        if (text.isBlank()) return
        val t = ensureTts() ?: run {
            Core.log("Синтез речи недоступен")
            return
        }
        withContext(Dispatchers.Main) {
            t.language = Locale.forLanguageTag(language)
            suspendCancellableCoroutine<Unit> { cont ->
                val id = UUID.randomUUID().toString()
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id && cont.isActive) cont.resume(Unit)
                    }
                })
                val params = Bundle().apply {
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                }
                t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id)
                cont.invokeOnCancellation { t.stop() }
            }
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }
}
