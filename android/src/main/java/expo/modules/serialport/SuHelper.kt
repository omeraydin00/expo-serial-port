package expo.modules.serialport

import java.io.File
import java.util.concurrent.TimeUnit

/** Only used when the app opens a UART with useSu: true. */
internal object SuHelper {
  /** Gives [path] rw for everyone through su. Returns true if the node is then readable and writable. */
  fun makeAccessible(path: String): Boolean {
    require(path.startsWith("/dev/") && !path.contains("..") && path.none { it.isWhitespace() || it == ';' || it == '\'' || it == '"' }) {
      "refusing to chmod $path"
    }
    return try {
      val p = ProcessBuilder("su", "-c", "chmod 666 $path").redirectErrorStream(true).start()
      p.waitFor(5, TimeUnit.SECONDS)
      val f = File(path)
      f.canRead() && f.canWrite()
    } catch (_: Exception) {
      false
    }
  }
}
