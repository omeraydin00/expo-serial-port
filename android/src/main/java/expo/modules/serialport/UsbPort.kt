package expo.modules.serialport

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort

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

  init {
    try {
      port.open(connection)
      port.setParameters(options.baudRate, options.dataBits, options.usbStopBits, options.parityCode)
    } catch (e: UnsupportedOperationException) {
      closeQuietly()
      throw SerialException("UNSUPPORTED_BAUD_RATE", "${port.driver.javaClass.simpleName}: ${e.message}")
    } catch (e: Exception) {
      closeQuietly()
      throw SerialException("OPEN_FAILED", "Could not open USB device $deviceId: ${e.message}", e)
    }
  }

  override fun read(buffer: ByteArray, timeoutMs: Int): Int {
    check(!closed) { "port closed" }
    return port.read(buffer, timeoutMs)
  }

  override fun write(data: ByteArray) {
    check(!closed) { "port closed" }
    port.write(data, WRITE_TIMEOUT_MS)
  }

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
    fun keyFor(deviceId: Int, portNumber: Int) = "usb:$deviceId:$portNumber"
  }
}
