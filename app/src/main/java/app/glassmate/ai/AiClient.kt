package app.glassmate.ai

import android.util.Base64
import app.glassmate.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Клиент ИИ-модели. Два формата:
 *  - Anthropic (Claude) — Messages API;
 *  - любой OpenAI-совместимый API (/chat/completions): OpenAI, OpenRouter, Gemini, LM Studio, Ollama и т.п.
 * Ответ приходит потоком (stream): onDelta получает текст по кусочкам, чтобы начать озвучку сразу.
 * Если сервер не умеет поток и отвечает обычным JSON — разбираем его целиком.
 */
class AiClient(private val s: Settings) {

    data class Turn(val role: String, val text: String)

    suspend fun ask(
        history: List<Turn>, question: String, jpeg: ByteArray?,
        imageFormat: Int? = null,
        onDelta: (String) -> Unit = {},
    ): String = coroutineScope {
        forcedFormat = imageFormat
        // Вопрос отменили (задали новый, нажали «стоп») — обрываем и сетевой запрос, а не ждём его до конца
        val watcher = launch {
            try { awaitCancellation() } finally { cancelled = true; call?.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                var lastErr: IOException? = null
                // обрыв соединения (VPN, смена сети) — молча повторяем
                repeat(3) { attempt ->
                    val sent = StringBuilder()
                    try {
                        return@withContext if (s.provider == "anthropic") anthropic(history, question, jpeg) { sent.append(it); onDelta(it) }
                        else openAi(history, question, jpeg) { sent.append(it); onDelta(it) }
                    } catch (e: HttpError) {
                        throw e
                    } catch (e: IOException) {
                        if (cancelled) throw CancellationException("вопрос отменён")
                        lastErr = e
                        // Поток оборвался после начала ответа: через этот сервис он ненадёжен —
                        // до конца работы приложения спрашиваем без потока.
                        val streamFailed = gotResponse && useStream()
                        if (streamFailed) streamBroken += endpoint()
                        if (sent.isNotEmpty()) {
                            // часть ответа уже показана и озвучена — повтор только запутает; отдаём что есть
                            app.glassmate.Core.log("Ответ оборвался на середине (${e.message})")
                            return@withContext sent.toString().trim()
                        }
                        app.glassmate.Core.log(
                            if (streamFailed) "Поток оборвался (${e.message}) — переспрашиваю без потока"
                            else "Связь с моделью оборвалась (${e.message}), повторяю…"
                        )
                        if (!streamFailed) delay(600L * (attempt + 1))
                    }
                }
                throw IOException(explain(lastErr))
            }
        } finally {
            call = null
            watcher.cancel()
        }
    }

    @Volatile private var call: Call? = null
    @Volatile private var cancelled = false
    /** Способ упаковки картинки, заданный проверкой зрения; null — тот, что сохранён в настройках. */
    @Volatile private var forcedFormat: Int? = null
    /** Сервер начал отвечать (пришли заголовки с кодом 2xx) — значит, сбой не в соединении. */
    @Volatile private var gotResponse = false

    /** Ключ, под которым запоминается рабочий способ упаковки картинки. */
    fun imageKey() = "${s.provider}|${s.baseUrl.trim()}|${s.model.trim()}"

    private fun useStream() = s.streamAnswer && endpoint() !in streamBroken

    /** Понятное объяснение сетевой ошибки вместо английского текста системы. */
    private fun explain(e: IOException?): String = when (e) {
        null -> "нет ответа от модели"
        is UnknownHostException ->
            "не нахожу сервер ${s.baseUrl.ifBlank { "модели" }}. Проверьте интернет и VPN и попробуйте ещё раз"
        is SocketTimeoutException -> "сервер модели не ответил вовремя"
        else -> e.message ?: "нет связи с сервером модели"
    }

    /** Ошибка от сервера (4xx/5xx) — повторять бессмысленно. */
    class HttpError(val code: Int, msg: String) : IOException(msg)

    /** Адрес, по которому пойдёт запрос, — чтобы заранее открыть соединение (см. Net.warm). */
    fun endpoint(): String =
        if (s.provider == "anthropic") normalizeBase(s.baseUrl, "https://api.anthropic.com") else normalizeBase(s.baseUrl, "https://api.openai.com/v1")

    /** Приводит адрес к виду https://host/…: дописывает протокол и исправляет опечатки вроде «httpa://». */
    private fun normalizeBase(raw: String, def: String): String {
        var b = raw.trim().ifBlank { def }.trimEnd('/')
        b = b.replace(Regex("^[a-zA-Z]{2,6}:/*"), "")   // убираем любой «протокол», даже с опечаткой
        return "https://$b"
    }

    /**
     * Системная инструкция. Если снимка нет, говорим это модели прямо: иначе на вопрос
     * «что ты видишь» она описывает выдуманную картинку.
     */
    private fun system(jpeg: ByteArray?): String =
        if (jpeg != null) s.systemPrompt
        else s.systemPrompt + " К этому сообщению снимок НЕ приложен — ты сейчас ничего не видишь. " +
            "Не описывай окружение и не придумывай, что перед пользователем."

    private fun anthropic(history: List<Turn>, question: String, jpeg: ByteArray?, onDelta: (String) -> Unit): String {
        val content = JSONArray()
        if (jpeg != null) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source", JSONObject()
                        .put("type", "base64")
                        .put("media_type", "image/jpeg")
                        .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", question))

        val messages = JSONArray()
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
        messages.put(JSONObject().put("role", "user").put("content", content))

        val body = JSONObject()
            .put("model", s.model.ifBlank { Settings.DEFAULT_ANTHROPIC_MODEL })
            .put("max_tokens", 300)
            .put("system", system(jpeg))
            .put("messages", messages)
            .put("stream", useStream())

        // Принимаем адрес в любом виде: host, https://host, https://host/v1 или полный …/v1/messages
        val base = normalizeBase(s.baseUrl, "https://api.anthropic.com")
            .removeSuffix("/messages").removeSuffix("/v1")
        val headers = mutableMapOf("x-api-key" to s.apiKey, "anthropic-version" to "2023-06-01")
        // Сторонние посредники (не api.anthropic.com) часто ждут ключ в Authorization
        if (!base.contains("api.anthropic.com")) headers["Authorization"] = "Bearer ${s.apiKey}"

        return post("$base/v1/messages", body, headers, onDelta,
            streamText = { ev ->
                when (ev.optString("type")) {
                    "content_block_delta" -> ev.optJSONObject("delta")?.optString("text")
                    "error" -> throw IOException(ev.optJSONObject("error")?.optString("message") ?: "ошибка модели")
                    else -> null
                }
            },
            fullText = { json ->
                val blocks = json.optJSONArray("content") ?: throw IOException("Пустой ответ модели")
                buildString {
                    for (i in 0 until blocks.length()) {
                        val b = blocks.getJSONObject(i)
                        if (b.optString("type") == "text") append(b.optString("text"))
                    }
                }
            })
    }

    private fun openAi(history: List<Turn>, question: String, jpeg: ByteArray?, onDelta: (String) -> Unit): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system(jpeg)))
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val text = JSONObject().put("type", "text").put("text", question)
        val content = JSONArray()
        if (jpeg == null) content.put(text) else {
            val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
            // Сервисы-посредники понимают картинку по-разному, поэтому способов несколько.
            // Рабочий подбирает «Проверка зрения модели» и запоминает для адреса и модели.
            when (forcedFormat ?: s.imageFormatFor(imageKey())) {
                // 0: как у OpenAI, с детализацией (low — модель разбирает снимок в разы быстрее)
                0 -> {
                    val detail = if (needsDetail(question)) "auto" else "low"
                    content.put(text)
                    content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url).put("detail", detail)))
                }
                // 1: без поля detail, картинка перед текстом
                1 -> {
                    content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
                    content.put(text)
                }
                // 2: адрес картинки строкой, а не объектом
                2 -> {
                    content.put(JSONObject().put("type", "image_url").put("image_url", url))
                    content.put(text)
                }
                // 3: блок в формате Anthropic — его ждут посредники, которые сами переводят запрос для Claude
                else -> {
                    content.put(JSONObject().put("type", "image").put("source", JSONObject()
                        .put("type", "base64").put("media_type", "image/jpeg")
                        .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))))
                    content.put(text)
                }
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", content))

        if (s.model.isBlank()) throw IOException("Укажите название модели в настройках")
        // Принимаем host/v1 или полный …/chat/completions; голый адрес дополняем /v1
        var base = normalizeBase(s.baseUrl, "https://api.openai.com/v1").removeSuffix("/chat/completions")
        if (Regex("^https?://[^/]+$").matches(base)) base += "/v1"
        val body = JSONObject().put("model", s.model).put("messages", messages)
            .put("stream", useStream())
        // быстрый режим: модель меньше «размышляет» перед коротким голосовым ответом
        // Быстрый режим: просим модель не «размышлять» перед коротким голосовым ответом — на это
        // и уходит основное время. Пробуем от самого быстрого уровня к более медленным; уровень,
        // который модель приняла, запоминается, так что подбор случается один раз.
        val effortKey = "$base|${s.model}"
        var level = if (s.fastMode) EFFORTS.indexOf(s.effortFor(effortKey)).takeIf { it >= 0 } ?: 0 else EFFORTS.lastIndex

        val headers = mapOf("Authorization" to "Bearer ${s.apiKey}")
        val call = { b: JSONObject -> post("$base/chat/completions", b, headers, onDelta,
            streamText = { ev ->
                ev.optJSONObject("error")?.let { throw IOException(it.optString("message", "ошибка модели")) }
                ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    ?.let { if (it.isNull("content")) null else it.optString("content") }
            },
            fullText = { json ->
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    ?: throw IOException("Пустой ответ модели")
            }) }
        while (true) {
            val effort = EFFORTS[level]
            if (effort.isEmpty()) body.remove("reasoning_effort") else body.put("reasoning_effort", effort)
            // У «думающих» моделей в лимит входят и размышления: если они не отключены,
            // 300 токенов могло не хватить на сам ответ, и он обрезался.
            body.put("max_tokens", if (effort == "none" || effort == "minimal") 300 else 800)
            body.put("stream", useStream())
            try {
                val answer = call(body)
                if (s.fastMode && s.effortFor(effortKey) != effort) {
                    s.setEffortFor(effortKey, effort)
                    app.glassmate.Core.log("Быстрый режим: модель приняла reasoning_effort = ${effort.ifEmpty { "(без параметра)" }}")
                }
                return answer
            } catch (e: HttpError) {
                // модель не знает такой уровень (или сам параметр) — пробуем следующий
                if ((e.code == 400 || e.code == 422) && level < EFFORTS.lastIndex) level++ else throw e
            }
        }
    }

    private fun post(
        url: String, body: JSONObject, headers: Map<String, String>, onDelta: (String) -> Unit,
        streamText: (JSONObject) -> String?, fullText: (JSONObject) -> String,
    ): String {
        val rb = Request.Builder().url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("accept", "text/event-stream, application/json")
        headers.forEach { (k, v) -> rb.header(k, v) }
        val c = Net.client.newCall(rb.build())
        call = c
        if (cancelled) c.cancel()
        c.execute().use { resp ->
            val code = resp.code
            if (code !in 200..299) {
                val text = resp.body?.string().orEmpty()
                val msg = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(300)
                throw HttpError(code, "HTTP $code: $msg")
            }
            gotResponse = true
            val respBody = resp.body ?: throw IOException("Пустой ответ сервера")
            val isStream = resp.header("content-type")?.contains("event-stream") == true
            if (!isStream) {
                val text = respBody.string()
                // Некоторые посредники шлют поток без правильного content-type
                if (text.trimStart().startsWith("data:") || text.trimStart().startsWith("event:")) {
                    return parseSse(text.lineSequence(), onDelta, streamText)
                }
                val full = fullText(JSONObject(text)).trim()
                onDelta(full)
                return full
            }
            return respBody.charStream().buffered().use { parseSse(it.lineSequence(), onDelta, streamText) }
        }
    }

    private fun parseSse(lines: Sequence<String>, onDelta: (String) -> Unit, streamText: (JSONObject) -> String?): String {
        val sb = StringBuilder()
        for (line in lines) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue
            val ev = runCatching { JSONObject(data) }.getOrNull() ?: continue
            val piece = streamText(ev)
            if (!piece.isNullOrEmpty()) {
                sb.append(piece)
                onDelta(piece)
            }
            // ответ закончен — не ждём, пока сервер сам закроет соединение
            val finish = ev.optJSONArray("choices")?.optJSONObject(0)?.let { if (it.isNull("finish_reason")) null else it.optString("finish_reason") }
            if (!finish.isNullOrEmpty() || ev.optString("type") == "message_stop") break
        }
        return sb.toString().trim()
    }

    companion object {
        /** Сколько способов упаковки картинки умеет формат OpenAI (см. openAi). */
        const val IMAGE_FORMATS = 4

        /** Адреса, через которые поток обрывался, — с ними работаем без потока. */
        private val streamBroken: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Нужны ли вопросу мелкие детали кадра (текст, цифры). */
        fun needsDetail(question: String) = fineDetail.containsMatchIn(question)

        /** Уровни «размышления» от самого быстрого; пустая строка — параметр не отправляется. */
        private val EFFORTS = listOf("none", "minimal", "low", "")

        /** Вопросы, где важны мелкие детали кадра (текст, цифры), — им нужен снимок в полном качестве. */
        private val fineDetail = Regex(
            "прочитай|прочти|читай|надпис|текст|переведи|вывеск|этикет|ценник|сколько стоит|цен[аыу]|номер|" +
                "меню|табличк|состав|экран|написано|read|text|translate|price",
            RegexOption.IGNORE_CASE
        )
    }
}
