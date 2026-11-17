package expo.modules.serialport

/**
 * Turns the chunks a port returns into the packets the app wants.
 * Not thread safe: each port's reader thread owns its framer.
 */
internal sealed class Framer {
  /** Adds [length] bytes from [data] read at [nowMs]. */
  abstract fun push(data: ByteArray, length: Int, nowMs: Long, emit: (ByteArray) -> Unit)

  /** Called after every read, data or not. */
  open fun tick(nowMs: Long, emit: (ByteArray) -> Unit) {}

  /** Every chunk as it comes. */
  class Raw : Framer() {
    override fun push(data: ByteArray, length: Int, nowMs: Long, emit: (ByteArray) -> Unit) {
      if (length > 0) emit(data.copyOf(length))
    }
  }

  /** Packets end with [delimiter], which is not included in the packet. */
  class Delimiter(private val delimiter: ByteArray) : Framer() {
    private val buffer = Buffer()

    init {
      require(delimiter.isNotEmpty()) { "empty delimiter" }
    }

    override fun push(data: ByteArray, length: Int, nowMs: Long, emit: (ByteArray) -> Unit) {
      for (i in 0 until length) {
        buffer.add(data[i])
        if (buffer.endsWith(delimiter)) {
          emit(buffer.take(buffer.size - delimiter.size))
          buffer.clear()
        } else if (buffer.size >= MAX_PACKET) {
          emit(buffer.take(buffer.size))
          buffer.clear()
        }
      }
    }
  }

  /** Packets of exactly [size] bytes. */
  class Length(private val size: Int) : Framer() {
    private val buffer = Buffer()

    init {
      require(size > 0) { "size must be positive" }
    }

    override fun push(data: ByteArray, length: Int, nowMs: Long, emit: (ByteArray) -> Unit) {
      for (i in 0 until length) {
        buffer.add(data[i])
        if (buffer.size == size) {
          emit(buffer.take(size))
          buffer.clear()
        }
      }
    }
  }

  /** A packet is whatever arrived before the line stayed quiet for [gapMs]. */
  class Idle(val gapMs: Long) : Framer() {
    private val buffer = Buffer()
    private var lastByteAt = 0L

    init {
      require(gapMs > 0) { "gap must be positive" }
    }

    override fun push(data: ByteArray, length: Int, nowMs: Long, emit: (ByteArray) -> Unit) {
      if (length <= 0) return
      // The gap may have passed without a tick in between (a read that returned data right away).
      tick(nowMs, emit)
      for (i in 0 until length) buffer.add(data[i])
      lastByteAt = nowMs
      if (buffer.size >= MAX_PACKET) {
        emit(buffer.take(buffer.size))
        buffer.clear()
      }
    }

    override fun tick(nowMs: Long, emit: (ByteArray) -> Unit) {
      if (buffer.size > 0 && nowMs - lastByteAt >= gapMs) {
        emit(buffer.take(buffer.size))
        buffer.clear()
      }
    }
  }

  /** Small growable byte buffer, so framing does not box every byte. */
  internal class Buffer {
    private var bytes = ByteArray(256)
    var size = 0
      private set

    fun add(b: Byte) {
      if (size == bytes.size) bytes = bytes.copyOf(bytes.size * 2)
      bytes[size++] = b
    }

    fun endsWith(suffix: ByteArray): Boolean {
      if (size < suffix.size) return false
      val start = size - suffix.size
      for (i in suffix.indices) if (bytes[start + i] != suffix[i]) return false
      return true
    }

    fun take(n: Int): ByteArray = bytes.copyOf(n)

    fun clear() {
      size = 0
    }
  }

  companion object {
    /** A packet longer than this is emitted as is, so a missing delimiter cannot grow the buffer forever. */
    const val MAX_PACKET = 64 * 1024
  }
}
