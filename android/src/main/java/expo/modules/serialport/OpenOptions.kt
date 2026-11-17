package expo.modules.serialport

import com.hoho.android.usbserial.driver.UsbSerialPort
import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record

/** Options already checked on the JS side; see src/options.ts. */
internal class OpenOptions : Record {
  @Field var baudRate: Int = 9600
  @Field var dataBits: Int = 8
  @Field var stopBits: Int = 1
  @Field var parity: String = "none"
  @Field var framing: FramingOptions = FramingOptions()
  @Field var useSu: Boolean = false

  /** 0 none, 1 odd, 2 even: the same numbers in termios.c and usb-serial-for-android. */
  val parityCode: Int
    get() = when (parity) {
      "odd" -> UsbSerialPort.PARITY_ODD
      "even" -> UsbSerialPort.PARITY_EVEN
      else -> UsbSerialPort.PARITY_NONE
    }

  val usbStopBits: Int
    get() = if (stopBits == 2) UsbSerialPort.STOPBITS_2 else UsbSerialPort.STOPBITS_1
}

internal class FramingOptions : Record {
  @Field var type: String = "raw"
  @Field var bytes: List<Int> = emptyList()
  @Field var size: Int = 0
  @Field var ms: Int = 0

  fun toFramer(): Framer = when (type) {
    "delimiter" -> Framer.Delimiter(ByteArray(bytes.size) { bytes[it].toByte() })
    "length" -> Framer.Length(size)
    "idle" -> Framer.Idle(ms.toLong())
    else -> Framer.Raw()
  }
}
