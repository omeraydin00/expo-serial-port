# expo-serial-port

[![npm](https://img.shields.io/npm/v/@omeraydin00/expo-serial-port)](https://www.npmjs.com/package/@omeraydin00/expo-serial-port)
[![CI](https://github.com/omeraydin00/expo-serial-port/actions/workflows/ci.yml/badge.svg)](https://github.com/omeraydin00/expo-serial-port/actions/workflows/ci.yml)
![platform](https://img.shields.io/badge/platform-Android-3DDC84)
[![license](https://img.shields.io/npm/l/@omeraydin00/expo-serial-port)](LICENSE)

Serial ports on Android for Expo and React Native: the board's own UARTs (`/dev/ttyS*` and friends) and USB-serial adapters (CH340, FTDI, CP210x, PL2303, CDC-ACM), behind one API.

Made for the Android boards inside vending machines, kiosks and industrial screens, which talk to their controllers over a serial line.

- Bytes in, bytes out: `Uint8Array`, or strings written as UTF-8. No hex strings, no `Buffer` polyfill.
- Packets split on the native side: by delimiter, fixed length, or a quiet gap on the line.
- Control lines (DTR, RTS, CTS, ...), break, RTS/CTS and XON/XOFF flow control, and the kernel's RS-485 mode.
- Virtual ports: a pseudo-terminal your code plays the device on, so you can try an app without the hardware.
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

| Option        | Default           |                                                                                      |
| ------------- | ----------------- | ------------------------------------------------------------------------------------ |
| `baudRate`    | required          | UART: the standard termios rates from 50 to 4000000                                  |
| `dataBits`    | `8`               | 5, 6, 7 or 8                                                                         |
| `stopBits`    | `1`               | 1 or 2                                                                               |
| `parity`      | `'none'`          | `'none'`, `'odd'` or `'even'`                                                        |
| `framing`     | `{ type: 'raw' }` | see below                                                                            |
| `useSu`       | `false`           | UART only, see [Permissions](#uart-permissions)                                      |
| `flowControl` | `'none'`          | `'rts-cts'`, `'xon-xoff'`, or `'dtr-dsr'` (USB only); see `capabilities.flowControl` |
| `rs485`       | `false`           | UART only, see [RS-485](#rs-485)                                                     |

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
- `capabilities`: what this port can do beyond reading and writing (below)
- `write(data: Uint8Array | string): Promise<void>`: writes from one port never interleave
- `onData(listener: (bytes: Uint8Array) => void): { remove() }`
- `onError(listener: (error: SerialError) => void): { remove() }`: called once when the port dies (USB unplugged, read failure); the port is already closed
- `setLines({ dtr?, rts? }): Promise<void>`: raises (`true`) or drops (`false`) DTR and RTS; lines left out stay as they are
- `getLines(): Promise<LineState>`: e.g. `{ dtr: true, rts: false, cts: false, dsr: false, cd: false, ri: false }`
- `onLinesChange(listener: (lines: LineState) => void): { remove() }`: called with the current state, then on every change (polled every 20 ms while someone listens)
- `sendBreak(ms): Promise<void>`: holds the line in break for 1 to 10000 ms, after any writes before it
- `close(): Promise<void>`

`capabilities` tells you which of these the port supports; a call outside it rejects with `NOT_SUPPORTED`:

```ts
port.capabilities;
// { lines: ['dtr', 'rts', 'cts', 'dsr', 'cd', 'ri'],
//   flowControl: ['none', 'rts-cts', 'xon-xoff'],
//   breakSignal: true,
//   rs485: false }
```

A UART reports all six lines when the driver answers `TIOCMGET`, and `rs485` when it has an RS-485 mode. A USB adapter reports what its chip can do (a CP2102, for example, has all six lines, break and every flow control mode). XON/XOFF on USB is only offered where the chip does it itself, so the bytes 0x11 and 0x13 are not lost in the data.

### `createVirtualPort(options?): Promise<VirtualPort>`

Creates a pseudo-terminal (`/dev/pts/N`). The app opens `virtual.path` like any UART, and your code plays the device on the other end. It needs no root and no hardware, so you can work on a protocol before the board is on your desk:

```ts
const device = await Serial.createVirtualPort();
device.onData((bytes) => {
  if (bytes[0] === 0x01) device.write(new Uint8Array([0x01, 0x00, 0x2a])); // answer like the controller would
});

const port = await Serial.open(device.path, {
  baudRate: 115200,
  framing: { type: 'idle', ms: 20 },
});
```

- `options.framing` splits what the app writes, on the device side (default raw).
- `VirtualPort` has `path`, `isOpen`, `write()`, `onData()` and `close()`. `close()` hangs up: a port the app has open on it gets `DISCONNECTED`.
- A virtual port has no control lines, break or flow control (`capabilities` says so), and the baud rate does not slow anything down.

### Errors

Every rejection and `onError` call is a `SerialError` with a `code`:

| Code                      | When                                                    |
| ------------------------- | ------------------------------------------------------- |
| `NOT_FOUND`               | the path or USB device does not exist                   |
| `PERMISSION_DENIED`       | the UART is not readable and writable by the app        |
| `USB_PERMISSION_REQUIRED` | opening a USB adapter before `requestUsbPermission`     |
| `UNSUPPORTED_BAUD_RATE`   | the UART or the USB chip does not support the rate      |
| `INVALID_OPTIONS`         | bad options, checked before anything is opened          |
| `ALREADY_OPEN`            | the port is already open in this app                    |
| `NOT_OPEN`                | write on a closed port                                  |
| `OPEN_FAILED`             | any other open failure; the message has the details     |
| `WRITE_FAILED`            | the driver rejected a write                             |
| `DISCONNECTED`            | USB unplugged, a read failed, or a virtual port hung up |
| `NOT_SUPPORTED`           | the port cannot do it (see `capabilities`)              |
| `CONTROL_FAILED`          | the driver rejected a line, break or RS-485 call        |
| `UNSUPPORTED_PLATFORM`    | iOS or web                                              |

## UART permissions

A UART device node is usually owned by `root` or `system` with mode `0660`, so an app cannot open it. On a board you control, the clean fix is in the ROM: give the node `0666` in `ueventd.rc`, or add the app to the node's group.

On a rooted board you can pass `useSu: true`. If the node is not readable and writable, the module runs `su -c chmod 666 <path>` for that one path, checks again, and then opens it. It never calls `su` unless you ask for it. The `chmod` does not survive a reboot.

Phones normally have no usable UART; use a USB adapter there.

## RS-485

Many boards put an RS-485 transceiver on a UART. There are two kinds:

- **Automatic direction.** The transceiver switches between sending and receiving by itself. Open the port as a plain UART and leave `rs485` off.
- **Direction on RTS.** RTS has to be high while the board sends and low right after. Pass `rs485: true` and the kernel's serial driver does it around every write, with the right timing:

```ts
const port = await Serial.open('/dev/ttyS4', {
  baudRate: 9600,
  rs485: { rtsOnSend: true, rtsAfterSend: false, delayAfterSendMs: 1 },
});
```

| `rs485` field       | Default |                                                              |
| ------------------- | ------- | ------------------------------------------------------------ |
| `rtsOnSend`         | `true`  | RTS level while sending                                      |
| `rtsAfterSend`      | `false` | RTS level after sending                                      |
| `delayBeforeSendMs` | `0`     | 0 to 1000                                                    |
| `delayAfterSendMs`  | `0`     | 0 to 1000                                                    |
| `receiveDuringSend` | `false` | keep receiving while sending (you read your own writes back) |

This needs a driver with an RS-485 mode (`TIOCSRS485`). The module writes the port's current RS-485 config back to it to find out, so `capabilities.rs485` is only `true` when the driver really takes it. If it does not, `open()` rejects with `NOT_SUPPORTED`. While RS-485 is on, the driver owns RTS and `setLines({ rts })` rejects with `INVALID_OPTIONS`. USB adapters take no `rs485` option; an adapter with its own RS-485 transceiver switches direction by itself.

RS-485 mode has not been tested on a real bus yet. The boards it was tried on run 8250 drivers without an RS-485 mode, and the module now says so (see below).

## Tested on

| Device                                | Android | Port                                                 | What                                                                                                                                                                                                                      |
| ------------------------------------- | ------- | ---------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| DWIN DMG10600C101 (RK3566, userdebug) | 11      | `/dev/ttyS9`, TX9 wired to RX9 on the terminal block | 256-byte loopback at 115200 (80 ms) and 9600 (352 ms); raw, delimiter, idle and length framing                                                                                                                            |
| DWIN DMG10600C101 (RK3566, userdebug) | 11      | `/dev/ttyS5` set to `0600`                           | `PERMISSION_DENIED` without `useSu`, opens with it (this ROM's `su` takes `su 0 cmd`, not `su -c`)                                                                                                                        |
| DWIN DMG10600C101 (RK3566, userdebug) | 11      | CP2102 in the board's USB-A port, TX wired to RX     | 256-byte loopback at 115200 (223 ms) and 9600 (424 ms), raw and idle framing                                                                                                                                              |
| Samsung Galaxy S25 FE                 | 17      | CP2102 over USB-C OTG, TX wired to RX                | permission dialog; 256-byte loopback at 115200 (56 ms) and 9600 (319 ms); all four framings; unplugging gives `DISCONNECTED`                                                                                              |
| Samsung Galaxy S25 FE                 | 17      | CP2102, port left open across `reloadAppAsync()`     | after the reload the port opens again, one reader thread, loopback 256/256                                                                                                                                                |
| Samsung Galaxy S25 FE                 | 17      | `/dev/ttySAC0`, `ttySAC1`                            | listed; `open()` rejects with `PERMISSION_DENIED` (phone UARTs are not for apps)                                                                                                                                          |
| Samsung Galaxy S25 FE                 | 17      | virtual port (`/dev/pts/0`), echo device             | 256-byte loopback in 26 to 42 ms with raw, idle and length framing; lines and break reject with `NOT_SUPPORTED`; `close()` on the device gives the app `DISCONNECTED`                                                     |
| Samsung Galaxy S25 FE                 | 17      | CP2102, TX wired to RX                               | 250 ms break comes back as one `0x00` byte; DTR and RTS set and read back; `onLinesChange` on each change; PING comes back with `xon-xoff`; with `rts-cts` nothing is sent (this module has no CTS pin, so CTS stays off) |
| DWIN DMG10600C101 (RK3566, userdebug) | 11      | virtual port (`/dev/pts/0`), echo device             | 256-byte loopback in 62 ms with raw, idle and length framing; `close()` on the device gives the app `DISCONNECTED`                                                                                                        |
| DWIN DMG10600C101 (RK3566, userdebug) | 11      | `/dev/ttyS9`, TX9 wired to RX9                       | 250 ms break comes back as one `0x00` byte; DTR and RTS set and read back; kernel 4.19's 8250 driver has no RS-485 mode on any UART: `capabilities.rs485` is `false` and `rs485: true` rejects with `NOT_SUPPORTED`       |

Times are from `write()` to the last byte arriving in JS. Not tested yet: CH340 and FTDI adapters, and RS-485 mode on a driver that has one.

## Example app

```sh
npm install
cd example
npm install
npx expo run:android
```

It lists the ports, opens one with the baud rate, framing and flow control you pick, sends text and runs a loopback check (wire TX to RX, it writes 256 bytes and compares what comes back). It also toggles DTR and RTS, watches the lines and sends a break. "Virtual device" adds an echo device to the list, so the loopback check works without a wire.

## License

MIT
