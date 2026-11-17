package expo.modules.serialport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ExpoSerialPortModule : Module() {
  private class OpenPort(val port: Port, val loop: ReadLoop, val writer: ExecutorService)

  private val ports = ConcurrentHashMap<Int, OpenPort>()
  private val nextHandle = AtomicInteger(1)
  private var detachReceiver: BroadcastReceiver? = null

  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val usb by lazy { UsbScanner(context) }

  override fun definition() = ModuleDefinition {
    Name("ExpoSerialPort")

    Events("onData", "onError")

    OnCreate {
      val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
          @Suppress("DEPRECATION")
          val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
          ports.entries
            .filter { (it.value.port as? UsbPort)?.deviceId == device.deviceId }
            .forEach { fail(it.key, "DISCONNECTED", "USB device ${device.deviceId} was unplugged") }
        }
      }
      // A system broadcast, so the receiver has to be exported to get it.
      ContextCompat.registerReceiver(
        context, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_EXPORTED
      )
      detachReceiver = receiver
    }

    OnDestroy {
      ports.keys.toList().forEach { closePort(it) }
      detachReceiver?.let { runCatching { context.unregisterReceiver(it) } }
      detachReceiver = null
    }

    AsyncFunction("list") {
      UartScanner.list() + usb.list()
    }

    AsyncFunction("requestUsbPermission") { deviceId: Int, promise: Promise ->
      usb.requestPermission(usb.find(deviceId).device) { promise.resolve(it) }
    }

    AsyncFunction("openUart") { path: String, options: OpenOptions -> synchronized(ports) {
      ensureNotOpen(UartPort.keyFor(path))
      val node = File(path)
      if (options.useSu && node.exists() && !(node.canRead() && node.canWrite())) {
        if (!SuHelper.makeAccessible(path)) {
          throw SerialException("PERMISSION_DENIED", "No read/write access to $path, and chmod through su did not help")
        }
      }
      register(UartPort(path, options), options)
    } }

    AsyncFunction("openUsb") { deviceId: Int, portNumber: Int, options: OpenOptions -> synchronized(ports) {
      ensureNotOpen(UsbPort.keyFor(deviceId, portNumber))
      val driver = usb.find(deviceId)
      val serialPort = driver.ports.getOrNull(portNumber)
        ?: throw SerialException("NOT_FOUND", "USB device $deviceId has no port $portNumber")
      register(UsbPort(serialPort, usb.openConnection(driver.device), options), options)
    } }

    // Called by JS once it is listening, so no packet arrives before there is a port to route it to.
    AsyncFunction("startReading") { handle: Int ->
      val open = ports[handle] ?: throw SerialException("NOT_OPEN", "Port $handle is not open")
      open.loop.start()
    }

    AsyncFunction("write") { handle: Int, data: ByteArray, promise: Promise ->
      val open = ports[handle] ?: throw SerialException("NOT_OPEN", "Port $handle is not open")
      open.writer.execute {
        try {
          open.port.write(data)
          promise.resolve(null)
        } catch (e: Exception) {
          promise.reject(SerialException("WRITE_FAILED", "Write failed: ${e.message}", e))
        }
      }
    }

    AsyncFunction("close") { handle: Int ->
      closePort(handle)
      Unit
    }
  }

  private fun ensureNotOpen(key: String) {
    if (ports.values.any { it.port.key == key }) {
      throw SerialException("ALREADY_OPEN", "${key.substringAfter(':')} is already open")
    }
  }

  private fun register(port: Port, options: OpenOptions): Int {
    val handle = nextHandle.getAndIncrement()
    val loop = ReadLoop(
      port,
      options.framing.toFramer(),
      onPacket = { bytes -> sendEvent("onData", mapOf("handle" to handle, "data" to bytes)) },
      onFailure = { e -> fail(handle, "DISCONNECTED", "Read failed: ${e.message}") }
    )
    val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "serial-write-${port.key}").apply { isDaemon = true } }
    ports[handle] = OpenPort(port, loop, writer)
    return handle
  }

  private fun fail(handle: Int, code: String, message: String) {
    if (closePort(handle)) {
      sendEvent("onError", mapOf("handle" to handle, "code" to code, "message" to message))
    }
  }

  /** Returns false if the port was not open. */
  private fun closePort(handle: Int): Boolean {
    val open = ports.remove(handle) ?: return false
    open.loop.stop()
    open.writer.shutdown()
    open.port.close()
    return true
  }
}
