package expo.modules.serialport

/** An open serial port. Implementations are used from one reader thread and one writer thread. */
internal interface Port {
  /** Same value for the same physical port, used to refuse a second open. */
  val key: String

  /**
   * True if [read] should be called with timeout 0 (block until data). USB needs this:
   * short bulk transfer timeouts lose data. [close] unblocks a pending read.
   */
  val blockingRead: Boolean

  /** Waits up to [timeoutMs] (0 = until data) for data. Returns the number of bytes read, 0 on timeout. Throws on a dead port. */
  fun read(buffer: ByteArray, timeoutMs: Int): Int

  fun write(data: ByteArray)

  fun close()
}
