# expo-serial-port

Serial ports on Android for Expo and React Native: the board's own UARTs (`/dev/ttyS*` and friends) and USB-serial adapters (CH340, FTDI, CP210x, PL2303, CDC-ACM), behind one API.

Made for the Android boards inside vending machines, kiosks and industrial screens, which talk to their controllers over a serial line.

- Bytes in, bytes out: `Uint8Array`, or strings written as UTF-8. No hex strings, no `Buffer` polyfill.
- Packets split on the native side: by delimiter, fixed length, or a quiet gap on the line.
- One error type with codes you can switch on (`PERMISSION_DENIED`, `DISCONNECTED`, ...).
- Ports are closed when the JS app reloads, so a reload does not leave the port busy.
- An Expo module (Expo Modules API, Kotlin). Built and run with Expo SDK 58.

Android only. On iOS and web `list()` returns `[]` and `open()` rejects with `UNSUPPORTED_PLATFORM`.

## Install

```sh
npx expo install @omeraydin00/expo-serial-port
```

Then rebuild the app (`npx expo run:android` or an EAS build). It does not run in Expo Go.

The USB drivers come from [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android), which is published on JitPack. Projects made with `expo prebuild` already have JitPack in `android/build.gradle`; in a bare React Native project, add `maven { url 'https://www.jitpack.io' }` to `allprojects.repositories`.

## Usage

```ts
import * as Serial from '@omeraydin00/expo-serial-port';

const ports = await Serial.list();
// [{ kind: 'uart', path: '/dev/ttyS3', readable: true, writable: true },
//  { kind: 'usb', deviceId: 1003, port: 0, vendorId: 6790, productId: 29987,
//    driver: 'Ch34x', name: 'USB Serial', hasPermission: false }]

const usb = ports.find((p) => p.kind === 'usb');
if (usb && !usb.hasPermission) await Serial.requestUsbPermission(usb.deviceId);

const port = await Serial.open('/dev/ttyS3', {
  baudRate: 115200,
  framing: { type: 'idle', ms: 20 },
});

port.onData((bytes) => console.log('packet', bytes));
port.onError((e) => console.warn(e.code, e.message)); // the port is closed by then

await port.write(new Uint8Array([0xaa, 0x01, 0x02]));
await port.write('PING\r\n');

await port.close();
```

## API

### `list(): Promise<PortInfo[]>`

UART device nodes under `/dev` (`ttyS*`, `ttyHS*`, `ttyMSM*`, `ttyAMA*`, `ttyMT*`, `ttySAC*`, `ttyUSB*`, `ttyACM*`) and attached USB-serial adapters. `readable` / `writable` tell you whether the app can open a UART as it is. A USB adapter with several ports shows up once per port.

### `requestUsbPermission(deviceId): Promise<boolean>`

Shows Android's USB permission dialog and resolves with the answer. Resolves `true` right away if the app already has permission.

### `open(port, options): Promise<SerialPort>`

`port` is an entry from `list()`, or a UART path.

| Option     | Default           |                                                     |
| ---------- | ----------------- | --------------------------------------------------- |
| `baudRate` | required          | UART: the standard termios rates from 50 to 4000000 |
| `dataBits` | `8`               | 5, 6, 7 or 8                                        |
| `stopBits` | `1`               | 1 or 2                                              |
| `parity`   | `'none'`          | `'none'`, `'odd'` or `'even'`                       |
| `framing`  | `{ type: 'raw' }` | see below                                           |
| `useSu`    | `false`           | UART only, see [Permissions](#uart-permissions)     |

Framing:

| `framing`                                    | `onData` gets                                           |
| -------------------------------------------- | ------------------------------------------------------- |
| `{ type: 'raw' }`                            | every chunk as it is read                               |
| `{ type: 'delimiter', bytes: [0x0d, 0x0a] }` | each packet up to the delimiter, without the delimiter  |
| `{ type: 'length', size: 12 }`               | packets of exactly 12 bytes                             |
| `{ type: 'idle', ms: 20 }`                   | whatever arrived before the line stayed quiet for 20 ms |

A delimiter or idle packet longer than 64 KB is emitted as it is, so a missing delimiter cannot grow the buffer forever.

### `SerialPort`

- `info: PortInfo`, `isOpen: boolean`
- `write(data: Uint8Array | string): Promise<void>`: writes from one port never interleave
- `onData(listener: (bytes: Uint8Array) => void): { remove() }`
- `onError(listener: (error: SerialError) => void): { remove() }`: called once when the port dies (USB unplugged, read failure); the port is already closed
- `close(): Promise<void>`

### Errors

Every rejection and `onError` call is a `SerialError` with a `code`:

| Code                      | When                                                |
| ------------------------- | --------------------------------------------------- |
| `NOT_FOUND`               | the path or USB device does not exist               |
| `PERMISSION_DENIED`       | the UART is not readable and writable by the app    |
| `USB_PERMISSION_REQUIRED` | opening a USB adapter before `requestUsbPermission` |
| `UNSUPPORTED_BAUD_RATE`   | the UART or the USB chip does not support the rate  |
| `INVALID_OPTIONS`         | bad options, checked before anything is opened      |
| `ALREADY_OPEN`            | the port is already open in this app                |
| `NOT_OPEN`                | write on a closed port                              |
| `OPEN_FAILED`             | any other open failure; the message has the details |
| `WRITE_FAILED`            | the driver rejected a write                         |
| `DISCONNECTED`            | USB unplugged or a read failed                      |
| `UNSUPPORTED_PLATFORM`    | iOS or web                                          |

## UART permissions

A UART device node is usually owned by `root` or `system` with mode `0660`, so an app cannot open it. On a board you control, the clean fix is in the ROM: give the node `0666` in `ueventd.rc`, or add the app to the node's group.

On a rooted board you can pass `useSu: true`. If the node is not readable and writable, the module runs `su -c chmod 666 <path>` for that one path, checks again, and then opens it. It never calls `su` unless you ask for it. The `chmod` does not survive a reboot.

Phones normally have no usable UART; use a USB adapter there.

## Tested on

| Device                                | Android | Port                                                 | What                                                                                                                         |
| ------------------------------------- | ------- | ---------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| DWIN DMG10600C101 (RK3566, userdebug) | 11      | `/dev/ttyS9`, TX9 wired to RX9 on the terminal block | 256-byte loopback at 115200 (80 ms) and 9600 (352 ms); raw, delimiter, idle and length framing                               |
| DWIN DMG10600C101 (RK3566, userdebug) | 11      | `/dev/ttyS5` set to `0600`                           | `PERMISSION_DENIED` without `useSu`, opens with it (this ROM's `su` takes `su 0 cmd`, not `su -c`)                           |
| DWIN DMG10600C101 (RK3566, userdebug) | 11      | CP2102 in the board's USB-A port, TX wired to RX     | 256-byte loopback at 115200 (223 ms) and 9600 (424 ms), raw and idle framing                                                 |
| Samsung Galaxy S25 FE                 | 17      | CP2102 over USB-C OTG, TX wired to RX                | permission dialog; 256-byte loopback at 115200 (56 ms) and 9600 (319 ms); all four framings; unplugging gives `DISCONNECTED` |
| Samsung Galaxy S25 FE                 | 17      | `/dev/ttySAC0`, `ttySAC1`                            | listed; `open()` rejects with `PERMISSION_DENIED` (phone UARTs are not for apps)                                             |

Times are from `write()` to the last byte arriving in JS. Not tested yet: CH340 and FTDI adapters, ports being closed on a JS reload.

## Example app

```sh
npm install
cd example
npm install
npx expo run:android
```

It lists the ports, opens one with the baud rate and framing you pick, sends text and runs a loopback check (wire TX to RX, it writes 256 bytes and compares what comes back).

## License

MIT
