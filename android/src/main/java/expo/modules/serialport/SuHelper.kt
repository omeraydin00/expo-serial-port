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
    val node = File(path)
    // Magisk and SuperSU take `su -c "cmd"`; the su in AOSP userdebug builds takes `su <uid> cmd...`.
    val attempts = listOf(
      listOf("su", "-c", "chmod 666 $path"),
      listOf("su", "0", "chmod", "666", path)
    )
    for (command in attempts) {
      try {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy()
      } catch (_: Exception) {
        // no su binary, try the next form
      }
      if (node.canRead() && node.canWrite()) return true
    }
    return false
  }
}
