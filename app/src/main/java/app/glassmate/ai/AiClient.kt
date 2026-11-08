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
 * Клиент ИИ-модели. Два варианта:
 *  - Anthropic (Claude) — Messages API;
 *  - любой OpenAI-совместимый API (/chat/completions): OpenAI, OpenRouter, Gemini, LM Studio, Ollama и т.п.
 */
class AiClient(private val s: Settings) {

    data class Turn(val role: String, val text: String)

    suspend fun ask(history: List<Turn>, question: String, jpeg: ByteArray?): String =
        withContext(Dispatchers.IO) {
            if (s.provider == "anthropic") anthropic(history, question, jpeg)
            else openAi(history, question, jpeg)
        }

    private fun anthropic(history: List<Turn>, question: String, jpeg: ByteArray?): String {
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
            .put("max_tokens", 1024)
            .put("system", s.systemPrompt)
            .put("messages", messages)

        // Принимаем адрес в любом виде: https://host, https://host/v1 или полный …/v1/messages
        val base = s.baseUrl.ifBlank { "https://api.anthropic.com" }.trim().trimEnd('/')
            .removeSuffix("/messages").removeSuffix("/v1")
        val headers = mutableMapOf("x-api-key" to s.apiKey, "anthropic-version" to "2023-06-01")
        // Сторонние посредники (не api.anthropic.com) часто ждут ключ в Authorization
        if (!base.contains("api.anthropic.com")) headers["Authorization"] = "Bearer ${s.apiKey}"
        val json = post("$base/v1/messages", body, headers)
        val blocks = json.optJSONArray("content") ?: throw IOException("Пустой ответ модели")
        val sb = StringBuilder()
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim()
    }

    private fun openAi(history: List<Turn>, question: String, jpeg: ByteArray?): String {
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
        // Принимаем https://host/v1 или полный …/chat/completions; голый https://host дополняем /v1
        var base = s.baseUrl.ifBlank { "https://api.openai.com/v1" }.trim().trimEnd('/')
            .removeSuffix("/chat/completions")
        if (Regex("^https?://[^/]+$").matches(base)) base += "/v1"
        val json = post("$base/chat/completions", body, mapOf("Authorization" to "Bearer ${s.apiKey}"))
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IOException("Пустой ответ модели")
        return choice.getJSONObject("message").optString("content").trim()
    }

    private fun post(url: String, body: JSONObject, headers: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 90000
            c.doOutput = true
            c.setRequestProperty("content-type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(text).optJSONObject("error")?.optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: text.take(300)
                throw IOException("HTTP $code: $msg")
            }
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }
}
