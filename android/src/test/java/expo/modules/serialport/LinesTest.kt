package expo.modules.serialport

import org.junit.Assert.assertEquals
import org.junit.Test

class LinesTest {
  @Test
  fun namesInFixedOrder() {
    assertEquals(listOf("dtr", "cts", "ri"), Lines.names(Lines.RI or Lines.DTR or Lines.CTS))
    assertEquals(emptyList<String>(), Lines.names(0))
    assertEquals(listOf("dtr", "rts", "cts", "dsr", "cd", "ri"), Lines.names(Lines.ALL))
  }

  @Test
  fun mapHasOnlySupportedLines() {
    val map = Lines.toMap(state = Lines.DTR or Lines.CD, supported = Lines.DTR or Lines.RTS or Lines.CTS)
    assertEquals(mapOf("dtr" to true, "rts" to false, "cts" to false), map)
  }

  @Test
  fun bitsMatchTermiosC() {
    // LINE_* in termios.c
    assertEquals(listOf(1, 2, 4, 8, 16, 32), listOf(Lines.DTR, Lines.RTS, Lines.CTS, Lines.DSR, Lines.CD, Lines.RI))
  }
}
