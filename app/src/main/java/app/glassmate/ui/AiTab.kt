package app.glassmate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.glassmate.Core
import app.glassmate.R
import kotlinx.coroutines.launch

/** Вкладка «AI» — разговор с ИИ. */
fun aiTab(h: Host): View = with(h.ctx) {
    val root = vbox()

    // --- кнопка Live ---
    val liveChip = label("● Live", 14f, bold = true).apply {
        setPadding(dp(14), dp(7), dp(14), dp(7))
        setOnClickListener { app.glassmate.ai.GeminiLive.toggle() }
    }

    // --- шапка ---
    root.addView(FrameLayout(this).apply {
        setPadding(dp(16), dp(14), dp(16), dp(14))
        addView(label("Разговор с ИИ", 19f, bold = true).apply {
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        })
        addView(liveChip, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
        addView(FrameLayout(context).apply {
            val s = dp(44)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(46) }
            background = clickable(rounded(Col.BG, s / 2f), s / 2f)
            addView(icon(R.drawable.ic_new_chat, Col.TEXT, 26).apply {
                layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
            })
            setOnClickListener { Core.assistant.clearHistory(); h.toast("Новый диалог") }
        })
        addView(FrameLayout(context).apply {
            val s = dp(44)
            layoutParams = FrameLayout.LayoutParams(s, s, Gravity.END or Gravity.CENTER_VERTICAL)
            background = clickable(rounded(Col.BG, s / 2f), s / 2f)
            addView(icon(R.drawable.ic_settings, Col.TEXT, 28).apply {
                layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
            })
            setOnClickListener { h.push(aiSettingsScreen(h)) }
        })
    })

    // --- лента сообщений ---
    val list = vbox(dp(14))
    val scroll = ScrollView(this).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(list)
    }
    // превью кадра с очков в Live-режиме
    val preview = android.widget.ImageView(this).apply {
        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        background = rounded(Col.CARD, dp(14).toFloat())
        clipToOutline = true
        visibility = View.GONE
    }
    root.addView(FrameLayout(this).apply {
        addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(preview, FrameLayout.LayoutParams(dp(120), dp(90), Gravity.END or Gravity.TOP).apply {
            setMargins(0, dp(6), dp(12), 0)
        })
    }, lin(MATCH, 0, 1f))

    val state = label("", 14f, Col.SUB).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
        setPadding(0, dp(4), 0, dp(4))
    }
    root.addView(state)

    // --- строка ввода ---
    fun circleButton(res: Int, color: Int, size: Int, onClick: () -> Unit): FrameLayout = FrameLayout(this).apply {
        val s = dp(size)
        layoutParams = LinearLayout.LayoutParams(s, s)
        background = clickable(rounded(color, s / 2f), s / 2f)
        addView(icon(res, Col.TEXT, 24).apply {
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        })
        setOnClickListener { onClick() }
    }

    val input = EditText(this).apply {
        hint = "Спросите что-нибудь…"
        setTextColor(Col.TEXT)
        setHintTextColor(Col.DIM)
        textSize = 16f
        maxLines = 4
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        background = rounded(Col.CARD, dp(24).toFloat())
        setPadding(dp(18), dp(12), dp(18), dp(12))
    }
    val mainButton = circleButton(R.drawable.ic_mic, Col.ACCENT, 50) {
        val q = input.text.toString().trim()
        if (q.isEmpty()) {
            Core.assistant.trigger("микрофон в чате")
        } else {
            input.setText("")
            Core.assistant.askText(q)
        }
    }
    val mainIcon = mainButton.getChildAt(0) as ImageView
    input.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) {
            mainIcon.setImageResource(if (s.isNullOrBlank()) R.drawable.ic_mic else R.drawable.ic_send)
        }
    })
    root.addView(hbox(dp(12)).apply {
        addView(circleButton(R.drawable.ic_eye, Col.CARD, 50) { Core.assistant.look() })
        addView(input, lin(0, WRAP, 1f, start = dp(10), end = dp(10)))
        addView(mainButton)
    })

    // --- отрисовка сообщений ---
    fun avatar(user: Boolean) = FrameLayout(this).apply {
        background = if (user) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF6B6D73.toInt()) }
        else GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF6A5AE0.toInt(), 0xFF2F80ED.toInt()))
            .apply { shape = GradientDrawable.OVAL }
        addView(icon(if (user) R.drawable.ic_person else R.drawable.ic_sparkles, if (user) 0xFF2A2B30.toInt() else Col.TEXT, 24).apply {
            layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
        })
    }

    fun bubble(item: Core.ChatItem): View {
        val maxW = (resources.displayMetrics.widthPixels * 0.72f).toInt()
        val content = vbox().apply {
            gravity = if (item.fromUser) Gravity.END else Gravity.START
            if (item.image != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(item.image)
                    adjustViewBounds = true
                    maxWidth = maxW
                    maxHeight = dp(260)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = rounded(Col.CARD, dp(16).toFloat())
                    clipToOutline = true
                }, lin(WRAP, WRAP, bottom = dp(6)))
            }
            addView(label(item.text, 16f).apply {
                maxWidth = maxW
                setLineSpacing(0f, 1.15f)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded(if (item.fromUser) Col.BUBBLE else Col.CARD, dp(20).toFloat())
                setOnLongClickListener {
                    getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("GlassMate", item.text))
                    h.toast("Скопировано")
                    true
                }
            })
        }
        return hbox().apply {
            gravity = (if (item.fromUser) Gravity.END else Gravity.START) or Gravity.TOP
            if (item.fromUser) {
                addView(content, lin(WRAP, WRAP, end = dp(10)))
                addView(avatar(true), lin(dp(40), dp(40)))
            } else {
                addView(avatar(false), lin(dp(40), dp(40)))
                addView(content, lin(WRAP, WRAP, start = dp(10)))
            }
        }
    }

    fun emptyState() = vbox().apply {
        gravity = Gravity.CENTER
        addView(avatar(false), lin(dp(72), dp(72)))
        addView(label("Спросите что угодно", 20f, bold = true).apply { gravity = Gravity.CENTER }, lin(WRAP, WRAP, top = dp(16)))
        addView(label(
            "Нажмите на микрофон или коснитесь дужки очков. ИИ услышит вопрос и увидит, что перед вами.\n\n" +
                "Кнопка с глазом — «Что я вижу?» без вопроса.",
            14f, Col.SUB
        ).apply { gravity = Gravity.CENTER }, lin(WRAP, WRAP, top = dp(8)))
    }

    h.ui.launch {
        Core.chat.collect { items ->
            list.removeAllViews()
            if (items.isEmpty()) {
                list.gravity = Gravity.CENTER
                list.addView(emptyState(), lin(MATCH, WRAP))
            } else {
                list.gravity = Gravity.TOP
                for (item in items) list.addView(bubble(item), lin(MATCH, WRAP, bottom = dp(16)))
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            }
        }
    }
    h.ui.launch {
        kotlinx.coroutines.flow.combine(
            Core.assistantState, app.glassmate.ai.GeminiLive.active, app.glassmate.ai.GeminiLive.state
        ) { a, live, ls -> Triple(a, live, ls) }.collect { (st, live, ls) ->
            val shown = if (live) "Live: $ls" else st
            state.text = shown
            state.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
            val rr = dp(25).toFloat()
            mainButton.background = clickable(rounded(if (st == "Слушаю…") Col.RED else Col.ACCENT, rr), rr)
            val cr = dp(18).toFloat()
            liveChip.background = clickable(rounded(if (live) Col.RED else Col.CARD2, cr), cr)
            liveChip.text = if (live) "■ Стоп" else "● Live"
            preview.visibility = if (live && Core.lastImage.value != null) View.VISIBLE else View.GONE
        }
    }
    h.ui.launch {
        Core.lastImage.collect { bmp ->
            if (bmp != null) preview.setImageBitmap(bmp)
            if (app.glassmate.ai.GeminiLive.active.value && bmp != null) preview.visibility = View.VISIBLE
        }
    }

    root
}
