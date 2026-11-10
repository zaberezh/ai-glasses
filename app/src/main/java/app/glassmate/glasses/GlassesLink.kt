package app.glassmate.glasses

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import app.glassmate.Core
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Соединение с очками по Bluetooth SPP (RFCOMM, как у официального приложения).
 * Запросы идут строго по одному: очки отвечают кадром типа RESPONSE с тем же кодом команды.
 */
class GlassesLink private constructor(private val socket: BluetoothSocket) {


    private val input = socket.inputStream
    private val output = socket.outputStream
    private val encoder = FrameEncoder()
    private val decoder = FrameDecoder(onForeign = ::answerStarBurst)
    private val writeLock = Any()
    /** Ожидающие ответа запросы: команда -> ответ. Разные команды не ждут друг друга. */
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    /** Одинаковые команды идут по одной (ответ различается только кодом команды). */
    private val cmdLocks = ConcurrentHashMap<Int, Mutex>()
    @Volatile var isOpen = true
        private set

    private val _notifications = MutableSharedFlow<Message>(extraBufferCapacity = 512)
    /** Уведомления от очков (и редкие запросы, которые очки шлют сами). */
    val notifications: SharedFlow<Message> = _notifications

    /**
     * Прямые приёмники потоковых данных (куски снимка): вызываются сразу в потоке чтения.
     * Через общий SharedFlow их пускать нельзя — при быстрой передаче его буфер
     * переполняется и куски теряются.
     */
    private val sinks = ConcurrentHashMap<Int, (Message) -> Unit>()

    fun setSink(cmd: Int, sink: (Message) -> Unit) { sinks[cmd] = sink }
    fun clearSink(cmd: Int) { sinks.remove(cmd) }

    /** Вызывается один раз, когда соединение закрылось (с любой стороны). */
    @Volatile var onClosed: (() -> Unit)? = null

    private val reader = Thread({ readLoop() }, "glasses-reader").apply { isDaemon = true }

    init {
        reader.start()
    }

    private fun readLoop() {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val chunk = buf.copyOf(n)
                val msgs = decoder.feed(chunk)
                // во время передачи снимка байты в журнал не пишем — это тормозит приём
                if (Core.settings.verboseLog && sinks.isEmpty() && msgs.none { it.cmd == Cmd.LIVE_STREAM }) {
                    Core.log("⇠ ${chunk.toHex(40)}")
                }
                for (m in msgs) dispatch(m)
            }
        } catch (_: IOException) {
        } finally {
            close()
        }
    }

    private fun dispatch(m: Message) {
        if (m.type == FrameType.RESPONSE) {
            val d = pending[m.cmd]
            if (d != null) {
                d.complete(m.payload)
                return
            }
        }
        val sink = sinks[m.cmd]
        if (sink != null) {
            runCatching { sink(m) }
            return
        }
        _notifications.tryEmit(m)
    }

    /**
     * Пакеты второго протокола (облачный ИИ StarBurst): [0x10][seq][0xF0][cmd][данные].
     * AIBuds отвечает на «рукопожатие» 0x60 пакетом [0x00][seq][0xF0][0x60][шаг+1][0x00];
     * без ответа очки повторяют его и медленнее обрабатывают остальные команды.
     */
    private fun answerStarBurst(c: ByteArray) {
        val cmd = c[3].toInt() and 0xFF
        if (Core.settings.verboseLog) Core.log("⇠ SB ${c.toHex(24)}")
        if (cmd != 0x60) return
        val step = if (c.size > 4) c[4].toInt() and 0xFF else 0
        val answer = byteArrayOf(0x00, c[1], 0xF0.toByte(), 0x60, (step + 1).toByte(), 0x00)
        try {
            synchronized(writeLock) { output.write(answer); output.flush() }
            if (Core.settings.verboseLog) Core.log("⇢ SB ${answer.toHex()}")
        } catch (_: IOException) {}
    }

    /** Отправить команду, не дожидаясь ответа. */
    fun send(cmd: Int, payload: ByteArray = ByteArray(0)) {
        if (!isOpen) throw IOException("Очки не подключены")
        val frames = encoder.encode(cmd, payload)
        synchronized(writeLock) {
            // одним куском: меньше пакетов Bluetooth и команда не рвётся посередине
            output.write(if (frames.size == 1) frames[0] else frames.reduce { a, b -> a + b })
            output.flush()
        }
        if (Core.settings.verboseLog) Core.log("⇢ ${frames.joinToString(" ") { it.toHex(24) }}")
    }

    /** Отправить команду и дождаться ответа. Возвращает payload ответа или null по таймауту. */
    suspend fun request(cmd: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = 8000): ByteArray? {
        // Новая команда отменяет ожидание старой такой же — нажатия не копятся в очередь
        pending[cmd]?.completeExceptionally(IOException("заменено новой командой"))
        return cmdLocks.getOrPut(cmd) { Mutex() }.withLock {
            val d = CompletableDeferred<ByteArray>()
            pending[cmd] = d
            val t0 = SystemClock.elapsedRealtime()
            try {
                withContext(Dispatchers.IO) { send(cmd, payload) }
                val r = withTimeoutOrNull(timeoutMs) { d.await() }
                val ms = SystemClock.elapsedRealtime() - t0
                if (r == null) Core.log("0x%02X: нет ответа за %d мс".format(cmd, ms))
                else Core.log("0x%02X: ответ за %d мс (%s)".format(cmd, ms, r.toHex(8)))
                r
            } finally {
                pending.remove(cmd, d)
            }
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        try { socket.close() } catch (_: IOException) {}
        pending.values.forEach { it.cancel() }
        onClosed?.invoke()
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** Блокирующее подключение — вызывать не из главного потока. */
        @SuppressLint("MissingPermission")
        fun connect(device: BluetoothDevice, adapter: BluetoothAdapter): GlassesLink {
            runCatching { adapter.cancelDiscovery() }
            val attempts = listOf<() -> BluetoothSocket>(
                { device.createRfcommSocketToServiceRecord(SPP_UUID) },
                { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                {
                    // Официальное приложение подключается к каналу RFCOMM 1
                    device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        .invoke(device, 1) as BluetoothSocket
                },
            )
            var last: Exception? = null
            for (make in attempts) {
                var s: BluetoothSocket? = null
                try {
                    s = make()
                    s.connect()
                    return GlassesLink(s)
                } catch (e: Exception) {
                    last = e
                    try { s?.close() } catch (_: IOException) {}
                }
            }
            throw IOException("Не удалось подключиться к очкам: ${last?.message}", last)
        }
    }
}
