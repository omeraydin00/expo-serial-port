package expo.modules.serialport

import android.system.OsConstants

/** JNI side of termios.c. */
internal object Termios {
  private const val ERR_BAUD = -100000

  init {
    System.loadLibrary("exposerialport")
  }

  @JvmStatic private external fun nativeOpen(path: String, baud: Int, dataBits: Int, stopBits: Int, parity: Int): Int
  @JvmStatic private external fun nativeRead(fd: Int, buffer: ByteArray, timeoutMs: Int): Int
  @JvmStatic private external fun nativeWrite(fd: Int, buffer: ByteArray, length: Int): Int
  @JvmStatic private external fun nativeClose(fd: Int)

  fun open(path: String, options: OpenOptions): Int {
    val fd = nativeOpen(path, options.baudRate, options.dataBits, options.stopBits, options.parityCode)
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
    if (n < 0) throw java.io.IOException("read failed (errno ${-n})")
    return n
  }

  fun write(fd: Int, data: ByteArray) {
    val r = nativeWrite(fd, data, data.size)
    if (r < 0) throw java.io.IOException("write failed (errno ${-r})")
  }

  fun close(fd: Int) = nativeClose(fd)
}
