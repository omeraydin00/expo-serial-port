package expo.modules.serialport

import java.io.File

/** Finds UART-style device nodes under /dev. */
internal object UartScanner {
  private val PREFIXES = listOf("ttyS", "ttyHS", "ttyMSM", "ttyAMA", "ttyUSB", "ttyACM", "ttyMT", "ttySAC")

  fun list(): List<Map<String, Any?>> {
    // Some ROMs do not let apps list /dev; stat on a known name still works there.
    val names = File("/dev").list()?.toList()
      ?: PREFIXES.flatMap { p -> (0..9).map { "$p$it" } }.filter { File("/dev/$it").exists() }
    return names
      .filter { name -> PREFIXES.any { name.startsWith(it) && name.length > it.length && name.substring(it.length).all(Char::isDigit) } }
      .sortedWith(compareBy({ n -> PREFIXES.indexOfFirst { n.startsWith(it) } }, { n -> n.dropWhile { !it.isDigit() }.toInt() }))
      .map { name ->
        val f = File("/dev/$name")
        mapOf("kind" to "uart", "path" to f.path, "readable" to f.canRead(), "writable" to f.canWrite())
      }
  }
}
