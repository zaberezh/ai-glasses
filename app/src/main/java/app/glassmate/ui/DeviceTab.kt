package app.glassmate.ui

import android.content.res.ColorStateList
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import app.glassmate.Core
import app.glassmate.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private class Tile(val view: LinearLayout, val ic: ImageView, val text: TextView)

/** Вкладка «Устройство» — как главный экран AIBuds. */
fun deviceTab(h: Host): View = with(h.ctx) {
    val root = vbox(dp(16))

    // --- шапка: журнал слева, настройки справа ---
    root.addView(hbox().apply {
        addView(roundButton(R.drawable.ic_list) { h.push(logScreen(h)) })
        addView(View(context), lin(0, 1, 1f))
        addView(roundButton(R.drawable.ic_settings) { h.push(settingsScreen(h)) })
    }, lin(bottom = dp(16)))

    // --- карточка очков ---
    val nameView = label("AI Glasses", 26f, bold = true).apply { maxLines = 1 }
    val battView = label("--", 15f, Col.SUB)
    val statusView = label("Не подключено", 15f).apply { maxLines = 1 }
    val alert = label("!", 13f, bold = true).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Col.RED) }
    }
    root.addView(card(18, onClick = { Actions.connectDialog(h) }).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(150)
        addView(icon(R.drawable.ic_glasses_outline, Col.DIM, 120))
        addView(vbox().apply {
            addView(nameView)
            addView(hbox().apply {
                addView(icon(R.drawable.ic_battery, Col.SUB, 18))
                addView(battView, lin(WRAP, WRAP, start = dp(2)))
                addView(icon(R.drawable.ic_bluetooth, Col.SUB, 18), lin(dp(18), dp(18), start = dp(14)))
                addView(statusView, lin(0, WRAP, 1f, start = dp(2)))
                addView(alert, lin(dp(20), dp(20), start = dp(4)))
            }, lin(top = dp(10)))
        }, lin(0, WRAP, 1f, start = dp(10)))
    }, lin(bottom = dp(14)))

    // --- три большие кнопки ---
    fun tile(res: Int, title: String, onClick: () -> Unit): Tile {
        val ic = icon(res, Col.TEXT, 36)
        val tx = label(title, 15f).apply { gravity = Gravity.CENTER }
        val v = card(12, onClick = onClick).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(112)
            addView(ic)
            addView(tx, lin(WRAP, WRAP, top = dp(8)))
        }
        return Tile(v, ic, tx)
    }
    val video = tile(R.drawable.ic_videocam, "Видеозапись") { Actions.toggleVideo(h) }
    val camera = tile(R.drawable.ic_camera, "Камера") { Actions.photo(h) }
    val audio = tile(R.drawable.ic_mic, "Аудиозапись") { Actions.toggleAudio(h) }
    root.addView(hbox().apply {
        addView(video.view, lin(0, WRAP, 1f))
        addView(camera.view, lin(0, WRAP, 1f, start = dp(10), end = dp(10)))
        addView(audio.view, lin(0, WRAP, 1f))
    }, lin(bottom = dp(14)))

    // --- громкость телефона (звук идёт в очки) ---
    val am = getSystemService(AudioManager::class.java)!!
    val r = dp(18).toFloat()
    val seek = SeekBar(this).apply {
        max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        progress = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val layers = LayerDrawable(arrayOf(
            rounded(Col.CARD2, r),
            ClipDrawable(rounded(0xFF6B6D73.toInt(), r), Gravity.START, ClipDrawable.HORIZONTAL),
        ))
        layers.setId(0, android.R.id.background)
        layers.setId(1, android.R.id.progress)
        progressDrawable = layers
        thumb = ColorDrawable(0)
        splitTrack = false
        minHeight = dp(34); maxHeight = dp(34)
        setPadding(0, 0, 0, 0)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }
    root.addView(card(18).apply {
        addView(hbox().apply {
            addView(icon(R.drawable.ic_volume, Col.SUB, 22))
            addView(label("Громкость системы", 16f, Col.SUB), lin(WRAP, WRAP, start = dp(10)))
        })
        addView(seek, lin(MATCH, dp(34), top = dp(16)))
    }, lin(bottom = dp(14)))

    // --- карточки функций ---
    fun bigCard(title: String, sub: String, res: Int, c1: Int, c2: Int, onClick: () -> Unit) = FrameLayout(this).apply {
        val rr = dp(20).toFloat()
        background = clickable(gradient(rr, c1, c2), rr)
        clipToOutline = true
        addView(icon(res, 0x40FFFFFF, 92).apply {
            layoutParams = FrameLayout.LayoutParams(dp(92), dp(92), Gravity.END or Gravity.TOP).apply {
                setMargins(0, dp(12), dp(12), 0)
            }
        })
        addView(vbox(dp(14)).apply {
            addView(label(title, 18f, bold = true).apply { maxLines = 2 })
            addView(label(sub, 13f, 0xE6FFFFFF.toInt()).apply { maxLines = 3 })
        }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setOnClickListener { onClick() }
    }

    fun smallCard(title: String, sub: String, res: Int, c1: Int, c2: Int, onClick: () -> Unit) =
        card(14, onClick = onClick).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(96)
            addView(FrameLayout(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
                    .apply { shape = GradientDrawable.OVAL }
                addView(icon(res, Col.TEXT, 24).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
                })
            }, lin(dp(48), dp(48)))
            addView(vbox().apply {
                addView(label(title, 16f, bold = true).apply { maxLines = 2 })
                addView(label(sub, 13f, Col.SUB).apply { maxLines = 2 })
            }, lin(0, WRAP, 1f, start = dp(12)))
        }

    fun pair(a: View, b: View, height: Int = WRAP) = hbox().apply {
        gravity = Gravity.TOP
        addView(a, lin(0, height, 1f, end = dp(6)))
        addView(b, lin(0, height, 1f, start = dp(6)))
    }

    root.addView(pair(
        bigCard("Голосовой помощник", "Нажмите и спросите — ИИ увидит, что перед вами",
            R.drawable.ic_mic, 0xFF6A5AE0.toInt(), 0xFF2F80ED.toInt()) {
            Core.assistant.trigger("голосовой помощник"); h.selectTab(1)
        },
        bigCard("Жесты сенсорной панели", "Касания дужек: ИИ, музыка, громкость",
            R.drawable.ic_touch, 0xFFFF8A3D.toInt(), 0xFFE5484D.toInt()) { h.push(gesturesScreen(h)) },
        dp(176)
    ), lin(bottom = dp(12)))

    root.addView(pair(
        smallCard("Настройки ИИ-помощника", "Модель ИИ", R.drawable.ic_settings,
            0xFFFF6FA5.toInt(), 0xFFE5484D.toInt()) { h.push(aiSettingsScreen(h)) },
        smallCard("Что я вижу?", "ИИ смотрит камерой", R.drawable.ic_eye,
            0xFFB06BFF.toInt(), 0xFF6A5AE0.toInt()) { Core.assistant.look(); h.selectTab(1) },
    ), lin(bottom = dp(12)))

    root.addView(pair(
        smallCard("ИИ-снимок", "Кадр с камеры очков", R.drawable.ic_camera,
            0xFF4FC3F7.toInt(), 0xFF2F80ED.toInt()) { Actions.aiPhoto(h) },
        smallCard("Импорт медиа", "Фото и видео по Wi-Fi", R.drawable.ic_download,
            0xFF43D17A.toInt(), 0xFF1E9E5A.toInt()) { Actions.importMedia(h) { h.selectTab(2) } },
    ), lin(bottom = dp(12)))

    // --- живые данные ---
    h.ui.launch { Core.deviceName.collect { nameView.text = it } }
    h.ui.launch {
        combine(Core.connected, Core.status) { c, s -> c to s }.collect { (connected, status) ->
            statusView.text = if (connected) "Подключено" else if (status == "Подключение…") status else "Нажмите, чтобы подключить"
            statusView.setTextColor(if (connected) Col.TEXT else Col.SUB)
            alert.visibility = if (connected || status == "Подключение…") View.GONE else View.VISIBLE
            listOf(video, camera, audio).forEach { it.view.alpha = if (connected) 1f else 0.45f }
        }
    }
    h.ui.launch {
        Core.battery.collect { b ->
            battView.text = when {
                b == null -> "--"
                b.second -> "${b.first}% ⚡"
                else -> "${b.first}%"
            }
        }
    }
    h.ui.launch {
        Core.recording.collect { rec ->
            video.text.text = if (rec == "video") "Стоп" else "Видеозапись"
            video.ic.imageTintList = ColorStateList.valueOf(if (rec == "video") Col.RED else Col.TEXT)
            audio.text.text = if (rec == "audio") "Стоп" else "Аудиозапись"
            audio.ic.imageTintList = ColorStateList.valueOf(if (rec == "audio") Col.RED else Col.TEXT)
        }
    }

    ScrollView(this).apply {
        isVerticalScrollBarEnabled = false
        addView(root)
    }
}
