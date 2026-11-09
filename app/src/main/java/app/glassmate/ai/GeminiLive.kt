package app.glassmate.ai

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.SystemClock
import android.util.Base64
import app.glassmate.Core
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Live-режим на Gemini Live API: непрерывный голосовой разговор через очки.
 *  - звук с микрофона очков (Bluetooth SCO) идёт в модель потоком, 16 кГц PCM;
 *  - кадры с камеры очков отправляются примерно раз в 1–2 секунды (Gemini Live
 *    и так обрабатывает видео около 1 кадра в секунду);
 *  - модель отвечает голосом (24 кГц PCM), её можно перебивать;
 *  - расшифровки речи обеих сторон попадают в чат.
 */
object GeminiLive {

    val active = MutableStateFlow(false)
    /** Что происходит: «Подключение…», «Слушаю», «Говорит» и т.п. */
    val state = MutableStateFlow("")

    private const val HOST = "https://generativelanguage.googleapis.com"
    private const val WS =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    private val http = Net.client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var ready: CompletableDeferred<Unit>? = null
    @Volatile private var resumeHandle: String? = null
    @Volatile private var stopping = false
    private var jobs = ArrayList<Job>()
    private var player: AudioTrack? = null
    private var sco = false

    // расшифровки текущего хода
    private val userText = StringBuilder()
    private val modelText = StringBuilder()
    private var modelBubble = false

    fun toggle() = if (active.value) stop() else start()

    fun start() {
        if (active.value) return
        val s = Core.settings
        if (s.geminiKey.isBlank()) {
            Core.addChat(Core.ChatItem(false, "Для Live-режима укажите ключ Gemini: AI → ⚙ → «Live-режим (Gemini)»."))
            return
        }
        stopping = false
        active.value = true
        state.value = "Подключение…"
        jobs += Core.scope.launch {
            try {
                val model = s.geminiLiveModel.ifBlank { pickModel(s.geminiKey) }
                Core.log("Live: модель $model")
                connect(model)
                sco = s.useGlassesMic && Core.assistant.voice.startSco()
                startPlayer()
                jobs += Core.scope.launch(Dispatchers.IO) { micLoop() }
                jobs += Core.scope.launch { frameLoop() }
                state.value = "Слушаю и смотрю"
                Core.addChat(Core.ChatItem(false, "🔴 Live-режим включён. Говорите — я слышу и вижу через очки."))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // остановлено пользователем во время подключения — это не ошибка
            } catch (e: Exception) {
                Core.log("Live: ошибка запуска — ${e.message}")
                Core.addChat(Core.ChatItem(false, "⚠ Live не запустился: ${e.message}"))
                stop()
            }
        }
    }

    fun stop() {
        if (stopping) return
        stopping = true
        jobs.forEach { it.cancel() }
        jobs.clear()
        runCatching { ws?.close(1000, "bye") }
        ws = null
        runCatching { player?.stop(); player?.release() }
        player = null
        if (sco) Core.assistant.voice.stopSco()
        sco = false
        flushTurn()
        if (active.value) Core.addChat(Core.ChatItem(false, "Live-режим выключен."))
        active.value = false
        state.value = ""
    }

    // ---------------- выбор модели ----------------

    /** Берёт из списка моделей ту, что умеет Live (bidiGenerateContent), предпочитая «native audio». */
    private suspend fun pickModel(key: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("$HOST/v1beta/models?pageSize=1000")
            .header("x-goog-api-key", key).build()
        val names = ArrayList<String>()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrNull()
                throw IOException("Gemini: HTTP ${r.code} ${msg ?: body.take(200)}")
            }
            val arr = JSONObject(body).optJSONArray("models") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                if ((0 until methods.length()).any { methods.getString(it) == "bidiGenerateContent" }) {
                    names += m.getString("name").removePrefix("models/")
                }
            }
        }
        Core.log("Live: доступные модели — ${names.joinToString()}")
        fun score(n: String): Int {
            var sc = 0
            if ("native-audio" in n) sc += 100
            if ("flash" in n) sc += 20
            if ("live" in n) sc += 10
            if ("preview" in n) sc -= 5
            if ("exp" in n || "thinking" in n || "dialog" in n && "native" !in n) sc -= 3
            return sc
        }
        names.maxWithOrNull(compareBy<String>({ score(it) }, { it }))
            ?: throw IOException("Для этого ключа нет моделей с поддержкой Live")
    }

    // ---------------- WebSocket ----------------

    private suspend fun connect(model: String) {
        val s = Core.settings
        val r = CompletableDeferred<Unit>()
        ready = r
        val req = Request.Builder().url(WS).header("x-goog-api-key", s.geminiKey).build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage(model).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) = handle(bytes.utf8())

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = runCatching { response?.body?.string() }.getOrNull()
                Core.log("Live: обрыв связи — ${t.message} ${body?.take(200) ?: ""}")
                r.completeExceptionally(IOException(t.message ?: "ошибка соединения"))
                if (webSocket === ws) reconnect(model)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Core.log("Live: соединение закрыто ($code) $reason")
                r.completeExceptionally(IOException("сервер закрыл соединение: $code $reason"))
                if (webSocket === ws) reconnect(model)
            }
        })
        withTimeoutOrNull(15000) { r.await() } ?: throw IOException("Gemini не ответил на подключение")
    }

    /** Сессии Live живут ограниченное время — переподключаемся с сохранённым контекстом. */
    private fun reconnect(model: String) {
        if (stopping || !active.value) return
        ws = null
        state.value = "Переподключение…"
        jobs += Core.scope.launch {
            delay(500)
            try {
                connect(model)
                state.value = "Слушаю и смотрю"
            } catch (e: Exception) {
                Core.log("Live: не удалось переподключиться — ${e.message}")
                Core.addChat(Core.ChatItem(false, "⚠ Live: связь потеряна (${e.message})"))
                stop()
            }
        }
    }

    private fun setupMessage(model: String): JSONObject {
        val s = Core.settings
        val sys = s.systemPrompt + " Ты в режиме живого разговора: слышишь пользователя и примерно раз в секунду " +
            "получаешь кадр с камеры его очков — это то, что он сейчас видит. Не описывай кадры без просьбы, " +
            "отвечай на вопросы, используя увиденное. Говори коротко и естественно."
        val setup = JSONObject()
            .put("model", "models/$model")
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", s.geminiVoice)))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            // без сжатия контекста сессия со звуком и видео ограничена ~2 минутами
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", JSONObject().apply { resumeHandle?.let { put("handle", it) } })
        return JSONObject().put("setup", setup)
    }

    private fun handle(text: String) {
        val m = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (m.has("setupComplete")) {
            ready?.complete(Unit)
            return
        }
        m.optJSONObject("sessionResumptionUpdate")?.let { u ->
            if (u.optBoolean("resumable") && u.has("newHandle")) resumeHandle = u.getString("newHandle")
        }
        m.optJSONObject("goAway")?.let { Core.log("Live: сервер скоро закроет сессию (${it.optString("timeLeft")})") }
        m.optJSONObject("error")?.let { Core.log("Live: ошибка ${it.optString("message")}") }
        val sc = m.optJSONObject("serverContent") ?: return

        sc.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            userText.append(it)
        }
        sc.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            if (!modelBubble) {
                if (userText.isNotBlank()) Core.addChat(Core.ChatItem(true, userText.toString().trim()))
                userText.setLength(0)
                Core.addChat(Core.ChatItem(false, ""))
                modelBubble = true
            }
            modelText.append(it)
            Core.updateLastChat(modelText.toString().trim())
        }
        if (sc.optBoolean("interrupted")) {
            // пользователь перебил — сразу замолкаем
            player?.let { p -> runCatching { p.pause(); p.flush(); p.play() } }
            state.value = "Слушаю и смотрю"
        }
        sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val data = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
                if (!data.optString("mimeType").startsWith("audio/")) continue
                val pcm = Base64.decode(data.getString("data"), Base64.DEFAULT)
                state.value = "Говорит…"
                player?.write(pcm, 0, pcm.size)
            }
        }
        if (sc.optBoolean("turnComplete")) {
            flushTurn()
            state.value = "Слушаю и смотрю"
        }
    }

    private fun flushTurn() {
        if (!modelBubble && userText.isNotBlank()) Core.addChat(Core.ChatItem(true, userText.toString().trim()))
        if (modelText.isNotBlank()) Core.lastAnswer.value = modelText.toString().trim()
        userText.setLength(0)
        modelText.setLength(0)
        modelBubble = false
    }

    private fun sendRealtime(kind: String, mime: String, bytes: ByteArray) {
        val w = ws ?: return
        val blob = JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        w.send(JSONObject().put("realtimeInput", JSONObject().put(kind, blob)).toString())
    }

    // ---------------- звук ----------------

    private fun startPlayer() {
        val rate = 24000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // «голосовая связь» — звук идёт в очки по тому же каналу, что и микрофон
                    .setUsage(if (sco) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min * 4, rate * 2))
            .build()
            .also { it.play() }
    }

    @SuppressLint("MissingPermission")
    private suspend fun micLoop() {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400)
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Core.log("Live: микрофон недоступен")
            rec.release()
            return
        }
        // подавление эха: иначе модель слышит саму себя из динамиков и перебивает себя
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true }
        val chunk = ByteArray(rate * 2 / 10) // 100 мс
        rec.startRecording()
        try {
            while (kotlinx.coroutines.currentCoroutineContext().isActive && active.value) {
                var off = 0
                while (off < chunk.size) {
                    val n = rec.read(chunk, off, chunk.size - off)
                    if (n <= 0) break
                    off += n
                }
                if (off > 0) sendRealtime("audio", "audio/pcm;rate=16000", chunk.copyOf(off))
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    // ---------------- кадры ----------------

    private suspend fun frameLoop() {
        var fails = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive && active.value) {
            val g = Core.glasses
            if (g == null) {
                delay(2000)
                continue
            }
            val t0 = SystemClock.elapsedRealtime()
            val raw = runCatching { g.captureAiPhoto(timeoutMs = 15000) }.getOrNull()
            if (raw == null) {
                fails++
                if (fails == 3) Core.log("Live: кадры с очков не приходят")
                delay(1000)
                continue
            }
            fails = 0
            val jpeg = withContext(Dispatchers.Default) { shrink(raw) }
            Core.liveFrame.value = SystemClock.elapsedRealtime() to jpeg
            sendRealtime("video", "image/jpeg", jpeg)
            val wait = Core.settings.liveFrameMs - (SystemClock.elapsedRealtime() - t0)
            if (wait > 0) delay(wait)
        }
    }

    /** 640 px по длинной стороне — этого модели хватает, а передаётся быстрее. */
    private fun shrink(raw: ByteArray): ByteArray {
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        Core.lastImage.value = bmp
        val long = maxOf(bmp.width, bmp.height)
        val out = ByteArrayOutputStream()
        val b = if (long > 640) {
            val k = 640f / long
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        } else bmp
        b.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return out.toByteArray()
    }
}
