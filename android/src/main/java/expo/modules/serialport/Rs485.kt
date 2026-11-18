package expo.modules.serialport

/** struct serial_rs485 flags, from linux/serial.h. */
internal object Rs485 {
  const val ENABLED = 1
  const val RTS_ON_SEND = 2
  const val RTS_AFTER_SEND = 4
  const val RX_DURING_TX = 16

  fun flags(rtsOnSend: Boolean, rtsAfterSend: Boolean, receiveDuringSend: Boolean): Int =
    ENABLED or
      (if (rtsOnSend) RTS_ON_SEND else 0) or
      (if (rtsAfterSend) RTS_AFTER_SEND else 0) or
      (if (receiveDuringSend) RX_DURING_TX else 0)
}
