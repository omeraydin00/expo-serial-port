package expo.modules.serialport

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialPort.ControlLine
import com.hoho.android.usbserial.driver.UsbSerialPort.FlowControl

internal class UsbPort(
  private val port: UsbSerialPort,
  private val connection: UsbDeviceConnection,
  options: OpenOptions
) : Port {
  @Volatile private var closed = false

  override val blockingRead = true

  override val key = keyFor(port.device.deviceId, port.portNumber)

  /** For matching USB detach broadcasts. */
  val deviceId: Int = port.device.deviceId

  override val capabilities: Capabilities = try {
    port.open(connection)
    port.setParameters(options.baudRate, options.dataBits, options.usbStopBits, options.parityCode)
    configure(options)
  } catch (e: SerialException) {
    closeQuietly()
    throw e
  } catch (e: UnsupportedOperationException) {
    closeQuietly()
    throw SerialException("UNSUPPORTED_BAUD_RATE", "${port.driver.javaClass.simpleName}: ${e.message}")
  } catch (e: Exception) {
    closeQuietly()
    throw SerialException("OPEN_FAILED", "Could not open USB device $deviceId: ${e.message}", e)
  }

  private fun configure(options: OpenOptions): Capabilities {
    val flows = port.supportedFlowControl.mapNotNull { flowNames[it] }
    if (options.flowControl !in flows) {
      throw notSupported("${port.driver.javaClass.simpleName} does not do ${options.flowControl} flow control")
    }
    flowNames.entries.first { it.value == options.flowControl }.key
      .takeIf { it != FlowControl.NONE }
      ?.let { port.setFlowControl(it) }
    return Capabilities(
      lines = toBits(port.supportedControlLines),
      flowControl = flows,
      breakSignal = true,
      rs485 = false
    )
  }

  override fun read(buffer: ByteArray, timeoutMs: Int): Int {
    check(!closed) { "port closed" }
    return port.read(buffer, timeoutMs)
  }

  override fun write(data: ByteArray) {
    check(!closed) { "port closed" }
    port.write(data, WRITE_TIMEOUT_MS)
  }

  override fun getLines() = toBits(port.controlLines)

  override fun setLines(set: Int, clear: Int) {
    if (((set or clear) and Lines.DTR) != 0) port.setDTR((set and Lines.DTR) != 0)
    if (((set or clear) and Lines.RTS) != 0) port.setRTS((set and Lines.RTS) != 0)
  }

  override fun setBreak(on: Boolean) = port.setBreak(on)

  override fun close() {
    if (closed) return
    closed = true
    closeQuietly()
  }

  private fun closeQuietly() {
    try { port.close() } catch (_: Exception) {}
    try { connection.close() } catch (_: Exception) {}
  }

  companion object {
    private const val WRITE_TIMEOUT_MS = 2000

    // XON_XOFF_INLINE (FTDI, PL2303) leaves filtering XON/XOFF out of the data to the app, so it is not offered.
    private val flowNames = mapOf(
      FlowControl.NONE to "none",
      FlowControl.RTS_CTS to "rts-cts",
      FlowControl.DTR_DSR to "dtr-dsr",
      FlowControl.XON_XOFF to "xon-xoff"
    )

    private val lineBits = mapOf(
      ControlLine.DTR to Lines.DTR,
      ControlLine.RTS to Lines.RTS,
      ControlLine.CTS to Lines.CTS,
      ControlLine.DSR to Lines.DSR,
      ControlLine.CD to Lines.CD,
      ControlLine.RI to Lines.RI
    )

    fun toBits(lines: Set<ControlLine>) = lines.fold(0) { bits, line -> bits or (lineBits[line] ?: 0) }

    fun keyFor(deviceId: Int, portNumber: Int) = "usb:$deviceId:$portNumber"
  }
}
