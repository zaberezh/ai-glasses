package app.glassmate

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent

/**
 * Держит приложение живым, пока очки подключены, и ловит медиакнопки:
 * касания дужки, настроенные на «плей/пауза» и «след./пред. трек», приходят сюда.
 */
class GlassesService : Service() {

    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (Core.settings.mediaButtons) setupMediaSession()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android перезапустил службу после нехватки памяти — возвращаем связь с очками
        if (intent == null) Core.autoConnect()
        when (intent?.action) {
            ACTION_ASK -> Core.assistant.trigger("уведомление")
            ACTION_LOOK -> Core.assistant.look()
            ACTION_STOP -> {
                Core.disconnect()
                stopSelf()
            }
            ACTION_REFRESH_BUTTONS -> {
                if (Core.settings.mediaButtons) {
                    if (session == null) setupMediaSession() else claimMediaButtons()
                } else {
                    releaseSession()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        releaseSession()
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Подключение к очкам", NotificationManager.IMPORTANCE_LOW)
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        fun action(title: String, act: String, code: Int) = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_glasses), title,
            PendingIntent.getService(this, code, Intent(this, GlassesService::class.java).setAction(act), flags)
        ).build()

        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle("GlassMate")
            .setContentText("Очки на связи")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action("Спросить", ACTION_ASK, 1))
            .addAction(action("Что я вижу?", ACTION_LOOK, 2))
            .addAction(action("Отключить", ACTION_STOP, 3))
            .build()

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            startForeground(1, n, type)
        } catch (e: Exception) {
            Core.log("Не удалось запустить фоновую службу: ${e.message}")
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    private fun setupMediaSession() {
        val s = MediaSession(this, "GlassMate")
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ev: KeyEvent? = if (Build.VERSION.SDK_INT >= 33) {
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                }
                if (ev == null || ev.action != KeyEvent.ACTION_UP) return true
                onButton(ev.keyCode)
                return true
            }
        })
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(PlaybackState.STATE_PLAYING, 0, 0f)
                .build()
        )
        s.isActive = true
        session = s
        claimMediaButtons()
        Core.log("Медиакнопки очков перехватываются")
    }

    private fun onButton(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> Core.assistant.trigger("кнопка плей/пауза")
            KeyEvent.KEYCODE_MEDIA_NEXT -> Core.assistant.look()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Core.assistant.stop()
            else -> Core.log("Медиакнопка: ${KeyEvent.keyCodeToString(code)}")
        }
    }

    /**
     * Android отправляет медиакнопки приложению, которое последним играло звук.
     * Проигрываем полсекунды тишины, чтобы стать таким приложением.
     */
    private fun claimMediaButtons() {
        Thread {
            runCatching {
                val rate = 16000
                val samples = rate / 2
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples * 2)
                    .build()
                track.write(ShortArray(samples), 0, samples)
                track.play()
                Thread.sleep(700)
                track.stop()
                track.release()
            }
        }.start()
    }

    private fun releaseSession() {
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
    }

    companion object {
        private const val CHANNEL = "glasses"
        const val ACTION_ASK = "app.glassmate.ASK"
        const val ACTION_LOOK = "app.glassmate.LOOK"
        const val ACTION_STOP = "app.glassmate.STOP"
        const val ACTION_REFRESH_BUTTONS = "app.glassmate.REFRESH_BUTTONS"

        fun start(ctx: Context, action: String? = null) {
            val i = Intent(ctx, GlassesService::class.java)
            if (action != null) i.action = action
            ctx.startForegroundService(i)
        }
    }
}
