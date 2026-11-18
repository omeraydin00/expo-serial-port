package expo.modules.serialport

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads a port's control lines every [intervalMs] and reports the state when it changes, starting
 * with the current state. Neither USB nor most UART drivers have a reliable change notification.
 */
internal class LinePoller(
  private val port: Port,
  private val onChange: (Int) -> Unit,
  intervalMs: Long = 20
) {
  private var last = -1

  private val executor = Executors.newSingleThreadScheduledExecutor { r ->
    Thread(r, "serial-lines-${port.key}").apply { isDaemon = true }
  }.also { it.scheduleWithFixedDelay({ poll() }, 0, intervalMs, TimeUnit.MILLISECONDS) }

  private fun poll() {
    val state = try {
      port.getLines()
    } catch (_: Exception) {
      return // a dead port is reported by its read loop
    }
    if (state != last) {
      last = state
      onChange(state)
    }
  }

  fun stop() {
    executor.shutdownNow()
  }
}
