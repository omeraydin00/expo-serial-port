# expo-serial-port: design

An Expo module for serial ports on Android: the board's own UARTs (`/dev/ttyS*` and friends) and USB-serial adapters (CH340, FTDI, CP210x, PL2303, CDC-ACM), behind one API.

## Why

Android boards in vending machines, kiosks and industrial screens talk to their controllers over UART. The React Native packages for this (react-native-serial-port-api, react-native-serialport, expo-serialport) have not been updated since 2022-2023. They send data as hex strings, need a `Buffer` polyfill, are not Expo modules and do not support the new architecture. USB-serial has a separate, unrelated set of packages. No maintained package does both.

## Scope

In:

- UART ports under `/dev` and USB-serial adapters, listed and opened the same way
- Raw bytes in and out as `Uint8Array`, strings written as UTF-8
- Optional framing on the native side: delimiter, fixed length, idle gap
- Optional `su` to fix a UART's file permissions, only when the app asks for it
- Expo SDK 54+, Android 7+ (API 24), old and new architecture

Out:

- iOS: no serial ports. `list()` returns `[]`, `open()` rejects with `UNSUPPORTED_PLATFORM`.
- Protocols on top (Modbus etc.), stream/pipe APIs, flow control beyond what the driver does by default

## JS API

```ts
import * as Serial from '@omeraydin00/expo-serial-port';

type PortInfo =
  | { kind: 'uart'; path: string; readable: boolean; writable: boolean }
  | { kind: 'usb'; deviceId: number; port: number; vendorId: number; productId: number;
      driver: string; name: string | null; hasPermission: boolean };
// `port` is the port number on multi-port adapters (FTDI FT4232 etc.), otherwise 0

type Framing =
  | { type: 'raw' }                              // default
  | { type: 'delimiter'; bytes: number[] }       // emitted packet excludes the delimiter
  | { type: 'length'; size: number }
  | { type: 'idle'; ms: number };                // emit when the line is quiet for `ms`

type OpenOptions = {
  baudRate: number;                              // required
  dataBits?: 5 | 6 | 7 | 8;                      // 8
  stopBits?: 1 | 2;                              // 1
  parity?: 'none' | 'odd' | 'even';              // 'none'
  framing?: Framing;                             // { type: 'raw' }
  useSu?: boolean;                               // false, UART only
};

Serial.list(): Promise<PortInfo[]>;
Serial.requestUsbPermission(deviceId: number): Promise<boolean>;
Serial.open(port: PortInfo | string, options: OpenOptions): Promise<SerialPort>;
// a string is taken as a UART path

interface SerialPort {
  readonly info: PortInfo;
  readonly isOpen: boolean;
  write(data: Uint8Array | string): Promise<void>;
  onData(listener: (bytes: Uint8Array) => void): { remove(): void };
  onError(listener: (error: SerialError) => void): { remove(): void };
  close(): Promise<void>;
}
```

Errors are `SerialError` with a `code` and a message that names the port and, where there is one, the fix:

| Code | When |
|---|---|
| `NOT_FOUND` | the path or USB device does not exist |
| `PERMISSION_DENIED` | the UART is not readable/writable by the app (and `useSu` is off or `su` failed) |
| `USB_PERMISSION_REQUIRED` | opening a USB device without permission |
| `UNSUPPORTED_BAUD_RATE` | the UART driver or USB chip rejects the rate |
| `INVALID_OPTIONS` | bad baud rate, data bits, framing etc., checked in JS before the native call |
| `ALREADY_OPEN` | the port is already open in this app |
| `NOT_OPEN` | write on a closed port |
| `OPEN_FAILED` | any other open failure (message has the errno or driver error) |
| `WRITE_FAILED` | the driver rejected a write |
| `DISCONNECTED` | the USB device was unplugged or a read failed; the port is closed |
| `UNSUPPORTED_PLATFORM` | iOS / web |

## Android structure

```
android/src/main/
  cpp/termios.c          open(), raw mode, speed, data/stop bits, parity, close
  java/expo/modules/serialport/
    ExpoSerialPortModule.kt  module definition: functions, events, cleanup on destroy
    Port.kt                  interface: read(buffer, timeoutMs): Int, write(bytes), close()
    UartPort.kt              fd from JNI, FileInputStream/FileOutputStream
    UsbPort.kt               wraps usb-serial-for-android's UsbSerialPort
    UartScanner.kt           lists ttyS*, ttyHS*, ttyMSM*, ttyAMA*, ttyUSB*, ttyACM* under /dev
    UsbScanner.kt            UsbManager + UsbSerialProber, permission request via PendingIntent
    Framer.kt                Raw, Delimiter, Length, Idle
    ReadLoop.kt              one thread per open port: read, frame, emit
    SuHelper.kt              `su -c chmod 666 <path>`, only called when useSu is true
```

Each unit is small and has one job. `Framer` has no Android dependency, so it is unit tested on the JVM.

### Data flow

1. `open()` checks the options in JS, then the module opens the port, configures it and returns a numeric handle. JS creates the `SerialPort` for that handle and only then calls `startReading(handle)`, so no packet arrives before there is a port to route it to.
2. `ReadLoop` reads, passes the bytes to the port's `Framer` and calls the module's `onData` event with `{ handle, bytes }` for every frame.
   - UART: `poll()` with a short timeout (50 ms, or half the idle gap); the timeout also drives the idle check.
   - USB: blocking reads (timeout 0). usb-serial-for-android notes that bulk transfers with timeouts up to 200 ms lose data, so the idle check runs on a separate ticker instead. Closing the port unblocks the read.
3. The JS side routes events to the right `SerialPort` by handle.
4. `write()` runs on a per-port single-thread executor, so writes from the same port never interleave.

### Lifecycle and errors

- USB detach (`ACTION_USB_DEVICE_DETACHED`) and read failures close the port and emit `onError` with `DISCONNECTED`.
- When the module is destroyed (app reload, JS context teardown) every open port is closed and its thread stopped. Ports left open across reloads are a common complaint with the old packages.
- One open per port per app. A second `open()` of the same path or USB device rejects with `ALREADY_OPEN`.

### UART permissions

Without `useSu` an unreadable port rejects with `PERMISSION_DENIED` and a message that says the device node needs `rw` for the app (or `useSu: true` on rooted boards). With `useSu: true` the module runs `chmod 666` on that one path through `su`, checks again and only then opens it. It never runs `su` on its own.

### Dependencies

- `com.github.mik3y:usb-serial-for-android` (MIT, via JitPack) for the USB drivers
- NDK for `termios.c` (CMake, built by the module's Gradle file)

## Testing

Automated (GitHub Actions on every push):

- Kotlin unit tests for `Framer`: split packets, two packets in one read, a delimiter split across reads, idle timeout, empty reads
- Jest tests for the JS layer: option checks, UTF-8 encoding, event routing by handle, `UNSUPPORTED_PLATFORM` off Android
- Example app builds for Android (`expo prebuild`, `gradlew assembleDebug`)

On hardware, before each release:

- UART loopback (TX to RX) on a rooted RK3566 board: 9600 and 115200 baud, all four framings, with and without `useSu`
- USB loopback on a CH340/FTDI adapter, on a phone over OTG and on the RK3566 board: permission prompt, data both ways, unplug gives `DISCONNECTED`
- Results go into a table in the README (device, Android version, port, what was checked)

## Release

- GitHub `omeraydin00/expo-serial-port`, MIT
- npm `@omeraydin00/expo-serial-port` (the unscoped name was refused as too close to `expo-serialport`), starting at 0.1.0
- README: install, a short example, API reference, permission troubleshooting, tested devices
