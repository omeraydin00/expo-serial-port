package expo.modules.serialport

import org.junit.Assert.assertEquals
import org.junit.Test

class Rs485Test {
  @Test
  fun defaultsDriveRtsHighWhileSending() {
    assertEquals(0b011, Rs485.flags(rtsOnSend = true, rtsAfterSend = false, receiveDuringSend = false))
  }

  @Test
  fun everyFlag() {
    assertEquals(0b10111, Rs485.flags(rtsOnSend = true, rtsAfterSend = true, receiveDuringSend = true))
    assertEquals(Rs485.ENABLED, Rs485.flags(rtsOnSend = false, rtsAfterSend = false, receiveDuringSend = false))
  }

  @Test
  fun valuesMatchLinuxSerialH() {
    // SER_RS485_ENABLED, _RTS_ON_SEND, _RTS_AFTER_SEND, _RX_DURING_TX
    assertEquals(listOf(1, 2, 4, 16), listOf(Rs485.ENABLED, Rs485.RTS_ON_SEND, Rs485.RTS_AFTER_SEND, Rs485.RX_DURING_TX))
  }
}
