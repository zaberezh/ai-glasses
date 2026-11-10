package app.glassmate

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.glassmate.ui.AlbumTab
import app.glassmate.ui.Col
import app.glassmate.ui.Host
import app.glassmate.ui.MATCH
import app.glassmate.ui.WRAP
import app.glassmate.ui.aiTab
import app.glassmate.ui.deviceTab
import app.glassmate.ui.dp
import app.glassmate.ui.hbox
import app.glassmate.ui.icon
import app.glassmate.ui.label
import app.glassmate.ui.lin
import app.glassmate.ui.meTab
import app.glassmate.ui.vbox
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Главное окно: четыре вкладки снизу, как в AIBuds, и стек вложенных экранов поверх. */
class MainActivity : Activity(), Host {

    override val ctx: Activity get() = this
    override val ui = MainScope()

    private lateinit var tabsHost: FrameLayout
    private lateinit var overlayHost: FrameLayout
    private val tabs = ArrayList<View>()
    private val navItems = ArrayList<Pair<ImageView, TextView>>()
    private val stack = ArrayList<View>()
    private lateinit var album: AlbumTab
    private var current = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Col.BG))
        @Suppress("DEPRECATION")
        window.statusBarColor = Col.BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = Col.BG
        build()
        requestNeededPermissions()
        tryAutoConnect()
    }

    /** Подключиться к последним очкам при запуске, если есть разрешение. */
    private fun tryAutoConnect() {
        val granted = Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (granted && Core.settings.lastDevice.isNotEmpty() && !Core.connected.value) {
            GlassesService.start(this)
            Core.autoConnect()
        }
    }

    override fun onResume() {
        super.onResume()
        // приложение открыли — скорее всего, сейчас будут снимать: будим камеру заранее
        Core.warmCamera()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    private fun build() {
        tabsHost = FrameLayout(this)
        album = AlbumTab(this)
        tabs += deviceTab(this)
        tabs += aiTab(this)
        tabs += album.view
        tabs += meTab(this)
        tabs.forEach { tabsHost.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }

        val nav = hbox().apply {
            setBackgroundColor(Col.BG)
            setPadding(0, dp(8), 0, dp(10))
        }
        val items = listOf(
            R.drawable.ic_glasses_outline to "Устройство",
            R.drawable.ic_sparkles to "AI",
            R.drawable.ic_image to "Альбом",
            R.drawable.ic_person to "Мой",
        )
        items.forEachIndexed { i, (res, title) ->
            val ic = icon(res, Col.SUB, 30)
            val tx = label(title, 13f, Col.SUB).apply { gravity = Gravity.CENTER }
            navItems += ic to tx
            nav.addView(vbox().apply {
                gravity = Gravity.CENTER
                addView(ic)
                addView(tx, lin(WRAP, WRAP, top = dp(2)))
                setOnClickListener { selectTab(i) }
            }, lin(0, WRAP, 1f))
        }

        val progress = label("", 13f, Col.TEXT).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Col.CARD)
            visibility = View.GONE
        }
        ui.launch {
            Core.importProgress.collect {
                progress.text = it
                progress.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            }
        }
        val main = vbox().apply {
            setBackgroundColor(Col.BG)
            addView(tabsHost, lin(MATCH, 0, 1f))
            addView(progress)
            addView(nav)
        }
        overlayHost = FrameLayout(this)
        setContentView(FrameLayout(this).apply {
            addView(main, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(overlayHost, FrameLayout.LayoutParams(MATCH, MATCH))
        })
        selectTab(0)
    }

    override fun selectTab(i: Int) {
        while (stack.isNotEmpty()) pop()
        current = i
        tabs.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { idx, (ic, tx) ->
            val c = if (idx == i) Col.TEXT else Col.SUB
            ic.imageTintList = ColorStateList.valueOf(c)
            tx.setTextColor(c)
        }
        if (i == 2) album.refresh()
    }

    override fun push(v: View) {
        v.isClickable = true // не пропускать касания к экрану под ним
        if (v.background == null) v.setBackgroundColor(Col.BG)
        stack += v
        overlayHost.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    override fun pop() {
        if (stack.isEmpty()) return
        overlayHost.removeView(stack.removeAt(stack.size - 1))
    }

    override fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            stack.isNotEmpty() -> pop()
            current != 0 -> selectTab(0)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    private fun requestNeededPermissions() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) need += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) {
            need += Manifest.permission.POST_NOTIFICATIONS
            need += Manifest.permission.NEARBY_WIFI_DEVICES
            need += Manifest.permission.READ_MEDIA_IMAGES
            need += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val missing = need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (current == 2) album.refresh()
        tryAutoConnect()
    }
}
