package app.glassmate

import android.app.Activity
import android.app.Application
import android.os.Bundle

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Core.init(this)
    }
}

/**
 * Срабатывает, когда жест очков настроен на «Голосовой ассистент»: телефон
 * открывает голосовой ввод, а GlassMate можно выбрать приложением для этого.
 */
class VoiceCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Core.assistant.trigger("голосовая команда гарнитуры")
        finish()
    }
}
