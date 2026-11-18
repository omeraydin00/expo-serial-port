package expo.modules.serialport

import android.system.OsConstants
import java.io.IOException

/** JNI side of termios.c. */
internal object Termios {
  private const val ERR_BAUD = -100000

  init {
    System.loadLibrary("exposerialport")
  }

  @JvmStatic private external fun nativeOpen(path: String, baud: Int, dataBits: Int, stopBits: Int, parity: Int, flow: Int): Int
  @JvmStatic private external fun nativeRead(fd: Int, buffer: ByteArray, timeoutMs: Int): Int
  @JvmStatic private external fun nativeWrite(fd: Int, buffer: ByteArray, length: Int): Int
  @JvmStatic private external fun nativeClose(fd: Int)
  @JvmStatic private external fun nativeGetLines(fd: Int): Int
  @JvmStatic private external fun nativeSetLines(fd: Int, set: Int, clear: Int): Int
  @JvmStatic private external fun nativeSetBreak(fd: Int, on: Boolean): Int
  @JvmStatic private external fun nativeGetRs485(fd: Int): Int
  @JvmStatic private external fun nativeSetRs485(fd: Int, flags: Int, delayBeforeMs: Int, delayAfterMs: Int): Int
  @JvmStatic private external fun nativeCreatePty(fds: IntArray): String?

  class Pty(val path: String, val master: Int, val slave: Int)

  fun open(path: String, options: OpenOptions): Int {
    val fd = nativeOpen(path, options.baudRate, options.dataBits, options.stopBits, options.parityCode, options.uartFlowCode)
    if (fd >= 0) return fd
    throw when (fd) {
      ERR_BAUD -> SerialException("UNSUPPORTED_BAUD_RATE", "$path does not support ${options.baudRate} baud")
      -OsConstants.EACCES, -OsConstants.EPERM -> SerialException(
        "PERMISSION_DENIED",
        "No read/write access to $path. The device node needs rw for this app, or pass useSu: true on a rooted device."
      )
      -OsConstants.ENOENT, -OsConstants.ENXIO, -OsConstants.ENODEV -> SerialException("NOT_FOUND", "$path does not exist")
      else -> SerialException("OPEN_FAILED", "Could not open $path (errno ${-fd})")
    }
  }

  fun read(fd: Int, buffer: ByteArray, timeoutMs: Int): Int {
    val n = nativeRead(fd, buffer, timeoutMs)
    if (n < 0) throw IOException("read failed (errno ${-n})")
    return n
  }

  fun write(fd: Int, data: ByteArray) {
    val r = nativeWrite(fd, data, data.size)
    if (r < 0) throw IOException("write failed (errno ${-r})")
  }

  fun close(fd: Int) = nativeClose(fd)

  /** [Lines] bits, or null if the driver has no modem lines. */
  fun linesOrNull(fd: Int): Int? = nativeGetLines(fd).takeIf { it >= 0 }

  fun getLines(fd: Int): Int = check("TIOCMGET", nativeGetLines(fd))

  fun setLines(fd: Int, set: Int, clear: Int) {
    check("TIOCMBIS/TIOCMBIC", nativeSetLines(fd, set, clear))
  }

  fun setBreak(fd: Int, on: Boolean) {
    check(if (on) "TIOCSBRK" else "TIOCCBRK", nativeSetBreak(fd, on))
  }

  /** The driver's RS-485 flags, or null if it has no RS-485 support. */
  fun rs485FlagsOrNull(fd: Int): Int? = nativeGetRs485(fd).takeIf { it >= 0 }

  /** Returns the flags the driver kept. */
  fun setRs485(fd: Int, flags: Int, delayBeforeMs: Int, delayAfterMs: Int): Int =
    check("TIOCSRS485", nativeSetRs485(fd, flags, delayBeforeMs, delayAfterMs))

  fun createPty(): Pty {
    val fds = IntArray(2)
    val path = nativeCreatePty(fds) ?: throw SerialException("OPEN_FAILED", "Could not create a pseudo-terminal (errno ${-fds[0]})")
    return Pty(path, fds[0], fds[1])
  }

  private fun check(what: String, result: Int): Int {
    if (result < 0) throw IOException("$what failed (errno ${-result})")
    return result
  }
}
