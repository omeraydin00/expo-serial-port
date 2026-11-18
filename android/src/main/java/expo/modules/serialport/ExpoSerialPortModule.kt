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
  private class OpenPort(
    val port: Port,
    val loop: ReadLoop,
    val writer: ExecutorService,
    /** True while flow control or RS-485 drives RTS. */
    val rtsOwnedByDriver: Boolean
  ) {
    var poller: LinePoller? = null
  }

  private val ports = ConcurrentHashMap<Int, OpenPort>()
  private val nextHandle = AtomicInteger(1)
  private var detachReceiver: BroadcastReceiver? = null

  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val usb by lazy { UsbScanner(context) }

  override fun definition() = ModuleDefinition {
    Name("ExpoSerialPort")

    Events("onData", "onError", "onLines")

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
          throw SerialException("PERMISSION_DENIED", "No read/write access to $path, and chmod through su did not help (no su, or su does not allow this app)")
        }
      }
      opened(register(UartPort(path, options), options.framing, options.rtsOwnedByDriver))
    } }

    AsyncFunction("openUsb") { deviceId: Int, portNumber: Int, options: OpenOptions -> synchronized(ports) {
      ensureNotOpen(UsbPort.keyFor(deviceId, portNumber))
      val driver = usb.find(deviceId)
      val serialPort = driver.ports.getOrNull(portNumber)
        ?: throw SerialException("NOT_FOUND", "USB device $deviceId has no port $portNumber")
      opened(register(UsbPort(serialPort, usb.openConnection(driver.device), options), options.framing, options.rtsOwnedByDriver))
    } }

    AsyncFunction("createVirtual") { framing: FramingOptions ->
      val port = VirtualPort.create()
      mapOf("handle" to register(port, framing, rtsOwnedByDriver = false), "path" to port.path)
    }

    // Called by JS once it is listening, so no packet arrives before there is a port to route it to.
    AsyncFunction("startReading") { handle: Int ->
      openPort(handle).loop.start()
    }

    AsyncFunction("write") { handle: Int, data: ByteArray, promise: Promise ->
      val open = openPort(handle)
      open.writer.execute {
        try {
          open.port.write(data)
          promise.resolve(null)
        } catch (e: Exception) {
          promise.reject(SerialException("WRITE_FAILED", "Write failed: ${e.message}", e))
        }
      }
    }

    AsyncFunction("getLines") { handle: Int ->
      val open = openPort(handle)
      val supported = open.port.capabilities.lines
      if (supported == 0) throw notSupported("${describe(open)} has no control lines")
      Lines.toMap(control { open.port.getLines() }, supported)
    }

    AsyncFunction("setLines") { handle: Int, set: Int, clear: Int, promise: Promise ->
      val open = openPort(handle)
      val missing = (set or clear) and open.port.capabilities.lines.inv()
      if (missing != 0) throw notSupported("${describe(open)} cannot set ${Lines.names(missing).joinToString()}")
      if (((set or clear) and Lines.RTS) != 0 && open.rtsOwnedByDriver) {
        throw SerialException("INVALID_OPTIONS", "RTS is driven by flow control or RS-485 on ${describe(open)}")
      }
      open.writer.execute { settle(promise) { open.port.setLines(set, clear) } }
    }

    AsyncFunction("sendBreak") { handle: Int, ms: Int, promise: Promise ->
      val open = openPort(handle)
      if (!open.port.capabilities.breakSignal) throw notSupported("${describe(open)} cannot send a break")
      open.writer.execute {
        settle(promise) {
          open.port.setBreak(true)
          try {
            Thread.sleep(ms.toLong())
          } finally {
            open.port.setBreak(false)
          }
        }
      }
    }

    AsyncFunction("watchLines") { handle: Int, on: Boolean ->
      val open = openPort(handle)
      synchronized(open) {
        if (!on) {
          open.poller?.stop()
          open.poller = null
        } else if (open.poller == null) {
          val supported = open.port.capabilities.lines
          if (supported == 0) throw notSupported("${describe(open)} has no control lines")
          open.poller = LinePoller(open.port, { state ->
            sendEvent("onLines", mapOf("handle" to handle, "lines" to Lines.toMap(state, supported)))
          })
        }
      }
      Unit
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

  private fun openPort(handle: Int) =
    ports[handle] ?: throw SerialException("NOT_OPEN", "Port $handle is not open")

  private fun describe(open: OpenPort) = open.port.key.substringAfter(':')

  private fun opened(handle: Int) =
    mapOf("handle" to handle, "capabilities" to ports.getValue(handle).port.capabilities.toMap())

  /** Runs a control line call; driver errors become CONTROL_FAILED. */
  private fun <T> control(block: () -> T): T = try {
    block()
  } catch (e: SerialException) {
    throw e
  } catch (e: UnsupportedOperationException) {
    throw notSupported(e.message ?: "Not supported by this driver")
  } catch (e: Exception) {
    throw SerialException("CONTROL_FAILED", "Control line call failed: ${e.message}", e)
  }

  private fun settle(promise: Promise, block: () -> Unit) {
    try {
      control(block)
      promise.resolve(null)
    } catch (e: SerialException) {
      promise.reject(e)
    }
  }

  private fun register(port: Port, framing: FramingOptions, rtsOwnedByDriver: Boolean): Int {
    val handle = nextHandle.getAndIncrement()
    val loop = ReadLoop(
      port,
      framing.toFramer(),
      onPacket = { bytes -> sendEvent("onData", mapOf("handle" to handle, "data" to bytes)) },
      onFailure = { e -> fail(handle, "DISCONNECTED", "Read failed: ${e.message}") }
    )
    val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "serial-write-${port.key}").apply { isDaemon = true } }
    ports[handle] = OpenPort(port, loop, writer, rtsOwnedByDriver)
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
    synchronized(open) { open.poller?.stop() }
    open.loop.stop()
    open.writer.shutdown()
    open.port.close()
    return true
  }
}
