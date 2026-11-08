package app.glassmate.ui

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateUtils
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.glassmate.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class Media(val uri: Uri, val video: Boolean, val dateMs: Long, val durationMs: Long)

class AlbumTab(private val h: Host) {
    private var filter = 0 // 0 = всё, 1 = фото, 2 = видео
    private var items: List<Media> = emptyList()
    private lateinit var grid: LinearLayout
    private lateinit var chips: LinearLayout
    lateinit var view: View
        private set

    init {
        val a = h.ctx
        with(a) {
            val root = vbox()
            root.addView(FrameLayout(this).apply {
                setPadding(dp(16), dp(14), dp(16), dp(10))
                addView(label("Альбом", 20f, bold = true).apply {
                    layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
                })
                addView(hbox().apply {
                    layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
                    addView(FrameLayout(context).apply {
                        val s = dp(44)
                        background = clickable(rounded(Col.BG, s / 2f), s / 2f)
                        addView(icon(R.drawable.ic_download, Col.TEXT, 28).apply {
                            layoutParams = FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
                        })
                        setOnClickListener { Actions.importMedia(h) { refresh() } }
                    }, lin(dp(44), dp(44)))
                })
            })
            chips = hbox().apply { setPadding(dp(12), 0, dp(12), dp(8)) }
            root.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(chips)
            })
            grid = vbox(dp(12))
            root.addView(ScrollView(this).apply {
                isVerticalScrollBarEnabled = false
                addView(grid)
            }, lin(MATCH, 0, 1f))
            view = root
        }
        drawChips()
    }

    private fun drawChips(): Unit = with(h.ctx) {
        chips.removeAllViews()
        listOf("Все", "Изображения", "Видео").forEachIndexed { i, t ->
            chips.addView(chip(t, i == filter) { filter = i; drawChips(); drawGrid() }, lin(WRAP, WRAP, end = dp(10)))
        }
    }

    /** Перечитать медиатеку (снимки ИИ и импорт с очков лежат в папках GlassMate). */
    fun refresh() {
        h.ui.launch {
            items = withContext(Dispatchers.IO) { query() }
            drawGrid()
        }
    }

    private fun query(): List<Media> {
        val out = ArrayList<Media>()
        val cr = h.ctx.contentResolver
        fun scan(collection: Uri, video: Boolean) {
            val proj = mutableListOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED)
            if (video) proj += MediaStore.Video.VideoColumns.DURATION
            runCatching {
                cr.query(
                    collection, proj.toTypedArray(),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("%GlassMate%"),
                    "${MediaStore.MediaColumns.DATE_ADDED} DESC"
                )?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        out += Media(
                            ContentUris.withAppendedId(collection, id), video,
                            c.getLong(1) * 1000, if (video) c.getLong(2) else 0
                        )
                    }
                }
            }
        }
        scan(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)
        scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
        return out.sortedByDescending { it.dateMs }
    }

    private fun drawGrid(): Unit = with(h.ctx) {
        grid.removeAllViews()
        val shown = items.filter { filter == 0 || (filter == 1 && !it.video) || (filter == 2 && it.video) }
        if (shown.isEmpty()) {
            grid.addView(vbox().apply {
                gravity = Gravity.CENTER
                addView(icon(R.drawable.ic_image, Col.DIM, 64))
                addView(label(
                    "Здесь появятся снимки ИИ и фото с видео с очков.\nНажмите ↓ вверху, чтобы импортировать их по Wi-Fi.",
                    14f, Col.SUB
                ).apply { gravity = Gravity.CENTER }, lin(WRAP, WRAP, top = dp(12)))
            }, lin(MATCH, WRAP, top = dp(80)))
            return@with
        }
        val cols = 4
        val gap = dp(4)
        val cell = (resources.displayMetrics.widthPixels - dp(12) * 2 - gap * (cols - 1)) / cols
        val dayFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        for ((day, group) in shown.groupBy { dayFmt.format(Date(it.dateMs)) }) {
            val title = when {
                DateUtils.isToday(group[0].dateMs) -> "Сегодня"
                DateUtils.isToday(group[0].dateMs + DateUtils.DAY_IN_MILLIS) -> "Вчера"
                else -> day
            }
            grid.addView(label(title, 18f, Col.SUB, bold = true).apply { setPadding(dp(4), dp(14), 0, dp(10)) })
            for (row in group.chunked(cols)) {
                grid.addView(hbox().apply {
                    row.forEachIndexed { i, m -> addView(cellView(m, cell), lin(cell, cell, start = if (i == 0) 0 else gap)) }
                }, lin(MATCH, WRAP, bottom = gap))
            }
        }
    }

    private fun cellView(m: Media, size: Int): View = with(h.ctx) {
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Col.CARD)
        }
        val cellRoot = FrameLayout(this).apply {
            background = rounded(Col.CARD, dp(12).toFloat())
            clipToOutline = true
            addView(img, FrameLayout.LayoutParams(MATCH, MATCH))
            if (m.video) {
                val sec = m.durationMs / 1000
                addView(label("▶ %02d:%02d".format(sec / 60, sec % 60), 12f).apply {
                    background = rounded(0x99000000.toInt(), dp(10).toFloat())
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.BOTTOM).apply {
                        setMargins(dp(6), 0, 0, dp(6))
                    }
                })
            }
            setOnClickListener {
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(m.uri, if (m.video) "video/*" else "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }.onFailure { h.toast("Не удалось открыть файл") }
            }
        }
        h.ui.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching { contentResolver.loadThumbnail(m.uri, Size(size, size), null) }.getOrNull()
            }
            if (bmp != null) img.setImageBitmap(bmp)
        }
        cellRoot
    }
}
