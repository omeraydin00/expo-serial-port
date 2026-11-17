package expo.modules.serialport

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber

/** Finds USB-serial adapters and asks for USB permission. */
internal class UsbScanner(private val context: Context) {
  private val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

  fun drivers(): List<UsbSerialDriver> = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

  fun list(): List<Map<String, Any?>> = drivers().flatMap { driver ->
    val d = driver.device
    driver.ports.map { port ->
      mapOf(
        "kind" to "usb",
        "deviceId" to d.deviceId,
        "port" to port.portNumber,
        "vendorId" to d.vendorId,
        "productId" to d.productId,
        "driver" to driver.javaClass.simpleName.removeSuffix("SerialDriver"),
        "name" to d.productName,
        "hasPermission" to manager.hasPermission(d)
      )
    }
  }

  fun find(deviceId: Int): UsbSerialDriver =
    drivers().firstOrNull { it.device.deviceId == deviceId }
      ?: throw SerialException("NOT_FOUND", "No USB-serial adapter with deviceId $deviceId")

  fun hasPermission(device: UsbDevice) = manager.hasPermission(device)

  fun openConnection(device: UsbDevice) = manager.openDevice(device)
    ?: throw SerialException("USB_PERMISSION_REQUIRED", "No permission for USB device ${device.deviceId}. Call requestUsbPermission first.")

  /** Shows the system dialog. [done] gets the user's answer. */
  fun requestPermission(device: UsbDevice, done: (Boolean) -> Unit) {
    if (manager.hasPermission(device)) return done(true)
    val action = "${context.packageName}.expo-serial-port.USB_PERMISSION.${device.deviceId}"
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        context.unregisterReceiver(this)
        done(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
      }
    }
    ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
    // The system fills in EXTRA_PERMISSION_GRANTED, so the intent has to be mutable (and explicit).
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
    val intent = PendingIntent.getBroadcast(context, device.deviceId, Intent(action).setPackage(context.packageName), flags)
    manager.requestPermission(device, intent)
  }
}
