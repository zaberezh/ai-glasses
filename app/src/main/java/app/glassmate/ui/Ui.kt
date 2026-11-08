package app.glassmate.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import app.glassmate.R

/** Цвета в стиле AIBuds: тёмный фон, тёмно-серые карточки, синий акцент. */
object Col {
    const val BG = 0xFF121212.toInt()
    const val CARD = 0xFF202125.toInt()
    const val CARD2 = 0xFF2B2C31.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val SUB = 0xFF8E9096.toInt()
    const val DIM = 0xFF55575D.toInt()
    const val ACCENT = 0xFF2F80ED.toInt()
    const val BUBBLE = 0xFF5B9BF5.toInt()
    const val RED = 0xFFE5484D.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val RIPPLE = 0x33FFFFFF
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun gradient(radius: Float, vararg colors: Int): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }

/** Фон с эффектом нажатия. */
fun clickable(bg: Drawable, radius: Float): Drawable =
    RippleDrawable(ColorStateList.valueOf(Col.RIPPLE), bg, rounded(0xFFFFFFFF.toInt(), radius))

fun lin(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
    LinearLayout.LayoutParams(w, h, weight).apply { setMargins(start, top, end, bottom) }

fun Context.vbox(pad: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(pad, pad, pad, pad)
}

fun Context.hbox(pad: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(pad, pad, pad, pad)
}

fun Context.label(t: CharSequence, size: Float = 15f, color: Int = Col.TEXT, bold: Boolean = false) =
    TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

fun Context.icon(res: Int, color: Int = Col.TEXT, size: Int = 24) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(color)
    layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
}

/** Тёмная карточка со скруглёнными углами. */
fun Context.card(pad: Int = 18, color: Int = Col.CARD, onClick: (() -> Unit)? = null) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    val r = dp(20).toFloat()
    background = if (onClick != null) clickable(rounded(color, r), r) else rounded(color, r)
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    if (onClick != null) setOnClickListener { onClick() }
}

/** Круглая белая кнопка с тёмной иконкой (как в шапке AIBuds). */
fun Context.roundButton(res: Int, onClick: () -> Unit) = FrameLayout(this).apply {
    val s = dp(48)
    layoutParams = LinearLayout.LayoutParams(s, s)
    background = clickable(rounded(0xFFF2F2F2.toInt(), s / 2f), s / 2f)
    addView(icon(res, 0xFF1A1A1A.toInt(), 24).apply {
        layoutParams = FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)
    })
    setOnClickListener { onClick() }
}

/** Заголовок экрана: стрелка назад + название по центру. */
fun Context.screenHeader(title: String, onBack: () -> Unit, action: View? = null) = FrameLayout(this).apply {
    setPadding(dp(8), dp(12), dp(8), dp(12))
    addView(label(title, 20f, bold = true).apply {
        layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
    })
    addView(FrameLayout(context).apply {
        val s = dp(44)
        layoutParams = FrameLayout.LayoutParams(s, s, Gravity.START or Gravity.CENTER_VERTICAL)
        background = clickable(rounded(Col.BG, s / 2f), s / 2f)
        addView(icon(R.drawable.ic_back).apply {
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER)
        })
        setOnClickListener { onBack() }
    })
    if (action != null) {
        action.layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
        addView(action)
    }
}

fun Context.sectionTitle(t: String) = label(t, 16f, Col.SUB).apply {
    setPadding(dp(4), dp(18), 0, dp(10))
}

/** Строка настроек: иконка, заголовок, подзаголовок, стрелка. */
fun Context.arrowRow(res: Int, title: String, subtitle: String? = null, onClick: () -> Unit): Pair<View, TextView> {
    val sub = label(subtitle ?: "", 13f, Col.SUB)
    val row = card(16, onClick = onClick).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(res, Col.TEXT, 26))
        addView(vbox().apply {
            addView(label(title, 16f))
            if (subtitle != null) addView(sub)
        }, lin(0, WRAP, 1f, start = dp(16)))
        addView(icon(R.drawable.ic_chevron, Col.SUB, 22))
    }
    return row to sub
}

/** Строка с переключателем. */
fun Context.switchRow(res: Int, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
    val sw = Switch(this).apply {
        isChecked = checked
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Col.TEXT, 0xFFB0B0B0.toInt())
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Col.ACCENT, 0xFF3A3B40.toInt())
        )
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    return card(16).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(icon(res, Col.TEXT, 26))
        addView(vbox().apply {
            addView(label(title, 16f))
            if (subtitle != null) addView(label(subtitle, 13f, Col.SUB))
        }, lin(0, WRAP, 1f, start = dp(16), end = dp(8)))
        addView(sw)
    }
}

/** Поле ввода в тёмной рамке с подписью. */
fun Context.field(title: String, value: String, hint: String = "", password: Boolean = false, multiline: Boolean = false): Pair<View, EditText> {
    val e = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(Col.TEXT)
        setHintTextColor(Col.DIM)
        textSize = 15f
        background = rounded(Col.CARD2, dp(12).toFloat())
        setPadding(dp(14), dp(12), dp(14), dp(12))
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        if (multiline) { minLines = 3; gravity = Gravity.TOP or Gravity.START }
    }
    val box = vbox().apply {
        addView(label(title, 13f, Col.SUB).apply { setPadding(dp(4), dp(12), 0, dp(6)) })
        addView(e, lin())
    }
    return box to e
}

/** Большая синяя кнопка. */
fun Context.primaryButton(t: String, onClick: () -> Unit) = label(t, 16f, bold = true).apply {
    gravity = Gravity.CENTER
    val r = dp(26).toFloat()
    background = clickable(rounded(Col.ACCENT, r), r)
    setPadding(dp(16), dp(15), dp(16), dp(15))
    setOnClickListener { onClick() }
}

/** Чип-переключатель («Все / Изображения / Видео»). */
fun Context.chip(t: String, selected: Boolean, onClick: () -> Unit) = label(t, 16f).apply {
    val r = dp(22).toFloat()
    background = clickable(rounded(if (selected) Col.ACCENT else Col.CARD, r), r)
    setPadding(dp(22), dp(10), dp(22), dp(10))
    setOnClickListener { onClick() }
}

fun Context.dialog(): AlertDialog.Builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
