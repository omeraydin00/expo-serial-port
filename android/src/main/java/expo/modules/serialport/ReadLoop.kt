package expo.modules.serialport

import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Reads one port on its own thread until it is stopped or the port fails. */
internal class ReadLoop(
  private val port: Port,
  private val framer: Framer,
  private val onPacket: (ByteArray) -> Unit,
  private val onFailure: (Exception) -> Unit
) {
  @Volatile private var running = true

  private val thread = Thread({ run() }, "serial-read-${port.key}").apply { isDaemon = true }

  // The idle framer has to notice a quiet line even while no read returns.
  // A blocking port gets a ticker; a polling port ticks after every read.
  private val tickMs: Long = if (framer is Framer.Idle) (framer.gapMs / 2).coerceIn(2, 50) else 50
  private var ticker: ScheduledExecutorService? = null

  private var started = false

  @Synchronized
  fun start() {
    if (started || !running) return
    started = true
    thread.start()
    if (port.blockingRead && framer is Framer.Idle) {
      ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "serial-tick-${port.key}").apply { isDaemon = true } }
        .also { it.scheduleWithFixedDelay({ tick() }, tickMs, tickMs, TimeUnit.MILLISECONDS) }
    }
  }

  /** Stops reading. A blocking read only returns once the port is closed. */
  fun stop() {
    running = false
    ticker?.shutdownNow()
    thread.interrupt()
  }

  private fun tick() = synchronized(framer) { framer.tick(SystemClock.elapsedRealtime(), onPacket) }

  private fun run() {
    val buffer = ByteArray(BUFFER_SIZE)
    val timeout = if (port.blockingRead) 0 else tickMs.toInt()
    while (running) {
      try {
        val n = port.read(buffer, timeout)
        synchronized(framer) {
          val now = SystemClock.elapsedRealtime()
          if (n > 0) framer.push(buffer, n, now, onPacket)
          if (!port.blockingRead) framer.tick(now, onPacket)
        }
      } catch (e: Exception) {
        if (running) {
          running = false
          ticker?.shutdownNow()
          onFailure(if (e is IOException) e else IOException(e.message, e))
        }
      }
    }
  }

  companion object {
    private const val BUFFER_SIZE = 16 * 1024
  }
}
