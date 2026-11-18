package expo.modules.serialport

import java.io.IOException

internal class UartPort(private val path: String, options: OpenOptions) : Port {
  private val pty = path.startsWith("/dev/pts/")
  private val fd: Int
  @Volatile private var closed = false

  override val blockingRead = false

  override val key = keyFor(path)

  override val capabilities: Capabilities

  init {
    val flows = if (pty) listOf("none") else listOf("none", "rts-cts", "xon-xoff")
    if (options.flowControl !in flows) throw notSupported("$path does not do ${options.flowControl} flow control")
    fd = Termios.open(path, options)
    capabilities = try {
      configure(options, flows)
    } catch (e: Exception) {
      Termios.close(fd)
      throw e
    }
  }

  private fun configure(options: OpenOptions, flows: List<String>): Capabilities {
    val rs485 = !pty && Termios.rs485FlagsOrNull(fd) != null
    if (options.rs485.enabled) {
      if (!rs485) throw notSupported("The driver of $path has no RS-485 mode")
      val r = options.rs485
      val kept = try {
        Termios.setRs485(fd, r.flags, r.delayBeforeSendMs, r.delayAfterSendMs)
      } catch (e: IOException) {
        throw notSupported("$path refused RS-485 mode: ${e.message}")
      }
      if ((kept and Rs485.ENABLED) == 0) throw notSupported("$path did not keep RS-485 mode on")
    }
    return Capabilities(
      lines = if (Termios.linesOrNull(fd) != null) Lines.ALL else 0,
      flowControl = flows,
      breakSignal = !pty,
      rs485 = rs485
    )
  }

  override fun read(buffer: ByteArray, timeoutMs: Int): Int {
    check(!closed) { "port closed" }
    return Termios.read(fd, buffer, timeoutMs)
  }

  override fun write(data: ByteArray) {
    check(!closed) { "port closed" }
    Termios.write(fd, data)
  }

  override fun getLines() = Termios.getLines(fd)

  override fun setLines(set: Int, clear: Int) = Termios.setLines(fd, set, clear)

  override fun setBreak(on: Boolean) = Termios.setBreak(fd, on)

  override fun close() {
    if (closed) return
    closed = true
    Termios.close(fd)
  }

  companion object {
    fun keyFor(path: String) = "uart:$path"
  }
}
