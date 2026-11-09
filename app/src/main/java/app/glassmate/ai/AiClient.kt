package app.glassmate.ai

import android.util.Base64
import app.glassmate.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        onDelta: (String) -> Unit = {},
    ): String = withContext(Dispatchers.IO) {
        var lastErr: IOException? = null
        // обрыв соединения (VPN, смена сети) — один раз молча повторяем
        repeat(3) { attempt ->
            val sent = StringBuilder()
            try {
                return@withContext if (s.provider == "anthropic") anthropic(history, question, jpeg) { sent.append(it); onDelta(it) }
                else openAi(history, question, jpeg) { sent.append(it); onDelta(it) }
            } catch (e: HttpError) {
                throw e
            } catch (e: IOException) {
                lastErr = e
                // если часть ответа уже показана и озвучена, повтор только запутает
                if (sent.isNotEmpty()) throw e
                app.glassmate.Core.log("Связь с моделью оборвалась (${e.message}), повторяю…")
                kotlinx.coroutines.delay(600L * (attempt + 1))
            }
        }
        throw lastErr ?: IOException("нет ответа от модели")
    }

    /** Ошибка от сервера (4xx/5xx) — повторять бессмысленно. */
    class HttpError(val code: Int, msg: String) : IOException(msg)

    /** Поддерживает ли модель параметр reasoning_effort — выясняется на первом запросе. */
    @Volatile private var effortOk = true

    /** Добавляет https:// если адрес вставлен без протокола. */
    /** Приводит адрес к виду https://host/…: дописывает протокол и исправляет опечатки вроде «httpa://». */
    private fun normalizeBase(raw: String, def: String): String {
        var b = raw.trim().ifBlank { def }.trimEnd('/')
        b = b.replace(Regex("^[a-zA-Z]{2,6}:/*"), "")   // убираем любой «протокол», даже с опечаткой
        return "https://$b"
    }

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
            .put("system", s.systemPrompt)
            .put("messages", messages)
            .put("stream", s.streamAnswer)

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
        if (s.systemPrompt.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", s.systemPrompt))
        }
        history.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.text)) }
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", question))
        if (jpeg != null) {
            val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
        }
        messages.put(JSONObject().put("role", "user").put("content", content))

        if (s.model.isBlank()) throw IOException("Укажите название модели в настройках")
        val body = JSONObject().put("model", s.model).put("messages", messages)
            .put("max_tokens", 300).put("stream", s.streamAnswer)
        // быстрый режим: модель меньше «размышляет» перед коротким голосовым ответом
        if (effortOk && s.fastMode) body.put("reasoning_effort", "low")
        // Принимаем host/v1 или полный …/chat/completions; голый адрес дополняем /v1
        var base = normalizeBase(s.baseUrl, "https://api.openai.com/v1").removeSuffix("/chat/completions")
        if (Regex("^https?://[^/]+$").matches(base)) base += "/v1"

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
        return try {
            call(body)
        } catch (e: HttpError) {
            // модель не знает reasoning_effort — больше его не отправляем
            if (e.code == 400 && body.has("reasoning_effort") && (e.message ?: "").contains("reasoning", true)) {
                effortOk = false
                body.remove("reasoning_effort")
                call(body)
            } else throw e
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
        Net.client.newCall(rb.build()).execute().use { resp ->
            val code = resp.code
            if (code !in 200..299) {
                val text = resp.body?.string().orEmpty()
                val msg = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(300)
                throw HttpError(code, "HTTP $code: $msg")
            }
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
}
