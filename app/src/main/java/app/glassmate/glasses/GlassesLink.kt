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
    private val decoder = FrameDecoder()
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
                for (m in decoder.feed(buf.copyOf(n))) dispatch(m)
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
        _notifications.tryEmit(m)
    }

    /** Отправить команду, не дожидаясь ответа. */
    fun send(cmd: Int, payload: ByteArray = ByteArray(0)) {
        if (!isOpen) throw IOException("Очки не подключены")
        val frames = encoder.encode(cmd, payload)
        synchronized(writeLock) {
            for (f in frames) output.write(f)
            output.flush()
        }
    }

    /** Отправить команду и дождаться ответа. Возвращает payload ответа или null по таймауту. */
    suspend fun request(cmd: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = 8000): ByteArray? =
        cmdLocks.getOrPut(cmd) { Mutex() }.withLock {
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
