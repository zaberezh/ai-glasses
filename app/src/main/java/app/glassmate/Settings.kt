package app.glassmate

import android.content.Context

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("glassmate", Context.MODE_PRIVATE)

    private fun str(key: String, def: String) = p.getString(key, def) ?: def
    private fun put(key: String, v: String) = p.edit().putString(key, v).apply()
    private fun putB(key: String, v: Boolean) = p.edit().putBoolean(key, v).apply()

    /** "anthropic" или "openai" (любой OpenAI-совместимый API: OpenAI, OpenRouter, Gemini, локальные модели). */
    var provider: String
        get() = str("provider", "anthropic")
        set(v) = put("provider", v)

    var baseUrl: String
        get() = str("baseUrl", "")
        set(v) = put("baseUrl", v)

    var model: String
        get() = str("model", DEFAULT_ANTHROPIC_MODEL)
        set(v) = put("model", v)

    var apiKey: String
        get() = str("apiKey", "")
        set(v) = put("apiKey", v)

    var systemPrompt: String
        get() = str("systemPrompt", DEFAULT_PROMPT)
        set(v) = put("systemPrompt", v)

    var lookQuestion: String
        get() = str("lookQuestion", "Что ты видишь? Опиши коротко.")
        set(v) = put("lookQuestion", v)

    var language: String
        get() = str("language", "ru-RU")
        set(v) = put("language", v)

    var attachPhoto: Boolean
        get() = p.getBoolean("attachPhoto", true)
        set(v) = putB("attachPhoto", v)

    var useGlassesMic: Boolean
        get() = p.getBoolean("useGlassesMic", true)
        set(v) = putB("useGlassesMic", v)

    var saveAiPhotos: Boolean
        get() = p.getBoolean("saveAiPhotos", false)
        set(v) = putB("saveAiPhotos", v)

    /** Перехватывать медиакнопки (касания дужки = плей/пауза, след./пред. трек). */
    var mediaButtons: Boolean
        get() = p.getBoolean("mediaButtons", true)
        set(v) = putB("mediaButtons", v)

    /** Запускать ассистента по уведомлению 0xB0 от очков (их собственная ИИ-кнопка). */
    var aiKeyTrigger: Boolean
        get() = p.getBoolean("aiKeyTrigger", false)
        set(v) = putB("aiKeyTrigger", v)

    var wearDetection: Boolean
        get() = p.getBoolean("wearDetection", true)
        set(v) = putB("wearDetection", v)

    var videoLimit: Int
        get() = p.getInt("videoLimit", 12)
        set(v) = p.edit().putInt("videoLimit", v).apply()

    var audioLimit: Int
        get() = p.getInt("audioLimit", 30)
        set(v) = p.edit().putInt("audioLimit", v).apply()

    /** Снимок только когда вопрос про то, что перед глазами. */
    var autoPhoto: Boolean
        get() = p.getBoolean("autoPhoto", true)
        set(v) = putB("autoPhoto", v)

    var speechRate: Float
        get() = p.getFloat("speechRate", 1.15f)
        set(v) = p.edit().putFloat("speechRate", v).apply()

    // --- Gemini Live ---
    var geminiKey: String
        get() = str("geminiKey", "")
        set(v) = put("geminiKey", v)

    /** Пусто = выбрать автоматически из списка моделей с поддержкой Live. */
    var geminiLiveModel: String
        get() = str("geminiLiveModel", "")
        set(v) = put("geminiLiveModel", v)

    var geminiVoice: String
        get() = str("geminiVoice", "Puck")
        set(v) = put("geminiVoice", v)

    /** Интервал кадров в Live-режиме, мс. */
    var liveFrameMs: Int
        get() = p.getInt("liveFrameMs", 1500)
        set(v) = p.edit().putInt("liveFrameMs", v).apply()

    /** Писать в журнал все байты обмена с очками (для отладки). */
    var verboseLog: Boolean
        get() = p.getBoolean("verboseLog", true)
        set(v) = putB("verboseLog", v)

    var lastDevice: String
        get() = str("lastDevice", "")
        set(v) = put("lastDevice", v)

    companion object {
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_PROMPT =
            "Ты голосовой ассистент в умных очках. Пользователь задаёт вопрос голосом. " +
                "К вопросу может быть приложен снимок с камеры очков — это то, что пользователь " +
                "сейчас видит перед собой. Отвечай на языке вопроса, коротко (1–3 предложения), " +
                "разговорным языком, без markdown, списков и эмодзи: ответ будет озвучен."
    }
}
