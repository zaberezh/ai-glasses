package app.glassmate.ai

import android.util.Base64
import app.glassmate.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
        if (s.provider == "anthropic") anthropic(history, question, jpeg, onDelta)
        else openAi(history, question, jpeg, onDelta)
    }

    /** Добавляет https:// если адрес вставлен без протокола. */
    private fun normalizeBase(raw: String, def: String): String {
        var b = raw.trim().ifBlank { def }.trimEnd('/')
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "https://$b"
        return b
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
            .put("max_tokens", 400)
            .put("system", s.systemPrompt)
            .put("messages", messages)
            .put("stream", true)

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
            .put("max_tokens", 400).put("stream", true)
        // Принимаем host/v1 или полный …/chat/completions; голый адрес дополняем /v1
        var base = normalizeBase(s.baseUrl, "https://api.openai.com/v1").removeSuffix("/chat/completions")
        if (Regex("^https?://[^/]+$").matches(base)) base += "/v1"

        return post("$base/chat/completions", body, mapOf("Authorization" to "Bearer ${s.apiKey}"), onDelta,
            streamText = { ev ->
                ev.optJSONObject("error")?.let { throw IOException(it.optString("message", "ошибка модели")) }
                ev.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    ?.let { if (it.isNull("content")) null else it.optString("content") }
            },
            fullText = { json ->
                json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
                    ?: throw IOException("Пустой ответ модели")
            })
    }

    private fun post(
        url: String, body: JSONObject, headers: Map<String, String>, onDelta: (String) -> Unit,
        streamText: (JSONObject) -> String?, fullText: (JSONObject) -> String,
    ): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            c.setRequestProperty("accept", "text/event-stream, application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val text = c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val msg = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(300)
                throw IOException("HTTP $code: $msg")
            }
            val isStream = c.contentType?.contains("event-stream") == true
            val reader = c.inputStream.bufferedReader(Charsets.UTF_8)
            if (!isStream) {
                val text = reader.use { it.readText() }
                // Некоторые посредники шлют поток без правильного content-type
                if (text.trimStart().startsWith("data:") || text.trimStart().startsWith("event:")) {
                    return parseSse(text.lineSequence(), onDelta, streamText)
                }
                val full = fullText(JSONObject(text)).trim()
                onDelta(full)
                return full
            }
            return reader.use { parseSse(it.lineSequence(), onDelta, streamText) }
        } finally {
            c.disconnect()
        }
    }

    private fun parseSse(lines: Sequence<String>, onDelta: (String) -> Unit, streamText: (JSONObject) -> String?): String {
        val sb = StringBuilder()
        for (line in lines) {
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data.isEmpty() || data == "[DONE]") continue
            val ev = runCatching { JSONObject(data) }.getOrNull() ?: continue
            val piece = streamText(ev)
            if (!piece.isNullOrEmpty()) {
                sb.append(piece)
                onDelta(piece)
            }
        }
        return sb.toString().trim()
    }
}
