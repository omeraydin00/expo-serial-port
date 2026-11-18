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
- Stream/pipe APIs

0.1 left out control lines, flow control and protocols on top. They are planned in [Roadmap](#roadmap).

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

## Roadmap

Each release is tested on hardware and published on its own. Later ones build on earlier ones.

| Version | Adds | Why this order |
|---|---|---|
| 0.2.0 | Virtual ports, control lines, break, flow control, RS-485 mode | Native groundwork. The virtual port is what the later releases are tested with. |
| 0.3.0 | `port.request()` (write, wait for one reply), a mock port for Jest | Modbus is a request/reply protocol |
| 0.4.0 | Modbus RTU client | Needs `request()`, and RS-485 is the usual wire |
| 0.5.0 | `useSerialPort()` hook, USB attach/detach events, reconnect, Expo config plugin (USB device filter) | Convenience on top |

## 0.2.0: virtual ports, control lines, RS-485

### Virtual ports

A virtual port is a pseudo-terminal the app creates itself. The app side is a real `/dev/pts/N` node that `open()` treats like any UART, and the other side is a `VirtualPort` the app drives from JS. It lets an app (and this library's own tests) run against a simulated device on any Android phone, without hardware or root.

```ts
const device = await Serial.createVirtualPort({ framing: { type: 'idle', ms: 5 } });
device.path;                                    // '/dev/pts/3'
device.onData((bytes) => device.write(reply(bytes)));
const port = await Serial.open(device.path, { baudRate: 9600 });

interface VirtualPort {
  readonly path: string;
  readonly isOpen: boolean;
  write(data: Uint8Array | string): Promise<void>; // arrives on the app's port
  onData(listener: (bytes: Uint8Array) => void): Subscription; // what the app wrote, framed
  close(): Promise<void>;                        // the app's port gets DISCONNECTED
}
```

- Created with `posix_openpt`, `grantpt`, `unlockpt`, `ptsname_r`. The pts side is put in raw mode at once, so nothing is echoed even before the app opens it.
- The `VirtualPort` keeps its own fd on the pts side open. Without it the master reads a hangup until the app opens the port.
- It is a `Port` like the others, so reading, framing and events go through the same `ReadLoop` and `Framer`.
- Closing it hangs up the pts side. The app's port reads `POLLHUP` and fails with `DISCONNECTED`, the same as an unplugged USB adapter.
- Baud rate, parity etc. are accepted and have no effect. Virtual ports have no control lines, no break and no RS-485.

### Control lines and break

```ts
interface SerialPort {
  readonly capabilities: Capabilities;
  setLines(lines: { dtr?: boolean; rts?: boolean }): Promise<void>;
  getLines(): Promise<Partial<Record<Line, boolean>>>; // only the lines in capabilities.lines
  onLinesChange(listener: (lines: Partial<Record<Line, boolean>>) => void): Subscription;
  sendBreak(ms: number): Promise<void>;          // 1 to 10000
}

type Line = 'dtr' | 'rts' | 'cts' | 'dsr' | 'cd' | 'ri';
type FlowControl = 'none' | 'rts-cts' | 'dtr-dsr' | 'xon-xoff';
type Capabilities = { lines: Line[]; flowControl: FlowControl[]; breakSignal: boolean; rs485: boolean };
```

- Anything outside `capabilities` rejects with the new code `NOT_SUPPORTED`. Nothing is silently ignored.
- `setLines({ rts })` rejects with `INVALID_OPTIONS` while RTS belongs to `rts-cts` flow control or RS-485.
- `setLines` and `sendBreak` run on the port's writer thread, so a break comes after the writes before it.
- `onLinesChange` polls every 20 ms on a native thread while it has listeners, and emits only changes. Neither USB nor most UART drivers have a reliable change notification (`TIOCMIWAIT` is missing on many), so polling is the portable choice.

| | UART | USB | Virtual |
|---|---|---|---|
| Lines | `TIOCMGET`/`TIOCMBIS`/`TIOCMBIC`; all six if `TIOCMGET` works, none otherwise | `getSupportedControlLines()` | none |
| Break | `TIOCSBRK`, sleep, `TIOCCBRK` | `setBreak(true/false)` | no |
| Flow control | `rts-cts` (`CRTSCTS`), `xon-xoff` (`IXON`/`IXOFF`) | `getSupportedFlowControl()`; `xon-xoff` uses `XON_XOFF`, else `XON_XOFF_INLINE` | none |
| RS-485 | if `TIOCGRS485` works | no | no |

### New open options

```ts
type OpenOptions = {
  // ...0.1 options
  flowControl?: FlowControl;                     // 'none'
  rs485?: boolean | {                            // UART only, off by default
    rtsOnSend?: boolean;                         // true: RTS level while sending
    rtsAfterSend?: boolean;                      // false: RTS level after sending
    delayBeforeSendMs?: number;                  // 0
    delayAfterSendMs?: number;                   // 0
    receiveDuringSend?: boolean;                 // false
  };
};
```

- `rs485: true` means the defaults above. JS rejects `rs485` on a USB port with `INVALID_OPTIONS`; USB-RS485 adapters switch direction themselves.
- On open, `TIOCSRS485` sets the mode, then `TIOCGRS485` reads it back. If the driver refuses or does not keep `SER_RS485_ENABLED`, the port is closed and `open()` rejects with `NOT_SUPPORTED`.
- A `flowControl` the port does not support also rejects `open()` with `NOT_SUPPORTED`.

### Native changes

- `Port` gets `capabilities`, `setLines(dtr: Boolean?, rts: Boolean?)`, `getLines(): Int` (bit mask) and `setBreak(on: Boolean)`.
- `termios.c`: flow control in `nativeOpen`; new `nativeGetLines`, `nativeSetLines`, `nativeSetBreak`, `nativeSetRs485`, `nativeCreatePty`.
- New `VirtualPort.kt` and `LinePoller.kt`.
- `openUart`/`openUsb` return `{ handle, capabilities }` instead of a bare handle.

### Testing

| What | Where | How |
|---|---|---|
| RS-485 flag encoding, line bit mapping | Kotlin unit tests | JVM |
| New options, `capabilities` checks, virtual port routing | Jest | mocked native module |
| Virtual port: 256 bytes both ways, all framings, close gives `DISCONNECTED` | Galaxy S25 FE (no root) and the RK3566 board | example app, Virtual screen |
| Break | CP2102 with TX bridged to RX | a break arrives as a `0x00` byte |
| DTR/RTS | CP2102, RK3566 ttyS9 | set, read back with `getLines()`; DTR pin with a multimeter if one is at hand |
| RS-485 mode | RK3566 | mode accepted and read back. Not tested on a real RS-485 line (no transceiver at hand); the README says so. |
