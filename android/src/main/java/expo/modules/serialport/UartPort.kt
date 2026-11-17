package expo.modules.serialport

internal class UartPort(private val path: String, options: OpenOptions) : Port {
  private val fd = Termios.open(path, options)
  @Volatile private var closed = false

  override val blockingRead = false

  override val key = keyFor(path)

  override fun read(buffer: ByteArray, timeoutMs: Int): Int {
    check(!closed) { "port closed" }
    return Termios.read(fd, buffer, timeoutMs)
  }

  override fun write(data: ByteArray) {
    check(!closed) { "port closed" }
    Termios.write(fd, data)
  }

  override fun close() {
    if (closed) return
    closed = true
    Termios.close(fd)
  }

  companion object {
    fun keyFor(path: String) = "uart:$path"
  }
}
