package expo.modules.serialport

/** The device side of a pseudo-terminal. The app opens [path] like any UART. */
internal class VirtualPort private constructor(private val pty: Termios.Pty) : Port {
  @Volatile private var closed = false

  val path: String get() = pty.path

  override val blockingRead = false

  override val key = "virtual:${pty.path}"

  override val capabilities = Capabilities.NONE

  override fun read(buffer: ByteArray, timeoutMs: Int): Int {
    check(!closed) { "port closed" }
    return Termios.read(pty.master, buffer, timeoutMs)
  }

  override fun write(data: ByteArray) {
    check(!closed) { "port closed" }
    Termios.write(pty.master, data)
  }

  override fun getLines(): Int = throw notSupported("Virtual ports have no control lines")

  override fun setLines(set: Int, clear: Int) = throw notSupported("Virtual ports have no control lines")

  override fun setBreak(on: Boolean) = throw notSupported("Virtual ports have no break signal")

  /** Hangs up the pts side, so the app's port fails with DISCONNECTED. */
  override fun close() {
    if (closed) return
    closed = true
    Termios.close(pty.slave)
    Termios.close(pty.master)
  }

  companion object {
    fun create() = VirtualPort(Termios.createPty())
  }
}
