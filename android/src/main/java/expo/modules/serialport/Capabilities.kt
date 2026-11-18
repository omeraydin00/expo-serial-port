package expo.modules.serialport

/** What an open port can do beyond reading and writing. */
internal class Capabilities(
  /** [Lines] bits. */
  val lines: Int,
  val flowControl: List<String>,
  val breakSignal: Boolean,
  val rs485: Boolean
) {
  fun toMap() = mapOf(
    "lines" to Lines.names(lines),
    "flowControl" to flowControl,
    "breakSignal" to breakSignal,
    "rs485" to rs485
  )

  companion object {
    val NONE = Capabilities(0, listOf("none"), breakSignal = false, rs485 = false)
  }
}
