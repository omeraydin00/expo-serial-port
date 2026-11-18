package expo.modules.serialport

/** Control line bits, the same values as LINE_* in termios.c. */
internal object Lines {
  const val DTR = 1
  const val RTS = 2
  const val CTS = 4
  const val DSR = 8
  const val CD = 16
  const val RI = 32
  const val ALL = DTR or RTS or CTS or DSR or CD or RI

  private val byName = listOf("dtr" to DTR, "rts" to RTS, "cts" to CTS, "dsr" to DSR, "cd" to CD, "ri" to RI)

  fun names(mask: Int): List<String> = byName.filter { (mask and it.second) != 0 }.map { it.first }

  /** The state of each line in [supported], for JS. */
  fun toMap(state: Int, supported: Int): Map<String, Boolean> =
    byName.filter { (supported and it.second) != 0 }.associate { it.first to ((state and it.second) != 0) }
}
